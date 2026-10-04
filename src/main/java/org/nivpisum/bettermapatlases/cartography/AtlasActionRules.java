/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.cartography;

/** Untrusted packet coordinates and IDs are checked before looking up saved data. */
public final class AtlasActionRules {
    public static final int MAX_PINS_PER_MAP = 256;

    private AtlasActionRules() {}

    public static int mapNumber(String name) {
        if (name == null || name.length() > 64) return -1;
        int start = name.lastIndexOf('_') + 1;
        if (start <= 0 || start == name.length()) return -1;
        long result = 0;
        for (int i = start; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') return -1;
            result = result * 10 + c - '0';
            if (result > Integer.MAX_VALUE) return -1;
        }
        return (int) result;
    }

    public static boolean insideMap(int centerX, int centerZ, int scale, int x, int z) {
        if (scale < 0 || scale > 4) return false;
        long radius = 64L << scale;
        long dx = (long) x - centerX;
        long dz = (long) z - centerZ;
        // Map tiles are half open; their positive edge belongs to the next tile.
        return dx >= -radius && dx < radius && dz >= -radius && dz < radius;
    }
}
