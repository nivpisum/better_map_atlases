/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.cartography.mixin.client;

import net.minecraft.server.level.ColumnPos;
import org.nivpisum.bettermapatlases.cartography.client.AtlasPinPosition;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pepjebs.mapatlases.client.AbstractAtlasWidget;
import pepjebs.mapatlases.client.screen.MapWidget;

/** Pins, coordinate readouts and map actions share the rendered map's inverse transform. */
@Mixin(value = MapWidget.class, remap = false)
public abstract class AtlasPinPositionMixin extends AbstractAtlasWidget {
    @Shadow @Final private int x;
    @Shadow @Final private int y;
    @Shadow @Final private int width;
    @Shadow @Final private int height;

    protected AtlasPinPositionMixin(int count) { super(count); }

    @Inject(method = "getHoveredPos", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$renderedWorldColumn(double mouseX, double mouseY,
                                                     CallbackInfoReturnable<ColumnPos> callback) {
        // Native getHoveredPos adds a +3 world-block "hackOffset" which the render
        // transform does not contain. Calculate the inverse directly instead.
        callback.setReturnValue(new ColumnPos(
                AtlasPinPosition.blockCoordinate(mouseX, x + width / 2.0, currentXCenter,
                        width, mapBlocksSize, zoomLevel),
                AtlasPinPosition.blockCoordinate(mouseY, y + height / 2.0, currentZCenter,
                        width, mapBlocksSize, zoomLevel)));
    }
}
