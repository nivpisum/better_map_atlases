/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin.client;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.nivpisum.bettermapatlases.client.ClientSurvey;
import org.nivpisum.bettermapatlases.client.AtlasDimensionNavigation;
import org.nivpisum.bettermapatlases.client.AtlasHudSettings;
import org.nivpisum.bettermapatlases.client.AtlasOverviewHeight;
import org.nivpisum.bettermapatlases.client.AtlasViewport;
import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;
import org.nivpisum.bettermapatlases.dynamic.SurveyGeometry;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pepjebs.mapatlases.client.screen.AtlasOverviewScreen;
import pepjebs.mapatlases.client.screen.DimensionBookmarkButton;
import pepjebs.mapatlases.client.screen.MapWidget;
import pepjebs.mapatlases.client.screen.SliceArrowButton;
import pepjebs.mapatlases.client.screen.SliceBookmarkButton;
import pepjebs.mapatlases.map_collection.IMapCollection;
import pepjebs.mapatlases.utils.MapType;
import pepjebs.mapatlases.utils.MapDataHolder;
import pepjebs.mapatlases.utils.Slice;

/** Local book browsing never changes the selected server recording page. */
@Mixin(value = AtlasOverviewScreen.class, remap = false)
public abstract class AtlasDynamicHistoryMixin extends Screen implements AtlasOverviewHeight {
    @Shadow @Final private boolean isPinOnly;
    @Shadow private boolean initialized;
    @Shadow private Slice selectedSlice;
    @Shadow private IMapCollection currentMaps;
    @Shadow private MapWidget mapWidget;
    @Shadow private SliceBookmarkButton sliceButton;
    @Shadow private SliceArrowButton sliceUp;
    @Shadow private SliceArrowButton sliceDown;
    @Shadow @Final private List<DimensionBookmarkButton> dimensionBookmarks;
    @Shadow protected abstract void recalculateDecorationWidgets();
    @Shadow public abstract boolean isEditingText();
    @Unique private final Map<ResourceKey<Level>, Integer> betterMapAtlases$heights = new HashMap<>();
    @Unique private final Set<ResourceKey<Level>> betterMapAtlases$manualHeights = new HashSet<>();
    @Unique private int[] betterMapAtlases$ids = new int[0];
    @Unique private int betterMapAtlases$publishedY16 = Integer.MIN_VALUE;
    @Unique private Slice betterMapAtlases$restore;
    @Unique private boolean betterMapAtlases$initializing;
    @Unique private AtlasDimensionNavigation.Center betterMapAtlases$restoreCenter;
    @Unique private boolean betterMapAtlases$restoreFollowing;

    protected AtlasDynamicHistoryMixin(Component title) { super(title); }

    @Inject(method = "getMapClosestToPlayer", at = @At("RETURN"))
    private void betterMapAtlases$availableInitialDimension(CallbackInfoReturnable<MapDataHolder> callback) {
        MapDataHolder closest = callback.getReturnValue();
        if (!initialized && closest != null && currentMaps.selectSection(selectedSlice).isEmpty()) selectedSlice = closest.slice;
    }

    @Inject(method = "init", remap = true, at = @At("HEAD"))
    private void betterMapAtlases$rememberView(CallbackInfo callback) {
        betterMapAtlases$restore = initialized ? selectedSlice : null;
        betterMapAtlases$restoreCenter = initialized && mapWidget != null ? ((AtlasViewport) mapWidget).betterMapAtlases$center() : null;
        betterMapAtlases$restoreFollowing = initialized && mapWidget != null && ((AtlasViewport) mapWidget).betterMapAtlases$followsPlayer();
        betterMapAtlases$initializing = true;
        dimensionBookmarks.clear();
    }

    @Inject(method = "init", remap = true, at = @At("RETURN"))
    private void betterMapAtlases$finishView(CallbackInfo callback) {
        // Native arrow instances remain available to native internals, but are not screen controls.
        removeWidget(sliceUp);
        removeWidget(sliceDown);
        if (betterMapAtlases$restore != null) {
            betterMapAtlases$select(betterMapAtlases$restore);
            if (SurveyAtlas.isAutoSlice(selectedSlice)) betterMapAtlases$showCave(true);
        }
        if (betterMapAtlases$restoreCenter != null)
            ((AtlasViewport) mapWidget).betterMapAtlases$center(betterMapAtlases$restoreCenter, betterMapAtlases$restoreFollowing);
        betterMapAtlases$initializing = false;
        if (SurveyAtlas.isAutoSlice(selectedSlice)) betterMapAtlases$showCave(true);
        sliceButton.setActive(!isPinOnly);
    }

    @Inject(method = "tick", remap = true, at = @At("RETURN"))
    private void betterMapAtlases$refreshPages(CallbackInfo callback) {
        if (SurveyAtlas.isAutoSlice(selectedSlice)) betterMapAtlases$showCave(false);
    }

