/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.cartography.mixin;

import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;

@Mixin(MapItemSavedData.class)
public interface MapDecorationsAccessor {
    @Accessor("decorations") Map<String, MapDecoration> betterMapAtlases$getDecorations();
}
