/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic;

/** Retained snapshot index references, independent of immutable shared samples. */
final class SurveySnapshotBudget {
    static final int MAX_COLUMNS = 2_000_000;
    private long columns, version;
    private int transfers;
    boolean canStart(int count) {
        if (count < 0) throw new IllegalArgumentException("Negative snapshot size");
        return transfers == 0 || columns + count <= MAX_COLUMNS;
    }
    void retain(int count) {
        if (!canStart(count)) throw new IllegalArgumentException("Exceeded retained survey snapshot budget");
        columns += count; transfers++; version++;
    }
    void release(int count) {
        if (count < 0 || count > columns || transfers == 0) throw new IllegalArgumentException("Invalid snapshot release");
        columns -= count; transfers--; version++;
    }
    long columns() { return columns; }
    long version() { return version; }
    int transfers() { return transfers; }
}
