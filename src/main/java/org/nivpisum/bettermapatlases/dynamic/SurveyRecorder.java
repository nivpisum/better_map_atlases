/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.nivpisum.bettermapatlases.BetterMapAtlases;
import org.nivpisum.bettermapatlases.MapLayer;
import org.nivpisum.bettermapatlases.MapLayerAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;

/** Server-thread surveying with a shared per-player budget and no loading world accessor. */
public final class SurveyRecorder {
    private static final int MAX_VIEW_CONTEXTS = 32;
    private static final int MAX_ACTIVE_MAPS = 32;
    private static final int MAX_BLOCK_READS_PER_TICK = 16_384;
    private static final int MAX_SURFACE_COLUMNS_PER_TICK = 1_024;
    private static final int REVISIT_TICKS = 20;
    private static final int AVATAR_REVISIT_TICKS = 4;
    private static final int WARMUP_SAMPLING_TICKS = 100;
    private static final Map<ServerPlayer, PlayerState> PLAYERS = new WeakHashMap<>();
    private static final AtomicLong OFF_THREAD_REJECTIONS = new AtomicLong();
    private SurveyRecorder() {}

    /** True means this is a dynamic page, including a deliberate no-op for locked/disabled pages. */
    public static boolean update(Level level, Entity entity, MapItemSavedData map) {
        if (!(map instanceof SurveyAccess access) || !SurveyAtlas.isManaged(map)) return false;
        if (!(level instanceof ServerLevel server) || !(entity instanceof ServerPlayer player)) return true;
        if (!server.getServer().isSameThread()) { OFF_THREAD_REJECTIONS.incrementAndGet(); return true; }
        if (map.locked || !BetterMapAtlases.SURVEY_ENABLED.get() || !map.dimension.equals(server.dimension())) return true;
        synchronized (PLAYERS) {
            PlayerState state = state(player);
            beginTick(state, player);
            MapJob previousJob = state.maps.get(map);
            if (previousJob != null && previousJob.refresh.alreadyUpdated(state.tick)) return true;
            if (!hasBudget(state)) return true;
            long started = System.nanoTime();
            try {
                LoadedWorld world = new LoadedWorld(server, state, deadline(state, started));
                Observation observation = context(player, state, world);
                if (observation == null || world.missing) return true;
                // Browsing another page must never reveal a place the carrier has not visited.
                if (SurveyAtlas.isDynamic(map) != observation.rawCave) return true;
                MapJob job = job(state, map, observation);
                job.refresh.beginUpdate(state.tick);
                SurveyLayer layer = access.accurateMaps$getSurvey(false);
                // This bookmark belongs to the physically held/shared page, not a player journal.
                if (layer != null && layer.columnKey(Mth.floor(observation.position.x), Mth.floor(observation.position.z)) >= 0)
                    layer.rememberView(observation.view.cave(), observation.view.anchorY16());
                long observedBefore = state.acceptedObservations;
                // Fresh physical pose gets at most a quarter of the remaining time. The
                // captured background footprint still receives the rest of each tick.
                revisitAvatar(player, state, world, map, layer, job, observation);
                if (job.cave) surveyCave(player, state, world, map, layer, job);
                else surveySurface(state, world, map, job);
                if (layer != null && state.acceptedObservations != observedBefore)
                    layer.rememberView(observation.view.cave(), observation.view.anchorY16());
            } catch (BudgetExhausted exhausted) {
                state.budgetExhaustions++;
            } finally { finishWork(state, started); }
        }
        return true;
    }

    /** Refreshes only the viewer context; it never creates map exploration. */
    public static SurveyView currentView(ServerPlayer player) {
        synchronized (PLAYERS) {
            if (!player.serverLevel().getServer().isSameThread()) {
                OFF_THREAD_REJECTIONS.incrementAndGet();
                PlayerState cached = PLAYERS.get(player);
                return cached == null ? null : cached.views.get(player.level().dimension().location());
            }
            PlayerState state = state(player);
            if (player.serverLevel().getServer().isSameThread()) {
                beginTick(state, player);
                if (hasBudget(state)) {
                    long started = System.nanoTime();
                    try { context(player, state, new LoadedWorld(player.serverLevel(), state, deadline(state, started))); }
                    catch (BudgetExhausted exhausted) { state.budgetExhaustions++; }
                    finally { finishWork(state, started); }
                }
            }
            return state.views.get(player.level().dimension().location());
        }
    }

