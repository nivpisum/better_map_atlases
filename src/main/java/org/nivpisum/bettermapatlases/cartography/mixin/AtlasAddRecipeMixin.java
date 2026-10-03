/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.cartography.mixin;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pepjebs.mapatlases.recipe.MapAtlasesAddRecipe;
import pepjebs.mapatlases.recipe.MapAtlasesCutExistingRecipe;

/** Retain atlas creation; modification of an existing atlas belongs to its tools and the table. */
@Mixin(value = {MapAtlasesAddRecipe.class, MapAtlasesCutExistingRecipe.class}, remap = false)
public abstract class AtlasAddRecipeMixin {
    // In the exact 6.0.20 JAR these typed methods keep their source names.
    // Only the generated Container bridge methods carry Minecraft's SRG names.
    @Inject(method = "matches(Lnet/minecraft/world/inventory/CraftingContainer;Lnet/minecraft/world/level/Level;)Z",
            at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$noCraftingMutation(CraftingContainer grid, Level level,
                                                   CallbackInfoReturnable<Boolean> callback) {
        callback.setReturnValue(false);
    }

    @Inject(method = "assemble(Lnet/minecraft/world/inventory/CraftingContainer;Lnet/minecraft/core/RegistryAccess;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$noDirectAssembly(CraftingContainer grid, RegistryAccess registry,
                                                  CallbackInfoReturnable<ItemStack> callback) {
        callback.setReturnValue(ItemStack.EMPTY);
    }
}
