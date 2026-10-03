/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.mixin;

import org.nivpisum.bettermapatlases.MapSampler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.nivpisum.bettermapatlases.dynamic.SurveyRecorder;

@Mixin(MapItem.class)
public abstract class MapItemMixin {
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void accurateMaps$survey(Level level, Entity entity, MapItemSavedData map, CallbackInfo ci) {
        if (SurveyRecorder.update(level, entity, map)) ci.cancel();
    }
    @Redirect(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;updateColor(IIB)Z"))
    private boolean accurateMaps$capture(MapItemSavedData map, int x, int z, byte color,
                                         Level level, Entity entity, MapItemSavedData argumentMap) {
        boolean changed = map.updateColor(x, z, color);
        MapSampler.capture(level, map, x, z, color);
        return changed;
    }
}
