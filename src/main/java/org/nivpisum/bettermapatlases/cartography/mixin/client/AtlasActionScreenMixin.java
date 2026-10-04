/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.cartography.mixin.client;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ColumnPos;
import org.jetbrains.annotations.Nullable;
import org.nivpisum.bettermapatlases.cartography.CartographyActions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pepjebs.mapatlases.client.screen.AtlasOverviewScreen;
import pepjebs.mapatlases.client.screen.PinButton;
import pepjebs.mapatlases.networking.C2SRemoveMapPacket;
import pepjebs.mapatlases.networking.MapAtlasesNetworking;
import pepjebs.mapatlases.utils.MapDataHolder;

/** Keep the native tools while waiting for real server results instead of editing the local book. */
@Mixin(value = AtlasOverviewScreen.class, remap = false)
public abstract class AtlasActionScreenMixin {
    @Shadow @Final private boolean isPinOnly;
    @Shadow private Pair<MapDataHolder, ColumnPos> partialPin;
    @Shadow protected abstract @Nullable MapDataHolder findMapContaining(int x, int z);
    @Shadow public abstract void clearCursorAction();

    @Inject(method = "placePinAt", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$placeCarriedBanner(ColumnPos pos, CallbackInfo callback) {
        callback.cancel();
        MapDataHolder map = findMapContaining(pos.x(), pos.z());
        if (map != null) PinButton.placePin(map, pos, "", 0);
        partialPin = null;
        clearCursorAction();
    }

    @Inject(method = "focusEditBox", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$quickPinUsesBannerName(boolean on, CallbackInfo callback) {
        if (!on || !isPinOnly) return;
        callback.cancel();
        // The native quick-pin keybind has already chosen the current page and position.
        if (partialPin != null) PinButton.placePin(partialPin.getFirst(), partialPin.getSecond(), "", 0);
        partialPin = null;
        clearCursorAction();
        ((AtlasOverviewScreen) (Object) this).onClose();
    }

    @Inject(method = "shearMapAt", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$requestMapCut(ColumnPos pos, CallbackInfo callback) {
        callback.cancel();
        var player = Minecraft.getInstance().player;
        if (player != null) {
            if (CartographyActions.findShears(player).isEmpty()) CartographyActions.message(player, "shears_required");
            else {
                MapDataHolder map = findMapContaining(pos.x(), pos.z());
                if (map != null) MapAtlasesNetworking.CHANNEL.sendToServer(new C2SRemoveMapPacket(map.id));
            }
        }
        clearCursorAction();
    }
}
