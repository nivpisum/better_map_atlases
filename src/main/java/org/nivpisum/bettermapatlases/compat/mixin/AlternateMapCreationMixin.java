/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.compat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.syric.betternethermap.items.AlternateMapItem;
import org.nivpisum.bettermapatlases.compat.NetherMapCompat;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = AlternateMapItem.class, remap = false)
public abstract class AlternateMapCreationMixin {
    @WrapOperation(method = {"use", "m_7203_"}, remap = false, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/item/MapItem;create(Lnet/minecraft/world/level/Level;IIBZZ)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack accurateMaps$rememberAlternateHeight(Level level, int x, int z, byte scale,
                                                          boolean track, boolean unlimited, Operation<ItemStack> original,
                                                          Level callerLevel, Player player, InteractionHand hand) {
        return NetherMapCompat.createAlternate(level, player, player.getItemInHand(hand),
                original.call(level, x, z, scale, track, unlimited));
    }
}
