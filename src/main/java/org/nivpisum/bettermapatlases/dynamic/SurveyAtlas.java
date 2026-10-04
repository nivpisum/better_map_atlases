/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic;

import net.mehvahdjukaar.moonlight.api.map.CustomMapData;
import net.mehvahdjukaar.moonlight.api.map.ExpandedMapData;
import net.mehvahdjukaar.moonlight.api.map.MapDataRegistry;
import net.mehvahdjukaar.moonlight.core.misc.IHoldingPlayerExtension;
import net.mehvahdjukaar.moonlight.core.misc.IMapDataPacketExtension;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import org.nivpisum.bettermapatlases.MapLayer;
import org.nivpisum.bettermapatlases.MapLayerAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.dimension.DimensionType;
import org.jetbrains.annotations.Nullable;
import pepjebs.mapatlases.config.MapAtlasesConfig;
import pepjebs.mapatlases.item.MapAtlasItem;
import pepjebs.mapatlases.map_collection.IMapCollection;
import pepjebs.mapatlases.map_collection.MapKey;
import pepjebs.mapatlases.utils.MapAtlasesAccessUtils;
import pepjebs.mapatlases.utils.MapType;
import pepjebs.mapatlases.utils.Slice;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/** Optional Atlas/Moonlight adapter. AUTO_HEIGHT is a page kind, never a world Y. */
public final class SurveyAtlas {
    public static final int AUTO_HEIGHT = Integer.MIN_VALUE + 197;
    public static final int DISCOVERY_REACH = 32;
    private static final String KIND_TAG = "accurate_maps:page_kind";
    private static final String MIGRATED_TAG = "better_map_atlases:migrated_pages";
    private static final AtomicLong KIND_REVISION = new AtomicLong();
    private static final Map<ServerPlayer, LecternAccess> LECTERN_ACCESS = new WeakHashMap<>();
    private static final Map<IMapCollection, Integer> MIGRATED_COUNTS = new WeakHashMap<>();
    private static CustomMapData.Type<KindData> type;
    private static Predicate<ServerPlayer> autoEligibility = player -> false;
    private static ToDoubleFunction<ResourceKey<Level>> viewYProvider = dimension -> Double.NaN;
    private static HistoryUi historyUi = new HistoryUi() {};

    private SurveyAtlas() {}

    public static void register() {
        if (type == null) type = MapDataRegistry.registerCustomMapSavedData(
                new ResourceLocation("accurate_maps", "page_kind"), KindData::new);
    }

    /** The bootstrap combines configuration and remote survey-channel presence. */
    public static void configureAuto(Predicate<ServerPlayer> eligibility) { autoEligibility = eligibility; }
    public static void setViewYProvider(ToDoubleFunction<ResourceKey<Level>> provider) { viewYProvider = provider; }
    public static void configureHistoryUi(HistoryUi callbacks) { historyUi = callbacks; }
    public static HistoryUi historyUi() { return historyUi; }
    public static double viewY(ResourceKey<Level> dimension) { return viewYProvider.applyAsDouble(dimension); }
    public static boolean isAutoHeight(@Nullable Integer height) { return height != null && height == AUTO_HEIGHT; }
    public static boolean isAutoSlice(@Nullable Slice slice) {
        return slice != null && slice.type() == MapType.VANILLA && isAutoHeight(slice.height());
    }
    public static long kindRevision() { return KIND_REVISION.get(); }

    public static boolean isDynamic(MapItemSavedData map) {
        if (map instanceof SurveyAccess access && access.accurateMaps$getSurvey(false) != null) return true;
        KindData kind = data(map, false);
        return kind != null && kind.dynamic;
    }

    public static boolean isManaged(MapItemSavedData map) {
        KindData kind = data(map, false);
        return isDynamic(map) || (kind != null && kind.surface);
    }

    public static void markSurface(MapItemSavedData map) {
        ((SurveyAccess) map).accurateMaps$setSurvey(null);
        KindData kind = data(map, true);
        if (kind != null) {
            kind.assign(false, true);
            kind.separated = true;
            kind.legacyMixed = false;
            kind.surfaceCopyId = -1;
            kind.setDirty(map, CustomMapData.SimpleDirtyCounter::markDirty);
        }
        map.setDirty();
    }

