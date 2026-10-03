/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class SurveyFloorContinuityTest {
    private SurveyProjection.Floor floor(int page, int x, int z, int floor, int ceiling) {
        return new SurveyProjection.Floor(page, x + z * 128, x, z, floor * 16, floor * 16, ceiling * 16);
    }
    @Test void aVerticalShaftDoesNotJoinTwoMineLevels() {
        List<SurveyProjection.Floor> lower = new ArrayList<>(), upper = new ArrayList<>();
        for (int x = 0; x < 12; x++) {
            lower.add(floor(0, x, 0, 26, x == 6 ? 60 : 29));
            if (x != 6) upper.add(floor(0, x, 0, 34, 37));
        }
        List<SurveyProjection.Floor> both = new ArrayList<>(lower); both.addAll(upper);
        var index = new SurveyProjection.Index(both);
        var atLower = index.select(26 * 16);
        var atUpper = index.select(34 * 16);
        assertTrue(lower.stream().allMatch(atLower::allows));
        assertTrue(upper.stream().noneMatch(atLower::allows));
        assertTrue(upper.stream().allMatch(atUpper::allows));
        assertTrue(lower.stream().noneMatch(atUpper::allows));
    }
    @Test void aSlopeContinuesAcrossTwoMapPages() {
        List<SurveyProjection.Floor> slope = new ArrayList<>();
        for (int x = 110; x < 144; x++) slope.add(floor(x < 128 ? 1 : 2, x, 0, x - 150, x - 146));
        var selection = new SurveyProjection.Index(slope).select(-15 * 16);
        assertTrue(selection.allows(slope.get(17)) && selection.allows(slope.get(18)),
            "the visible part of a slope must continue across the paper edge at X=128");
        assertFalse(selection.allows(slope.get(0)), "a remote height stays outside the navigation horizon");
    }
    @Test void aNearbyUnstackedCorridorCanProvideContext() {
        var lower = floor(0, 0, 0, 26, 29);
        var upper = floor(0, 1, 0, 30, 33);
        var selected = new SurveyProjection.Index(List.of(lower, upper)).select(26 * 16);
        assertTrue(selected.allows(lower)); assertTrue(selected.allows(upper));
    }
    @Test void aThinSolidFloorStillSeparatesRooms() {
        var lower = new SurveyProjection.Floor(0, 0, 0, 0, 0, 0, 8);
        var upper = new SurveyProjection.Floor(0, 0, 0, 0, 16, 16, 48);
        var selected = new SurveyProjection.Index(List.of(lower, upper)).select(16);
        assertFalse(selected.allows(lower)); assertTrue(selected.allows(upper));
    }
    @Test void aTallChamberDoesNotAdvertiseItsFloorThirtyBlocksBelowTheSelectedHeight() {
        List<SurveyProjection.Floor> room = new ArrayList<>();
        for (int x = 0; x < 10; x++) room.add(floor(0, x, 0, 0, 64));
        var selected = new SurveyProjection.Index(room).select(30 * 16);
        assertTrue(room.stream().noneMatch(selected::allows));
        var nearby = new SurveyProjection.Index(room).select(9 * 16);
        assertTrue(room.stream().allMatch(nearby::allows));
        assertEquals(160, nearby.alpha(room.get(0)), "distant ground is context, not a certified walking route");
    }
    @Test void rowsDoNotWrapAndSeparateBooksCannotSupplySlopeContinuity() {
        var low = floor(0, 127, 0, 10, 13);
        var above = floor(0, 127, 0, 20, 23);
        var high = floor(0, 0, 1, 11, 14);
        var selected = new SurveyProjection.Index(List.of(low, above, high)).select(20 * 16);
        assertFalse(selected.allows(low)); assertTrue(selected.allows(above));
        assertTrue(selected.allows(high), "row wrapping must not attach this floor to the suppressed lower sheet");
        assertFalse(new SurveyProjection.Index(List.of(high)).select(10 * 16).allows(low), "another book cannot supply missing observations");
    }
}