    /** Only dimensions with an actual previous viewer context appear here. */
    public static SurveyView viewFor(ServerPlayer player, ResourceLocation dimension) {
        synchronized (PLAYERS) { return state(player).views.get(dimension); }
    }
    public static Collection<SurveyView> viewsFor(ServerPlayer player) {
        synchronized (PLAYERS) { return List.copyOf(state(player).views.values()); }
    }
    public static void forget(ServerPlayer player) { synchronized (PLAYERS) { PLAYERS.remove(player); } }
    public static void clear() { synchronized (PLAYERS) { PLAYERS.clear(); OFF_THREAD_REJECTIONS.set(0); } }

    public static Map<String, Object> describe() {
        synchronized (PLAYERS) {
            return Map.of("onlineContexts", PLAYERS.size(), "offThreadRejections", OFF_THREAD_REJECTIONS.get(),
                "loadedOnly", true, "chunkRequests", 0, "worldWrites", 0,
                "budgetMicrosPerPlayerTick", BetterMapAtlases.SURVEY_BUDGET_MICROS.get(),
                "maxBlockReadsPerPlayerTick", MAX_BLOCK_READS_PER_TICK,
                "maxCaveCandidatesPerPlayerTick", BetterMapAtlases.SURVEY_COLUMNS_PER_TICK.get(),
                "maxSurfaceCandidatesPerPlayerTick", MAX_SURFACE_COLUMNS_PER_TICK);
        }
    }
    public static Map<String, Object> describe(ServerPlayer player) {
        synchronized (PLAYERS) {
            PlayerState state = state(player);
            Map<String, Object> result = new LinkedHashMap<>(describe());
            result.put("tick", state.tick); result.put("tickMicros", state.tickNanos / 1000);
            result.put("maxTickMicros", state.maxTickNanos / 1000);
            result.put("warmupSamplingTicks", WARMUP_SAMPLING_TICKS);
            result.put("completedSamplingTicks", state.completedSamplingTicks);
            result.put("steadyCompletedTicks", state.steadyTicks);
            result.put("steadyMaxTickMicros", state.steadyMaxTickNanos / 1000);
            result.put("steadyAverageTickMicros", state.steadyTicks == 0 ? 0.0
                : state.steadyTotalNanos / (1000.0 * state.steadyTicks));
            result.put("steadyBudgetOverruns", state.steadyBudgetOverruns);
            result.put("tickCandidates", state.tickCandidates); result.put("blockReads", state.blockReads);
            result.put("tickCaveCandidates", state.tickCaveCandidates);
            result.put("tickSurfaceCandidates", state.tickSurfaceCandidates);
            result.put("caveCandidates", state.caveCandidates); result.put("surfaceCandidates", state.surfaceCandidates);
            result.put("loadedChunkLookups", state.chunkLookups); result.put("missingChunkReads", state.missingChunkReads);
            result.put("candidates", state.candidates); result.put("caveCommits", state.caveCommits);
            result.put("surfacePixels", state.surfacePixels); result.put("occluded", state.occluded);
            result.put("budgetExhaustions", state.budgetExhaustions); result.put("views", List.copyOf(state.views.values()));
            result.put("activeMaps", state.maps.size());
            result.put("avatarRevisitTicks", AVATAR_REVISIT_TICKS);
            result.put("backgroundRevisitTicks", REVISIT_TICKS);
            return result;
        }
    }

    private static PlayerState state(ServerPlayer player) {
        PlayerState found = PLAYERS.get(player);
        if (found != null) return found;
        PlayerState state = new PlayerState();
        PLAYERS.put(player, state);
        return state;
    }

