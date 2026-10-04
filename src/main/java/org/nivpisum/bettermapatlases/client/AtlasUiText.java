/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.client;

import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import pepjebs.mapatlases.client.screen.AtlasOverviewScreen;

/** Display-only names; identifiers and player-authored marker names stay in their original form. */
public final class AtlasUiText {
    private AtlasUiText() {}

    public static MutableComponent dimensionName(ResourceLocation dimension) {
        String key = "dimension." + dimension.getNamespace() + "." + dimension.getPath().replace('/', '.');
        return Component.translatableWithFallback(key, AtlasOverviewScreen.getReadableName(dimension));
    }

    public static MutableComponent markerName(MapDecoration.Type type) {
        String name = type.name().toLowerCase(Locale.ROOT);
        if (name.startsWith("banner_")) {
            return Component.translatable("block.minecraft." + name.substring("banner_".length()) + "_banner");
        }
        return Component.translatableWithFallback("better_map_atlases.marker." + name,
                AtlasOverviewScreen.getReadableName(name));
    }

    public static MutableComponent customMarkerName(ResourceLocation type) {
        String name = type.getPath();
        if (type.getNamespace().equals("better_map_atlases") && name.startsWith("banner_pin_")) {
            return Component.translatable("block.minecraft." + name.substring("banner_pin_".length()) + "_banner");
        }
        String key = "map_marker_type." + type.getNamespace() + "." + name.replace('/', '.');
        return Component.translatableWithFallback(key, AtlasOverviewScreen.getReadableName(type));
    }
}
