/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.compat.mixin;

import org.nivpisum.bettermapatlases.compat.NetherMapCompat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MapItem.class, priority = 900)
public abstract class BnmMapItemMixin {
    @Inject(method = "getSavedData(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;", at = @At("RETURN"))
    private static void accurateMaps$importBnmItem(ItemStack stack, Level level,
                                                 CallbackInfoReturnable<MapItemSavedData> callback) {
        NetherMapCompat.importItem(level, stack, callback.getReturnValue());
    }
}
