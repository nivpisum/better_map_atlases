/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.cartography.mixin.client;

import net.minecraft.client.Minecraft;
import org.nivpisum.bettermapatlases.cartography.CartographyActions;
import org.nivpisum.bettermapatlases.cartography.mixin.MapDecorationsAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pepjebs.mapatlases.client.screen.AtlasOverviewScreen;
import pepjebs.mapatlases.client.screen.DecorationBookmarkButton;
import pepjebs.mapatlases.networking.C2SRemoveMarkerPacket;
import pepjebs.mapatlases.networking.MapAtlasesNetworking;
import pepjebs.mapatlases.utils.MapDataHolder;

@Mixin(value = DecorationBookmarkButton.Vanilla.class, remap = false)
public abstract class AtlasBannerMarkerDeleteMixin extends DecorationBookmarkButton {
    protected AtlasBannerMarkerDeleteMixin(int x, int y, AtlasOverviewScreen screen, MapDataHolder map, String id) {
        super(x, y, screen, map, id);
    }

    @Inject(method = "deleteMarker", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$waitForServerDeletion(CallbackInfo callback) {
        callback.cancel();
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        if (CartographyActions.findShears(player).isEmpty()) {
            CartographyActions.message(player, "shears_required");
            return;
        }
        var decoration = ((MapDecorationsAccessor) mapData.data).betterMapAtlases$getDecorations().get(decorationId);
        if (decoration != null) MapAtlasesNetworking.CHANNEL.sendToServer(
                new C2SRemoveMarkerPacket(mapData.stringId, decoration.hashCode(), false));
    }
}
