/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/** Explicit detached-map checks for the actual Forge class loader. Does not access or edit a world. */
public final class SurveyRuntimeChecks {
    private SurveyRuntimeChecks() {}

    public static Map<String, Object> run() throws IOException {
        List<String> passed = new ArrayList<>();
        ResourceLocation plains = new ResourceLocation("minecraft", "plains");
        MapItemSavedData map = MapItemSavedData.createFresh(0, 0, (byte) 1, true, false, Level.OVERWORLD);
        SurveyLayer layer = ((SurveyAccess) map).accurateMaps$getSurvey(true);
        map.setDirty(false);
        SurveySample lower = new SurveySample(8, 40, 8, 48, 8,
            Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM),
            plains, new BlockPos(0, 0, 0).asLong(), (byte) 1, SurveySample.Kind.FLOOR, true, true);
        SurveySample upper = new SurveySample(56, 80, 56, 128, 56, Blocks.BLUE_WOOL.defaultBlockState(),
            plains, new BlockPos(0, 3, 0).asLong(), (byte) 2, SurveySample.Kind.FLOOR, true, false);
        int key = layer.columnKey(0, 0);
        layer.observe(key, lower);
        layer.observe(key, upper);
        require(map.isDirty(), "observation dirties owner");
        require(layer.lookup(key, 7) == null && layer.lookup(key, 8).floorY16() == 8
            && layer.lookup(key, 40) == null && layer.lookup(key, 56).floorY16() == 56
            && layer.lookup(key, 80) == null, "slab geometry and unobserved height gaps");
        require(layer.lookupWorld(1, 0, 8) == null, "coarse subcolumn remains unknown");
        passed.add("actual-column isolation, fractional slab floor and observation-only lookup");

        long historyRevision = layer.revision();
        map.setDirty(false);
        require(layer.rememberView(true, 56) && map.isDirty(), "page bookmark dirties owner");
        map.setDirty(false);
        require(!layer.rememberView(true, 56) && !map.isDirty() && layer.revision() == historyRevision,
            "unchanged page view has no dirty or history churn");
        byte[] vanilla = map.colors.clone();
        CompoundTag saved = map.save(new CompoundTag());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(saved, bytes);
        MapItemSavedData loadedMap = MapItemSavedData.load(NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray())));
        SurveyLayer loaded = ((SurveyAccess) loadedMap).accurateMaps$getSurvey(false);
        require(loaded != null && loaded.writable() && loaded.save().equals(layer.save()), "compressed NBT exact roundtrip");
        require(loaded.contentHash().equals(layer.contentHash()), "stable decoded content digest after compressed reopen");
        require(loaded.projection(56).equals(Map.of(key, upper)) && loaded.projection(40).isEmpty(),
            "direct projection includes only observed height");
        require(Arrays.equals(vanilla, loadedMap.colors), "surface colors unchanged");
        require(new SurveyLayer.Bookmark(true, 56, 1).equals(loaded.bookmark()), "page bookmark saved and reopened");
        CompoundTag stableState = saved.getCompound(SurveyLayer.TAG).getList("state_palette", Tag.TAG_COMPOUND).getCompound(0);
        require("minecraft:stone_slab".equals(stableState.getString("Name"))
            && "bottom".equals(stableState.getCompound("Properties").getString("type")), "stable block name and slab properties");
        passed.add("compressed map save/reopen retains stable palette, biome, revision, gaps and surface colors");

        MapItemSavedData lockedMap = map.locked();
        SurveyLayer locked = ((SurveyAccess) lockedMap).accurateMaps$getSurvey(false);
        require(locked != layer && locked.save().equals(layer.save()), "locked deep copy");
        String unchangedHistory = layer.contentHash();
        layer.rememberView(false, 1024);
        require(unchangedHistory.equals(layer.contentHash()), "bookmark excluded from content digest");
        require(new SurveyLayer.Bookmark(true, 56, 1).equals(locked.bookmark()), "locked bookmark remains independent");
        layer.observe(key, new SurveySample(8, 40, 8, 48, 8, Blocks.RED_WOOL.defaultBlockState(), plains,
            0, (byte) 1, SurveySample.Kind.FLOOR, true, true));
        require(locked.lookup(key, 8).state().is(Blocks.STONE_SLAB), "locked copy remains independent");
        require(!locked.contentHash().equals(layer.contentHash()), "stable block-state content affects digest");
        MapItemSavedData scaledMap = map.scaled(1);
        SurveyLayer scaled = ((SurveyAccess) scaledMap).accurateMaps$getSurvey(false);
        require(scaled != null && scaled.scale() == 2 && scaled.observedColumns() == 0
            && scaled.centerX() == scaledMap.centerX && scaled.centerZ() == scaledMap.centerZ
            && scaled.bookmark() == null, "scaled empty dynamic identity");
        require(((SurveyAccess) MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, Level.OVERWORLD))
            .accurateMaps$getSurvey(false) == null, "ordinary maps remain ordinary");
        passed.add("independent locked copy, empty scaled dynamic map and unchanged ordinary-map identity");
        MapItemSavedData separateBookPage = MapItemSavedData.createFresh(0, 0, (byte) 1, true, false, Level.OVERWORLD);
        SurveyLayer separate = ((SurveyAccess) separateBookPage).accurateMaps$getSurvey(true);
        require(separate.bookmark() == null && separate.observedColumns() == 0, "independent book page has no inherited journal");
        CompoundTag legacy = loaded.save();
        legacy.remove("bookmark");
        require(SurveyLayer.load(legacy).bookmark() == null, "old format-one history needs no bookmark migration");
        passed.add("page-owned last view persists, locks independently and does not populate another book or scaled page");

        CompoundTag unknown = loaded.save();
        unknown.getList("state_palette", Tag.TAG_COMPOUND).getCompound(0).putString("Name", "missing_mod:retained_block");
        SurveyLayer protectedHistory = SurveyLayer.loadPreserving(unknown, loaded.metadata(), () -> {});
        require(!protectedHistory.writable() && protectedHistory.save().equals(unknown), "unavailable mod block preserved");
        CompoundTag malformed = loaded.save();
        CompoundTag column = malformed.getList("columns", Tag.TAG_COMPOUND).getCompound(0);
        int[] samples = column.getIntArray("samples");
        samples[1] = samples[3] + 1;
        column.putIntArray("samples", samples);
        require(!SurveyLayer.loadPreserving(malformed, loaded.metadata(), () -> {}).writable(), "malformed observation rejected");
        CompoundTag invalidMapTag = saved.copy();
        invalidMapTag.put(SurveyLayer.TAG, unknown);
        MapItemSavedData preservedMap = MapItemSavedData.load(invalidMapTag);
        require(preservedMap.save(new CompoundTag()).getCompound(SurveyLayer.TAG).equals(unknown), "autosave retains unreadable raw history");
        passed.add("invalid intervals rejected and unavailable mod states survive map load/autosave without history loss");
        return Map.of("passed", true, "checks", passed, "compressedTestMapBytes", bytes.size());
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Dynamic survey self-test failed: " + label);
    }
}
