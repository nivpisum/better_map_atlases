/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.cartography.client;

/** Inverse of AbstractAtlasWidget.drawAtlas's world-to-widget transform. */
public final class AtlasPinPosition {
    private AtlasPinPosition() {}

    public static int blockCoordinate(double mouseCoordinate, double widgetCenter, double worldCenter,
                                      int widgetWidth, int mapBlocksSize, double zoomLevel) {
        // Both axes use the width-derived scale: the map is not stretched to widget height.
        // Floor the final world position, including a fractional pan/animation center.
        return (int) Math.floor(worldCenter
                + (mouseCoordinate - widgetCenter) * zoomLevel * mapBlocksSize / widgetWidth);
    }
}
