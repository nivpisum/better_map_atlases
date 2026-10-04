/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.cartography.mixin;

import net.mehvahdjukaar.moonlight.api.platform.network.ChannelHandler;
import net.minecraft.server.level.ServerPlayer;
import org.nivpisum.bettermapatlases.cartography.CartographyActions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pepjebs.mapatlases.networking.C2SRemoveMarkerPacket;

@Mixin(value = C2SRemoveMarkerPacket.class, remap = false)
public abstract class AtlasRemoveMarkerPacketMixin {
    @Shadow @Final private int decoHash;
    @Shadow @Final private String mapId;
    @Shadow @Final private boolean isCustom;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$chargeShears(ChannelHandler.Context context, CallbackInfo callback) {
        callback.cancel();
        if (context.getSender() instanceof ServerPlayer player)
            CartographyActions.removeMarker(player, mapId, decoHash, isCustom);
    }
}