    public static void markDynamic(MapItemSavedData map) {
        ((SurveyAccess) map).accurateMaps$getSurvey(true);
        synchronizeKind(map);
        KindData kind = data(map, false);
        if (kind != null) { kind.separated = true; kind.legacyMixed = false; }
        map.setDirty();
    }

    /** Also repairs the small descriptor after a saved-data copy or scale. */
    public static void synchronizeKind(MapItemSavedData map) {
        if (!(map instanceof SurveyAccess access) || access.accurateMaps$getSurvey(false) == null) return;
        KindData kind = data(map, false);
        if (kind == null) {
            kind = data(map, true);
            if (kind != null) kind.legacyMixed = true;
        }
        if (kind != null && kind.assign(true, false)) kind.setDirty(map, CustomMapData.SimpleDirtyCounter::markDirty);
    }

    private static @Nullable KindData data(MapItemSavedData map, boolean create) {
        if (type == null || !(map instanceof ExpandedMapData expanded)) return null;
        CustomMapData<?> existing = expanded.getCustomData().get(type.id());
        if (existing instanceof KindData kind) return kind;
        if (!create) return null;
        KindData kind = new KindData();
        expanded.getCustomData().put(type.id(), kind);
        return kind;
    }

    public static void includeKindInPacket(MapItemSavedData map, @Nullable Packet<?> packet) {
        if (!(packet instanceof IMapDataPacketExtension extended)) return;
        KindData kind = data(map, false);
        if (kind == null || (!kind.dynamic && !kind.surface)) return;
        CompoundTag previous = extended.moonlight$getCustomMapDataTag();
        CompoundTag merged = previous == null ? new CompoundTag() : previous.copy();
        merged.put(KIND_TAG, kind.value());
        extended.moonlight$sendCustomMapDataTag(merged);
    }

    public static void requestKindSync(MapItemSavedData map, ServerPlayer player) {
        synchronizeKind(map);
        KindData kind = data(map, false);
        if (kind == null || (!kind.dynamic && !kind.surface)) return;
        ((IHoldingPlayerExtension) map.getHoldingPlayer(player))
                .moonlight$setCustomDataDirty(type, CustomMapData.SimpleDirtyCounter::markDirty);
    }

    /** Recording follows the carrier's actual environment, never the page being browsed. */
    public static Slice initialSlice(Player player, ItemStack atlas, Slice selected) {
        if (!(player instanceof ServerPlayer serverPlayer) || !autoEligibility.test(serverPlayer)
                || selected.type() != MapType.VANILLA) return selected;
        IMapCollection maps = MapAtlasItem.getMaps(atlas, player.level());
        migratePages(serverPlayer, atlas, maps);
        SurveyView view = SurveyRecorder.currentView(serverPlayer);
        if (view == null) return selected;
        Slice actual = Slice.of(MapType.VANILLA, view.cave() ? AUTO_HEIGHT : null, player.level().dimension());
        // Adopt ordinary surface maps already placed in a book, preserving all existing pixels.
        for (var holder : maps.selectSection(Slice.of(MapType.VANILLA, null, player.level().dimension()))) {
            if (!isManaged(holder.data)) markSurface(holder.data);
        }
        if (atlas.getTag() == null) MapAtlasItem.setEmptyMaps(atlas, MapAtlasesConfig.pityActivationMapCount.get());
        if (!actual.equals(selected)) MapAtlasItem.setSelectedSlice(atlas, actual);
        return actual;
    }

