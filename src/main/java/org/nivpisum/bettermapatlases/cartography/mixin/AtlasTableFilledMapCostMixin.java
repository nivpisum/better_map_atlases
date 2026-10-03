/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.cartography.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import pepjebs.mapatlases.item.MapAtlasItem;
import pepjebs.mapatlases.utils.MapAtlasesAccessUtils;

/** Map Atlases already charges a filled map in its onTake HEAD hook. */
@Mixin(targets = "net.minecraft.world.inventory.CartographyTableMenu$5")
public abstract class AtlasTableFilledMapCostMixin {
    @WrapOperation(method = "onTake", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/Slot;remove(I)Lnet/minecraft/world/item/ItemStack;"), require = 2)
    private ItemStack betterMapAtlases$chargeFilledMapOnce(Slot slot, int amount, Operation<ItemStack> original,
            @Share("betterMapAtlases$filledMapAlreadyPaid") LocalBooleanRef paid) {
        if (slot.getContainerSlot() == 0) {
            // Runs after every HEAD hook, but before vanilla consumes the atlas.
            // If only one filled map was supplied, the upstream hook already emptied
            // slot 1; allowing vanilla to remove from that empty slot is harmless.
            paid.set(slot.getItem().getItem() instanceof MapAtlasItem
                    && MapAtlasesAccessUtils.isValidFilledMap(slot.container.getItem(1)));
        } else if (slot.getContainerSlot() == 1 && paid.get()) {
            return ItemStack.EMPTY;
        }
        return original.call(slot, amount);
    }
}
