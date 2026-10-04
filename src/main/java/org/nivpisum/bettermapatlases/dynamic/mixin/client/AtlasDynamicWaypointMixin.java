/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import pepjebs.mapatlases.integration.XaeroMinimapCompat;
import pepjebs.mapatlases.utils.Slice;

@Mixin(value = XaeroMinimapCompat.class, remap = false)
public abstract class AtlasDynamicWaypointMixin {
    @WrapOperation(method = "loadXaeroWaypoints", at = @At(value = "INVOKE",
            target = "Lpepjebs/mapatlases/utils/Slice;heightOrTop()I"))
    private static int accurateMaps$logicalMarkerHeight(Slice slice, Operation<Integer> original) {
        return SurveyAtlas.isAutoSlice(slice) ? Integer.MAX_VALUE : original.call(slice);
    }
}
