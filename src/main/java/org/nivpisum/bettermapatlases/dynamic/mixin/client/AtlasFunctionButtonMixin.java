/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import pepjebs.mapatlases.client.screen.BookmarkButton;
import pepjebs.mapatlases.client.screen.PinButton;
import pepjebs.mapatlases.client.screen.ShearButton;

/** Align the function icons' visible edges without moving their interactive boxes. */
@Mixin(value = BookmarkButton.class, remap = false)
public abstract class AtlasFunctionButtonMixin {
    @Redirect(method = "renderWidget", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V"))
    private void betterMapAtlases$alignFunctionIcon(GuiGraphics graphics, ResourceLocation texture,
            int x, int y, int u, int v, int width, int height) {
        BookmarkButton button = (BookmarkButton) (Object) this;
        boolean pin = button instanceof PinButton;
        if (!pin && !(button instanceof ShearButton)) {
            graphics.blit(texture, x, y, u, v, width, height);
        } else if (button.selected()) {
            // The pin is opaque at [1,13); the shears at [0,12). Keep their original pixels.
            graphics.blit(texture, x + (pin ? 0 : 1), y, u, v, width, height);
        } else {
            // Native unselected sprites are 16px-wide tab strips rather than icon glyphs.
            graphics.blit(texture, x + 1, y, 12, height, u, v, width, height, 256, 256);
        }
    }
}
