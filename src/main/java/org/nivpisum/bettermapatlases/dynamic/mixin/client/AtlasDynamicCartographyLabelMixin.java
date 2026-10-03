/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;
import org.nivpisum.bettermapatlases.client.AtlasUiText;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import pepjebs.mapatlases.client.screen.CartographyTableAtlasButton;
import pepjebs.mapatlases.utils.AtlasCartographyTable;

@Mixin(value = CartographyTableAtlasButton.class, remap = false)
public abstract class AtlasDynamicCartographyLabelMixin {
    @Shadow @Final protected AbstractContainerMenu menu;

    @WrapOperation(method = "renderWidget", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent accurateMaps$autoPageLabel(String key, Object[] arguments,
                                                        Operation<MutableComponent> original) {
        if (key.equals("message.map_atlases.slice_height") && arguments.length == 1
                && arguments[0] instanceof Integer height && SurveyAtlas.isAutoHeight(height))
            return Component.translatable("better_map_atlases.slice.auto");
        return original.call(key, arguments);
    }

    @WrapOperation(method = "renderWidget", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/network/chat/Component;literal(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent betterMapAtlases$dimensionLabel(String name, Operation<MutableComponent> original) {
        if (menu instanceof AtlasCartographyTable table && table.mapatlases$getSelectedSlice() != null) {
            return AtlasUiText.dimensionName(table.mapatlases$getSelectedSlice().dimension().location());
        }
        return original.call(name);
    }
}
