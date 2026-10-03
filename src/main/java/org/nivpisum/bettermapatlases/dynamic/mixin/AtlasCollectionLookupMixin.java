/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.nivpisum.bettermapatlases.dynamic.AtlasCollectionLookup;
import org.nivpisum.bettermapatlases.dynamic.AtlasCollectionSections;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pepjebs.mapatlases.map_collection.MapCollection;
import pepjebs.mapatlases.map_collection.MapKey;
import pepjebs.mapatlases.utils.MapDataHolder;
import pepjebs.mapatlases.utils.Slice;

/** Collection-owned derived cache. No static references or saved-data/protocol changes. */
@Mixin(value = MapCollection.class, remap = false)
public abstract class AtlasCollectionLookupMixin implements AtlasCollectionLookup {
    @Shadow @Final private Map<MapKey, MapDataHolder> maps;
    @Shadow @Final private Set<Integer> ids;
    @Shadow private CompoundTag lazyNbt;
    @Shadow public abstract boolean isInitialized();
    @Shadow private void assertInitialized() { throw new AssertionError(); }
    @Unique private AtlasCollectionSections<MapKey, Slice, MapDataHolder> betterMapAtlases$sections;
    @Unique private List<Integer> betterMapAtlases$ids;
    @Unique private long betterMapAtlases$revision;
    @Unique private boolean betterMapAtlases$addingKnownId;
    @Unique private boolean betterMapAtlases$removingKnownKey;
    @Unique private int betterMapAtlases$knownMapCount;

    @Unique private void betterMapAtlases$changed() {
        betterMapAtlases$sections = null;
        betterMapAtlases$ids = null;
        betterMapAtlases$knownMapCount = maps.size();
        betterMapAtlases$revision++;
    }

    @Unique private AtlasCollectionSections<MapKey, Slice, MapDataHolder> betterMapAtlases$sections() {
        assertInitialized();
        if (betterMapAtlases$sections == null)
            betterMapAtlases$sections = new AtlasCollectionSections<>(maps, MapKey::slice);
        return betterMapAtlases$sections;
    }

    @Override public void betterMapAtlases$order(List<MapDataHolder> hits) {
        betterMapAtlases$sections().order(hits);
    }

    @Override public long betterMapAtlases$revision() { return betterMapAtlases$revision; }

    @Override public List<Integer> betterMapAtlases$ids() {
        if (betterMapAtlases$ids == null) {
            if (isInitialized()) betterMapAtlases$ids = ids.stream().sorted().toList();
            else betterMapAtlases$ids = java.util.Arrays.stream(lazyNbt.getIntArray(MapCollection.MAP_LIST_NBT))
                .boxed().sorted().toList();
        }
        return betterMapAtlases$ids;
    }

    @Inject(method = "initialize", at = @At("HEAD"))
    private void betterMapAtlases$initialize(Level level, CallbackInfo callback) {
        if (!isInitialized()) betterMapAtlases$changed();
    }

    @Inject(method = "deserializeNBT", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$reuseSnapshot(CompoundTag tag, CallbackInfo callback) {
        // Inventory updates can deliver the same book again. Pending page payloads
        // still resolve through addNotSynced; kind/height revisions keep their hooks.
        if (isInitialized()) {
            int[] incoming = tag.getIntArray(MapCollection.MAP_LIST_NBT);
            if (incoming.length == ids.size()) {
                Set<Integer> unique = new HashSet<>();
                for (int id : incoming) unique.add(id);
                if (unique.equals(ids)) { callback.cancel(); return; }
            }
        }
        betterMapAtlases$changed();
    }

    @Inject(method = "add", at = @At("HEAD"))
    private void betterMapAtlases$adding(int id, Level level, CallbackInfoReturnable<Boolean> callback) {
        betterMapAtlases$addingKnownId = ids.contains(id);
    }

    @Inject(method = "add", at = @At("RETURN"))
    private void betterMapAtlases$added(int id, Level level, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValue() || (!betterMapAtlases$addingKnownId && ids.contains(id)))
            betterMapAtlases$changed();
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void betterMapAtlases$removing(MapDataHolder holder, CallbackInfoReturnable<Boolean> callback) {
        betterMapAtlases$removingKnownKey = maps.containsKey(holder.makeKey());
    }

    @Inject(method = "remove", at = @At("RETURN"))
    private void betterMapAtlases$removed(MapDataHolder holder, CallbackInfoReturnable<Boolean> callback) {
        // Even a malformed collection can remove its key without removing its ID.
        if (callback.getReturnValue() || betterMapAtlases$removingKnownKey) betterMapAtlases$changed();
    }

    @Inject(method = "addNotSynced", at = @At("RETURN"))
    private void betterMapAtlases$refreshed(Level level, CallbackInfo callback) {
        // The existing kind/BNM hooks may clear the map and fail to re-add every
        // pending payload. Successful re-adds already invalidate via add RETURN.
        if (betterMapAtlases$knownMapCount != maps.size()) betterMapAtlases$changed();
    }

    @Inject(method = "selectSection", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$section(Slice slice, CallbackInfoReturnable<List<MapDataHolder>> callback) {
        callback.setReturnValue(betterMapAtlases$sections().section(slice));
    }

    @Inject(method = "filterSection", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$filtered(Slice slice, Predicate<MapItemSavedData> predicate,
                                         CallbackInfoReturnable<List<MapDataHolder>> callback) {
        List<MapDataHolder> result = new ArrayList<>();
        for (MapDataHolder holder : betterMapAtlases$sections().section(slice))
            if (predicate.test(holder.data)) result.add(holder);
        callback.setReturnValue(result);
    }

    @Inject(method = "getAll", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$all(CallbackInfoReturnable<List<MapDataHolder>> callback) {
        callback.setReturnValue(new ArrayList<>(betterMapAtlases$sections().all()));
    }

    @Inject(method = "getClosest", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$closest(double x, double z, Slice slice,
                                        CallbackInfoReturnable<MapDataHolder> callback) {
        MapDataHolder nearest = null;
        for (MapDataHolder holder : betterMapAtlases$sections().section(slice)) {
            if (nearest == null || MapCollection.distSquare(nearest.data, x, z) > MapCollection.distSquare(holder.data, x, z))
                nearest = holder;
        }
        callback.setReturnValue(nearest);
    }
}
