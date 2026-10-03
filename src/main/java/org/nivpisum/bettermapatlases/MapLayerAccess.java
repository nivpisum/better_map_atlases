/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases;

public interface MapLayerAccess {
    MapLayer accurateMaps$getLayer(boolean create);
    void accurateMaps$setLayer(MapLayer layer);
}
