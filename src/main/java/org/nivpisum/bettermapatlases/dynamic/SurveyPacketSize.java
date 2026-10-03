/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic;

import java.nio.charset.StandardCharsets;

/** Exact primitive sizes for the wire codec, including signed-height extrema. */
final class SurveyPacketSize {
    static final int TARGET_BYTES = 16 * 1024;
    // A legal resource identifier may be 32767 characters. One record plus metadata
    // must still fit, without discarding history or truncating a mod's identifier.
    static final int MAX_BYTES = 128 * 1024;
    static final int MAX_RECORDS = 1024;
    private SurveyPacketSize() {}
    static int varInt(int value) {
        int bytes = 1;
        while ((value & ~127) != 0) { value >>>= 7; bytes++; }
        return bytes;
    }
    static int varLong(long value) {
        int bytes = 1;
        while ((value & ~127L) != 0) { value >>>= 7; bytes++; }
        return bytes;
    }
    static int zigzag(int value) { return (value << 1) ^ (value >> 31); }
    static int unzigzag(int value) { return (value >>> 1) ^ -(value & 1); }
    static int utf(String value) {
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        return varInt(length) + length;
    }
    static final class Burst {
        private final int maximumParts;
        private int parts, bytes;
        Burst(int maximumParts) {
            if (maximumParts < 1) throw new IllegalArgumentException("Missing packet budget");
            this.maximumParts = maximumParts;
        }
        boolean available() { return parts < maximumParts && bytes < MAX_BYTES; }
        int remainingBytes() { return MAX_BYTES - bytes; }
        void sent(int payloadBytes) {
            if (!available() || payloadBytes < 0 || payloadBytes > remainingBytes())
                throw new IllegalArgumentException("Exceeded survey tick budget");
            parts++; bytes += payloadBytes;
        }
    }
}
