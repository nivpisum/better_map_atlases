/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic;

import java.util.List;
import pepjebs.mapatlases.utils.MapDataHolder;

/** Allows exact coordinate lookup to retain the collection's native scheduling order. */
public interface AtlasCollectionLookup {
    void betterMapAtlases$order(List<MapDataHolder> hits);
    long betterMapAtlases$revision();
    List<Integer> betterMapAtlases$ids();
}
