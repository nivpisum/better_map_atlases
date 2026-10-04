/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic;

import net.mehvahdjukaar.moonlight.api.map.ExpandedMapData;
import net.mehvahdjukaar.moonlight.core.misc.IMapDataPacketExtension;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.server.level.ServerPlayer;
import pepjebs.mapatlases.MapAtlasesMod;
import pepjebs.mapatlases.item.MapAtlasItem;
import pepjebs.mapatlases.utils.MapType;
import pepjebs.mapatlases.utils.Slice;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Detached adapter checks for the transformed Forge loader; no world/map-ID writes. */
public final class SurveyAtlasChecks {
    private SurveyAtlasChecks() {}

    public static Map<String, Object> run() {
        List<String> passed = new ArrayList<>();
        require(SurveyAtlas.AUTO_HEIGHT < DimensionType.MIN_Y && SurveyAtlas.AUTO_HEIGHT != Integer.MAX_VALUE,
                "Auto is neither world height nor Atlas surface");
        MapItemSavedData map = fresh();
        Arrays.fill(map.colors, (byte) 24);
        byte[] oldColors = map.colors.clone();
        require(!SurveyAtlas.isDynamic(map), "ordinary map stays ordinary");
        SurveyAtlas.markDynamic(map);
        require(SurveyAtlas.isDynamic(map) && SurveyAtlas.isAutoHeight(MapType.VANILLA.getHeight(map)),
                "dynamic classification");
        require(Arrays.equals(oldColors, map.colors), "kind does not change surface history");
        Slice auto = Slice.of(MapType.VANILLA, SurveyAtlas.AUTO_HEIGHT, Level.OVERWORLD);
        require(SurveyAtlas.isAutoSlice(Slice.parse(auto.save(), Level.OVERWORLD)), "Auto selection NBT roundtrip");
        require(auto.getDiscoveryReach() == SurveyAtlas.DISCOVERY_REACH, "bounded discovery reach");
        ItemStack extracted = MapType.VANILLA.createExistingMapItem(123, SurveyAtlas.AUTO_HEIGHT);
        require(extracted.is(Items.FILLED_MAP) && extracted.getTag().getInt("map") == 123
                && !extracted.getTag().contains("yLevel") && !extracted.getTag().contains("forceCave"), "ordinary ID-preserving extraction");
        passed.add("Auto classification, selection persistence, bounded reach and ordinary extracted ID");

        ClientboundMapItemDataPacket packet = new ClientboundMapItemDataPacket(123, map.scale, false, null, null);
        CompoundTag unrelated = new CompoundTag();
        unrelated.putString("other_mod", "preserved");
        ((IMapDataPacketExtension) packet).moonlight$sendCustomMapDataTag(unrelated);
        SurveyAtlas.includeKindInPacket(map, packet);
        CompoundTag sent = ((IMapDataPacketExtension) packet).moonlight$getCustomMapDataTag();
        require("preserved".equals(sent.getString("other_mod")) && sent.getCompound("accurate_maps:page_kind").getBoolean("dynamic"),
                "kind metadata merges other custom tags");
        require(!sent.contains(SurveyLayer.TAG), "no rich history in page-kind packet");
        MapItemSavedData clientMap = fresh();
        SurveyAtlas.KindData clientKind = new SurveyAtlas.KindData();
        ((ExpandedMapData) clientMap).getCustomData().put(new ResourceLocation("accurate_maps", "page_kind"), clientKind);
        long before = SurveyAtlas.kindRevision();
        clientKind.loadUpdateTag(sent);
        long changed = SurveyAtlas.kindRevision();
        clientKind.loadUpdateTag(sent);
        require(changed > before && changed == SurveyAtlas.kindRevision(), "only a kind change triggers reindex");
        require(SurveyAtlas.isDynamic(clientMap) && ((SurveyAccess) clientMap).accurateMaps$getSurvey(false) == null
                && SurveyAtlas.isAutoHeight(MapType.VANILLA.getHeight(clientMap)), "classifies before rich snapshot");
        require(SurveyAtlas.isDynamic(map.locked()) && SurveyAtlas.isDynamic(map.scaled(1)), "locked and scaled identity");
        passed.add("small merged metadata, history-free packet, idempotent reindex and kind-before-history classification");

        MapItemSavedData surface = fresh();
        surface.setColor(64, 64, (byte) 24);
        SurveyAtlas.markSurface(surface);
        MapItemSavedData reopened = MapItemSavedData.load(surface.save(new CompoundTag()));
        require(SurveyAtlas.isManaged(reopened) && !SurveyAtlas.isDynamic(reopened)
            && MapType.VANILLA.getHeight(reopened) == null, "surface identity survives save");
        require(((SurveyAccess) reopened).accurateMaps$getSurvey(false) == null && reopened.colors[8256] == 24,
            "surface has pixels but no cave exploration");
        require(!SurveyAtlas.isDynamic(surface.locked()) && SurveyAtlas.isManaged(surface.locked()), "locked surface remains surface");
        passed.add("independent surface identity, no cave history, pixel save/load and lock");

        return Map.of("passed", true, "checks", passed, "autoHeight", SurveyAtlas.AUTO_HEIGHT);
    }

