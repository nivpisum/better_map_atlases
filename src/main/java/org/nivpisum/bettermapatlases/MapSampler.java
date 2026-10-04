/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

public final class MapSampler {
    private MapSampler() {}

    /** Invoked only for the pixels the vanilla update has actually visited. */
    public static void capture(Level level, MapItemSavedData map, int pixelX, int pixelZ, byte color) {
        if (!(level instanceof ServerLevel server) || map.locked
            || level.dimensionType().hasCeiling()) return;
        int index = pixelX + pixelZ * 128;
        if (!server.getServer().isSameThread()) {
            DeferredMapSamples.request(server, map, index, color);
            return;
        }
        synchronized (map) {
            DeferredMapSamples.cancel(map, index);
            captureNow(server, map, pixelX, pixelZ);
        }
    }

    /** An external vanilla edit clears both stale metadata and deferred sampling for that pixel. */
    public static void invalidate(MapItemSavedData map, int pixelX, int pixelZ) {
        synchronized (map) {
            int index = pixelX + pixelZ * 128;
            DeferredMapSamples.cancel(map, index);
            MapLayer layer = ((MapLayerAccess) map).accurateMaps$getLayer(false);
            if (layer != null) layer.set(index, null, null, 0);
        }
    }

    /** Caller holds the owning map monitor and runs on the server thread. */
    static void captureNow(ServerLevel level, MapItemSavedData map, int pixelX, int pixelZ) {
        captureStep(level, map, new PixelCapture(pixelX, pixelZ), Long.MAX_VALUE);
    }

    /** False retains a partial pixel; true finishes or safely rejects the entire sample. */
    static boolean captureStep(ServerLevel level, MapItemSavedData map, PixelCapture pixel, long deadline) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Map sampling must run on the server thread");
        if (map.locked || level.dimensionType().hasCeiling()) return true;
        int scale = 1 << map.scale;
        int startX = (map.centerX / scale + pixel.x - 64) * scale;
        int startZ = (map.centerZ / scale + pixel.z - 64) * scale;
        LoadedWorld world = new LoadedWorld(level, deadline);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        try {
            while (pixel.column < scale * scale) {
                int x = startX + pixel.column / scale;
                int z = startZ + pixel.column % scale;
                // Never acquire a ticket, generate, wait for, or load a chunk.
                LevelChunk chunk = world.chunk(x, z);
                if (chunk == null || chunk.isEmpty()) return true;
                if (pixel.y == Integer.MAX_VALUE)
                    pixel.y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockState state = Blocks.BEDROCK.defaultBlockState();
                if (pixel.y > level.getMinBuildHeight()) {
                    while (true) {
                        pos.set(x, pixel.y, z); state = world.getBlockState(pos);
                        if (state.getMapColor(world, pos) != MapColor.NONE || pixel.y <= level.getMinBuildHeight() + 1) break;
                        pixel.y--;
                    }
                    if (!state.getFluidState().isEmpty() && !state.isFaceSturdy(world, pos, Direction.UP)) {
                        state = state.getFluidState().createLegacyBlock();
                    }
                } else {
                    pos.set(x, level.getMinBuildHeight(), z);
                }
                if (!state.isAir() && state.getMapColor(world, pos) != MapColor.NONE) {
                    ResourceLocation biome = chunk.getNoiseBiome(x >> 2, pos.getY() >> 2, z >> 2)
                        .unwrapKey().map(key -> key.location()).orElse(null);
                    if (biome != null) {
                        SampleKey key = new SampleKey(state, biome);
                        SampleCount count = pixel.counts.get(key);
                        if (count == null) pixel.counts.put(key, new SampleCount(pos.asLong()));
                        else count.count++;
                    }
                }
                if (world.missing) return true;
                pixel.column++; pixel.y = Integer.MAX_VALUE;
            }
        } catch (BudgetExhausted exhausted) { return false; }
        Map.Entry<SampleKey, SampleCount> best = null;
        for (Map.Entry<SampleKey, SampleCount> entry : pixel.counts.entrySet()) {
            if (best == null || entry.getValue().count > best.getValue().count) best = entry;
        }
        MapLayerAccess access = (MapLayerAccess) map;
        MapLayer layer = access.accurateMaps$getLayer(best != null);
        if (layer == null) return true;
        boolean changed = best == null
            ? layer.set(pixel.x + pixel.z * 128, null, null, 0)
            : layer.set(pixel.x + pixel.z * 128, best.getKey().state, best.getKey().biome, best.getValue().position);
        if (changed) map.setDirty();
        return true;
    }

    static final class PixelCapture {
        private final int x, z;
        private int column, y = Integer.MAX_VALUE;
        private final Map<SampleKey, SampleCount> counts = new LinkedHashMap<>();
        PixelCapture(int x, int z) { this.x = x; this.z = z; }
    }

    /** Shape and colour callbacks also get a nonloading accessor, including neighbour reads. */
    private static final class LoadedWorld implements BlockGetter {
        private final ServerLevel level;
        private final long deadline;
        private final Map<Long, LevelChunk> chunks = new HashMap<>();
        private boolean missing;
        private LoadedWorld(ServerLevel level, long deadline) { this.level = level; this.deadline = deadline; }
        private void check() { if (System.nanoTime() >= deadline) throw BudgetExhausted.INSTANCE; }
        private LevelChunk chunk(int x, int z) {
            check();
            long key = (long) (x >> 4) << 32 | (z >> 4) & 0xffffffffL;
            if (!chunks.containsKey(key)) chunks.put(key, level.getChunkSource().getChunkNow(x >> 4, z >> 4));
            LevelChunk chunk = chunks.get(key);
            if (chunk == null || chunk.isEmpty()) { missing = true; return null; }
            return chunk;
        }
        @Override public BlockState getBlockState(BlockPos pos) {
            if (pos.getY() < getMinBuildHeight() || pos.getY() >= getMaxBuildHeight()) return Blocks.BEDROCK.defaultBlockState();
            LevelChunk chunk = chunk(pos.getX(), pos.getZ());
            return chunk == null ? Blocks.BEDROCK.defaultBlockState() : chunk.getBlockState(pos);
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            LevelChunk chunk = chunk(pos.getX(), pos.getZ());
            return chunk == null ? null : chunk.getBlockEntities().get(pos);
        }
        @Override public int getHeight() { return level.getHeight(); }
        @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
    }
    private static final class BudgetExhausted extends RuntimeException {
        private static final BudgetExhausted INSTANCE = new BudgetExhausted();
        private BudgetExhausted() { super(null, null, false, false); }
    }

    private record SampleKey(BlockState state, ResourceLocation biome) {}
    private static final class SampleCount {
        private int count = 1;
        private final long position;
        private SampleCount(long position) { this.position = position; }
    }
}
