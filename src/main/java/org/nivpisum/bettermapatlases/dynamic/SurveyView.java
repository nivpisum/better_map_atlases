/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** A viewer's presentation context. It is never stored as a shared map's fixed height. */
public record SurveyView(ResourceLocation dimension, boolean cave, int anchorY16, long revision) {
    public SurveyView { Objects.requireNonNull(dimension, "dimension"); }
    public double anchorY() { return anchorY16 / 16.0; }
}
