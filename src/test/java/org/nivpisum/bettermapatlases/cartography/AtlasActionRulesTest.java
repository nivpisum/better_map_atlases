/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.cartography;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class AtlasActionRulesTest {
    @Test void mapIdsCannotOverflowOrUseSignedSuffixes() {
        assertEquals(0, AtlasActionRules.mapNumber("map_0"));
        assertEquals(123, AtlasActionRules.mapNumber("map_123"));
        assertEquals(Integer.MAX_VALUE, AtlasActionRules.mapNumber("map_2147483647"));
        for (String input : new String[] {"map_", "map_-1", "map_+1", "map_2147483648",
                "map_999999999999999999999999999", "map_12x", "0", "map_1/../2"}) {
            assertEquals(-1, AtlasActionRules.mapNumber(input), input);
        }
        assertEquals(-1, AtlasActionRules.mapNumber(null));
    }

    @Test void tileEdgeBelongsToExactlyOneNeighbourForEveryVanillaScale() {
        for (int scale = 0; scale <= 4; scale++) {
            int radius = 64 << scale;
            assertTrue(AtlasActionRules.insideMap(0, 0, scale, -radius, -radius));
            assertTrue(AtlasActionRules.insideMap(0, 0, scale, radius - 1, radius - 1));
            assertFalse(AtlasActionRules.insideMap(0, 0, scale, radius, 0));
            assertTrue(AtlasActionRules.insideMap(radius * 2, 0, scale, radius, 0));
            assertFalse(AtlasActionRules.insideMap(0, 0, scale, -radius - 1, 0));
        }
    }

    @Test void maliciousCoordinatesCannotWrapOntoTheMap() {
        assertFalse(AtlasActionRules.insideMap(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0));
        assertFalse(AtlasActionRules.insideMap(Integer.MAX_VALUE, 0, 0, Integer.MIN_VALUE, 0));
        assertFalse(AtlasActionRules.insideMap(0, 0, -1, 0, 0));
        assertFalse(AtlasActionRules.insideMap(0, 0, 32, 0, 0));
    }
}