    private static void beginTick(PlayerState state, ServerPlayer player) {
        int tick = player.serverLevel().getServer().getTickCount();
        if (state.tick == tick) return;
        state.maxTickNanos = Math.max(state.maxTickNanos, state.tickNanos);
        if (state.tickNanos > 0) {
            state.completedSamplingTicks++;
            if (state.completedSamplingTicks > WARMUP_SAMPLING_TICKS) {
                state.steadyTicks++; state.steadyTotalNanos += state.tickNanos;
                state.steadyMaxTickNanos = Math.max(state.steadyMaxTickNanos, state.tickNanos);
                if (state.tickNanos > BetterMapAtlases.SURVEY_BUDGET_MICROS.get() * 1000L) state.steadyBudgetOverruns++;
            }
        }
        state.tick = tick; state.tickNanos = 0; state.tickCandidates = 0; state.tickBlockReads = 0;
        state.tickCaveCandidates = 0; state.tickSurfaceCandidates = 0;
        state.observation = null;
    }
    private static boolean hasBudget(PlayerState state) {
        return state.tickNanos < BetterMapAtlases.SURVEY_BUDGET_MICROS.get() * 1000L
            && state.tickBlockReads < MAX_BLOCK_READS_PER_TICK;
    }
    private static long deadline(PlayerState state, long started) {
        return started + Math.max(0, BetterMapAtlases.SURVEY_BUDGET_MICROS.get() * 1000L - state.tickNanos);
    }
    private static void finishWork(PlayerState state, long started) {
        state.tickNanos += System.nanoTime() - started;
        state.maxTickNanos = Math.max(state.maxTickNanos, state.tickNanos);
    }

    private static Observation context(ServerPlayer player, PlayerState state, LoadedWorld world) {
        Vec3 position = player.position();
        Vec3 eye = player.getEyePosition();
        ResourceLocation dimension = player.level().dimension().location();
        if (state.observation != null && state.observation.dimension.equals(dimension)
            && state.observation.position.equals(position) && state.observation.eye.equals(eye)) return state.observation;
        state.observation = null;
        int x = Mth.floor(position.x), z = Mth.floor(position.z);
        SurveyGeometry.Column geometry = SurveyGeometry.inspect(world, x, z, position.x - x, position.z - z,
            position.y + 1.0 / 16.0, BetterMapAtlases.SURVEY_SCAN_REACH.get());
        if (geometry == null || world.missing) return null;
        boolean cave = geometry.structuralRoof()
            || structuralHeightmapRoof(world, x, z, position.y);
        if (world.missing) return null;
        int feet = SurveyGeometry.fixed16(position.y);
        int desired = SurveyGeometry.supportAnchor(geometry, feet);
        SurveyView previous = state.views.get(dimension);
        boolean dimensionChange = !dimension.equals(state.lastDimension);
        boolean teleport = state.lastPosition != null && (state.lastPosition.distanceToSqr(position) > 64
            || Math.abs(state.lastPosition.y - position.y) > 6);
        boolean separated = previous != null && !geometry.contains(previous.anchorY16());
        boolean immediate = previous == null || dimensionChange || teleport || separated;
        boolean displayedCave = cave;
        if (!cave && !immediate && previous.cave() != cave) {
            if (state.pendingMode == null || state.pendingMode != cave) {
                state.pendingMode = cave; state.pendingModeSince = state.tick;
            }
            if (state.tick - state.pendingModeSince < BetterMapAtlases.SURVEY_STABILITY_TICKS.get()) displayedCave = previous.cave();
        } else state.pendingMode = null;
        if (!immediate && previous != null && Math.abs(desired - previous.anchorY16()) < 8) desired = previous.anchorY16();
        SurveyView view = previous;
        if (previous == null || previous.cave() != displayedCave || previous.anchorY16() != desired) {
            view = new SurveyView(dimension, displayedCave, desired, ++state.viewRevision);
            if (!state.views.containsKey(dimension) && state.views.size() >= MAX_VIEW_CONTEXTS)
                state.views.remove(state.views.keySet().iterator().next());
            state.views.put(dimension, view);
        }
        if (dimensionChange || teleport) state.maps.clear();
        state.lastDimension = dimension; state.lastPosition = position;
        state.observation = new Observation(dimension, position, eye, feet, cave, view, geometry);
        return state.observation;
    }

