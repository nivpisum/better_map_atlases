/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.compat;

import com.syric.betternethermap.config.BNMConfig;
import com.syric.betternethermap.getMapHeight;
import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;
import net.mehvahdjukaar.moonlight.api.map.CustomMapData;
import net.mehvahdjukaar.moonlight.api.map.ExpandedMapData;
import net.mehvahdjukaar.moonlight.api.map.MapDataRegistry;
import net.mehvahdjukaar.moonlight.core.misc.IMapDataPacketExtension;
import net.mehvahdjukaar.moonlight.core.misc.IHoldingPlayerExtension;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.jetbrains.annotations.Nullable;
import pepjebs.mapatlases.item.MapAtlasItem;
import pepjebs.mapatlases.map_collection.IMapCollection;
import pepjebs.mapatlases.utils.MapType;
import pepjebs.mapatlases.utils.Slice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Optional bridge for the pinned Forge BNM and Map Atlases releases. */
public final class NetherMapCompat {
    private static final String TAG = "accurate_maps:nether_height";
    private static final int UNMAPPED = -1000;
    private static CustomMapData.Type<HeightData> type;
    private static final AtomicLong HEIGHT_REVISION = new AtomicLong();

    private NetherMapCompat() {}

    /** Called on both physical sides, after checking all three optional mods. */
    public static void register() {
        if (type == null) {
            type = MapDataRegistry.registerCustomMapSavedData(
                    new ResourceLocation("accurate_maps", "nether_height"), HeightData::new);
        }
    }

    /** Changes only when stored layer identity changes, never for ordinary map pixels. */
    public static long heightRevision() { return HEIGHT_REVISION.get(); }

    private static @Nullable HeightData data(MapItemSavedData map, boolean create) {
        if (type == null || !(map instanceof ExpandedMapData expanded)) return null;
        CustomMapData<?> existing = expanded.getCustomData().get(type.id());
        if (existing instanceof HeightData height) return height;
        if (!create) return null;
        HeightData height = new HeightData();
        expanded.getCustomData().put(type.id(), height);
        return height;
    }

    /** Atlas grouping: natural cave maps always apply, forced caves honor BNM config. */
    public static @Nullable Integer getHeight(MapItemSavedData map) {
        if (SurveyAtlas.isManaged(map)) return null;
        HeightData height = data(map, false);
        return height != null && height.active() ? height.height : null;
    }

    /** Scan ceiling for the vanilla updater and Accurate Maps' sampled metadata. */
    public static @Nullable Integer sampleHeight(Level level, MapItemSavedData map) {
        if (SurveyAtlas.isManaged(map)) return null;
        HeightData height = data(map, false);
        if (height == null || height.height == null || !map.dimension.equals(level.dimension())) return null;
        if (!level.dimensionType().hasCeiling()
                && !(height.forceCave && BNMConfig.enableOverworldMapping.get())) return null;
        return Mth.clamp(height.height, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
    }

    /** Atlas can send a page after Moonlight's one-shot height counter was cleared. */
    public static void includeHeightInPacket(MapItemSavedData map, @Nullable Packet<?> packet) {
        if (!(packet instanceof IMapDataPacketExtension extended)) return;
        HeightData height = data(map, false);
        if (height == null || height.height == null) return;
        CompoundTag existing = extended.moonlight$getCustomMapDataTag();
        CompoundTag merged = existing == null ? new CompoundTag() : existing.copy();
        merged.put(TAG, height.value());
        extended.moonlight$sendCustomMapDataTag(merged);
    }

    /** A full Atlas GUI sync must include inactive pages, even with clean pixel data. */
    public static void requestHeightSync(MapItemSavedData map, ServerPlayer player) {
        HeightData height = data(map, false);
        if (height == null || height.height == null) return;
        ((IHoldingPlayerExtension) map.getHoldingPlayer(player))
                .moonlight$setCustomDataDirty(type, CustomMapData.SimpleDirtyCounter::markDirty);
    }

    /** Read-only inspection for the isolated runtime checks. */
    public static Map<String, Object> describe(MapItemSavedData map) {
        Map<String, Object> result = new LinkedHashMap<>();
        HeightData height = data(map, false);
        result.put("registered", type != null);
        result.put("storedHeight", height == null ? null : height.height);
        result.put("atlasHeight", getHeight(map));
        result.put("ceilingDimension", height != null && height.ceilingDimension);
        result.put("forceCave", height != null && height.forceCave);
        result.put("format", 1);
        return result;
    }

    /** Import old BNM item metadata before Atlas discards the item and retains its ID. */
    public static void importItem(Level level, ItemStack stack, @Nullable MapItemSavedData map) {
        if (map == null || SurveyAtlas.isDynamic(map) || !(stack.getItem() instanceof MapItem)) return;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("yLevel", Tag.TAG_INT)) return;
        int y = tag.getInt("yLevel");
        if (y == UNMAPPED || SurveyAtlas.isAutoHeight(y)) return;
        HeightData existing = data(map, false);
        // A map ID has one authoritative layer. Do not let a stale item copy move it.
        if (existing != null && existing.height != null) return;
        boolean ceiling = hasCeiling(level, map);
        boolean force = tag.getBoolean("forceCave");
        if (ceiling || force) set(map, y, ceiling, force);
    }

    public static void importItem(Level level, ItemStack stack) {
        Integer id = MapItem.getMapId(stack);
        if (id != null) importItem(level, stack, level.getMapData(MapItem.makeKey(id)));
    }

