/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Bounded local column geometry; neither air connectivity nor a height band is a room ID. */
public final class SurveyGeometry {
    private static final double EPSILON = 1.0e-5;
    private SurveyGeometry() {}

    public record Column(int minY16, int maxY16, int floorY16,
                         boolean lowerKnown, boolean upperKnown, boolean solidAtQuery,
                         BlockState floor, BlockPos floorPosition,
                         BlockState ceiling, BlockPos ceilingPosition,
                         BlockState fluid, BlockPos fluidPosition, int fluidSurfaceY16) {
        public boolean structuralRoof() {
            return upperKnown && ceiling != null && !ceiling.is(BlockTags.LEAVES);
        }
        public boolean contains(int y16) { return y16 >= minY16 && y16 < maxY16; }
        /** Sub-block collision gaps are not terrain passages, even when a surface ray can reach them. */
        public boolean hasFloorSpace() { return !solidAtQuery && (!upperKnown || maxY16 - floorY16 >= SurveyProjection.MIN_PASSAGE_Y16); }
    }

    /** Uses collision geometry at the actual subcolumn, preserving slab/stair support heights. */
    public static Column inspect(BlockGetter world, int x, int z, double sampleX, double sampleZ,
                                 double queryY, int reach) {
        reach = Mth.clamp(reach, 1, 32);
        double lower = Math.max(world.getMinBuildHeight(), queryY - reach);
        double upper = Math.min(world.getMaxBuildHeight(), queryY + reach);
        if (upper <= lower) return null;
        int start = Mth.floor(queryY);
        BlockPos startPosition = new BlockPos(x, start, z);
        BlockState startState = world.getBlockState(startPosition);
        AABB containing = containing(startState, world, startPosition, sampleX, sampleZ, queryY);
        if (containing != null) {
            int min = fixed16(containing.minY + start);
            int max = fixed16(containing.maxY + start);
            return new Column(min, max, max, true, true, true,
                startState, startPosition, startState, startPosition, null, null, min);
        }
        BlockState floor = null, ceiling = null, fluid = null;
        BlockPos floorPosition = null, ceilingPosition = null, fluidPosition = null;
        double fluidY = lower;
        boolean lowerKnown = false, upperKnown = false;
        for (int y = start; y >= Math.floor(lower); y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = y == start ? startState : world.getBlockState(pos);
            double top = highestBelow(state, world, pos, sampleX, sampleZ, queryY);
            FluidState liquid = state.getFluidState();
            if (fluid == null && !liquid.isEmpty()) {
                double height = y + liquid.getHeight(world, pos);
                // Water/lava is coloured as the first visible medium. It is not a solid roof.
                if (height > lower && (Double.isNaN(top) || height > top + EPSILON)) {
                    fluid = liquid.createLegacyBlock(); fluidPosition = pos; fluidY = height;
                }
            }
            if (!Double.isNaN(top) && top >= lower - EPSILON) {
                lower = top; lowerKnown = true; floor = state; floorPosition = pos; break;
            }
        }
        for (int y = start; y <= Math.floor(upper - EPSILON); y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = y == start ? startState : world.getBlockState(pos);
            double bottom = lowestAbove(state, world, pos, sampleX, sampleZ, queryY);
            if (!Double.isNaN(bottom) && bottom <= upper + EPSILON) {
                upper = bottom; upperKnown = true; ceiling = state; ceilingPosition = pos; break;
            }
        }
        int min = fixed16(lower), max = fixed16(upper);
        if (max <= min) return null;
        return new Column(min, max, min, lowerKnown, upperKnown, false,
            floor, floorPosition, ceiling, ceilingPosition, fluid, fluidPosition, fixed16(fluidY));
    }

    /** An uphill floor can be above the observer's feet. Find its local air, then let the caller check visibility. */
    public static Column spaceAbove(BlockGetter world, int x, int z, double localX, double localZ,
                                    Column solid, double originalY, int reach) {
        double ceiling = Math.min(world.getMaxBuildHeight(), originalY + Mth.clamp(reach, 1, 32));
        double probe = solid.maxY16() / 16.0 + 1.0 / 16.0;
        while (probe < ceiling) {
            Column next = inspect(world, x, z, localX, localZ, probe, reach);
            if (next == null) return null;
            if (next.hasFloorSpace()) return next;
            probe = Math.max(probe + 1.0 / 16.0, next.maxY16() / 16.0 + 1.0 / 16.0);
        }
        return null;
    }

