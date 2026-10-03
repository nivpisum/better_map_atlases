/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.compat.mixin;

import org.nivpisum.bettermapatlases.compat.NetherMapCompat;
import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pepjebs.mapatlases.map_collection.MapCollection;
import pepjebs.mapatlases.map_collection.MapKey;
import pepjebs.mapatlases.utils.MapDataHolder;
import pepjebs.mapatlases.utils.MapType;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Rebuild client-only derived indices when their authoritative inputs change. */
@Mixin(value = MapCollection.class, remap = false)
public abstract class AtlasCollectionMixin {
    @Shadow @Final private Map<MapKey, MapDataHolder> maps;
    @Shadow @Final private Set<Integer> ids;
    @Shadow @Final private Map<ResourceKey<Level>, Map<MapType, TreeSet<Integer>>> dimensionSlices;
    @Shadow @Final private Set<Integer> notSyncedIds;
    @Shadow private CompoundTag lazyNbt;
    @Shadow public abstract boolean isInitialized();
    @Shadow public abstract boolean add(int mapId, Level level);

    @Unique private long accurateMaps$heightRevision = -1;
    @Unique private long accurateMaps$kindRevision = -1;
    @Unique private boolean accurateMaps$rebuildingSnapshot;

    @Inject(method = "initialize", at = @At("HEAD"))
    private void accurateMaps$beginSnapshot(Level level, CallbackInfo callback) {
        if (!level.isClientSide || lazyNbt == null) return;
        int[] snapshot = lazyNbt.getIntArray(MapCollection.MAP_LIST_NBT);
        maps.clear();
        dimensionSlices.clear();
        ids.clear();
        notSyncedIds.clear();
        // Retain every authoritative ID, including pages whose height packet has
        // not arrived yet and currently collides with another surface map key.
        for (int id : snapshot) {
            ids.add(id);
            notSyncedIds.add(id);
        }
        accurateMaps$rebuildingSnapshot = true;
    }

    @Inject(method = "initialize", at = @At("RETURN"))
    private void accurateMaps$finishSnapshot(Level level, CallbackInfo callback) {
        if (!accurateMaps$rebuildingSnapshot) return;
        for (MapDataHolder holder : maps.values()) notSyncedIds.remove(holder.id);
        accurateMaps$heightRevision = NetherMapCompat.heightRevision();
        accurateMaps$kindRevision = SurveyAtlas.kindRevision();
        accurateMaps$rebuildingSnapshot = false;
    }

    @Inject(method = "addNotSynced", at = @At("HEAD"))
    private void accurateMaps$refreshChangedHeights(Level level, CallbackInfo callback) {
        if (!level.isClientSide || !isInitialized()) return;
        long revision = NetherMapCompat.heightRevision();
        long kindRevision = SurveyAtlas.kindRevision();
        if (accurateMaps$heightRevision == revision && accurateMaps$kindRevision == kindRevision) return;
        accurateMaps$heightRevision = revision;
        accurateMaps$kindRevision = kindRevision;
        int[] snapshot = ids.stream().mapToInt(Integer::intValue).toArray();
        maps.clear();
        dimensionSlices.clear();
        notSyncedIds.clear();
        for (int id : snapshot) {
            notSyncedIds.add(id);
            if (add(id, level)) notSyncedIds.remove(id);
        }
    }
}