    private static boolean hasCeiling(Level level, MapItemSavedData map) {
        if (map.dimension.equals(level.dimension())) return level.dimensionType().hasCeiling();
        if (level instanceof ServerLevel serverLevel) {
            ServerLevel target = serverLevel.getServer().getLevel(map.dimension);
            if (target != null) return target.dimensionType().hasCeiling();
        }
        // Client levels do not expose other dimensions; server custom data supplies
        // modded dimensions. The vanilla Nether is unambiguous during recipe preview.
        return map.dimension.equals(Level.NETHER);
    }

    public static ItemStack initializeCreated(Level level, ItemStack filled, int y, boolean forceCave) {
        if (SurveyAtlas.isAutoHeight(y)) return filled;
        filled.getOrCreateTag().putInt("yLevel", y);
        if (forceCave) filled.getOrCreateTag().putBoolean("forceCave", true);
        Integer id = MapItem.getMapId(filled);
        if (id == null || y == UNMAPPED) return filled;
        MapItemSavedData map = level.getMapData(MapItem.makeKey(id));
        if (map != null && SurveyAtlas.isDynamic(map)) {
            filled.getOrCreateTag().remove("yLevel");
            filled.getOrCreateTag().remove("forceCave");
            return filled;
        }
        boolean ceiling = level.dimensionType().hasCeiling();
        if (map != null && (ceiling || forceCave)) set(map, y, ceiling, forceCave);
        return filled;
    }

    private static void set(MapItemSavedData map, int y, boolean ceiling, boolean forceCave) {
        HeightData height = data(map, true);
        if (height == null) return;
        if (!height.assign(y, ceiling, forceCave)) return;
        height.setDirty(map, CustomMapData.SimpleDirtyCounter::markDirty);
        map.setDirty();
    }

    public static ItemStack createVanilla(Level level, Player player, ItemStack filled) {
        return initializeCreated(level, filled, getMapHeight.getVanillaMapHeight(player, level), false);
    }

    public static ItemStack createAlternate(Level level, Player player, ItemStack blank, ItemStack filled) {
        return initializeCreated(level, filled, getMapHeight.getAltMapHeight(blank, player, level),
                BNMConfig.enableOverworldMapping.get());
    }

    /** Seed the first cave layer before Atlas calculates its current map key. */
    public static Slice initialSlice(Player player, ItemStack atlas, Slice selected) {
        Level level = player.level();
        if (MapAtlasItem.isLocked(atlas) || selected.type() != MapType.VANILLA || selected.height() != null
                || !level.dimensionType().hasCeiling()) return selected;
        IMapCollection maps = MapAtlasItem.getMaps(atlas, level);
        if (maps.getHeightTree(level.dimension(), MapType.VANILLA).stream()
                .anyMatch(y -> y != Integer.MAX_VALUE && !SurveyAtlas.isAutoHeight(y))) return selected;
        int y = getMapHeight.getVanillaMapHeight(player, level);
        if (y == UNMAPPED) return selected;
        Slice result = Slice.of(MapType.VANILLA, y, level.dimension());
        MapAtlasItem.setSelectedSlice(atlas, result);
        return result;
    }

    public static final class HeightData implements CustomMapData<CustomMapData.SimpleDirtyCounter> {
        private @Nullable Integer height;
        private boolean ceilingDimension;
        private boolean forceCave;

        private boolean active() {
            return height != null && (ceilingDimension || forceCave && BNMConfig.enableOverworldMapping.get());
        }

        @Override public Type<HeightData> getType() { return type; }
        @Override public SimpleDirtyCounter createDirtyCounter() { return new SimpleDirtyCounter(); }

        @Override public void load(CompoundTag tag) {
            if (tag.contains(TAG, Tag.TAG_COMPOUND)) loadUpdateTag(tag);
            else assign(null, false, false);
        }

        @Override public void loadUpdateTag(CompoundTag tag) {
            if (!tag.contains(TAG, Tag.TAG_COMPOUND)) return;
            CompoundTag value = tag.getCompound(TAG);
            if (value.getInt("format") != 1) {
                assign(null, false, false);
                return;
            }
            Integer loadedHeight = value.contains("height", Tag.TAG_INT) ? value.getInt("height") : null;
            if (loadedHeight != null && (loadedHeight == UNMAPPED || loadedHeight < DimensionType.MIN_Y || loadedHeight > DimensionType.MAX_Y)) loadedHeight = null;
            assign(loadedHeight, value.getBoolean("ceiling_dimension"), value.getBoolean("force_cave"));
        }

        private boolean assign(@Nullable Integer nextHeight, boolean ceiling, boolean forced) {
            if (Objects.equals(height, nextHeight) && ceilingDimension == ceiling && forceCave == forced) return false;
            height = nextHeight;
            ceilingDimension = ceiling;
            forceCave = forced;
            HEIGHT_REVISION.incrementAndGet();
            return true;
        }

        private CompoundTag value() {
            CompoundTag value = new CompoundTag();
            value.putInt("format", 1);
            if (height != null) value.putInt("height", height);
            value.putBoolean("ceiling_dimension", ceilingDimension);
            value.putBoolean("force_cave", forceCave);
            return value;
        }

        @Override public void save(CompoundTag tag) {
            if (height != null) tag.put(TAG, value());
        }

        @Override public void saveToUpdateTag(CompoundTag tag, SimpleDirtyCounter dirtyCounter) {
            tag.put(TAG, value());
        }
        // Moonlight defaults preserve custom data on copy, lock and scale.
    }
}
