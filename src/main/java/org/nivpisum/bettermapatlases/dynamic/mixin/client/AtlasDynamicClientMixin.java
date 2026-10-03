/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;
import org.nivpisum.bettermapatlases.client.ClientSurvey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pepjebs.mapatlases.lifecycle.MapAtlasesClientEvents;
import pepjebs.mapatlases.client.MapAtlasesClient;
import pepjebs.mapatlases.item.MapAtlasItem;
import pepjebs.mapatlases.map_collection.IMapCollection;
import pepjebs.mapatlases.utils.MapType;
import pepjebs.mapatlases.utils.Slice;
import java.util.TreeSet;

@Mixin(value = MapAtlasesClientEvents.class, remap = false)
public abstract class AtlasDynamicClientMixin {
    @Inject(method = "onClientTick", at = @At("HEAD"))
    private static void betterMapAtlases$followEnvironment(Minecraft client, ClientLevel level, CallbackInfo callback) {
        if (level == null || client.player == null) return;
        ItemStack atlas = MapAtlasesClient.getCurrentActiveAtlas();
        if (atlas.isEmpty()) return;
        double y = ClientSurvey.viewY(level.dimension());
        if (Double.isNaN(y)) return;
        Slice actual = Slice.of(MapType.VANILLA, Double.isFinite(y) ? SurveyAtlas.AUTO_HEIGHT : null, level.dimension());
        if (!actual.equals(MapAtlasItem.getSelectedSlice(atlas, level.dimension()))) MapAtlasItem.setSelectedSlice(atlas, actual);
    }
    @Inject(method = "maybeChangeSlice", at = @At("HEAD"), cancellable = true)
    private static void accurateMaps$keepAuto(Player player, Level level, IMapCollection maps, Slice lastSlice,
                                             ItemStack atlas, CallbackInfo callback) {
        if (lastSlice.type() == MapType.VANILLA) callback.cancel();
    }

    @WrapOperation(method = "getClosestSlice", at = @At(value = "INVOKE",
            target = "Lpepjebs/mapatlases/map_collection/IMapCollection;getHeightTree(Lnet/minecraft/resources/ResourceKey;Lpepjebs/mapatlases/utils/MapType;)Ljava/util/TreeSet;"))
    private static TreeSet<Integer> accurateMaps$onlyNumericalHeights(IMapCollection maps, ResourceKey<Level> dimension,
                                                                     MapType type, Operation<TreeSet<Integer>> original) {
        TreeSet<Integer> heights = original.call(maps, dimension, type);
        if (!heights.contains(SurveyAtlas.AUTO_HEIGHT)) return heights;
        TreeSet<Integer> numerical = new TreeSet<>(heights);
        numerical.remove(SurveyAtlas.AUTO_HEIGHT);
        return numerical;
    }
}
