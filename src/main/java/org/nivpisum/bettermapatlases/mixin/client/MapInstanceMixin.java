/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import org.nivpisum.bettermapatlases.client.ClientMaps;
import org.nivpisum.bettermapatlases.client.ClientSurvey;
import net.minecraft.client.gui.MapRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.MapRenderer$MapInstance")
public abstract class MapInstanceMixin {
    @Shadow private MapItemSavedData data;
    @Shadow private boolean requiresUpload;
    @Unique private int accurateMaps$id;
    @Unique private long accurateMaps$revision = Long.MIN_VALUE;
    @Unique private long accurateMaps$epoch = Long.MIN_VALUE;
    @Unique private long accurateMaps$surveyEpoch = Long.MIN_VALUE;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void accurateMaps$init(MapRenderer renderer, int id, MapItemSavedData map, CallbackInfo callback) {
        accurateMaps$id = id;
    }

    @Inject(method = "draw", at = @At("HEAD"))
    private void accurateMaps$refresh(PoseStack pose, MultiBufferSource buffers, boolean decorations, int light, CallbackInfo callback) {
        long revision = ClientMaps.revision(accurateMaps$id);
        long epoch = ClientMaps.resourceEpoch();
        long surveyEpoch = ClientSurvey.renderEpoch(accurateMaps$id, data);
        if (revision != accurateMaps$revision || epoch != accurateMaps$epoch || surveyEpoch != accurateMaps$surveyEpoch) {
            requiresUpload = true;
            accurateMaps$revision = revision;
            accurateMaps$epoch = epoch;
            accurateMaps$surveyEpoch = surveyEpoch;
        }
    }

    @Redirect(method = "updateTexture", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/DynamicTexture;upload()V"))
    private void accurateMaps$overlay(DynamicTexture texture) {
        ClientMaps.overlay(accurateMaps$id, data, texture.getPixels());
        ClientSurvey.overlay(accurateMaps$id, data, texture.getPixels());
        texture.upload();
    }
}
