/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import pepjebs.mapatlases.item.MapAtlasItem;

@Mixin(value = MapAtlasItem.class, remap = false)
public abstract class AtlasDynamicItemLabelMixin {
    @WrapOperation(method = "appendHoverText", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent accurateMaps$hideSentinel(String key, Object[] arguments,
                                                       Operation<MutableComponent> original) {
        if (key.equals("item.map_atlases.atlas.tooltip_slice") && arguments.length == 1
                && arguments[0] instanceof Integer height && SurveyAtlas.isAutoHeight(height))
            return Component.translatable("better_map_atlases.slice.auto");
        return original.call(key, arguments);
    }
}
