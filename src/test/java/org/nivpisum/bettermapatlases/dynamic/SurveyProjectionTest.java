/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SurveyProjectionTest {
    private long rank(int floor, int ceiling, int view, int witness) {
        return SurveyProjection.rank(floor * 16, floor * 16, ceiling * 16, view * 16, 16 * 16, 32 * 16,
            witness * 16, (witness + 4) * 16);
    }
    @Test void walkedSlopeRemainsVisibleAfterChangingHeight() {
        assertNotEquals(Long.MAX_VALUE, rank(48, 52, 64, 48));
        assertNotEquals(Long.MAX_VALUE, rank(72, 76, 64, 72));
    }
    @Test void currentAirSpaceWinsOverACloserSeparateFloor() {
        assertTrue(rank(60, 70, 65, 60) < rank(67, 75, 65, 67));
        assertTrue(rank(74, 80, 75, 74) < rank(65, 70, 75, 65));
    }
    @Test void overviewHasRealVerticalBoundsIncludingNegativeY() {
        assertEquals(Long.MAX_VALUE, rank(-70, -64, -33, -70));
        assertNotEquals(Long.MAX_VALUE, rank(-60, -55, -33, -60));
        assertEquals(Long.MAX_VALUE, rank(-10, -5, -33, -10));
    }
}
