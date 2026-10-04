/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.nivpisum.bettermapatlases.client.AtlasHudSettings;
import org.nivpisum.bettermapatlases.client.AtlasHudText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pepjebs.mapatlases.client.AbstractAtlasWidget;
import pepjebs.mapatlases.client.Anchoring;
import pepjebs.mapatlases.client.ui.MapAtlasesHUD;
import pepjebs.mapatlases.client.MapAtlasesClient;
import pepjebs.mapatlases.utils.MapType;
import pepjebs.mapatlases.utils.MapDataHolder;

/** HUD geometry, map markers, player pointer and labels have independent screen sizes. */
@Mixin(value = MapAtlasesHUD.class, remap = false)
public abstract class AtlasHudZoomMixin extends AbstractAtlasWidget {
    @Shadow private float globalScale;
    @Unique private String betterMapAtlases$label = "";
    @Unique private int betterMapAtlases$labelX, betterMapAtlases$labelY, betterMapAtlases$labelWidth;
    @Unique private float betterMapAtlases$labelScale;
    protected AtlasHudZoomMixin(int count) { super(count); }

    @Unique private void betterMapAtlases$apply() {
        AtlasHudSettings settings = AtlasHudSettings.get();
        var window = Minecraft.getInstance().getWindow();
        globalScale = settings.displayScale(window.getGuiScaledWidth(), window.getGuiScaledHeight());
        rotatesWithPlayer = !settings.northUp();
        zoomLevel = 1;
    }
    @Inject(method = "initialize", at = @At("RETURN"))
    private void betterMapAtlases$initSettings(MapDataHolder map, CallbackInfo callback) { betterMapAtlases$apply(); }

