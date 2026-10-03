/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.server.level.ServerPlayer;

/** Atlas workers submit pixel coordinates only. World reads stay on the server thread. */
@Mod.EventBusSubscriber(modid = BetterMapAtlases.MOD_ID)
public final class DeferredMapSamples {
    private static final int PIXELS_PER_TICK = 1024;
    private static final int PIXELS_PER_TURN = 32;
    private static final long NANOS_PER_TICK = 2_000_000L;
    private static final Object LIFECYCLE = new Object();
    private static final Set<ServerLevel> ACTIVE_LEVELS = ConcurrentHashMap.newKeySet();
    private static final Map<MapItemSavedData, Work> PENDING = new ConcurrentHashMap<>();
    private static final Map<MinecraftServer, ConcurrentLinkedQueue<Work>> QUEUES = new ConcurrentHashMap<>();

    private DeferredMapSamples() {}

    static void request(ServerLevel level, MapItemSavedData map, int index, byte color) {
        // Shares the map monitor with invalidation and the final sampling/commit.
        synchronized (map) {
            Work work = PENDING.get(map);
            if (work == null) {
                synchronized (LIFECYCLE) {
                    if (!ACTIVE_LEVELS.contains(level) || level.getServer().isStopped()) return;
                    work = new Work(level, map);
                    work.pixels.request(index, color);
                    PENDING.put(map, work);
                    QUEUES.computeIfAbsent(level.getServer(), ignored -> new ConcurrentLinkedQueue<>()).add(work);
                }
            } else {
                work.pixels.request(index, color);
            }
        }
    }

    /** Caller holds the map monitor. A later external vanilla edit supersedes queued work. */
    static void cancel(MapItemSavedData map, int index) {
        Work work = PENDING.get(map);
        if (work != null) {
            work.pixels.cancel(index);
            if (work.activeIndex == index) work.clearActive();
        }
    }

    /** Finish this map's visited pixels before saving or making a locked copy. */
    public static void flush(MapItemSavedData map) {
        Work work = PENDING.get(map);
        if (work == null || !work.level.getServer().isSameThread()) return;
        synchronized (map) {
            if (PENDING.get(map) != work) return;
            while (sampleNext(work, Long.MAX_VALUE)) { }
            PENDING.remove(map, work);
        }
    }

    /** Read-only queue diagnostics; does not drain work or access a chunk. */
    public static Map<String, Object> describe(MapItemSavedData map) {
        synchronized (map) {
            Work work = PENDING.get(map);
            return Map.of("pendingPixels", work == null ? 0 : work.pixels.size() + (work.capture == null ? 0 : 1),
                "pixelBudgetPerTick", PIXELS_PER_TICK, "timeBudgetNanos", NANOS_PER_TICK);
        }
    }

    private static boolean sampleNext(Work work, long deadline) {
        if (!ACTIVE_LEVELS.contains(work.level) || work.map.locked) return false;
        if (work.capture == null) {
            int index = work.pixels.next();
            if (index < 0) return false;
            work.activeIndex = index;
            work.activeColor = work.pixels.color(index);
            work.capture = new MapSampler.PixelCapture(index & 127, index >> 7);
        }
        // An edit which bypassed setColor must not receive stale queued metadata either.
        if (work.map.colors[work.activeIndex] == work.activeColor
            && !MapSampler.captureStep(work.level, work.map, work.capture, deadline)) return false;
        work.clearActive();
        return true;
    }

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ConcurrentLinkedQueue<Work> queue = QUEUES.get(event.getServer());
        if (queue == null) return;
        long started = System.nanoTime();
        long deadline = started + NANOS_PER_TICK;
        int remaining = PIXELS_PER_TICK;
        while (remaining > 0 && System.nanoTime() - started < NANOS_PER_TICK) {
            Work work = queue.poll();
            if (work == null) return;
            synchronized (work.map) {
                if (PENDING.get(work.map) != work) continue;
                int turn = Math.min(remaining, PIXELS_PER_TURN);
                while (turn-- > 0 && System.nanoTime() < deadline && sampleNext(work, deadline)) remaining--;
                if (work.pixels.isEmpty() && work.capture == null || !ACTIVE_LEVELS.contains(work.level) || work.map.locked) {
                    PENDING.remove(work.map, work);
                } else {
                    queue.add(work);
                }
            }
        }
    }

    @SubscribeEvent public static void load(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            synchronized (LIFECYCLE) { ACTIVE_LEVELS.add(level); }
        }
    }

    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            synchronized (LIFECYCLE) {
                ACTIVE_LEVELS.remove(level);
                PENDING.entrySet().removeIf(entry -> entry.getValue().level == level);
                ConcurrentLinkedQueue<Work> queue = QUEUES.get(level.getServer());
                if (queue != null) queue.removeIf(work -> work.level == level);
            }
        }
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        synchronized (LIFECYCLE) {
            ACTIVE_LEVELS.removeIf(level -> level.getServer() == event.getServer());
            PENDING.entrySet().removeIf(entry -> entry.getValue().level.getServer() == event.getServer());
            QUEUES.remove(event.getServer());
        }
        MapNetwork.clear();
    }

    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) MapNetwork.forget(player);
    }

    private static final class Work {
        private final ServerLevel level;
        private final MapItemSavedData map;
        private final PendingPixels pixels = new PendingPixels();
        private int activeIndex = -1;
        private byte activeColor;
        private MapSampler.PixelCapture capture;
        private Work(ServerLevel level, MapItemSavedData map) { this.level = level; this.map = map; }
        private void clearActive() { activeIndex = -1; capture = null; }
    }
}