    /** Split old mixed pages once. The existing cave ID and all original data remain recoverable. */
    public static void migratePages(ServerPlayer player, ItemStack atlas, IMapCollection maps) {
        if (MIGRATED_COUNTS.getOrDefault(maps, -1) == maps.getCount()) return;
        Level level = player.level();
        java.util.Set<Integer> migrated = new java.util.HashSet<>();
        if (atlas.hasTag()) for (int id : atlas.getTag().getIntArray(MIGRATED_TAG)) migrated.add(id);
        boolean changed = false;
        for (var holder : java.util.List.copyOf(maps.getAll())) {
            MapItemSavedData cave = holder.data;
            if (!isDynamic(cave)) continue;
            synchronizeKind(cave);
            KindData kind = data(cave, false);
            if (kind == null || !kind.legacyMixed || migrated.contains(holder.id)) continue;
            var existing = maps.select(cave.centerX, cave.centerZ, Slice.of(MapType.VANILLA, null, cave.dimension));
            if (existing != null) {
                mergeSurface(cave, existing.data);
                markSurface(existing.data);
            } else if (kind.surfaceCopyId >= 0 && level.getMapData(MapItem.makeKey(kind.surfaceCopyId)) != null) {
                maps.add(kind.surfaceCopyId, level);
            } else if (hasSurfacePixels(cave)) {
                CompoundTag saved = cave.save(new CompoundTag());
                saved.remove(SurveyLayer.TAG);
                MapItemSavedData surface = MapItemSavedData.load(saved.copy());
                markSurface(surface);
                int id = level.getFreeMapId();
                level.setMapData(MapItem.makeKey(id), surface);
                kind.surfaceCopyId = id;
                maps.add(id, level);
            }
            kind.separated = true;
            kind.setDirty(cave, CustomMapData.SimpleDirtyCounter::markDirty);
            cave.setDirty();
            migrated.add(holder.id); changed = true;
        }
        if (changed) atlas.getOrCreateTag().putIntArray(MIGRATED_TAG, migrated.stream().mapToInt(Integer::intValue).sorted().toArray());
        MIGRATED_COUNTS.put(maps, maps.getCount());
    }

    private static boolean hasSurfacePixels(MapItemSavedData map) {
        for (byte colour : map.colors) if (colour != 0) return true;
        return false;
    }

    private static void mergeSurface(MapItemSavedData from, MapItemSavedData to) {
        MapLayer source = ((MapLayerAccess) from).accurateMaps$getLayer(false);
        MapLayer target = ((MapLayerAccess) to).accurateMaps$getLayer(true);
        for (int i = 0; i < from.colors.length; i++) if (to.colors[i] == 0 && from.colors[i] != 0) {
            to.setColor(i & 127, i >> 7, from.colors[i]);
            if (source != null && source.state(i) != null)
                target.set(i, source.state(i), source.biome(i), source.position(i));
        }
        to.setDirty();
    }

    /** Upstream still performs creation and charges the blank map exactly once. */
    public static boolean canCreateDynamic(ServerPlayer player, ItemStack atlas, IMapCollection maps,
                                           Slice slice, int x, int z) {
        if (slice.type() != MapType.VANILLA || (slice.height() != null && !isAutoSlice(slice))) return true;
        if (!autoEligibility.test(player) || MapAtlasItem.isLocked(atlas)) return false;
        if (maps.select(MapKey.at(maps.getScale(), x, z, slice)) != null) return false;
        int capacity = MapAtlasItem.getMaxMapCount();
        if (capacity >= 0 && maps.getCount() >= capacity) return false;
        int empty = atlas.getTag() == null ? MapAtlasesConfig.pityActivationMapCount.get() : MapAtlasItem.getEmptyMaps(atlas);
        return empty > 0 || player.isCreative() || !MapAtlasesConfig.requireEmptyMapsToExpand.get();
    }

    public static Component autoLabel(ResourceKey<Level> dimension) {
        double y = viewYProvider.applyAsDouble(dimension);
        return Double.isFinite(y) ? Component.translatable("better_map_atlases.slice_y", (int) Math.floor(y))
                : Component.translatable("better_map_atlases.slice");
    }

    /** Numeric-only integrations must never receive the logical Auto sentinel. */
    public static @Nullable Integer coordinateHeight(Slice slice) {
        if (!isAutoSlice(slice)) return slice.height();
        double y = viewYProvider.applyAsDouble(slice.dimension());
        return Double.isFinite(y) ? (int) Math.max(DimensionType.MIN_Y, Math.min(DimensionType.MAX_Y, Math.floor(y))) : null;
    }

