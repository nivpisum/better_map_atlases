/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin;

import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;
import org.nivpisum.bettermapatlases.dynamic.SurveyRecorder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pepjebs.mapatlases.utils.MapDataHolder;

@Mixin(value = MapDataHolder.class, remap = false)
public abstract class AtlasDynamicHolderMixin {
    @Shadow @Final public MapItemSavedData data;
    @Shadow private void updateMarkers(Player player, int range) { throw new AssertionError(); }

    @Inject(method = "updateMap", at = @At("HEAD"), cancellable = true)
    private void accurateMaps$surveyOnServerThread(ServerPlayer player, CallbackInfo callback) {
        if (!SurveyAtlas.isManaged(data)) return;
        // The survey branch must run before Atlas submits work to its executor.
        SurveyRecorder.update(player.level(), player, data);
        data.getHoldingPlayer(player).step++;
        updateMarkers(player, 128);
        callback.cancel();
    }
}
