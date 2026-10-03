/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/** Explicit detached geometry checks in Forge's class loader. This class never receives a live world. */
public final class SurveySamplingChecks {
    private SurveySamplingChecks() {}

    public static Map<String, Object> run() {
        List<String> passed = new ArrayList<>();
        Fixture stacked = new Fixture();
        stacked.put(0, -1, 0, Blocks.STONE.defaultBlockState());
        stacked.put(0, 2, 0, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        stacked.put(0, 6, 0, Blocks.STONE.defaultBlockState());
        SurveyGeometry.Column lower = inspect(stacked, 0.0625);
        SurveyGeometry.Column upper = inspect(stacked, 2.5625);
        require(lower.minY16() == 0 && lower.maxY16() == 32, "lower slab room interval");
        require(upper.minY16() == 40 && upper.maxY16() == 96, "upper slab support interval");
        require(!upper.contains(0) && !lower.contains(40), "thin floor immediately separates anchors");
        SurveyGeometry.ObservedWindow lowerWindow = SurveyGeometry.observedWindow(lower, 0, 4);
        require(lowerWindow.minimumY16() == 0 && lowerWindow.maximumY16() == 32, "witness clipped by slab");
        require(inspect(stacked, 2.25).solidAtQuery(), "solid query does not tunnel to a room above");
        require(SurveyGeometry.supportAnchor(inspect(stacked, 1.3125), 20) == 0, "jump keeps local support anchor");
        passed.add("stacked rooms inside one small height band, slab fractions, solid query and jump anchor");

        Fixture ramp = new Fixture();
        for (int x = 0; x <= 20; x++) {
            for (int y = 60; y < 65 + x; y++) ramp.put(x, y, 0, Blocks.STONE.defaultBlockState());
            ramp.put(x, 69 + x, 0, Blocks.STONE.defaultBlockState());
        }
        Vec3 rampEye = new Vec3(0.5, 66.62, 0.5);
        SurveyGeometry.Column cut = SurveyGeometry.inspect(ramp, 12, 0, 0.5, 0.5, 65.0625, 32);
        SurveyGeometry.Column raised = SurveyGeometry.spaceAbove(ramp, 12, 0, 0.5, 0.5, cut, 65, 32);
        require(cut.solidAtQuery() && raised != null && raised.floorY16() == 77 * 16, "uphill floor above avatar height found");
        Vec3 rampTarget = new Vec3(12.5, 77.03, 0.5);
        require(SurveyGeometry.visibleFloor(ramp, rampEye, raised, 12.5, 0.5, null), "visible air over an uphill tread accepted");
        SurveyGeometry.ObservedWindow localWindow = SurveyGeometry.observedWindow(raised, raised.floorY16(), 4);
        require(localWindow.minimumY16() == 77 * 16, "slope witness belongs to local surface height");
        for (int y = 65; y <= 78; y++) ramp.put(1, y, 0, Blocks.STONE.defaultBlockState());
        require(!SurveyGeometry.visibleFloor(ramp, rampEye, raised, 12.5, 0.5, null), "closed foreground wall still hides uphill room");
        passed.add("sloping cave picks local higher floor, keeps its height, and still rejects a sealed room");

        Fixture stairs = new Fixture();
        stairs.put(0, 0, 0, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
        SurveyGeometry.Column north = SurveyGeometry.inspect(stairs, 0, 0, 0.5, 0.25, 2.0625, 32);
        SurveyGeometry.Column south = SurveyGeometry.inspect(stairs, 0, 0, 0.5, 0.75, 2.0625, 32);
        require(Set.of(north.floorY16(), south.floorY16()).equals(Set.of(8, 16)), "stair fractional support");
        Fixture trapdoor = new Fixture();
        trapdoor.put(0, -1, 0, Blocks.STONE.defaultBlockState());
        trapdoor.put(0, 2, 0, Blocks.OAK_TRAPDOOR.defaultBlockState());
        require(inspect(trapdoor, 0.0625).maxY16() == 32, "closed trapdoor ceiling");
        require(inspect(trapdoor, 2.25).minY16() == 35, "three-sixteenths trapdoor support");
        passed.add("stairs and thin trapdoor collision shapes retain exact support heights");

        Fixture sealed = new Fixture();
        for (int y = -1; y <= 3; y++) sealed.put(0, y, 0, Blocks.STONE.defaultBlockState());
        Vec3 eye = new Vec3(-2.5, 1.62, 0.5), behind = new Vec3(2.5, 0.03, 0.5);
        require(!SurveyGeometry.visible(sealed, eye, behind, null, null), "sealed neighbor not observed");
        require(SurveyGeometry.visible(sealed, eye, new Vec3(0.5, 1.5, 0.5), null,
            new BlockPos(0, 1, 0)), "front wall itself can be observed");
        for (int y = -1; y <= 3; y++) sealed.put(0, y, 0, Blocks.GLASS.defaultBlockState());
        require(!SurveyGeometry.visible(sealed, eye, behind, null, null), "glass separates exploration");
        for (int y = 0; y <= 2; y++) sealed.put(0, y, 0, Blocks.AIR.defaultBlockState());
        require(SurveyGeometry.visible(sealed, eye, behind, null, null), "actual opening admits visible neighbor");
        passed.add("sealed same-height room and glass reject LOS; only an actual opening admits it");

        Fixture tallWall = new Fixture();
        tallWall.put(11, 65, 0, Blocks.STONE.defaultBlockState());
        tallWall.put(11, 66, 0, Blocks.BLUE_CONCRETE.defaultBlockState());
        tallWall.put(13, 65, 0, Blocks.RED_CONCRETE.defaultBlockState());
        Vec3 wallEye = new Vec3(0.5, 66.62, 0.5), wallTarget = new Vec3(11.5, 65.99, 0.5);
        require(!SurveyGeometry.visible(tallWall, wallEye, wallTarget, null, new BlockPos(11, 65, 0)),
            "regression fixture hits a different Y block on the target wall");
        SurveyGeometry.WallFace wallFace = SurveyGeometry.visibleWallFace(tallWall, wallEye, wallTarget, null, 65.0625, 32);
        require(wallFace != null && wallFace.position().equals(new BlockPos(11, 66, 0))
            && wallFace.state().is(Blocks.BLUE_CONCRETE), "continuous tall wall paints the actually visible face");
        SurveyGeometry.Column wallSlice = SurveyGeometry.inspect(tallWall, 11, 0, 0.5, 0.5, 65.0625, 32);
        require(wallSlice.minY16() == 65 * 16 && wallSlice.maxY16() == 66 * 16,
            "front-face correction keeps the original local wall slice");
        require(SurveyGeometry.visibleWallFace(tallWall, wallEye, new Vec3(13.5, 65.99, 0.5), null, 65.0625, 32) == null
            && !SurveyGeometry.visible(tallWall, wallEye, new Vec3(13.5, 65.03, 0.5), null, null),
            "front wall still hides the neighboring red wall and room floor");
        Fixture wallGap = new Fixture();
        wallGap.put(1, 65, 0, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        wallGap.put(1, 66, 0, Blocks.BLUE_CONCRETE.defaultBlockState());
        require(SurveyGeometry.visibleWallFace(wallGap, wallEye, new Vec3(1.5, 65.49, 0.5), null, 65.0625, 32) == null,
            "a gap does not join an upper face to the lower wall slice");
        passed.add("tall-wall eye-height hit retains its local slice and visible color without revealing a sealed neighbor");

        Fixture shaft = new Fixture();
        shaft.put(0, -50, 0, Blocks.STONE.defaultBlockState());
        SurveyGeometry.Column opening = inspect(shaft, 10.0625);
        require(!opening.lowerKnown() && !opening.upperKnown() && opening.floor() == null, "scan endpoints stay unknown");
        require(shaft.reads <= 70, "shaft scan is hard bounded");
        SurveyGeometry.ObservedWindow window = SurveyGeometry.observedWindow(opening, 160, 100);
        require(window.minimumY16() == 96 && window.maximumY16() == 225, "only actual plus-minus-four witness");
        require(window.maximumY16() - window.minimumY16() < opening.maxY16() - opening.minY16(), "shaft geometry not exploration");
        passed.add("deep shaft retains unknown scan ends and a clipped observation window");

        Fixture pit = new Fixture();
        pit.put(2, -13, 0, Blocks.STONE.defaultBlockState());
        SurveyGeometry.Column pitColumn = SurveyGeometry.inspect(pit, 2, 0, 0.5, 0.5, 4.0625, 32);
        SurveyGeometry.ObservedWindow pitWindow = SurveyGeometry.observedWindow(pitColumn, 64, 4);
        Vec3 pitEye = new Vec3(-2.5, 4.62, 0.5);
        Vec3 pitFloor = new Vec3(2.5, -11.97, 0.5), pitOpening = new Vec3(2.5, 4.62, 0.5);
        require(pitColumn.lowerKnown() && pitColumn.floorY16() == -192, "visible pit floor within scan reach");
        require(pitWindow.minimumY16() == 0 && pitWindow.maximumY16() == 129,
            "visible deep appearance does not reveal the pit's unvisited heights");
        require(SurveyGeometry.visible(pit, pitEye, pitFloor, null, null), "deep floor can be directly observed");
        pit.put(0, -4, 0, Blocks.STONE.defaultBlockState());
        require(!SurveyGeometry.visible(pit, pitEye, pitFloor, null, null), "lip occludes the deep floor");
        require(SurveyGeometry.visible(pit, pitEye, pitOpening, null, null), "occluded floor may retain a visible opening cue");
        passed.add("deep visible floor appearance and occluded opening cue keep the same narrow observation heights");

        Fixture flooded = new Fixture();
        flooded.put(0, -10, 0, Blocks.STONE.defaultBlockState());
        for (int y = -9; y <= 2; y++) flooded.put(0, y, 0, Blocks.WATER.defaultBlockState());
        SurveyGeometry.Column water = inspect(flooded, 0.0625);
        require(water.fluid() != null && water.fluid().is(Blocks.WATER), "visible water remains fluid color");
        require(!water.upperKnown() && water.minY16() == -144, "water is not a separating roof/floor");
        require(SurveyGeometry.supportAnchor(water, 0) == 0, "swimmer anchor follows real height");
        Fixture leaves = new Fixture(); leaves.put(0, 3, 0, Blocks.OAK_LEAVES.defaultBlockState());
        require(!inspect(leaves, 0.0625).structuralRoof(), "leaf canopy does not select cave mode");
        passed.add("fluid medium does not split geometry or imply seabed observation; leaves do not classify a cave");

        Set<Long> offsets = new HashSet<>();
        for (int i = 0; i < 33 * 33; i++) {
            long offset = SurveyGeometry.spiral(i);
            require(Math.abs((int) (offset >> 32)) <= 16 && Math.abs((int) offset) <= 16, "spiral radius bound");
            require(offsets.add(offset), "spiral never repeats an offset");
        }
        require(SurveyGeometry.spiral(0) == 0 && offsets.size() == 1089, "avatar first and complete bounded queue");
        require(SurveyGeometry.fixed16(-0.01) == -1 && SurveyGeometry.fixed16(-0.5) == -8, "negative fixed-point floor");
        passed.add("avatar-first bounded round robin and negative fractional heights");
        require(SurveyGeometry.sameSurfaceChunk(0.1, 0.1, 15.9, 15.9), "walking does not restart surface work every block");
        require(!SurveyGeometry.sameSurfaceChunk(15.9, 0.1, 16.0, 0.1), "surface work renews at the next chunk");
        require(SurveyGeometry.sameSurfaceChunk(-15.9, -0.1, -0.1, -15.9)
            && !SurveyGeometry.sameSurfaceChunk(-0.1, -0.1, 0.1, -0.1), "surface origins floor correctly across zero");
        passed.add("surface footprint remains stable during ordinary walking and renews at chunk boundaries");
        return Map.of("passed", true, "checks", passed, "worldWrites", 0, "shaftBlockReads", shaft.reads);
    }

    private static SurveyGeometry.Column inspect(Fixture fixture, double y) {
        return SurveyGeometry.inspect(fixture, 0, 0, 0.5, 0.5, y, 32);
    }
    private static void require(boolean condition, String check) {
        if (!condition) throw new IllegalStateException("Dynamic survey geometry check failed: " + check);
    }
    private static final class Fixture implements BlockGetter {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        private int reads;
        private void put(int x, int y, int z, BlockState state) { blocks.put(new BlockPos(x, y, z), state); }
        @Override public BlockState getBlockState(BlockPos pos) {
            reads++; return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }
}
