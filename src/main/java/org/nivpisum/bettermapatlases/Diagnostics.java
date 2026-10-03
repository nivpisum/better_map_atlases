/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases;

import java.util.*;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/** Optional read-only diagnostics. No commands, packets or gameplay hooks. */
public final class Diagnostics {
    private Diagnostics() {}
    public static Map<String, Object> describe(MapItemSavedData map) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("vanillaColorsLength", map.colors.length);
        result.put("vanillaColorsHash", Arrays.hashCode(map.colors));
        int known = 0;
        for (byte color : map.colors) if (color != 0) known++;
        result.put("knownPixels", known);
        result.put("dimension", map.dimension.location().toString());
        result.put("dirty", map.isDirty());
        result.put("centerX", map.centerX);
        result.put("centerZ", map.centerZ);
        result.put("scale", map.scale);
        result.put("locked", map.locked);
        result.put("deferredSampling", DeferredMapSamples.describe(map));
        MapLayer layer = ((MapLayerAccess) map).accurateMaps$getLayer(false);
        result.put("layer", layer == null ? null : layer.describe());
        return result;
    }
    public static Map<String, Object> pixel(MapItemSavedData map, int x, int z) {
        MapLayer layer = ((MapLayerAccess) map).accurateMaps$getLayer(false);
        return layer == null ? Map.of("present", false) : layer.pixel(x, z);
    }
}
