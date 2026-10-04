/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin.client;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.nivpisum.bettermapatlases.client.AtlasUiText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pepjebs.mapatlases.client.screen.DimensionBookmarkButton;

@Mixin(value = DimensionBookmarkButton.class, remap = false)
public abstract class AtlasDimensionLabelMixin {
    @Shadow @Final private ResourceKey<Level> dimension;

    @Inject(method = "createTooltip", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$dimensionTooltip(CallbackInfoReturnable<Tooltip> callback) {
        callback.setReturnValue(Tooltip.create(AtlasUiText.dimensionName(dimension.location())));
    }
}
