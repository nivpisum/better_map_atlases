/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases;

import org.nivpisum.bettermapatlases.client.TextureColors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColourMathTest {
    @Test void nativeImageChannelsAreAbgrAndAlwaysOpaque() {
        assertEquals(0xFF0000FF, TextureColors.shade(0xFF0000, 2));
        assertEquals(0xFF00FF00, TextureColors.shade(0x00FF00, 2));
        assertEquals(0xFFFF0000, TextureColors.shade(0x0000FF, 2));
        assertEquals(0xFF000000, TextureColors.shade(0x000000, 2));
    }
    @Test void vanillaBrightnessFactorsAndIntegerRoundingArePreserved() {
        assertEquals(0xFFB4B4B4, TextureColors.shade(0xFFFFFF, 0));
        assertEquals(0xFFDCDCDC, TextureColors.shade(0xFFFFFF, 1));
        assertEquals(0xFFFFFFFF, TextureColors.shade(0xFFFFFF, 2));
        assertEquals(0xFF878787, TextureColors.shade(0xFFFFFF, 3));
        assertEquals(0xFF214263, TextureColors.shade(0xBB7D3F, 3));
    }
}