    /** Allocates only fixture map IDs. Call only inside a disposable test world. */
    public static Map<String, Object> migration(ServerPlayer player) {
        var level = player.serverLevel();
        MapItemSavedData legacy = fresh();
        legacy.colors[8256] = 24;
        SurveyAtlas.markDynamic(legacy);
        CompoundTag old = new CompoundTag(), value = new CompoundTag();
        value.putInt("format", 1); value.putBoolean("dynamic", true); old.put("accurate_maps:page_kind", value);
        ((ExpandedMapData) legacy).getCustomData().get(new ResourceLocation("accurate_maps", "page_kind")).load(old);
        int id = level.getFreeMapId(); level.setMapData(MapType.VANILLA.makeStringKey(id), legacy);
        ItemStack first = new ItemStack(MapAtlasesMod.MAP_ATLAS.get());
        MapAtlasItem.setEmptyMaps(first, 5);
        var firstMaps = MapAtlasItem.getMaps(first, level); firstMaps.add(id, level);
        ItemStack second = first.copy();
        SurveyAtlas.migratePages(player, first, firstMaps);
        var surface = firstMaps.select(0, 0, Slice.of(MapType.VANILLA, null, Level.OVERWORLD));
        require(surface != null && surface.id != id && surface.data.colors[8256] == 24, "legacy surface detached from cave");
        require(!SurveyAtlas.isDynamic(surface.data) && SurveyAtlas.isDynamic(legacy), "independent migrated identities");
        require(surface.data.colors != legacy.colors, "migrated pixel arrays are independently owned");
        require(firstMaps.getCount() == 2 && MapAtlasItem.getEmptyMaps(first) == 5, "migration never consumes blank maps");
        SurveyAtlas.migratePages(player, first, firstMaps);
        require(firstMaps.getCount() == 2, "migration idempotent");
        var secondMaps = MapAtlasItem.getMaps(second, level);
        SurveyAtlas.migratePages(player, second, secondMaps);
        require(secondMaps.hasId(surface.id) && secondMaps.getCount() == 2, "copied old books share the same detached surface ID");
        firstMaps.remove(surface);
        SurveyAtlas.migratePages(player, first, firstMaps);
        require(firstMaps.getCount() == 1, "cut migrated surface stays removed");
        require(legacy.colors[8256] == 24, "old payload retained for recovery");
        ItemStack restored = ItemStack.of(first.save(new CompoundTag()));
        require(MapAtlasItem.getMaps(restored, level).getCount() == 1, "book capability and migration marker persist");
        return Map.of("passed", true, "oldCaveId", id, "surfaceId", surface.id,
            "sharedCopy", true, "chargedBlankMaps", 0, "cutPageNotResurrected", true);
    }

    private static MapItemSavedData fresh() { return MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, Level.OVERWORLD); }
    private static void require(boolean value, String label) {
        if (!value) throw new IllegalStateException("Dynamic Atlas adapter self-test failed: " + label);
    }
}
