/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pepjebs.mapatlases.client.screen.AtlasOverviewScreen;
import pepjebs.mapatlases.client.screen.BookmarkButton;
import pepjebs.mapatlases.client.screen.SliceBookmarkButton;
import pepjebs.mapatlases.client.MapAtlasesClient;
import pepjebs.mapatlases.utils.Slice;

/** The native slice bookmark is a stable cave-view tab, including surface-only books. */
@Mixin(value = SliceBookmarkButton.class, remap = false)
public abstract class AtlasDynamicLabelMixin extends BookmarkButton {
    @Shadow private Slice slice;
    @Unique private static final ItemStack betterMapAtlases$caveIcon = new ItemStack(Items.DEEPSLATE);
    protected AtlasDynamicLabelMixin(int x, int y, int width, int height, int u, int v, AtlasOverviewScreen screen) {
        super(x, y, width, height, u, v, screen);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void betterMapAtlases$matchDimensionTab(int x, int y, Slice initialSlice, AtlasOverviewScreen screen, CallbackInfo callback) {
        setX(x + 3);
        setWidth(24);
        setHeight(18);
        setMessage(Component.translatable("better_map_atlases.slice.tab"));
    }
    /** This Forge build invokes the two-argument Widget hook; upstream only defines the three-argument overload. */
    @Override public void onClick(double mouseX, double mouseY) { parentScreen.cycleSliceType(); }

    @Inject(method = "refreshState", at = @At("RETURN"))
    private void betterMapAtlases$enableCave(boolean slices, boolean types, CallbackInfo callback) {
        setActive(true);
        setSelected(SurveyAtlas.isAutoSlice(slice));
    }
    @Inject(method = "isValidClickButton", remap = true, at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$clickable(int button, CallbackInfoReturnable<Boolean> callback) { callback.setReturnValue(button == 0); }

    @Inject(method = "createTooltip", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$caveTooltip(CallbackInfoReturnable<Tooltip> callback) {
        callback.setReturnValue(Tooltip.create(Component.translatable("better_map_atlases.slice.tab")));
    }
    @Inject(method = "renderWidget", remap = true, at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$caveLabel(GuiGraphics graphics, int mouseX, int mouseY, float tick, CallbackInfo callback) {
        if (active && visible) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 2);
            RenderSystem.enableDepthTest();
            // The native slice sprite starts at v=231 and has no selected row:
            // BookmarkButton's v+height reads through the 256px texture edge.
            // Reuse the complete, paired dimension-tab sprites instead.
            graphics.blit(MapAtlasesClient.ATLAS_BACKGROUND_TEXTURE,
                    getX(), getY(), 0, selected() ? 185 : 167, 24, 18);
            if (parentScreen.isEditingText()) isHovered = false;
            graphics.renderFakeItem(betterMapAtlases$caveIcon, getX() + 4, getY() + 2);
            graphics.pose().popPose();
        }
        callback.cancel();
    }
}
