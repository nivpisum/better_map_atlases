/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin.client;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import org.nivpisum.bettermapatlases.client.AtlasUiText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pepjebs.mapatlases.client.screen.DecorationBookmarkButton;

@Mixin(value = DecorationBookmarkButton.Vanilla.class, remap = false)
public abstract class AtlasVanillaMarkerLabelMixin {
    @Shadow @Final private MapDecoration decoration;

    @Inject(method = "getDecorationName", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$defaultMarkerName(CallbackInfoReturnable<Component> callback) {
        if (decoration.getName() == null) {
            callback.setReturnValue(AtlasUiText.markerName(decoration.getType()));
        }
    }
}
