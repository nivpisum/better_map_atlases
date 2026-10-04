/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pepjebs.mapatlases.item.MapAtlasItem;
import pepjebs.mapatlases.utils.MapDataHolder;
import pepjebs.mapatlases.utils.TriState;

@Mixin(value = MapAtlasItem.class, remap = false)
public abstract class AtlasDynamicGuiSyncMixin {
    @Inject(method = "syncAndOpenGui", at = @At("HEAD"))
    private static void accurateMaps$rememberLectern(ServerPlayer player, ItemStack atlas, BlockPos lecternPos,
                                                    boolean pinOnly, CallbackInfo callback) {
        SurveyAtlas.recordGuiAccess(player, lecternPos);
    }
    @WrapOperation(method = "syncAndOpenGui", at = @At(value = "INVOKE",
            target = "Lpepjebs/mapatlases/utils/MapAtlasesAccessUtils;updateMapDataAndSync(Lpepjebs/mapatlases/utils/MapDataHolder;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;Lpepjebs/mapatlases/utils/TriState;)V"))
    private static void accurateMaps$syncPageKind(MapDataHolder holder, ServerPlayer player, ItemStack atlas,
                                                TriState carried, Operation<Void> original) {
        SurveyAtlas.requestKindSync(holder.data, player);
        original.call(holder, player, atlas, carried);
    }
}
