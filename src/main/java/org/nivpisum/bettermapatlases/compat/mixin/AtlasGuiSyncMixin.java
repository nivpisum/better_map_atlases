/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.compat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.nivpisum.bettermapatlases.compat.NetherMapCompat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import pepjebs.mapatlases.item.MapAtlasItem;
import pepjebs.mapatlases.utils.MapDataHolder;
import pepjebs.mapatlases.utils.TriState;

@Mixin(value = MapAtlasItem.class, remap = false)
public abstract class AtlasGuiSyncMixin {
    @WrapOperation(method = "syncAndOpenGui", at = @At(value = "INVOKE",
            target = "Lpepjebs/mapatlases/utils/MapAtlasesAccessUtils;updateMapDataAndSync(Lpepjebs/mapatlases/utils/MapDataHolder;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;Lpepjebs/mapatlases/utils/TriState;)V"))
    private static void accurateMaps$syncEveryPageHeight(MapDataHolder holder, ServerPlayer player,
                                                       ItemStack atlas, TriState carried,
                                                       Operation<Void> original) {
        NetherMapCompat.requestHeightSync(holder.data, player);
        original.call(holder, player, atlas, carried);
    }
}