    @Inject(method = "selectDimension", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$surfaceTab(ResourceKey<Level> dimension, CallbackInfo callback) {
        // First opening retains the actual current context; subsequent tab clicks select surface.
        if (!initialized || betterMapAtlases$initializing) return;
        AtlasViewport viewport = (AtlasViewport) mapWidget;
        AtlasDimensionNavigation.Center previous = viewport.betterMapAtlases$center();
        boolean ownDimension = minecraft.level != null && minecraft.player != null && minecraft.level.dimension().equals(dimension);
        String readerDimension = minecraft.level == null ? null : minecraft.level.dimension().location().toString();
        AtlasDimensionNavigation.Center readerCenter = minecraft.player == null ? null
                : new AtlasDimensionNavigation.Center(minecraft.player.getX(), minecraft.player.getZ());
        AtlasDimensionNavigation.Center next = AtlasDimensionNavigation.centerForSwitch(AtlasHudSettings.get().coordinateLinks(),
                selectedSlice.dimension().location().toString(), dimension.location().toString(), previous.x(), previous.z(),
                readerDimension, readerCenter);
        betterMapAtlases$select(Slice.of(MapType.VANILLA, null, dimension));
        viewport.betterMapAtlases$center(next, ownDimension);
        callback.cancel();
    }

    @Inject(method = "cycleSliceType", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$caveTab(CallbackInfo callback) {
        betterMapAtlases$manualHeights.remove(selectedSlice.dimension());
        betterMapAtlases$select(Slice.of(MapType.VANILLA, SurveyAtlas.AUTO_HEIGHT, selectedSlice.dimension()));
        betterMapAtlases$showCave(true);
        callback.cancel();
    }

    @Inject(method = "updateSlice", at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$localSelection(Slice next, CallbackInfoReturnable<Boolean> callback) {
        callback.setReturnValue(betterMapAtlases$select(next));
    }

    @Unique private boolean betterMapAtlases$select(Slice next) {
        boolean changed = !Objects.equals(selectedSlice, next);
        selectedSlice = next;
        sliceButton.setSlice(next);
        sliceButton.setActive(!isPinOnly);
        sliceButton.setSelected(SurveyAtlas.isAutoSlice(next));
        for (DimensionBookmarkButton button : dimensionBookmarks)
            button.setSelected(button.getDimension().equals(next.dimension()));
        sliceUp.setActive(false);
        sliceDown.setActive(false);
        if (changed) {
            betterMapAtlases$ids = new int[0];
            SurveyAtlas.historyUi().followPlayer();
            recalculateDecorationWidgets();
            mapWidget.resetZoom();
        }
        if (SurveyAtlas.isAutoSlice(next)) betterMapAtlases$showCave(changed);
        else {
            int[] ids = currentMaps.selectSection(next).stream().mapToInt(page -> page.id).sorted().toArray();
            SurveyAtlas.historyUi().showSurface(next.dimension(), ids);
        }
        return changed;
    }

    @Inject(method = "keyPressed", remap = true, at = @At("HEAD"), cancellable = true)
    private void betterMapAtlases$heightKey(int key, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> callback) {
        if (isPinOnly || isEditingText() || !SurveyAtlas.isAutoSlice(selectedSlice)) return;
        if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) {
            int old = betterMapAtlases$currentY16();
            int desired = old + (key == GLFW.GLFW_KEY_PAGE_UP ? 16 : -16);
            int next = ClientSurvey.clampBlockY(selectedSlice.dimension(), Math.floorDiv(desired, 16)) * 16
                + Math.floorMod(desired, 16);
            betterMapAtlases$heights.put(selectedSlice.dimension(), next);
            betterMapAtlases$manualHeights.add(selectedSlice.dimension());
            betterMapAtlases$showCave(true);
            callback.setReturnValue(true);
        }
    }

    @Override public int betterMapAtlases$heightY16() { return betterMapAtlases$currentY16(); }

    @Unique private int betterMapAtlases$currentY16() {
        ResourceKey<Level> dimension = selectedSlice.dimension();
        if (!betterMapAtlases$manualHeights.contains(dimension) && minecraft.level != null
                && minecraft.player != null && minecraft.level.dimension().equals(dimension)) {
            Integer live = ClientSurvey.liveY16(dimension);
            return live == null ? SurveyGeometry.fixed16(minecraft.player.getY()) : live;
        }
        return betterMapAtlases$heights.computeIfAbsent(selectedSlice.dimension(), dim -> {
            Integer live = ClientSurvey.liveY16(dim);
            Slice caves = Slice.of(MapType.VANILLA, SurveyAtlas.AUTO_HEIGHT, dim);
            return ClientSurvey.bookmarkY16(currentMaps.selectSection(caves).stream().mapToInt(page -> page.id).toArray(),
                    live == null ? 64 * 16 : live);
        });
    }

    @Unique private void betterMapAtlases$showCave(boolean force) {
        int[] ids = currentMaps.selectSection(selectedSlice).stream().mapToInt(page -> page.id).sorted().toArray();
        int y16 = betterMapAtlases$currentY16();
        if (force || !Arrays.equals(ids, betterMapAtlases$ids) || y16 != betterMapAtlases$publishedY16
                || !Double.isFinite(SurveyAtlas.viewY(selectedSlice.dimension()))) {
            betterMapAtlases$ids = ids;
            betterMapAtlases$publishedY16 = y16;
            SurveyAtlas.historyUi().showHistory(selectedSlice.dimension(), Arrays.copyOf(ids, ids.length), y16);
        }
    }

    @Override public void removed() {
        SurveyAtlas.historyUi().followPlayer();
        super.removed();
    }
}