    private static boolean structuralHeightmapRoof(LoadedWorld world, int x, int z, double feetY) {
        LevelChunk chunk = world.chunk(x, z);
        if (chunk == null) return false;
        int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= feetY + 1.5) return false;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = world.getBlockState(pos);
        return !state.is(BlockTags.LEAVES) && !state.getCollisionShape(world, pos).isEmpty();
    }

    private static MapJob job(PlayerState state, MapItemSavedData map, Observation observation) {
        boolean cave = observation.rawCave;
        MapJob job = state.maps.get(map);
        if (job == null || !job.matches(observation, cave)) {
            if (state.maps.size() >= MAX_ACTIVE_MAPS) state.maps.remove(state.maps.keySet().iterator().next());
            job = new MapJob(observation, cave); state.maps.put(map, job);
        } else if (job.completedTick >= 0 && state.tick - job.completedTick >= REVISIT_TICKS) {
            MapJob renewed = new MapJob(observation, cave);
            renewed.refresh = job.refresh;
            job = renewed; state.maps.put(map, job);
        }
        return job;
    }

    private static void revisitAvatar(ServerPlayer player, PlayerState state, LoadedWorld background,
                                      MapItemSavedData map, SurveyLayer layer, MapJob job, Observation observation) {
        int scale = 1 << map.scale;
        int x = Mth.floor(observation.position.x), z = Mth.floor(observation.position.z);
        int px = Math.floorDiv(x - (map.centerX - 64 * scale), scale);
        int pz = Math.floorDiv(z - (map.centerZ - 64 * scale), scale);
        if (!job.cave && (px < 0 || px >= 128 || pz < 0 || pz >= 128)) return;
        long key = job.cave ? BlockPos.asLong(x, Mth.floor(observation.position.y), z) : px + pz * 128;
        if (!job.refresh.avatarDue(state.tick, key)) return;
        long now = System.nanoTime();
        LoadedWorld world = new LoadedWorld(background.level, state, now + Math.max(0, background.deadline - now) / 4);
        try {
            if (job.cave) {
                if (!canAttemptCave(state)) return;
                attemptCave(player, state, world, layer, observation, x, z);
            } else {
                if (job.avatarSurface == null || job.avatarSurface.x != px || job.avatarSurface.z != pz)
                    job.avatarSurface = new SurfacePixel(px, pz);
                SurfacePixel pixel = job.avatarSurface;
                int columns = Math.min(scale * scale, MAX_SURFACE_COLUMNS_PER_TICK / 4);
                while (pixel.column < scale * scale && columns-- > 0 && canAttemptSurface(state)) {
                    world.check();
                    state.tickCandidates++; state.candidates++;
                    state.tickSurfaceCandidates++; state.surfaceCandidates++;
                    long missingBefore = state.missingChunkReads;
                    SurfaceColumn column = surfaceColumn(world, map.centerX - 64 * scale + px * scale + pixel.column % scale,
                        map.centerZ - 64 * scale + pz * scale + pixel.column / scale);
                    if (column == null || state.missingChunkReads != missingBefore) {
                        job.avatarSurface = null; job.refresh.avatarFinished(state.tick, key); return;
                    }
                    pixel.accept(column); pixel.column++;
                }
                if (pixel.column < scale * scale) return;
                commitSurface(map, pixel, scale, state);
                job.avatarSurface = null;
            }
            job.refresh.avatarFinished(state.tick, key);
        } catch (BudgetExhausted exhausted) {
            // Keep the partial surface pixel. Exhausting its small priority slice
            // must not prevent the background cursor from advancing this tick.
            state.budgetExhaustions++;
        }
    }

    private static void surveyCave(ServerPlayer player, PlayerState state, LoadedWorld world,
                                   MapItemSavedData map, SurveyLayer layer, MapJob job) {
        int radius = Math.min(64, BetterMapAtlases.SURVEY_RADIUS.get());
        int span = radius * 2 + 1;
        int cx = Mth.floor(job.observation.position.x), cz = Mth.floor(job.observation.position.z);
        if (job.completedTick >= 0) return;
        while (job.cursor < span * span && canAttemptCave(state)) {
            world.check();
            long offset = SurveyGeometry.spiral(job.cursor);
            int dx = (int) (offset >> 32), dz = (int) offset;
            if (dx * dx + dz * dz > radius * radius) { job.cursor++; continue; }
            attemptCave(player, state, world, layer, job.observation, cx + dx, cz + dz);
            job.cursor++;
        }
        if (job.cursor >= span * span) job.completedTick = state.tick;
    }

    private static boolean canAttemptCave(PlayerState state) {
        return state.tickCaveCandidates < BetterMapAtlases.SURVEY_COLUMNS_PER_TICK.get();
    }
    private static boolean canAttemptSurface(PlayerState state) {
        return state.tickSurfaceCandidates < MAX_SURFACE_COLUMNS_PER_TICK;
    }
    private static void attemptCave(ServerPlayer player, PlayerState state, LoadedWorld world,
                                    SurveyLayer layer, Observation observation, int x, int z) {
        state.tickCandidates++; state.candidates++;
        state.tickCaveCandidates++; state.caveCandidates++;
        int key = layer.columnKey(x, z);
        if (key < 0) return;
        long missingBefore = state.missingChunkReads;
        boolean avatar = x == Mth.floor(observation.position.x) && z == Mth.floor(observation.position.z);
        double localX = avatar ? observation.position.x - x : 0.5;
        double localZ = avatar ? observation.position.z - z : 0.5;
        SurveyGeometry.Column column = SurveyGeometry.inspect(world, x, z, localX, localZ,
            observation.position.y + 1.0 / 16.0, BetterMapAtlases.SURVEY_SCAN_REACH.get());
        if (column == null || state.missingChunkReads != missingBefore) return;
        if (column.solidAtQuery() || column.lowerKnown() && !column.hasFloorSpace()) {
            SurveyGeometry.Column uphill = SurveyGeometry.spaceAbove(world, x, z, localX, localZ,
                column, observation.position.y, BetterMapAtlases.SURVEY_SCAN_REACH.get());
            if (uphill != null && uphill.lowerKnown()) {
                double surface = uphill.fluid() == null ? uphill.floorY16() / 16.0 : uphill.fluidSurfaceY16() / 16.0;
                BlockPos endpoint = uphill.fluid() == null ? uphill.floorPosition() : uphill.fluidPosition();
                if (uphill.fluid() == null
                    ? SurveyGeometry.visibleFloor(world, observation.eye, uphill, x + localX, z + localZ, player)
                    : SurveyGeometry.visible(world, observation.eye, new Vec3(x + localX, surface + 0.03, z + localZ), player, endpoint))
                    column = uphill;
            }
        }
        if (!column.solidAtQuery() && column.lowerKnown() && !column.hasFloorSpace()) { state.occluded++; return; }
        SurveySample.Kind kind;
        BlockState sampleState;
        BlockPos tintPosition;
        double targetY;
        if (column.solidAtQuery()) {
            kind = SurveySample.Kind.WALL; sampleState = column.floor(); tintPosition = column.floorPosition();
            targetY = Mth.clamp(observation.eye.y, column.minY16() / 16.0 + 0.01, column.maxY16() / 16.0 - 0.01);
        } else if (column.fluid() != null) {
            kind = SurveySample.Kind.FLUID; sampleState = column.fluid(); tintPosition = column.fluidPosition();
            targetY = Math.min(column.fluidSurfaceY16() / 16.0 + 0.03,
                Mth.clamp(observation.eye.y, column.minY16() / 16.0 + 0.03, column.maxY16() / 16.0 - 0.03));
        } else if (column.lowerKnown()) {
            kind = SurveySample.Kind.FLOOR; sampleState = column.floor(); tintPosition = column.floorPosition();
            targetY = column.floorY16() / 16.0 + 0.03;
        } else {
            kind = SurveySample.Kind.OPEN_DEPTH; sampleState = null;
            tintPosition = new BlockPos(x, Mth.floor(observation.position.y), z);
            targetY = Mth.clamp(observation.eye.y, column.minY16() / 16.0 + 0.03, column.maxY16() / 16.0 - 0.03);
        }
        Vec3 target = new Vec3(x + localX, targetY, z + localZ);
        if (kind == SurveySample.Kind.WALL) {
            SurveyGeometry.WallFace face = SurveyGeometry.visibleWallFace(world, observation.eye, target, player,
                observation.position.y + 1.0 / 16.0, BetterMapAtlases.SURVEY_SCAN_REACH.get());
            if (face == null) { state.occluded++; return; }
            sampleState = face.state(); tintPosition = face.position();
        } else if (!(kind == SurveySample.Kind.FLOOR
            ? SurveyGeometry.visibleFloor(world, observation.eye, column, x + localX, z + localZ, player)
            : SurveyGeometry.visible(world, observation.eye, target, player, null))) {
            state.occluded++;
            if (kind == SurveySample.Kind.OPEN_DEPTH) return;
            // A low floor may be hidden by a lip although the nearby opening itself is visible.
            // Record only that opening; its occluded surface appearance remains unknown.
            targetY = Mth.clamp(observation.eye.y, column.minY16() / 16.0 + 0.03, column.maxY16() / 16.0 - 0.03);
            if (!SurveyGeometry.visible(world, observation.eye,
                new Vec3(x + localX, targetY, z + localZ), player, null)) { state.occluded++; return; }
            kind = SurveySample.Kind.OPEN_DEPTH; sampleState = null;
            tintPosition = new BlockPos(x, Mth.floor(observation.position.y), z);
        }
        if (state.missingChunkReads != missingBefore) return;
        int localWitness = switch (kind) {
            case FLOOR -> column.floorY16();
            case FLUID -> column.fluidSurfaceY16();
            default -> observation.feetY16;
        };
        localWitness = Mth.clamp(localWitness, column.minY16(), column.maxY16() - 1);
        SurveyGeometry.ObservedWindow observed = SurveyGeometry.observedWindow(column, localWitness,
            BetterMapAtlases.SURVEY_OBSERVATION_WINDOW.get());
        if (observed == null) return;
        LevelChunk chunk = world.chunk(x, z);
        if (chunk == null) return;
        ResourceLocation biome = biome(chunk, x, tintPosition.getY(), z);
        if (biome == null) return;
        byte shade = (byte) MapColor.Brightness.NORMAL.id;
        SurveySample sample = new SurveySample(observed.minimumY16(), observed.maximumY16(), column.minY16(), column.maxY16(),
            kind == SurveySample.Kind.FLUID ? column.fluidSurfaceY16() : column.floorY16(), sampleState, biome, tintPosition.asLong(), shade, kind,
            column.lowerKnown(), column.upperKnown());
        world.check();
        state.acceptedObservations++;
        if (layer.observe(key, sample)) state.caveCommits++;
    }

    /** A resumable conventional top-down pixel scan, using only chunks already present now. */
    private static void surveySurface(PlayerState state, LoadedWorld world, MapItemSavedData map, MapJob job) {
        if (job.completedTick >= 0) return;
        int scale = 1 << map.scale;
        int radius = Math.max(1, 128 / scale), span = radius * 2 + 1;
        int minX = map.centerX - 64 * scale, minZ = map.centerZ - 64 * scale;
        int cx = Math.floorDiv(Mth.floor(job.observation.position.x) - minX, scale);
        int cz = Math.floorDiv(Mth.floor(job.observation.position.z) - minZ, scale);
        while (job.cursor < span * span && canAttemptSurface(state)) {
            world.check();
            if (job.surface == null) {
                long offset = SurveyGeometry.spiral(job.cursor);
                int dx = (int) (offset >> 32), dz = (int) offset;
                int px = cx + dx, pz = cz + dz;
                if (dx * dx + dz * dz > radius * radius || px < 0 || px >= 128 || pz < 0 || pz >= 128) {
                    job.cursor++; continue;
                }
                job.surface = new SurfacePixel(px, pz);
            }
            SurfacePixel pixel = job.surface;
            int x = minX + pixel.x * scale + pixel.column % scale;
            int z = minZ + pixel.z * scale + pixel.column / scale;
            state.tickCandidates++; state.candidates++;
            state.tickSurfaceCandidates++; state.surfaceCandidates++;
            long missingBefore = state.missingChunkReads;
            SurfaceColumn column = surfaceColumn(world, x, z);
            if (column == null || state.missingChunkReads != missingBefore) {
                job.surface = null; job.cursor++; continue;
            }
            pixel.accept(column); pixel.column++;
            if (pixel.column == scale * scale) {
                commitSurface(map, pixel, scale, state);
                job.surface = null; job.cursor++;
            }
        }
        if (job.cursor >= span * span) job.completedTick = state.tick;
    }

    private static SurfaceColumn surfaceColumn(LoadedWorld world, int x, int z) {
        LevelChunk chunk = world.chunk(x, z);
        if (chunk == null) return null;
        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int bottom = Math.max(world.getMinBuildHeight(), top - 32);
        for (int y = top; y >= bottom; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = world.getBlockState(pos);
            if (state.isAir() || state.getMapColor(world, pos) == MapColor.NONE) continue;
            if (!state.getFluidState().isEmpty() && !state.isFaceSturdy(world, pos, Direction.UP))
                state = state.getFluidState().createLegacyBlock();
            ResourceLocation biome = biome(chunk, x, y, z);
            if (biome == null) return null;
            return new SurfaceColumn(state, biome, pos.asLong(), y, state.getMapColor(world, pos));
        }
        return null;
    }
    private static ResourceLocation biome(LevelChunk chunk, int x, int y, int z) {
        return chunk.getNoiseBiome(x >> 2, y >> 2, z >> 2).unwrapKey().map(key -> key.location()).orElse(null);
    }
    private static void commitSurface(MapItemSavedData map, SurfacePixel pixel, int scale, PlayerState state) {
        Map.Entry<SurfaceKey, SurfaceCount> best = null;
        for (Map.Entry<SurfaceKey, SurfaceCount> entry : pixel.counts.entrySet())
            if (best == null || entry.getValue().count > best.getValue().count) best = entry;
        if (best == null) return;
        SurfaceCount count = best.getValue();
        SurfaceKey key = best.getKey();
        MapLayer layer = ((MapLayerAccess) map).accurateMaps$getLayer(true);
        int index = pixel.x + pixel.z * 128;
        double height = pixel.height / (double) (scale * scale);
        double previousHeight = pixel.z > 0 && layer.state(index - 128) != null
            ? BlockPos.of(layer.position(index - 128)).getY() : height;
        double slope = (height - previousHeight) * 4.0 / (scale + 4.0)
            + (((pixel.x + pixel.z) & 1) - 0.5) * 0.4;
        MapColor.Brightness brightness = slope > 0.6 ? MapColor.Brightness.HIGH
            : slope < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        if (key.color == MapColor.WATER) brightness = MapColor.Brightness.NORMAL;
        boolean changed = map.updateColor(pixel.x, pixel.z, key.color.getPackedId(brightness));
        changed |= layer.set(index, key.state, key.biome, count.position);
        state.acceptedObservations++;
        if (changed) { map.setDirty(); state.surfacePixels++; }
    }

    private record Observation(ResourceLocation dimension, Vec3 position, Vec3 eye, int feetY16,
                               boolean rawCave, SurveyView view, SurveyGeometry.Column geometry) {}
    private static final class PlayerState {
        private int tick = Integer.MIN_VALUE, tickCandidates, tickCaveCandidates, tickSurfaceCandidates, tickBlockReads, pendingModeSince;
        private long tickNanos, maxTickNanos, viewRevision, blockReads, chunkLookups, missingChunkReads;
        private long completedSamplingTicks, steadyTicks, steadyTotalNanos, steadyMaxTickNanos, steadyBudgetOverruns;
        private long candidates, caveCandidates, surfaceCandidates, caveCommits, surfacePixels, occluded, budgetExhaustions, acceptedObservations;
        private ResourceLocation lastDimension;
        private Vec3 lastPosition;
        private Boolean pendingMode;
        private Observation observation;
        private final LinkedHashMap<ResourceLocation, SurveyView> views = new LinkedHashMap<>();
        private final LinkedHashMap<MapItemSavedData, MapJob> maps = new LinkedHashMap<>();
    }
    private static final class MapJob {
        private final Observation observation;
        private final boolean cave;
        private int cursor, completedTick = -1;
        private RefreshSchedule refresh = new RefreshSchedule();
        private SurfacePixel surface, avatarSurface;
        private MapJob(Observation observation, boolean cave) { this.observation = observation; this.cave = cave; }
        private boolean matches(Observation next, boolean nextCave) {
            if (cave != nextCave || !observation.dimension.equals(next.dimension)) return false;
            // A captured surface footprint can finish while the player walks across its origin chunk.
            // context() clears dimension/teleport jobs; the mode gate above rejects cave transitions.
            if (!cave) return SurveyGeometry.sameSurfaceChunk(observation.position.x, observation.position.z,
                next.position.x, next.position.z);
            // Finish nearby columns from an actually visited pose while walking, instead of restarting at every step.
            return observation.position.distanceToSqr(next.position) < 16
                && Math.max(observation.geometry.minY16(), next.geometry.minY16())
                    < Math.min(observation.geometry.maxY16(), next.geometry.maxY16());
        }
    }
    /** Pure scheduling state, independent of persistent map metadata and world geometry. */
    static final class RefreshSchedule {
        private int updatedAt = Integer.MIN_VALUE;
        private int avatarAt = Integer.MIN_VALUE / 2;
        private long avatarKey = Long.MIN_VALUE;
        boolean alreadyUpdated(int tick) { return updatedAt == tick; }
        boolean beginUpdate(int tick) {
            if (alreadyUpdated(tick)) return false;
            updatedAt = tick; return true;
        }
        boolean avatarDue(int tick, long key) {
            return key != avatarKey || tick - avatarAt >= AVATAR_REVISIT_TICKS;
        }
        void avatarFinished(int tick, long key) { avatarAt = tick; avatarKey = key; }
    }
    private record SurfaceColumn(BlockState state, ResourceLocation biome, long position, int height, MapColor color) {}
    private record SurfaceKey(BlockState state, ResourceLocation biome, MapColor color) {}
    private static final class SurfaceCount {
        private int count = 1; private final long position;
        private SurfaceCount(long position) { this.position = position; }
    }
    private static final class SurfacePixel {
        private final int x, z; private int column; private long height;
        private final Map<SurfaceKey, SurfaceCount> counts = new LinkedHashMap<>();
        private SurfacePixel(int x, int z) { this.x = x; this.z = z; }
        private void accept(SurfaceColumn sample) {
            SurfaceKey key = new SurfaceKey(sample.state, sample.biome, sample.color);
            SurfaceCount found = counts.get(key);
            if (found == null) counts.put(key, new SurfaceCount(sample.position)); else found.count++;
            height += sample.height;
        }
    }

    /** Every neighbour lookup made by collision/fluid/map-color code remains nonloading. */
    private static final class LoadedWorld implements BlockGetter {
        private final ServerLevel level;
        private final PlayerState state;
        private final long deadline;
        private final Map<Long, LevelChunk> chunks = new HashMap<>();
        private boolean missing;
        private LoadedWorld(ServerLevel level, PlayerState state, long deadline) {
            this.level = level; this.state = state; this.deadline = deadline;
        }
        private void check() {
            if (System.nanoTime() >= deadline || state.tickBlockReads >= MAX_BLOCK_READS_PER_TICK) throw BudgetExhausted.INSTANCE;
        }
        private LevelChunk chunk(int x, int z) {
            check();
            long key = (long) (x >> 4) << 32 | (z >> 4) & 0xffffffffL;
            LevelChunk chunk;
            if (chunks.containsKey(key)) chunk = chunks.get(key);
            else {
                state.chunkLookups++;
                chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                chunks.put(key, chunk);
            }
            if (chunk == null) { missing = true; state.missingChunkReads++; }
            return chunk;
        }
        @Override public BlockState getBlockState(BlockPos pos) {
            check(); state.blockReads++; state.tickBlockReads++;
            if (pos.getY() < getMinBuildHeight() || pos.getY() >= getMaxBuildHeight()) return Blocks.BEDROCK.defaultBlockState();
            LevelChunk chunk = chunk(pos.getX(), pos.getZ());
            return chunk == null ? Blocks.BEDROCK.defaultBlockState() : chunk.getBlockState(pos);
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getHeight() { return level.getHeight(); }
        @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
    }
    private static final class BudgetExhausted extends RuntimeException {
        private static final BudgetExhausted INSTANCE = new BudgetExhausted();
        private BudgetExhausted() { super(null, null, false, false); }
    }
}
