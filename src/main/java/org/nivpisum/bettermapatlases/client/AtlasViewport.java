/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.client;

/** Exact displayed coordinates; dimension navigation never needs to select or generate a map page. */
public interface AtlasViewport {
    AtlasDimensionNavigation.Center betterMapAtlases$center();
    boolean betterMapAtlases$followsPlayer();
    void betterMapAtlases$center(AtlasDimensionNavigation.Center center, boolean following);
}
