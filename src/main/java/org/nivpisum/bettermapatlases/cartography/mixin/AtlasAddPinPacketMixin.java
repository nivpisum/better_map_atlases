/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.cartography.mixin;

import net.mehvahdjukaar.moonlight.api.platform.network.ChannelHandler;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerPlayer;
import org.nivpisum.bettermapatlases.cartography.CartographyActions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pepjebs.mapatlases.networking.C2SMarkerPacket;

@Mixin(value = C2SMarkerPacket.class, remap = false)
public abstract class AtlasAddPinPacketMixin {
    @Shadow @Final private ColumnPos pos;
    @Shadow @Final private String mapId;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$chargeBanner(ChannelHandler.Context context, CallbackInfo callback) {
        callback.cancel();
        if (context.getSender() instanceof ServerPlayer player) CartographyActions.addPin(player, mapId, pos);
    }
}
