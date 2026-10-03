/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.compat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.nivpisum.bettermapatlases.compat.NetherMapCompat;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.EmptyMapItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EmptyMapItem.class)
public abstract class EmptyMapCreationMixin {
    @WrapOperation(method = "use", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/MapItem;create(Lnet/minecraft/world/level/Level;IIBZZ)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack accurateMaps$rememberDefaultHeight(Level level, int x, int z, byte scale,
                                                        boolean track, boolean unlimited, Operation<ItemStack> original,
                                                        Level callerLevel, Player player, InteractionHand hand) {
        return NetherMapCompat.createVanilla(level, player, original.call(level, x, z, scale, track, unlimited));
    }
}