    @Inject(method = "render", at = @At("HEAD"))
    private void betterMapAtlases$liveSettings(GuiGraphics graphics, float tick, int width, int height, CallbackInfo callback) {
        betterMapAtlases$apply();
        betterMapAtlases$label = "";
    }
    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD",
            target = "Lpepjebs/mapatlases/config/MapAtlasesClientConfig;miniMapZoomMultiplier:Ljava/util/function/Supplier;"))
    private Supplier<Double> betterMapAtlases$zoom(Supplier<Double> nativeValue) { return () -> AtlasHudSettings.get().zoom(); }

    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD",
            target = "Lpepjebs/mapatlases/config/MapAtlasesClientConfig;miniMapBorder:Ljava/util/function/Supplier;"))
    private Supplier<Boolean> betterMapAtlases$pageBorders(Supplier<Boolean> original) { return () -> AtlasHudSettings.get().pageBorders(); }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lpepjebs/mapatlases/client/ui/MapAtlasesHUD;drawAtlas(Lnet/minecraft/client/gui/GuiGraphics;IIIILnet/minecraft/world/entity/player/Player;FZLpepjebs/mapatlases/utils/MapType;ILnet/minecraft/world/level/saveddata/maps/MapItemSavedData;)V"))
    private void betterMapAtlases$paperBeforeMap(MapAtlasesHUD hud, GuiGraphics graphics, int x, int y, int width, int height,
            Player player, float zoom, boolean borders, MapType type, int light, MapItemSavedData selected, Operation<Void> original) {
        // Native HUD draws paper after the map. Translucent map pixels have already
        // written depth there, so the late paper cannot fill them and the world shows through.
        int border = (64 - width) / 2;
        RenderSystem.disableDepthTest();
        graphics.blit(MapAtlasesClient.MAP_HUD_BACKGROUND_TEXTURE, x - border, y - border, -2, 0, 0, 64, 64, 64, 64);
        original.call(hud, graphics, x, y, width, height, player, zoom, borders, type, light, selected);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIFFIIII)V"))
    private void betterMapAtlases$skipLatePaper(GuiGraphics graphics, ResourceLocation texture, int x, int y, int z,
            float u, float v, int width, int height, int textureWidth, int textureHeight, Operation<Void> original) {
        if (!texture.equals(MapAtlasesClient.MAP_HUD_BACKGROUND_TEXTURE))
            original.call(graphics, texture, x, y, z, u, v, width, height, textureWidth, textureHeight);
    }

    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD",
            target = "Lpepjebs/mapatlases/config/MapAtlasesClientConfig;miniMapAnchoring:Ljava/util/function/Supplier;"))
    private Supplier<Anchoring> betterMapAtlases$position(Supplier<Anchoring> nativeValue) { return () -> AtlasHudSettings.get().position(); }

    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD",
            target = "Lpepjebs/mapatlases/config/MapAtlasesClientConfig;miniMapVerticalOffset:Ljava/util/function/Supplier;"))
    private Supplier<Integer> betterMapAtlases$spaceBelowBottomMap(Supplier<Integer> nativeValue) {
        AtlasHudSettings settings = AtlasHudSettings.get();
        return settings.position().isUp ? nativeValue : () -> nativeValue.get() - settings.labelSpace();
    }

    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD",
            target = "Lpepjebs/mapatlases/config/MapAtlasesClientConfig;drawMinimapCardinals:Ljava/util/function/Supplier;"))
    private Supplier<Boolean> betterMapAtlases$hideCardinals(Supplier<Boolean> nativeValue) { return () -> false; }

    @ModifyConstant(method = "render", constant = @Constant(intValue = 5))
    private int betterMapAtlases$screenEdgePadding(int nativePadding) {
        return 0;
    }

    @ModifyArg(method = "render", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lpepjebs/mapatlases/client/MapAtlasesClient;setDecorationsScale(F)V"), index = 0)
    private float betterMapAtlases$fixedIconSize(float original) {
        AtlasHudSettings settings = AtlasHudSettings.get();
        return (float) (2 * zoomLevel * settings.zoom() * settings.iconScale() / globalScale);
    }
    @ModifyArg(method = "render", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lpepjebs/mapatlases/client/MapAtlasesClient;setDecorationsTextScale(F)V"), index = 0)
    private float betterMapAtlases$fixedTextSize(float original) {
        return (float) (2 * zoomLevel * AtlasHudSettings.get().zoom() / globalScale);
    }

    @Inject(method = "render", at = @At(value = "FIELD",
            target = "Lpepjebs/mapatlases/client/ui/MapAtlasesHUD;drawBigPlayerMarker:Z"))
    private void betterMapAtlases$playerPointer(GuiGraphics graphics, float tick, int width, int height, CallbackInfo callback) {
        // This native 8x8 blit occurs after decorationsScale has been reset. Its pose
        // is already centered and rotated here, but has not yet applied (-4.5, -4).
        float scale = (float) AtlasHudSettings.get().playerIconScale() / globalScale;
        graphics.pose().scale(scale, scale, 1);
    }

    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD",
            target = "Lpepjebs/mapatlases/config/MapAtlasesClientConfig;minimapCoordsAndBiomeScale:Ljava/util/function/Supplier;"))
    private Supplier<Double> betterMapAtlases$labelSize(Supplier<Double> nativeValue) { return () -> AtlasHudSettings.get().textScale(); }

    @ModifyArg(method = "render", at = @At(value = "INVOKE", ordinal = 0, remap = true,
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"), index = 1,
            slice = @Slice(from = @At(value = "FIELD",
                    target = "Lpepjebs/mapatlases/config/MapAtlasesClientConfig;minimapCoordsAndBiomeScale:Ljava/util/function/Supplier;"),
                    to = @At(value = "INVOKE", ordinal = 0,
                            target = "Lpepjebs/mapatlases/client/ui/MapAtlasesHUD;drawMapComponentCoords(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIFLnet/minecraft/core/BlockPos;Z)V")))
    private float betterMapAtlases$bottomLabelOffset(float nativeOffset) {
        // The bottom-anchored map has moved up to keep this row below it too.
        return 0;
    }

    @Redirect(method = "drawMapComponentCoords", at = @At(value = "INVOKE",
            target = "Lpepjebs/mapatlases/client/ui/MapAtlasesHUD;drawScaledComponent(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IILjava/lang/String;FII)V"))
    private void betterMapAtlases$collectCoordinates(GuiGraphics graphics, Font font, int x, int y, String text,
                                                    float localScale, int screenWidth, int localWidth) {
        betterMapAtlases$collectHudLabel(x, y, text, localScale, localWidth, false);
    }

    @Redirect(method = "drawMapComponentBiome", at = @At(value = "INVOKE",
            target = "Lpepjebs/mapatlases/client/ui/MapAtlasesHUD;drawScaledComponent(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IILjava/lang/String;FII)V"))
    private void betterMapAtlases$collectBiome(GuiGraphics graphics, Font font, int x, int y, String text,
                                              float localScale, int screenWidth, int localWidth) {
        betterMapAtlases$collectHudLabel(x, y, text, localScale, localWidth, true);
    }

    @Unique private void betterMapAtlases$collectHudLabel(int x, int y, String text, float localScale,
                                                         int localWidth, boolean biome) {
        // Keep native coordinate/chunk/biome switches and translations. Only the
        // final draw is replaced; the first enabled nonempty piece anchors the row.
        if (text.isBlank()) return;
        if (betterMapAtlases$label.isEmpty()) {
            betterMapAtlases$labelX = x;
            betterMapAtlases$labelY = y;
            betterMapAtlases$labelScale = localScale;
            betterMapAtlases$labelWidth = localWidth;
        }
        // Atlas supplies the biome last; present it before either coordinate form.
        betterMapAtlases$label = biome ? AtlasHudText.join(text, betterMapAtlases$label)
                : AtlasHudText.join(betterMapAtlases$label, text);
    }

    @Inject(method = "render", at = @At(value = "FIELD",
            target = "Lpepjebs/mapatlases/config/MapAtlasesClientConfig;drawMinimapCardinals:Ljava/util/function/Supplier;"))
    private void betterMapAtlases$drawHudLabel(GuiGraphics graphics, float tick, int width, int height, CallbackInfo callback) {
        if (betterMapAtlases$label.isEmpty()) return;
        Font font = Minecraft.getInstance().font;
        int textWidth = font.width(betterMapAtlases$label);
        // Fit to the actual minimap width once. Native code multiplies textScale
        // into its width constraint a second time, shrinking long labels too far.
        float scale = Math.min(betterMapAtlases$labelScale, betterMapAtlases$labelWidth / (float) Math.max(1, textWidth));
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(betterMapAtlases$labelX + betterMapAtlases$labelWidth / 2f, betterMapAtlases$labelY + 2 / globalScale, 5);
        pose.scale(scale, scale, 1);
        graphics.drawString(font, betterMapAtlases$label, -textWidth / 2, 0, 0xFFE0E0E0, true);
        pose.popPose();
    }

    @ModifyArg(method = "render", at = @At(value = "INVOKE",
            target = "Lpepjebs/mapatlases/integration/moonlight/ClientMarkersRenderer;drawSmallPins(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;DDLpepjebs/mapatlases/utils/Slice;FLnet/minecraft/world/entity/player/Player;ZLpepjebs/mapatlases/map_collection/IMapCollection;)V"), index = 5)
    private float betterMapAtlases$trackingRange(float original) {
        return original * (float) AtlasHudSettings.get().zoom();
    }
    @Inject(method = "increaseZoom", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$zoomIn(CallbackInfo callback) {
        AtlasHudSettings settings = AtlasHudSettings.get();
        settings.zoom(settings.zoom() - .25); settings.save(); callback.cancel();
    }
    @Inject(method = "decreaseZoom", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$zoomOut(CallbackInfo callback) {
        AtlasHudSettings settings = AtlasHudSettings.get();
        settings.zoom(settings.zoom() + .25); settings.save(); callback.cancel();
    }
}
