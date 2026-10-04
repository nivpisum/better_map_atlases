/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases;

import java.util.BitSet;

/** Bounded, deduplicated work for one map. Its owner supplies synchronization. */
final class PendingPixels {
    static final int SIZE = 128 * 128;
    private final BitSet pending = new BitSet(SIZE);
    private final byte[] colors = new byte[SIZE];
    private int cursor;

    void request(int index, byte color) {
        checkIndex(index);
        colors[index] = color;
        pending.set(index);
    }

    void cancel(int index) {
        checkIndex(index);
        pending.clear(index);
    }

    int next() {
        int index = pending.nextSetBit(cursor);
        if (index < 0) index = pending.nextSetBit(0);
        if (index >= 0) {
            pending.clear(index);
            cursor = (index + 1) % SIZE;
        }
        return index;
    }

    byte color(int index) { return colors[index]; }
    int size() { return pending.cardinality(); }
    boolean isEmpty() { return pending.isEmpty(); }

    private static void checkIndex(int index) {
        if (index < 0 || index >= SIZE) throw new IllegalArgumentException("Pixel outside map");
    }
}
