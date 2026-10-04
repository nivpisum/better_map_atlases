/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases;

public interface MapLayerAccess {
    MapLayer accurateMaps$getLayer(boolean create);
    void accurateMaps$setLayer(MapLayer layer);
}
