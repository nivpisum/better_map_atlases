/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import pepjebs.mapatlases.client.screen.AtlasOverviewScreen;
import pepjebs.mapatlases.client.screen.BookmarkButton;

/** Small gear in the existing pin/shears function column. */
public final class AtlasSettingsButton extends BookmarkButton {
    private static final int BUTTON_SIZE = 16;
    private static final int ICON_SIZE = 12;
    private static final int GEAR_TEXTURE_SIZE = 32;
    private static final int GEAR_MIDDLE_TEXTURE_SIZE = 16;
    private static final ResourceLocation GEAR_TEXTURE = new ResourceLocation(
            "better_map_atlases", "textures/gui/legacy_gear.png");
    private static final ResourceLocation GEAR_MIDDLE_TEXTURE = new ResourceLocation(
            "better_map_atlases", "textures/gui/legacy_gear_middle.png");

    public AtlasSettingsButton(int x, int y, AtlasOverviewScreen screen) {
        super(x, y, BUTTON_SIZE, BUTTON_SIZE, 30, 152, screen);
        setMessage(Component.translatable("better_map_atlases.settings.title"));
        setTooltip(Tooltip.create(getMessage()));
    }
    @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        if (!visible || !active) return;
        if (parentScreen.isEditingText()) isHovered = false;
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        int iconX = getX() + 1;
        int iconY = getY() + (BUTTON_SIZE - ICON_SIZE) / 2;
        // Native selected pin/shears sprites occupy 12×12 inside their 16×16 buttons.
        graphics.blit(GEAR_TEXTURE, iconX, iconY, ICON_SIZE, ICON_SIZE, 0, 0,
                GEAR_TEXTURE_SIZE, GEAR_TEXTURE_SIZE, GEAR_TEXTURE_SIZE, GEAR_TEXTURE_SIZE);
        // Infdev overlays the full 16×16 middle onto the outer's full output canvas.
        graphics.blit(GEAR_MIDDLE_TEXTURE, iconX, iconY, ICON_SIZE, ICON_SIZE, 0, 0,
                GEAR_MIDDLE_TEXTURE_SIZE, GEAR_MIDDLE_TEXTURE_SIZE,
                GEAR_MIDDLE_TEXTURE_SIZE, GEAR_MIDDLE_TEXTURE_SIZE);
    }
    @Override public void onClick(double mouseX, double mouseY) {
        parentScreen.getMinecraft().setScreen(new AtlasSettingsScreen(parentScreen));
    }
}
