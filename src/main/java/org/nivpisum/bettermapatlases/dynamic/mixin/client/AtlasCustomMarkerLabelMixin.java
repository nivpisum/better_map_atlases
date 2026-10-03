/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin.client;

import net.mehvahdjukaar.moonlight.api.map.CustomMapDecoration;
import net.mehvahdjukaar.moonlight.api.util.Utils;
import net.minecraft.network.chat.Component;
import org.nivpisum.bettermapatlases.client.AtlasUiText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pepjebs.mapatlases.integration.moonlight.CustomDecorationButton;

@Mixin(value = CustomDecorationButton.class, remap = false)
public abstract class AtlasCustomMarkerLabelMixin {
    @Shadow @Final private CustomMapDecoration decoration;

    @Inject(method = "getDecorationName", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$defaultCustomMarkerName(CallbackInfoReturnable<Component> callback) {
        if (decoration.getDisplayName() == null) {
            callback.setReturnValue(AtlasUiText.customMarkerName(Utils.getID(decoration.getType())));
        }
    }
}
