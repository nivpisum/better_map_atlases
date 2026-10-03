/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.compat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.nivpisum.bettermapatlases.compat.NetherMapCompat;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import pepjebs.mapatlases.lifecycle.MapAtlasesServerEvents;
import pepjebs.mapatlases.utils.Slice;

@Mixin(value = MapAtlasesServerEvents.class, remap = false)
public abstract class AtlasServerEventsMixin {
    @WrapOperation(method = "onPlayerTick", at = @At(value = "INVOKE",
            target = "Lpepjebs/mapatlases/item/MapAtlasItem;getSelectedSlice(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/resources/ResourceKey;)Lpepjebs/mapatlases/utils/Slice;"))
    private static Slice accurateMaps$initializeCaveLayer(ItemStack atlas, ResourceKey<Level> dimension,
                                                         Operation<Slice> original, Player player) {
        // Better Map Atlases owns automatic surveying; BNM remains available for existing fixed pages.
        return original.call(atlas, dimension);
    }
}
