/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.cartography.mixin.client;

import net.mehvahdjukaar.moonlight.api.map.ExpandedMapData;
import net.minecraft.client.Minecraft;
import org.nivpisum.bettermapatlases.cartography.CartographyActions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pepjebs.mapatlases.client.screen.AtlasOverviewScreen;
import pepjebs.mapatlases.client.screen.DecorationBookmarkButton;
import pepjebs.mapatlases.integration.moonlight.ClientMarkers;
import pepjebs.mapatlases.integration.moonlight.CustomDecorationButton;
import pepjebs.mapatlases.networking.C2SRemoveMarkerPacket;
import pepjebs.mapatlases.networking.MapAtlasesNetworking;
import pepjebs.mapatlases.utils.MapDataHolder;

@Mixin(value = CustomDecorationButton.class, remap = false)
public abstract class AtlasCustomMarkerDeleteMixin extends DecorationBookmarkButton {
    protected AtlasCustomMarkerDeleteMixin(int x, int y, AtlasOverviewScreen screen, MapDataHolder map, String id) {
        super(x, y, screen, map, id);
    }

    @Inject(method = "deleteMarker", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$waitForServerDeletion(CallbackInfo callback) {
        var player = Minecraft.getInstance().player;
        if (player == null || CartographyActions.findShears(player).isEmpty()) {
            callback.cancel();
            if (player != null) CartographyActions.message(player, "shears_required");
            return;
        }
        // Preserve old client-only waypoints: there is no server object to remove or charge for.
        if (ClientMarkers.send(mapData.id, mapData.data).stream()
                .anyMatch(marker -> marker.getMarkerId().equals(decorationId))) return;
        callback.cancel();
        var decoration = ((ExpandedMapData) mapData.data).getCustomDecorations().get(decorationId);
        if (decoration != null) MapAtlasesNetworking.CHANNEL.sendToServer(
                new C2SRemoveMarkerPacket(mapData.stringId, decoration.hashCode(), true));
    }
}
