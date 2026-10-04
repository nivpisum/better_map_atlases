/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.nivpisum.bettermapatlases.dynamic.AtlasCollectionLookup;
import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;
import org.nivpisum.bettermapatlases.dynamic.SurveyRecorder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pepjebs.mapatlases.lifecycle.MapAtlasesServerEvents;
import pepjebs.mapatlases.map_collection.IMapCollection;
import pepjebs.mapatlases.utils.Slice;
import pepjebs.mapatlases.utils.MapDataHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

@Mixin(value = MapAtlasesServerEvents.class, remap = false)
public abstract class AtlasDynamicServerMixin {
    @WrapOperation(method = "onPlayerTick", at = @At(value = "INVOKE",
            target = "Lpepjebs/mapatlases/map_collection/IMapCollection;filterSection(Lpepjebs/mapatlases/utils/Slice;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private static List<MapDataHolder> betterMapAtlases$nearby(IMapCollection maps, Slice slice,
            Predicate<MapItemSavedData> predicate, Operation<List<MapDataHolder>> original,
            @Local Set<Vector2i> discoveringEdges) {
        // Exact map centers are already computed by upstream. Use its public
        // coordinate lookup and retain predicate and native traversal order.
        if (!(maps instanceof AtlasCollectionLookup indexed)) return original.call(maps, slice, predicate);
        List<MapDataHolder> nearby = new ArrayList<>(discoveringEdges.size() + 1);
        for (Vector2i center : discoveringEdges) {
            MapDataHolder holder = maps.select(center.x, center.y, slice);
            if (holder != null && predicate.test(holder.data)) nearby.add(holder);
        }
        indexed.betterMapAtlases$order(nearby);
        return nearby;
    }

    @WrapOperation(method = "onPlayerTick", at = @At(value = "INVOKE",
            target = "Ljava/util/List;isEmpty()Z"))
    private static boolean betterMapAtlases$surveyEveryTick(List<MapDataHolder> nearby,
            Operation<Boolean> original, Player player) {
        // This point includes the active page and precedes native packet sync.
        // Sampling owns one shared budget and per-map tick dedup. Rotate the
        // starting page so a busy footprint cannot starve its neighboring pages.
        if (player instanceof ServerPlayer serverPlayer && serverPlayer.server.isSameThread()) {
            List<MapDataHolder> managed = new ArrayList<>(nearby.size());
            for (MapDataHolder holder : nearby) if (SurveyAtlas.isManaged(holder.data)) managed.add(holder);
            if (!managed.isEmpty()) {
                int start = Math.floorMod(player.level().getGameTime(), managed.size());
                for (int i = 0; i < managed.size(); i++)
                    SurveyRecorder.update(player.level(), player, managed.get((start + i) % managed.size()).data);
            }
        }
        return original.call(nearby);
    }

    @WrapOperation(method = "onPlayerTick", at = @At(value = "INVOKE",
            target = "Lpepjebs/mapatlases/item/MapAtlasItem;getSelectedSlice(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/resources/ResourceKey;)Lpepjebs/mapatlases/utils/Slice;"))
    private static Slice accurateMaps$initializeAuto(ItemStack atlas, ResourceKey<Level> dimension,
                                                    Operation<Slice> original, Player player) {
        return SurveyAtlas.initialSlice(player, atlas, original.call(atlas, dimension));
    }

    @Inject(method = "maybeCreateNewMapEntry", at = @At("HEAD"), cancellable = true)
    private static void accurateMaps$guardDynamicCreation(ServerPlayer player, ItemStack atlas, IMapCollection maps,
                                                         Slice slice, int x, int z, CallbackInfo callback) {
        if (!SurveyAtlas.canCreateDynamic(player, atlas, maps, slice, x, z)) callback.cancel();
    }
}
