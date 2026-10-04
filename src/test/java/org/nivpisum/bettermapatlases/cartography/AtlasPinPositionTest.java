/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.cartography;

import org.junit.jupiter.api.Test;
import org.nivpisum.bettermapatlases.cartography.client.AtlasPinPosition;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class AtlasPinPositionTest {
    @Test void renderedWorldColumnsRoundTripAcrossMapScaleZoomPanAndWindowShape() {
        int[][] widgets = {{22, 35, 93, 70}, {17, 19, 478, 435}, {61, 44, 991, 193}};
        double[] centers = {-1057.875, -0.125, 117.125};
        double[] zooms = {0.5, 1, 3, 20};
        int[] columns = {-1280, -64, -1, 0, 64, 1279};
        for (int[] widget : widgets) for (double center : centers) for (double zoom : zooms) {
            for (int mapScale = 0; mapScale <= 4; mapScale++) {
                int mapBlocksSize = 128 << mapScale;
                // This is the native forward transform: widgetScale*zoomScale/scaleIndex.
                double pixelsPerBlock = (widget[2] / (3.0 * 128)) * (3 / zoom) / (1 << mapScale);
                for (int column : columns) for (double fraction : new double[]{0.25, 0.75}) {
                    double renderedDelta = (column + fraction - center) * pixelsPerBlock;
                    double renderedX = widget[0] + widget[2] / 2.0 + renderedDelta;
                    double renderedY = widget[1] + widget[3] / 2.0 + renderedDelta;
                    assertEquals(column, AtlasPinPosition.blockCoordinate(renderedX,
                            widget[0] + widget[2] / 2.0, center, widget[2], mapBlocksSize, zoom));
                    assertEquals(column, AtlasPinPosition.blockCoordinate(renderedY,
                            widget[1] + widget[3] / 2.0, center, widget[2], mapBlocksSize, zoom));
                }
            }
        }
    }

    @Test void centerClickSelectsTheRenderedColumnWithoutAWorldOffset() {
        assertEquals(117, AtlasPinPosition.blockCoordinate(256, 256, 117.125, 478, 128, 3));
        assertEquals(-1, AtlasPinPosition.blockCoordinate(256, 256, -0.125, 478, 128, 3));
        assertEquals(-1058, AtlasPinPosition.blockCoordinate(256, 256, -1057.875, 478, 128, 3));
    }

    @Test void fractionalPanningIsAppliedBeforeChoosingTheWorldColumn() {
        // At one widget pixel per block, a quarter-pixel displacement crosses world zero.
        assertEquals(0, AtlasPinPosition.blockCoordinate(50.25, 50, -0.125, 128, 128, 1));
        assertEquals(-1, AtlasPinPosition.blockCoordinate(49.75, 50, 0.125, 128, 128, 1));
    }
}
