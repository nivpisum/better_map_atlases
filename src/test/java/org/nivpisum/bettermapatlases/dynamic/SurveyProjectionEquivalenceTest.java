/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Output oracle from our own pre-optimization d200899 implementation, kept in test sources only. */
final class SurveyProjectionEquivalenceTest {
    private record Scenario(String name, List<SurveyProjection.Floor> floors) {}
    private static final int[] VIEWS = {-64, -24, 0, 24, 48, 96, 176, 208};
    private static final int[][] REACHES = {{0, 0}, {24, 64}, {192, 192}, {512, 256}};

    @Test void structuredSheetsKeepExactlyTheBaselineVisibility() {
        for (Scenario scenario : structuredScenarios()) compare(scenario);
    }

    @Test void deterministicMixedSmallGraphsKeepExactlyTheBaselineVisibility() {
        Random random = new Random(0x51ee7c0deL);
        for (int sample = 0; sample < 18; sample++) {
            List<SurveyProjection.Floor> floors = new ArrayList<>();
            int origin = (sample % 3 - 1) * 48;
            for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
                if (random.nextInt(5) == 0) continue;
                int height = origin + 8 * (random.nextInt(7) - 3);
                int storeys = random.nextBoolean() ? 1 : 2;
                for (int storey = 0; storey < storeys; storey++) {
                    int floor = height + storey * 64;
                    int[] clearances = {8, 10, 29, 48, 96, 240};
                    SurveyProjection.Floor observation = new SurveyProjection.Floor(storey, z * 4 + x,
                        x - 2, z - 2, floor, floor + random.nextInt(2),
                        floor + clearances[random.nextInt(clearances.length)],
                        random.nextInt(6) == 0, random.nextInt(5) != 0);
                    floors.add(observation);
                    if (random.nextInt(8) == 0) floors.add(observation);
                }
            }
            // Do not coalesce records by projection Key: coordinates and flags belong to Floor equality.
            if (!floors.isEmpty()) {
                SurveyProjection.Floor first = floors.get(0);
                floors.add(new SurveyProjection.Floor(first.page(), first.column(), first.x() + 1,
                    first.z(), first.floor(), first.minAir(), first.maxAir(), !first.fluid(), !first.upperKnown()));
            }
            Collections.shuffle(floors, random);
            compare(new Scenario("seeded graph " + sample, floors));
        }
    }

    @Test void selectionMatchingAndRepeatedQueriesKeepBaselineSemantics() {
        List<SurveyProjection.Floor> floors = new ArrayList<>();
        for (int x = 0; x < 6; x++) floors.add(floor(0, x, x, 0, x * 16, x * 16 + 64));
        // At view zero these steps require routes; at 512 all selected supports share one sheet identity.
        SurveyProjection.Index actual = new SurveyProjection.Index(floors);
        LegacySurveyProjection.Index expected = new LegacySurveyProjection.Index(legacyFloors(floors));
        List<SurveyProjection.Support> supports = List.of(new SurveyProjection.Support(0, 0, 0),
            new SurveyProjection.Support(1, 0, 16), new SurveyProjection.Support(5, 0, 80),
            new SurveyProjection.Support(99, 99, 0));
        for (int view : new int[]{0, 512}) for (SurveyProjection.Support support : supports) {
            SurveyProjection.Selection result = actual.select(view, 512, 512, support);
            LegacySurveyProjection.Selection oracle = expected.select(view, 512, 512, legacySupport(support));
            assertSame(result, actual.select(view, 512, 512, support));
            for (SurveyProjection.Support candidate : supports) {
                assertEquals(oracle.matches(view, 512, 512, legacySupport(candidate)),
                    result.matches(view, 512, 512, candidate), "support matching at view " + view);
            }
            assertEquals(oracle.matches(view, 512, 512, null), result.matches(view, 512, 512, null));
            assertFalse(result.matches(view + 1, 512, 512, support));
            assertFalse(result.matches(view, 511, 512, support));
            assertFalse(result.matches(view, 512, 511, support));
        }
    }

    @Test void anUnchangedPageCannotMaskAnotherPagesVisibilityChange() {
        List<SurveyProjection.Floor> floors = List.of(floor(0, 0, 0, 0, 0, 64),
            floor(1, 0, 10, 0, 144, 208));
        SurveyProjection.Index index = new SurveyProjection.Index(floors);
        LegacySurveyProjection.Index baseline = new LegacySurveyProjection.Index(legacyFloors(floors));
        for (int[] views : new int[][]{{-16, 0}, {160, 144}}) {
            SurveyProjection.Selection before = index.select(views[0], 192, 192);
            SurveyProjection.Selection after = index.select(views[1], 192, 192);
            int stablePage = views[0] < 0 ? 0 : 1;
            int changingPage = 1 - stablePage;
            assertArrayEquals(before.pageVisibility(stablePage), after.pageVisibility(stablePage));
            assertFalse(Arrays.equals(before.pageVisibility(changingPage), after.pageVisibility(changingPage)));
            for (int page = 0; page <= 1; page++) {
                LegacySurveyProjection.Floor floor = legacyFloors(floors).get(page);
                short[] oldMask = {(short) ((baseline.select(views[0], 192, 192).allows(floor) ? 256 : 0)
                    | baseline.select(views[0], 192, 192).alpha(floor))};
                short[] newMask = {(short) ((baseline.select(views[1], 192, 192).allows(floor) ? 256 : 0)
                    | baseline.select(views[1], 192, 192).alpha(floor))};
                assertArrayEquals(oldMask, before.pageVisibility(page));
                assertArrayEquals(newMask, after.pageVisibility(page));
            }
        }
        SurveyProjection.Selection selected = index.select(0, 192, 192);
        short[] detached = selected.pageVisibility(0);
        short[] original = detached.clone();
        detached[0] ^= 511;
        assertArrayEquals(original, selected.pageVisibility(0), "a returned mask must be detached from the selection");
        assertEquals(0, selected.pageVisibility(-99).length);
    }

    private static List<Scenario> structuredScenarios() {
        List<Scenario> cases = new ArrayList<>();
        cases.add(new Scenario("empty", List.of()));
        List<SurveyProjection.Floor> stacked = new ArrayList<>();
        for (int z = 0; z < 2; z++) for (int x = 0; x < 5; x++) {
            for (int storey = 0; storey < 3; storey++) {
                int height = storey * 48 - 24;
                stacked.add(floor(storey, z * 5 + x, x, z, height, height + 32));
            }
        }
        cases.add(new Scenario("three roof-separated overlapping storeys", stacked));
        List<SurveyProjection.Floor> reversed = new ArrayList<>(stacked);
        Collections.reverse(reversed);
        cases.add(new Scenario("reversed overlapping storeys", reversed));
        List<SurveyProjection.Floor> ramp = new ArrayList<>();
        for (int x = 0; x < 18; x++) {
            int height = x < 10 ? x * 16 : 144;
            ramp.add(floor(0, x, x, 0, height, height + 64));
            if (x >= 8) ramp.add(floor(1, x, x, 1, height, height + 64));
        }
        ramp.add(floor(2, 0, 0, 0, 144, 224));
        ramp.add(floor(2, 1, 1, 0, 144, 224));
        cases.add(new Scenario("slope returning to a stacked plateau", ramp));
        cases.add(new Scenario("passage and route clearance boundaries", List.of(
            floor(0, 0, 0, 0, 0, 9), floor(0, 1, 1, 0, 0, 10),
            floor(0, 2, 2, 0, 0, 28), floor(0, 3, 3, 0, 0, 29),
            floor(0, 4, 4, 0, 16, 80), floor(0, 5, 5, 0, 33, 96),
            floor(0, 6, 6, 0, 57, 112), floor(0, 7, 7, 0, 82, 144))));
        cases.add(new Scenario("fluids and unknown roofs", List.of(
            new SurveyProjection.Floor(0, 0, -1, 0, -48, -48, -40, true, true),
            new SurveyProjection.Floor(0, 1, 0, 0, 0, 0, 240, true, true),
            new SurveyProjection.Floor(0, 2, 1, 0, 0, 0, 16, true, false),
            new SurveyProjection.Floor(1, 0, -1, 0, 0, 0, 8, false, false),
            new SurveyProjection.Floor(1, 1, 0, 0, 48, 48, 64, false, false),
            floor(1, 2, 1, 0, 48, 96), floor(2, 2, 1, 0, 96, 144))));
        SurveyProjection.Floor duplicate = floor(0, 0, 0, 0, 0, 64);
        cases.add(new Scenario("exact duplicates and colliding Keys with different Floor fields", List.of(
            duplicate, duplicate,
            new SurveyProjection.Floor(0, 0, 2, 0, 0, 0, 64, false, true),
            new SurveyProjection.Floor(0, 0, 0, 1, 0, 0, 64, true, true),
            new SurveyProjection.Floor(0, 0, 0, 0, 0, 0, 64, false, false),
            floor(1, 0, 0, 0, 96, 144), floor(1, 1, 1, 0, 16, 80))));
        return cases;
    }

    private static void compare(Scenario scenario) {
        // Conversion preserves the supplied order, including duplicates and Key aliases.
        List<LegacySurveyProjection.Floor> legacy = legacyFloors(scenario.floors);
        Map<Integer, List<LegacySurveyProjection.Floor>> pages = new LinkedHashMap<>();
        // The page mask follows full Floor equality and first occurrence, rather than deduplicating by Key.
        for (LegacySurveyProjection.Floor floor : new LinkedHashSet<>(legacy)) {
            if (floor.fluid() || !floor.upperKnown()
                    || floor.maxAir() - floor.floor() >= LegacySurveyProjection.MIN_PASSAGE_Y16)
                pages.computeIfAbsent(floor.page(), ignored -> new ArrayList<>()).add(floor);
        }
        SurveyProjection.Index actual = new SurveyProjection.Index(scenario.floors);
        LegacySurveyProjection.Index expected = new LegacySurveyProjection.Index(legacy);
        List<SurveyProjection.Support> supports = new ArrayList<>();
        supports.add(null);
        supports.add(new SurveyProjection.Support(99, -99, 0));
        if (!scenario.floors.isEmpty()) {
            SurveyProjection.Floor first = scenario.floors.get(0);
            SurveyProjection.Floor last = scenario.floors.get(scenario.floors.size() - 1);
            supports.add(new SurveyProjection.Support(first.x(), first.z(), first.floor()));
            supports.add(new SurveyProjection.Support(last.x(), last.z(), last.floor() + 16));
            supports.add(new SurveyProjection.Support(first.x(), first.z(), first.maxAir()));
        }
        for (int view : VIEWS) for (int[] reach : REACHES) for (SurveyProjection.Support support : supports) {
            SurveyProjection.Selection result = actual.select(view, reach[0], reach[1], support);
            LegacySurveyProjection.Selection oracle = expected.select(view, reach[0], reach[1], legacySupport(support));
            for (int i = 0; i < scenario.floors.size(); i++) {
                SurveyProjection.Floor floor = scenario.floors.get(i);
                LegacySurveyProjection.Floor oldFloor = legacy.get(i);
                String context = scenario.name + ", view=" + view + ", above=" + reach[0]
                    + ", below=" + reach[1] + ", support=" + support + ", floor[" + i + "]=" + floor;
                assertEquals(oracle.allows(oldFloor), result.allows(floor), context);
                assertEquals(oracle.alpha(oldFloor), result.alpha(floor), context);
                assertEquals(oracle.allows(floor.page(), floor.column(), floor.floor(), floor.minAir(), floor.maxAir()),
                    result.allows(floor.page(), floor.column(), floor.floor(), floor.minAir(), floor.maxAir()), context);
                assertEquals(oracle.alpha(floor.page(), floor.column(), floor.floor(), floor.minAir(), floor.maxAir()),
                    result.alpha(floor.page(), floor.column(), floor.floor(), floor.minAir(), floor.maxAir()), context);
            }
            assertEquals(oracle.allows(-99, -99, view, view, view + 64),
                result.allows(-99, -99, view, view, view + 64), scenario.name);
            assertEquals(oracle.alpha(-99, -99, view, view, view + 64),
                result.alpha(-99, -99, view, view, view + 64), scenario.name);
            for (var page : pages.entrySet()) {
                short[] mask = new short[page.getValue().size()];
                for (int i = 0; i < mask.length; i++) {
                    LegacySurveyProjection.Floor floor = page.getValue().get(i);
                    mask[i] = (short) ((oracle.allows(floor) ? 256 : 0) | oracle.alpha(floor));
                }
                assertArrayEquals(mask, result.pageVisibility(page.getKey()),
                    scenario.name + ", page=" + page.getKey() + ", view=" + view + ", support=" + support);
            }
            assertEquals(0, result.pageVisibility(-99).length, scenario.name + ": absent page");
        }
    }

    private static SurveyProjection.Floor floor(int page, int column, int x, int z, int height, int roof) {
        return new SurveyProjection.Floor(page, column, x, z, height, height, roof);
    }

    private static List<LegacySurveyProjection.Floor> legacyFloors(List<SurveyProjection.Floor> floors) {
        return floors.stream().map(f -> new LegacySurveyProjection.Floor(f.page(), f.column(), f.x(), f.z(),
            f.floor(), f.minAir(), f.maxAir(), f.fluid(), f.upperKnown())).toList();
    }

    private static LegacySurveyProjection.Support legacySupport(SurveyProjection.Support support) {
        return support == null ? null : new LegacySurveyProjection.Support(support.x(), support.z(), support.feetY16());
    }
}
