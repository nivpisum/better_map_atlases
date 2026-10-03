/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import org.nivpisum.bettermapatlases.client.AtlasSettingsButton;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pepjebs.mapatlases.client.MapAtlasesClient;
import pepjebs.mapatlases.client.screen.AtlasOverviewScreen;
import pepjebs.mapatlases.client.screen.PinButton;
import pepjebs.mapatlases.client.screen.PinNameBox;
import pepjebs.mapatlases.client.screen.ShearButton;

/** Fit native map widgets to the window; retain Atlas' bookmark and parchment styling. */
@Mixin(value = AtlasOverviewScreen.class, remap = false)
public abstract class AtlasOverviewLayoutMixin extends Screen {
    @Shadow @Final @Mutable private int BOOK_WIDTH;
    @Shadow @Final @Mutable private int BOOK_HEIGHT;
    @Shadow @Final @Mutable private int MAP_WIDGET_WIDTH;
    @Shadow @Final @Mutable private int MAP_WIDGET_HEIGHT;
    @Shadow @Final @Mutable public float globalScale;
    @Shadow @Final private boolean isPinOnly;
    @Shadow @Final private LecternBlockEntity lectern;
    @Shadow private PinNameBox editBox;

    protected AtlasOverviewLayoutMixin(Component title) { super(title); }

    @Inject(method = "init", remap = true, at = @At("HEAD"))
    private void betterMapAtlases$fitWindow(CallbackInfo callback) {
        globalScale = 1;
        BOOK_WIDTH = Math.max(128, width - 88);
        BOOK_HEIGHT = Math.max(104, height - (lectern == null ? 24 : 64));
        MAP_WIDGET_WIDTH = BOOK_WIDTH - 34;
        MAP_WIDGET_HEIGHT = BOOK_HEIGHT - 34;
    }

    @ModifyArg(method = "init", remap = true, at = @At(value = "INVOKE", remap = false,
            target = "Lpepjebs/mapatlases/client/screen/MapWidget;<init>(IIIIILpepjebs/mapatlases/client/screen/AtlasOverviewScreen;Lpepjebs/mapatlases/utils/MapDataHolder;)V"), index = 1)
    private int betterMapAtlases$mapInsideBinding(int nativeY) {
        return (height - BOOK_HEIGHT) / 2 + 17;
    }

    @Inject(method = "init", remap = true, at = @At("RETURN"))
    private void betterMapAtlases$settingsControl(CallbackInfo callback) {
        if (isPinOnly) return;
        int x = (width + BOOK_WIDTH) / 2 + 20;
        int y = (height - BOOK_HEIGHT) / 2 + 16;
        for (var child : children()) {
            if (child instanceof PinButton || child instanceof ShearButton)
                y = Math.max(y, ((AbstractWidget) child).getY() + 20);
        }
        addRenderableWidget(new AtlasSettingsButton(x, y, (AtlasOverviewScreen) (Object) this));
    }

    @Inject(method = "render", remap = true, at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$renderWindow(GuiGraphics graphics, int mouseX, int mouseY, float tick, CallbackInfo callback) {
        renderBackground(graphics);
        if (!isPinOnly) {
            RenderSystem.enableDepthTest();
            graphics.blitNineSliced(MapAtlasesClient.ATLAS_BACKGROUND_TEXTURE,
                    (width - BOOK_WIDTH) / 2, (height - BOOK_HEIGHT) / 2,
                    BOOK_WIDTH, BOOK_HEIGHT, 12, 162, 167, 0, 0);
            super.render(graphics, mouseX, mouseY, tick);
        }
        if (editBox.active) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 22);
            renderBackground(graphics);
            editBox.render(graphics, mouseX, mouseY, tick);
            graphics.pose().popPose();
        }
        callback.cancel();
    }
}
