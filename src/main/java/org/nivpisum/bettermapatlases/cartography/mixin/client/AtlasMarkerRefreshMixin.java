/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.cartography.mixin.client;

import net.mehvahdjukaar.moonlight.api.map.ExpandedMapData;
import org.nivpisum.bettermapatlases.cartography.mixin.MapDecorationsAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pepjebs.mapatlases.client.screen.AtlasOverviewScreen;
import pepjebs.mapatlases.map_collection.IMapCollection;
import pepjebs.mapatlases.utils.Slice;

/** Native bookmarks only refresh for immediate local edits; server confirmations arrive later. */
@Mixin(value = AtlasOverviewScreen.class, remap = false)
public abstract class AtlasMarkerRefreshMixin {
    @Shadow private IMapCollection currentMaps;
    @Shadow private Slice selectedSlice;
    @Shadow protected abstract void recalculateDecorationWidgets();
    @Unique private int betterMapAtlases$markerTick;
    @Unique private long betterMapAtlases$markerStamp = Long.MIN_VALUE;

    @Inject(method = "tick", remap = true, at = @At("RETURN"))
    private void betterMapAtlases$refreshConfirmedMarkers(CallbackInfo callback) {
        if (++betterMapAtlases$markerTick % 5 != 0 || currentMaps == null || selectedSlice == null) return;
        long stamp = selectedSlice.hashCode();
        for (var map : currentMaps.selectSection(selectedSlice)) {
            long markers = 0;
            for (var entry : ((MapDecorationsAccessor) map.data).betterMapAtlases$getDecorations().entrySet()) {
                if (entry.getValue().renderOnFrame()) markers += entry.hashCode();
            }
            if (map.data instanceof ExpandedMapData expanded) markers += expanded.getCustomDecorations().hashCode();
            stamp += ((long) map.id << 32) ^ markers;
        }
        if (stamp == betterMapAtlases$markerStamp) return;
        betterMapAtlases$markerStamp = stamp;
        recalculateDecorationWidgets();
    }
}
