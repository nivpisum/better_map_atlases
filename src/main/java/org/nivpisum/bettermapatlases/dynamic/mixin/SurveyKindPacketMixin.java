/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin;

import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MapItemSavedData.class)
public abstract class SurveyKindPacketMixin {
    @Inject(method = "getUpdatePacket", at = @At("HEAD"))
    private void accurateMaps$prepareKind(int id, Player player, CallbackInfoReturnable<Packet<?>> callback) {
        SurveyAtlas.synchronizeKind((MapItemSavedData) (Object) this);
    }
    @Inject(method = "getUpdatePacket", at = @At("RETURN"))
    private void accurateMaps$includeKind(int id, Player player, CallbackInfoReturnable<Packet<?>> callback) {
        SurveyAtlas.includeKindInPacket((MapItemSavedData) (Object) this, callback.getReturnValue());
    }
}
