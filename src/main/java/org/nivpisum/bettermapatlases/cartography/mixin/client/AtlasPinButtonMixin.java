/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.cartography.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ColumnPos;
import org.nivpisum.bettermapatlases.cartography.CartographyActions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pepjebs.mapatlases.client.screen.PinButton;
import pepjebs.mapatlases.networking.C2SMarkerPacket;
import pepjebs.mapatlases.networking.MapAtlasesNetworking;
import pepjebs.mapatlases.utils.MapDataHolder;

/** Moonlight normally bypasses the server here and writes a free local-only pin. */
@Mixin(value = PinButton.class, remap = false)
public abstract class AtlasPinButtonMixin {
    @Inject(method = "placePin", at = @At("HEAD"), cancellable = true)
    private static void betterMapAtlases$requestBannerPin(MapDataHolder map, ColumnPos pos, String ignoredName,
                                                         int ignoredIcon, CallbackInfo callback) {
        callback.cancel();
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        if (CartographyActions.findBanner(player).isEmpty()) {
            CartographyActions.message(player, "banner_required");
            return;
        }
        // The server derives the name and colour from the actual carried banner.
        MapAtlasesNetworking.CHANNEL.sendToServer(new C2SMarkerPacket(pos, map.stringId, null));
    }
}
