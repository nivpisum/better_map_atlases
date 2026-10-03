/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PendingPixelsTest {
    @Test void repeatedVisitsKeepOnlyTheLatestColor() {
        PendingPixels pixels = new PendingPixels();
        for (int i = 0; i < 50_000; i++) pixels.request(17, (byte) i);
        assertEquals(1, pixels.size());
        assertEquals(17, pixels.next());
        assertEquals((byte) 49_999, pixels.color(17));
        assertEquals(-1, pixels.next());
    }

    @Test void lateVanillaEditsCancelPendingSamplesWithoutAffectingAnotherMap() {
        PendingPixels first = new PendingPixels();
        PendingPixels second = new PendingPixels();
        first.request(127, (byte) 33);
        first.request(128, (byte) 34);
        second.request(127, (byte) 49);
        first.cancel(127);
        assertEquals(128, first.next());
        assertEquals(-1, first.next());
        assertEquals(127, second.next());
        assertEquals((byte) 49, second.color(127));
    }

    @Test void hotLowPixelsCannotStarveOtherPixels() {
        PendingPixels pixels = new PendingPixels();
        pixels.request(0, (byte) 1);
        pixels.request(16_383, (byte) 2);
        assertEquals(0, pixels.next());
        pixels.request(0, (byte) 3);
        assertEquals(16_383, pixels.next());
        assertEquals(0, pixels.next());
        assertTrue(pixels.isEmpty());
    }

    @Test void queueIsBoundedToOneMapAndRejectsInvalidPixels() {
        PendingPixels pixels = new PendingPixels();
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < PendingPixels.SIZE; i++) pixels.request(i, (byte) round);
        }
        assertEquals(PendingPixels.SIZE, pixels.size());
        assertThrows(IllegalArgumentException.class, () -> pixels.request(-1, (byte) 1));
        assertThrows(IllegalArgumentException.class, () -> pixels.request(PendingPixels.SIZE, (byte) 1));
        for (int i = 0; i < PendingPixels.SIZE; i++) assertEquals(i, pixels.next());
        assertTrue(pixels.isEmpty());
    }
}