    /** Transparent-looking solid blocks remain barriers; fluids alone do not split spaces. */
    public static boolean visible(BlockGetter world, Vec3 eye, Vec3 target, Entity observer,
                                  BlockPos allowedWallEndpoint) {
        BlockHitResult hit = world.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, observer));
        return hit.getType() == HitResult.Type.MISS
            || allowedWallEndpoint != null && allowedWallEndpoint.equals(hit.getBlockPos());
    }

    /** A stair riser may hide the exact top pixel while the air immediately over that floor is plainly visible. */
    public static boolean visibleFloor(BlockGetter world, Vec3 eye, Column column, double x, double z, Entity observer) {
        double floor = column.floorY16() / 16.0;
        if (visible(world, eye, new Vec3(x, floor + 0.03, z), observer, column.floorPosition())) return true;
        double headroom = column.maxY16() / 16.0 - floor;
        double space = floor + Math.min(1.5, headroom / 2.0);
        return space > floor && visible(world, eye, new Vec3(x, space, z), observer, null);
    }

    public record WallFace(BlockState state, BlockPos position) {}

    /**
     * The eye ray can hit the next block up on a continuous wall before reaching its local slice.
     * Accept only that target X/Z's first face, with solid collision coverage back to the query height.
     * The returned appearance belongs to the face actually seen, not a hidden block at the feet.
     */
    public static WallFace visibleWallFace(BlockGetter world, Vec3 eye, Vec3 target, Entity observer,
                                           double queryY, int reach) {
        BlockHitResult hit = world.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, observer));
        int x = Mth.floor(target.x), z = Mth.floor(target.z);
        BlockPos hitPosition = hit.getBlockPos();
        if (hit.getType() != HitResult.Type.BLOCK || hitPosition.getX() != x || hitPosition.getZ() != z) return null;
        double hitY = hit.getLocation().y;
        if (Math.abs(hitY - queryY) > Mth.clamp(reach, 1, 32)
            || !continuousCollision(world, x, z, target.x - x, target.z - z, queryY, hitY)) return null;
        return new WallFace(world.getBlockState(hitPosition), hitPosition.immutable());
    }

    /** Local vertical collision coverage only; this never searches a connected room/component. */
    private static boolean continuousCollision(BlockGetter world, int x, int z, double localX, double localZ,
                                               double firstY, double secondY) {
        double reached = Math.min(firstY, secondY), end = Math.max(firstY, secondY);
        for (int y = Mth.floor(reached); y <= Mth.floor(end); y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = world.getBlockState(pos);
            var boxes = state.getCollisionShape(world, pos, CollisionContext.empty()).toAabbs();
            boolean extended;
            do {
                extended = false;
                for (AABB box : boxes) {
                    double low = y + box.minY, high = y + box.maxY;
                    if (covers(box, localX, localZ) && low <= reached + EPSILON && high > reached) {
                        reached = high; extended = true;
                    }
                }
            } while (extended && reached < end - EPSILON);
            if (reached >= end - EPSILON) return true;
            if (reached < y + 1 - EPSILON) return false;
        }
        return false;
    }

    public static int fixed16(double y) { return Mth.floor(y * 16.0); }

    /** Surface work retains an actually captured origin while walking, including in negative chunks. */
    static boolean sameSurfaceChunk(double originX, double originZ, double currentX, double currentZ) {
        return (Mth.floor(originX) >> 4) == (Mth.floor(currentX) >> 4)
            && (Mth.floor(originZ) >> 4) == (Mth.floor(currentZ) >> 4);
    }

    /** A jump over the same support does not move the presentation anchor to another floor. */
    static int supportAnchor(Column geometry, int feetY16) {
        boolean swimming = geometry.fluid() != null && feetY16 < geometry.fluidSurfaceY16();
        return !swimming && geometry.lowerKnown() && feetY16 - geometry.floorY16() >= -1
            && feetY16 - geometry.floorY16() <= 24 ? geometry.floorY16() : Math.floorDiv(feetY16, 8) * 8;
    }

    public record ObservedWindow(int minimumY16, int maximumY16) {}
    /** The scan reach is geometry, never permission to disclose the full height of a shaft. */
    public static ObservedWindow observedWindow(Column column, int feetY16, int windowBlocks) {
        int window = Mth.clamp(windowBlocks, 0, 4) * 16;
        int minimum = Math.max(column.minY16(), feetY16 - window);
        int maximum = Math.min(column.maxY16(), feetY16 + window + 1);
        return minimum < maximum ? new ObservedWindow(minimum, maximum) : null;
    }

    /** Square spiral, beginning with the avatar's column; allocation is independent of radius. */
    static long spiral(int index) {
        if (index == 0) return 0;
        int ring = (int) Math.ceil((Math.sqrt(index + 1.0) - 1.0) / 2.0);
        int side = ring * 2;
        int offset = index - (side - 1) * (side - 1);
        int x, z;
        if (offset < side) { x = ring; z = -ring + 1 + offset; }
        else if (offset < side * 2) { x = ring - 1 - (offset - side); z = ring; }
        else if (offset < side * 3) { x = -ring; z = ring - 1 - (offset - side * 2); }
        else { x = -ring + 1 + (offset - side * 3); z = -ring; }
        return (long) x << 32 | z & 0xffffffffL;
    }

    private static boolean covers(AABB box, double x, double z) {
        return x > box.minX - EPSILON && x < box.maxX + EPSILON
            && z > box.minZ - EPSILON && z < box.maxZ + EPSILON;
    }
    private static AABB containing(BlockState state, BlockGetter world, BlockPos pos,
                                   double x, double z, double queryY) {
        if (state.isAir()) return null;
        for (AABB box : state.getCollisionShape(world, pos, CollisionContext.empty()).toAabbs()) {
            if (covers(box, x, z) && queryY >= pos.getY() + box.minY - EPSILON
                && queryY < pos.getY() + box.maxY - EPSILON) return box;
        }
        return null;
    }
    private static double highestBelow(BlockState state, BlockGetter world, BlockPos pos,
                                       double x, double z, double queryY) {
        double result = Double.NaN;
        if (state.isAir()) return result;
        for (AABB box : state.getCollisionShape(world, pos, CollisionContext.empty()).toAabbs()) {
            double top = pos.getY() + box.maxY;
            if (covers(box, x, z) && top <= queryY + EPSILON && (Double.isNaN(result) || top > result)) result = top;
        }
        return result;
    }
    private static double lowestAbove(BlockState state, BlockGetter world, BlockPos pos,
                                      double x, double z, double queryY) {
        double result = Double.NaN;
        if (state.isAir()) return result;
        for (AABB box : state.getCollisionShape(world, pos, CollisionContext.empty()).toAabbs()) {
            double bottom = pos.getY() + box.minY;
            if (covers(box, x, z) && bottom >= queryY - EPSILON && (Double.isNaN(result) || bottom < result)) result = bottom;
        }
        return result;
    }
}
