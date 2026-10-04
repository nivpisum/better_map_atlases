/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.client;

/** Shared single-line label formatting and its screen-space height reservation. */
public final class AtlasHudText {
    private AtlasHudText() { }

    public static String join(String... parts) {
        StringBuilder line = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (!line.isEmpty()) line.append(" · ");
            line.append(part.strip());
        }
        return line.toString();
    }

    public static int reservedHeight(boolean coordinates, boolean chunkCoordinates, boolean biome, double textScale) {
        // All enabled pieces share one row, with a small gap below the map.
        return coordinates || chunkCoordinates || biome ? (int) Math.ceil(10 * textScale + 4) : 0;
    }
}