    /** Authorize only real carried books and a still-nearby, explicitly opened lectern. */
    public static boolean canAccessMap(ServerPlayer player, int id) {
        if (containsMap(player, MapAtlasesAccessUtils.getAtlasFromPlayerByConfig(player), id)) return true;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (containsMap(player, player.getInventory().getItem(slot), id)) return true;
        }
        LecternAccess access = LECTERN_ACCESS.get(player);
        if (access == null || !access.dimension.equals(player.level().dimension())
                || player.distanceToSqr(access.pos.getCenter()) > 64
                || player.serverLevel().getChunkSource().getChunkNow(access.pos.getX() >> 4, access.pos.getZ() >> 4) == null) return false;
        return player.level().getBlockEntity(access.pos) instanceof LecternBlockEntity lectern
                && containsMap(player, lectern.getBook(), id);
    }

    private static boolean containsMap(ServerPlayer player, ItemStack atlas, int id) {
        return atlas.getItem() instanceof MapAtlasItem && MapAtlasItem.getMaps(atlas, player.level()).hasId(id);
    }

    public static void recordGuiAccess(ServerPlayer player, @Nullable BlockPos lecternPos) {
        if (lecternPos == null) LECTERN_ACCESS.remove(player);
        else if (player.distanceToSqr(lecternPos.getCenter()) <= 64)
            LECTERN_ACCESS.put(player, new LecternAccess(player.level().dimension(), lecternPos.immutable()));
    }

    private record LecternAccess(ResourceKey<Level> dimension, BlockPos pos) {}

    /** Client-only view selection; every request is restricted to this book's Auto IDs. */
    public interface HistoryUi {
        default int[] historyHeights(int[] mapIds) { return new int[0]; }
        default void showHistory(ResourceKey<Level> dimension, int[] mapIds, int anchorY16) {}
        default void showSurface(ResourceKey<Level> dimension, int[] mapIds) {}
        default void followPlayer() {}
    }

    /** Tiny page identity only. Survey history uses the separate bounded protocol. */
    public static final class KindData implements CustomMapData<CustomMapData.SimpleDirtyCounter> {
        private boolean dynamic;
        private boolean surface;
        private boolean separated;
        private boolean legacyMixed;
        private int surfaceCopyId = -1;
        @Override public Type<KindData> getType() { return type; }
        @Override public SimpleDirtyCounter createDirtyCounter() { return new SimpleDirtyCounter(); }
        @Override public boolean persistOnCopyOrLock() { return true; }
        @Override public boolean persistOnRescale() { return true; }
        @Override public void load(CompoundTag tag) {
            if (tag.contains(KIND_TAG, Tag.TAG_COMPOUND)) loadUpdateTag(tag);
            else assign(false, false);
        }
        @Override public void loadUpdateTag(CompoundTag tag) {
            if (!tag.contains(KIND_TAG, Tag.TAG_COMPOUND)) return;
            CompoundTag value = tag.getCompound(KIND_TAG);
            int format = value.getInt("format");
            if (format != 1 && format != 2) return;
            assign(value.getBoolean("dynamic"), format == 2 && value.getBoolean("surface"));
            separated = format == 2 && value.getBoolean("separated");
            legacyMixed = format == 1 && dynamic || value.getBoolean("legacy_mixed");
            surfaceCopyId = value.contains("surface_copy", Tag.TAG_INT) ? value.getInt("surface_copy") : -1;
        }
        private boolean assign(boolean value, boolean isSurface) {
            if (dynamic == value && surface == isSurface) return false;
            dynamic = value; surface = isSurface;
            KIND_REVISION.incrementAndGet();
            return true;
        }
        private CompoundTag value() {
            CompoundTag value = new CompoundTag();
            value.putInt("format", 2);
            value.putBoolean("dynamic", dynamic);
            value.putBoolean("surface", surface);
            value.putBoolean("separated", separated);
            value.putBoolean("legacy_mixed", legacyMixed);
            if (surfaceCopyId >= 0) value.putInt("surface_copy", surfaceCopyId);
            return value;
        }
        @Override public void save(CompoundTag tag) { if (dynamic || surface) tag.put(KIND_TAG, value()); }
        @Override public void saveToUpdateTag(CompoundTag tag, SimpleDirtyCounter counter) { tag.put(KIND_TAG, value()); }
        @Override public @Nullable Component onItemTooltip(MapItemSavedData map, ItemStack stack) {
            return dynamic ? autoLabel(map.dimension) : null;
        }
    }
}
