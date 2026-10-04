/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SurveyNavigationTest {
    private SurveyProjection.Floor ground(int x, int z, int y) {
        return new SurveyProjection.Floor(0, x + 128 * z, x, z, y * 16, y * 16, (y + 4) * 16);
    }
    @Test void anOpenTwoBlockRiseIsNotAnUpperStorey() {
        var low = new SurveyProjection.Floor(0, 0, 0, 0, 38 * 16, 38 * 16, 90 * 16);
        var built = new SurveyProjection.Floor(0, 0, 0, 0, 39 * 16, 39 * 16, 90 * 16);
        var rise = new SurveyProjection.Floor(0, 1, 1, 0, 41 * 16, 41 * 16, 90 * 16);
        var older = new SurveyProjection.Floor(0, 1, 1, 0, 38 * 16, 38 * 16, 90 * 16);
        var at = new SurveyProjection.Index(List.of(low, built, rise, older))
            .select(39 * 16, 512, 512, new SurveyProjection.Support(0, 0, 39 * 16));
        assertTrue(at.allows(built)); assertTrue(at.allows(rise)); assertTrue(at.alpha(rise) > 0);
    }
    @Test void aDistantRampCannotAdvertiseTheWholeLowerPlateauBelowACliff() {
        List<SurveyProjection.Floor> all = new ArrayList<>(), lower = new ArrayList<>(), ramp = new ArrayList<>();
        for (int x = 0; x < 10; x++) all.add(ground(x, 0, 70));
        for (int x = 10; x <= 20; x++) { var f = ground(x, 0, 50); lower.add(f); all.add(f); }
        for (int x = 0; x <= 20; x++) { var f = ground(x, 1, 70 - x); ramp.add(f); all.add(f); }
        var at = new SurveyProjection.Index(all).select(70 * 16, 512, 512, new SurveyProjection.Support(8, 0, 70 * 16));
        assertTrue(lower.stream().noneMatch(at::allows));
        assertTrue(ramp.subList(0, 12).stream().allMatch(f -> at.alpha(f) > 0));
        assertTrue(ramp.subList(12, 21).stream().allMatch(f -> at.alpha(f) == 0));
    }
    @Test void routeCostDoesNotResetAtAFlatPlateauAndMovingAtTheSameYChangesAffinity() {
        List<SurveyProjection.Floor> all = new ArrayList<>();
        for (int x = 0; x <= 80; x++) { all.add(ground(x, 0, 70)); all.add(ground(x, 5, 65)); }
        for (int z = 1; z < 5; z++) all.add(ground(80, z, 70 - z));
        var target = ground(80, 5, 65); var index = new SurveyProjection.Index(all);
        var remote = index.select(70 * 16, 512, 512, new SurveyProjection.Support(0, 0, 70 * 16));
        var near = index.select(70 * 16, 512, 512, new SurveyProjection.Support(80, 0, 70 * 16));
        assertEquals(160, remote.alpha(target)); assertEquals(255, near.alpha(target));
        assertNotSame(remote, near);
        assertSame(near, index.select(70 * 16, 512, 512, new SurveyProjection.Support(80, 0, 70 * 16 + 8)));
    }
    @Test void liquidIsAHazardSurfaceAndCannotJoinTheWalkingGraph() {
        var start = ground(0, 0, 30); var island = ground(2, 0, 32);
        var lava = new SurveyProjection.Floor(0, 1, 1, 0, 31 * 16, 0, 80 * 16, true, false);
        var at = new SurveyProjection.Index(List.of(start, lava, island))
            .select(30 * 16, 512, 512, new SurveyProjection.Support(0, 0, 30 * 16));
        assertEquals(255, at.alpha(lava)); assertEquals(160, at.alpha(island));
        var ocean = new SurveyProjection.Floor(0, 0, 0, 0, 32 * 16, 0, 90 * 16, true, false);
        assertEquals(255, new SurveyProjection.Index(List.of(ocean)).select(50 * 16).alpha(ocean));
    }
    @Test void aClosedLiquidPocketKeepsTheShortHeightHorizon() {
        var pool = new SurveyProjection.Floor(0, 0, 0, 0, 46 * 16, 40 * 16, 49 * 16, true, true);
        var index = new SurveyProjection.Index(List.of(pool));
        assertEquals(255, index.select(55 * 16).alpha(pool));
        assertEquals(170, index.select(56 * 16).alpha(pool));
        assertEquals(0, index.select(58 * 16).alpha(pool));
        assertFalse(index.select(59 * 16).allows(pool));
        var upstairs = ground(0, 0, 50);
        var stacked = new SurveyProjection.Index(List.of(pool, upstairs)).select(50 * 16);
        assertTrue(stacked.allows(upstairs)); assertFalse(stacked.allows(pool), "a known roof separates liquid rooms too");
    }
    @Test void aClippedUnknownRoofCannotVetoAnAdjacentStorey() {
        var clipped = new SurveyProjection.Floor(0, 0, 0, 0, 26 * 16, 26 * 16, 29 * 16, false, false);
        var upper = ground(0, 0, 30); var index = new SurveyProjection.Index(List.of(clipped, upper));
        assertTrue(index.select(30 * 16).allows(clipped)); assertTrue(index.select(30 * 16).allows(upper));
    }
    @Test void walkingAcrossAFlatFloorReusesTheUnchangedSelection() {
        List<SurveyProjection.Floor> flat = new ArrayList<>();
        for (int x = 0; x < 80; x++) flat.add(ground(x, 0, 70));
        var index = new SurveyProjection.Index(flat);
        var first = index.select(70 * 16, 512, 512, new SurveyProjection.Support(0, 0, 70 * 16));
        for (int x = 1; x < 80; x++) {
            var support = new SurveyProjection.Support(x, 0, 70 * 16);
            assertSame(first, index.select(70 * 16, 512, 512, support)); assertTrue(first.matches(70 * 16, 512, 512, support));
        }
    }
    @Test void aCollisionSlitIsNotASeparateFloorAndCannotPoisonAnOpenSlope() {
        var slit = new SurveyProjection.Floor(0, 1, 1, 0, 83 * 16 + 14, 83 * 16 + 14, 84 * 16);
        var top = new SurveyProjection.Floor(0, 1, 1, 0, 84 * 16 + 14, 84 * 16 + 14, 88 * 16);
        var start = new SurveyProjection.Floor(0, 0, 0, 0, 83 * 16 + 14, 83 * 16 + 14, 88 * 16);
        var at = new SurveyProjection.Index(List.of(slit, top, start)).select(83 * 16 + 14, 512, 512,
            new SurveyProjection.Support(0, 0, 83 * 16 + 14));
        assertFalse(at.allows(slit)); assertTrue(at.allows(start)); assertTrue(at.allows(top));
        var crawl = new SurveyProjection.Floor(0, 4, 4, 0, 0, 0, 12);
        assertTrue(new SurveyProjection.Index(List.of(crawl)).select(0).allows(crawl), "a real crawl passage remains recorded terrain");
    }
}
