/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.compat.mixin;

import org.nivpisum.bettermapatlases.compat.NetherMapCompat;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CartographyTableMenu.class)
public abstract class AtlasCartographyMixin {
    @Shadow @Final private ContainerLevelAccess access;

    @Inject(method = "slotsChanged", at = @At("HEAD"))
    private void accurateMaps$importCartographyInput(Container input, CallbackInfo callback) {
        access.execute((level, position) -> {
            for (int i = 0; i < Math.min(2, input.getContainerSize()); i++) {
                NetherMapCompat.importItem(level, input.getItem(i));
            }
        });
    }
}
