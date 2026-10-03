/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin;

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

/** Used without BNM; with BNM the existing combined height/kind hook owns this. */
@Mixin(value = MapCollection.class, remap = false)
public abstract class AtlasDynamicCollectionMixin {
    @Shadow @Final private Map<MapKey, MapDataHolder> maps;
    @Shadow @Final private Set<Integer> ids;
    @Shadow @Final private Map<ResourceKey<Level>, Map<MapType, TreeSet<Integer>>> dimensionSlices;
    @Shadow @Final private Set<Integer> notSyncedIds;
    @Shadow private CompoundTag lazyNbt;
    @Shadow public abstract boolean isInitialized();
    @Shadow public abstract boolean add(int mapId, Level level);
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
        for (int id : snapshot) { ids.add(id); notSyncedIds.add(id); }
        accurateMaps$rebuildingSnapshot = true;
    }
    @Inject(method = "initialize", at = @At("RETURN"))
    private void accurateMaps$finishSnapshot(Level level, CallbackInfo callback) {
        if (!accurateMaps$rebuildingSnapshot) return;
        for (MapDataHolder holder : maps.values()) notSyncedIds.remove(holder.id);
        accurateMaps$kindRevision = SurveyAtlas.kindRevision();
        accurateMaps$rebuildingSnapshot = false;
    }
    @Inject(method = "addNotSynced", at = @At("HEAD"))
    private void accurateMaps$refreshKinds(Level level, CallbackInfo callback) {
        if (!level.isClientSide || !isInitialized()) return;
        long revision = SurveyAtlas.kindRevision();
        if (accurateMaps$kindRevision == revision) return;
        accurateMaps$kindRevision = revision;
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
