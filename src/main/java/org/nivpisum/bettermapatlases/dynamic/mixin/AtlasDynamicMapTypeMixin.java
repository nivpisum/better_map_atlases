/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin;

import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pepjebs.mapatlases.MapAtlasesMod;
import pepjebs.mapatlases.integration.SupplementariesCompat;
import pepjebs.mapatlases.utils.MapType;

@Mixin(value = MapType.class, remap = false, priority = 1100)
public abstract class AtlasDynamicMapTypeMixin {
    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void accurateMaps$dynamicHeight(MapItemSavedData data, CallbackInfoReturnable<Integer> callback) {
        if ((Object) this == MapType.VANILLA && SurveyAtlas.isManaged(data))
            callback.setReturnValue(SurveyAtlas.isDynamic(data) ? SurveyAtlas.AUTO_HEIGHT : null);
    }

    @Inject(method = "createNewMapItem", at = @At("HEAD"), cancellable = true)
    private void accurateMaps$createDynamic(int x, int z, byte scale, Level level, Integer height,
                                          ItemStack atlas, CallbackInfoReturnable<ItemStack> callback) {
        if ((Object) this != MapType.VANILLA || (height != null && !SurveyAtlas.isAutoHeight(height))) return;
        ItemStack item = MapItem.create(level, x, z, scale, true, false);
        Integer id = MapItem.getMapId(item);
        if (id != null) {
            MapItemSavedData map = level.getMapData(MapItem.makeKey(id));
            if (map != null) {
                if (height == null) SurveyAtlas.markSurface(map);
                else SurveyAtlas.markDynamic(map);
            }
        }
        if (MapAtlasesMod.SUPPLEMENTARIES && SupplementariesCompat.hasAntiqueInk(atlas))
            SupplementariesCompat.setMapAntique(item, level);
        callback.setReturnValue(item);
    }

    @Inject(method = "createExistingMapItem", at = @At("HEAD"), cancellable = true)
    private void accurateMaps$extractDynamic(int id, Integer height, CallbackInfoReturnable<ItemStack> callback) {
        if ((Object) this != MapType.VANILLA || !SurveyAtlas.isAutoHeight(height)) return;
        ItemStack item = new ItemStack(Items.FILLED_MAP);
        item.getOrCreateTag().putInt("map", id);
        callback.setReturnValue(item);
    }

    @Inject(method = "getDiscoveryReach", at = @At("HEAD"), cancellable = true)
    private void accurateMaps$dynamicReach(Integer height, CallbackInfoReturnable<Integer> callback) {
        // The upstream 128-block preallocation bought eight blank neighbouring pages at rest.
        // Expand near a tile boundary, with the same reach for surface and cave books.
        if ((Object) this == MapType.VANILLA && (height == null || SurveyAtlas.isAutoHeight(height)))
            callback.setReturnValue(SurveyAtlas.DISCOVERY_REACH);
    }
}
