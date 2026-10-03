/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** An immutable observation. All Y values are sixteenths of a block; intervals are half-open. */
public record SurveySample(
    int observedMinY16, int observedMaxY16,
    int geometryMinY16, int geometryMaxY16, int floorY16,
    BlockState state, ResourceLocation biome, long position, byte vanillaShade, Kind kind,
    boolean lowerBoundaryKnown, boolean upperBoundaryKnown
) {
    public enum Kind { FLOOR, WALL, FLUID, OPEN_DEPTH }

    public SurveySample {
        if (geometryMinY16 >= geometryMaxY16 || observedMinY16 >= observedMaxY16
            || observedMinY16 < geometryMinY16 || observedMaxY16 > geometryMaxY16) {
            throw new IllegalArgumentException("Observation must be a nonempty interval inside its geometry");
        }
        Objects.requireNonNull(biome, "biome");
        Objects.requireNonNull(kind, "kind");
        if (state == null && kind != Kind.OPEN_DEPTH) {
            throw new IllegalArgumentException("Only an open-depth cue may omit its block state");
        }
        if (vanillaShade < 0 || vanillaShade > 3) throw new IllegalArgumentException("Shade must be a brightness id 0..3");
    }

    public boolean contains(int viewY16) {
        return observedMinY16 <= viewY16 && viewY16 < observedMaxY16;
    }

    /** Older observations stored the bed/scan limit for liquid. Recover its visible medium from the recorded block. */
    public int displayY16() {
        if (kind != Kind.FLUID || state == null || state.getFluidState().isEmpty()) return floorY16;
        int medium = fixed16(BlockPos.of(position).getY() + state.getFluidState().getOwnHeight());
        return Math.max(floorY16, medium);
    }

    /** Clips knowledge only; geometry and its actual-boundary flags remain unchanged. */
    public SurveySample withObservedRange(int minimumY16, int maximumY16) {
        return new SurveySample(minimumY16, maximumY16, geometryMinY16, geometryMaxY16, floorY16,
            state, biome, position, vanillaShade, kind, lowerBoundaryKnown, upperBoundaryKnown);
    }

    public boolean sameGeometryAndAppearance(SurveySample other) {
        return other != null && geometryMinY16 == other.geometryMinY16
            && geometryMaxY16 == other.geometryMaxY16 && floorY16 == other.floorY16
            && Objects.equals(state, other.state) && biome.equals(other.biome) && position == other.position
            && vanillaShade == other.vanillaShade && kind == other.kind
            && lowerBoundaryKnown == other.lowerBoundaryKnown && upperBoundaryKnown == other.upperBoundaryKnown;
    }

    /** Floors, including at negative heights. Rejects NaN, infinity and integer overflow. */
    public static int fixed16(double y) {
        double value = Math.floor(y * 16.0);
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Height cannot be represented in sixteenths");
        }
        return (int) value;
    }
}
