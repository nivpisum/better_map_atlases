/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.compat.mixin;

import org.nivpisum.bettermapatlases.compat.NetherMapCompat;
import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pepjebs.mapatlases.utils.MapType;

@Mixin(value = MapType.class, remap = false)
public abstract class AtlasMapTypeMixin {
    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void accurateMaps$bnmSlice(MapItemSavedData data, CallbackInfoReturnable<Integer> callback) {
        if ((Object) this != MapType.VANILLA || SurveyAtlas.isManaged(data)) return;
        Integer height = NetherMapCompat.getHeight(data);
        if (height != null) callback.setReturnValue(height);
    }

    @Inject(method = "createNewMapItem", at = @At("RETURN"))
    private void accurateMaps$propagateHeight(int x, int z, byte scale, Level level, Integer height,
                                            ItemStack atlas, CallbackInfoReturnable<ItemStack> callback) {
        if ((Object) this == MapType.VANILLA && height != null && !SurveyAtlas.isAutoHeight(height) && !callback.getReturnValue().isEmpty()) {
            NetherMapCompat.initializeCreated(level, callback.getReturnValue(), height, !level.dimensionType().hasCeiling());
        }
    }

    @Inject(method = "createExistingMapItem", at = @At("RETURN"))
    private void accurateMaps$restoreItemHeight(int id, Integer height, CallbackInfoReturnable<ItemStack> callback) {
        if ((Object) this == MapType.VANILLA && height != null && !SurveyAtlas.isAutoHeight(height) && !callback.getReturnValue().isEmpty()) {
            callback.getReturnValue().getOrCreateTag().putInt("yLevel", height);
            callback.getReturnValue().getOrCreateTag().putBoolean("forceCave", true);
        }
    }
}
