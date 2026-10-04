/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ColumnPos;
import org.nivpisum.bettermapatlases.client.AtlasDimensionNavigation;
import org.nivpisum.bettermapatlases.client.AtlasHudSettings;
import org.nivpisum.bettermapatlases.client.AtlasOverviewHeight;
import org.nivpisum.bettermapatlases.client.AtlasViewport;
import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pepjebs.mapatlases.client.screen.AtlasOverviewScreen;
import pepjebs.mapatlases.client.screen.MapWidget;
import pepjebs.mapatlases.client.AbstractAtlasWidget;
import pepjebs.mapatlases.client.ui.MapAtlasesHUD;
import pepjebs.mapatlases.config.MapAtlasesClientConfig;
import pepjebs.mapatlases.utils.Slice;

@Mixin(value = MapWidget.class, remap = false)
public abstract class AtlasDynamicMapWidgetMixin extends AbstractAtlasWidget implements AtlasViewport {
    @Shadow @Final private int width;
    @Shadow @Final private int height;
    @Shadow @Final private int x;
    @Shadow @Final private int y;
    @Shadow @Final private AtlasOverviewScreen mapScreen;
    @Shadow private double targetXCenter;
    @Shadow private double targetZCenter;
    @Shadow public abstract ColumnPos getHoveredPos(double mouseX, double mouseY);
    protected AtlasDynamicMapWidgetMixin(int count) { super(count); }

    @Override public AtlasDimensionNavigation.Center betterMapAtlases$center() {
        return new AtlasDimensionNavigation.Center(currentXCenter, currentZCenter);
    }
    @Override public boolean betterMapAtlases$followsPlayer() { return followingPlayer; }
    @Override public void betterMapAtlases$center(AtlasDimensionNavigation.Center center, boolean following) {
        currentXCenter = targetXCenter = center.x(); currentZCenter = targetZCenter = center.z();
        followingPlayer = following;
    }

    @ModifyExpressionValue(method = "render", remap = true, at = @At(value = "FIELD", remap = false,
            target = "Lpepjebs/mapatlases/config/MapAtlasesClientConfig;worldMapBorder:Ljava/util/function/Supplier;"))
    private Supplier<Boolean> betterMapAtlases$pageBorders(Supplier<Boolean> original) { return () -> AtlasHudSettings.get().pageBorders(); }

    @Inject(method = "tick", at = @At("RETURN"))
    private void betterMapAtlases$exactFollowing(CallbackInfo callback) {
        // Native following truncates the target to int; keep the reader's exact position instead.
        var player = Minecraft.getInstance().player;
        if (followingPlayer && player != null) { targetXCenter = player.getX(); targetZCenter = player.getZ(); }
    }

    @Inject(method = "renderPositionText", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$xyz(GuiGraphics graphics, Font font, int mouseX, int mouseY, CallbackInfo callback) {
        callback.cancel();
        if (!MapAtlasesClientConfig.drawWorldMapCoords.get()) return;
        ColumnPos position = getHoveredPos(mouseX, mouseY);
        double viewY = ((AtlasOverviewHeight) mapScreen).betterMapAtlases$heightY16() / 16.0;
        String heightText = viewY == Math.floor(viewY) ? Integer.toString((int) viewY) : Double.toString(viewY);
        String coordinates = Component.translatable("better_map_atlases.coordinates", position.x(), heightText, position.z()).getString();
        MapAtlasesHUD.drawScaledComponent(graphics, font, x, y + height + 8, coordinates,
                (float) (double) MapAtlasesClientConfig.worldMapCoordsScale.get(), width, width);
    }

    @ModifyArg(method = "render", remap = true, at = @At(value = "INVOKE", remap = false, ordinal = 0,
            target = "Lpepjebs/mapatlases/client/MapAtlasesClient;setDecorationsScale(F)V"), index = 0)
    private float betterMapAtlases$windowIndependentIcons(float original) { return original * 128f / width; }

    @ModifyArg(method = "render", remap = true, at = @At(value = "INVOKE", remap = false, ordinal = 0,
            target = "Lpepjebs/mapatlases/client/MapAtlasesClient;setDecorationsTextScale(F)V"), index = 0)
    private float betterMapAtlases$windowIndependentText(float original) { return original * 128f / width; }

    @WrapOperation(method = "mouseClicked", remap = true, at = @At(value = "INVOKE", remap = false,
            target = "Lpepjebs/mapatlases/utils/Slice;height()Ljava/lang/Integer;"))
    private Integer accurateMaps$teleportCoordinate(Slice slice, Operation<Integer> original) {
        return SurveyAtlas.isAutoSlice(slice) ? SurveyAtlas.coordinateHeight(slice) : original.call(slice);
    }
}
