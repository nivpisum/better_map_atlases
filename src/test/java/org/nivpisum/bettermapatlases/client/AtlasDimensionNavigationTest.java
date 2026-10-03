/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.client;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.nivpisum.bettermapatlases.client.AtlasDimensionNavigation.Link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AtlasDimensionNavigationTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String END = "minecraft:the_end";

    @Test void defaultConfigurationDeclaresOnlyTheOverworldNetherCorrespondence() {
        assertEquals(List.of(new Link(OVERWORLD, NETHER, 0.125)), AtlasDimensionNavigation.defaultLinks());
        assertCenter(100, -200, AtlasDimensionNavigation.centerForSwitch(OVERWORLD, NETHER, 800, -1600));
    }

    @Test void overworldToNetherConvertsBothAxes() {
        assertCenter(100, -200, switchDefault(OVERWORLD, NETHER, 800, -1600));
    }

    @Test void netherToOverworldConvertsBothAxes() {
        assertCenter(-1031.75, 2056.25, switchDefault(NETHER, OVERWORLD, -128.96875, 257.03125));
    }

    @Test void negativeFractionalCentersSurviveARoundTripWithoutRounding() {
        var nether = switchDefault(OVERWORLD, NETHER, -1031.75, 2056.25);
        assertCenter(-128.96875, 257.03125, nether);
        assertCenter(-1031.75, 2056.25, switchDefault(NETHER, OVERWORLD, nether.x(), nether.z()));
    }

    @Test void fractionalCentersNearZeroKeepTheirSignAndPrecision() {
        var nether = switchDefault(OVERWORLD, NETHER, -0.5, 0.125);
        assertCenter(-0.0625, 0.015625, nether);
        assertCenter(-0.5, 0.125, switchDefault(NETHER, OVERWORLD, nether.x(), nether.z()));
    }

    @Test void panningInNetherChangesTheReturnCenter() {
        var nether = switchDefault(OVERWORLD, NETHER, 2048, -1536);
        assertCenter(2074, -1596, switchDefault(
                NETHER, OVERWORLD, nether.x() + 3.25, nether.z() - 7.5));
    }

    @Test void crossingsIntoOrOutOfTheEndStartAtOrigin() {
        for (String dimension : new String[]{OVERWORLD, NETHER}) {
            assertCenter(0, 0, switchDefault(dimension, END, 800.25, -1600.5));
            assertCenter(0, 0, switchDefault(END, dimension, -800.25, 1600.5));
        }
    }

    @Test void returningFromEndToTheReaderInNetherUsesTheirLivePosition() {
        var end = AtlasDimensionNavigation.centerForSwitch(AtlasDimensionNavigation.defaultLinks(), NETHER, END,
                341.75, -91.125, NETHER, new AtlasDimensionNavigation.Center(341.75, -91.125));
        assertCenter(0, 0, end);
        // The player may have moved while another tab was open.
        assertCenter(347.625, -93.5, AtlasDimensionNavigation.centerForSwitch(AtlasDimensionNavigation.defaultLinks(),
                END, NETHER, end.x(), end.z(), NETHER, new AtlasDimensionNavigation.Center(347.625, -93.5)));
    }

    @Test void returningToACustomReaderDimensionNeedsNoCoordinateLink() {
        assertCenter(-92.25, 17.875, AtlasDimensionNavigation.centerForSwitch(List.of(), END, "example:moon",
                0, 0, "example:moon", new AtlasDimensionNavigation.Center(-92.25, 17.875)));
    }

    @Test void aForeignLinkedDimensionStillUsesTheViewedCenter() {
        assertCenter(200, -100, AtlasDimensionNavigation.centerForSwitch(AtlasDimensionNavigation.defaultLinks(),
                OVERWORLD, NETHER, 1600, -800, OVERWORLD, new AtlasDimensionNavigation.Center(800, 400)));
    }

    @Test void unavailableReaderPositionRetainsCoordinateOnlyNavigation() {
        assertCenter(100, -200, AtlasDimensionNavigation.centerForSwitch(AtlasDimensionNavigation.defaultLinks(),
                OVERWORLD, NETHER, 800, -1600, NETHER, null));
        assertCenter(0, 0, AtlasDimensionNavigation.centerForSwitch(List.of(), END, NETHER, 10, 20,
                NETHER, new AtlasDimensionNavigation.Center(Double.NaN, 2)));
    }

    @Test void reselectingAnyDimensionPreservesItsCurrentCenter() {
        for (String dimension : new String[]{OVERWORLD, NETHER, END, "example:moon"}) {
            assertCenter(-0.125, 812.75,
                    AtlasDimensionNavigation.centerForSwitch(List.of(), dimension, dimension, -0.125, 812.75));
        }
    }

    @Test void unrelatedCustomDimensionsStartAtOrigin() {
        for (String dimension : new String[]{OVERWORLD, NETHER, END, "example:other"}) {
            assertCenter(0, 0, switchDefault(dimension, "example:moon", 2400.5, -1024.25));
            assertCenter(0, 0, switchDefault("example:moon", dimension, 2400.5, -1024.25));
        }
    }

    @Test void removingTheDefaultLinkRemovesTheBuiltinCorrespondence() {
        assertCenter(0, 0, AtlasDimensionNavigation.centerForSwitch(List.of(), OVERWORLD, NETHER, 800, -1600));
        assertCenter(0, 0, AtlasDimensionNavigation.centerForSwitch(List.of(), NETHER, OVERWORLD, 100, -200));
        assertCenter(0, 0, AtlasDimensionNavigation.centerForSwitch(null, OVERWORLD, NETHER, 800, -1600));
    }

    @Test void explicitlyLinkedCustomDimensionsConvertInBothDirections() {
        var links = List.of(new Link("example:moon", "example:station", 3));
        var station = AtlasDimensionNavigation.centerForSwitch(links, "example:moon", "example:station", -8.5, 0.25);
        assertCenter(-25.5, 0.75, station);
        assertCenter(-8.5, 0.25,
                AtlasDimensionNavigation.centerForSwitch(links, "example:station", "example:moon", station.x(), station.z()));
    }

    @Test void declaredChainsComposeForwardAndReverseScales() {
        var links = List.of(new Link("example:earth", "example:moon", 0.25),
                new Link("example:moon", "example:station", 2));
        var station = AtlasDimensionNavigation.centerForSwitch(links, "example:earth", "example:station", -12.5, 0.25);
        assertCenter(-6.25, 0.125, station);
        assertCenter(-12.5, 0.25,
                AtlasDimensionNavigation.centerForSwitch(links, "example:station", "example:earth", station.x(), station.z()));
    }

    @Test void chainsCanTraverseAReversedDeclaration() {
        var links = List.of(new Link("example:earth", "example:moon", 0.5),
                new Link("example:station", "example:moon", 4));
        assertCenter(8, -2, AtlasDimensionNavigation.centerForSwitch(links, "example:earth", "example:station", 64, -16));
    }

    @Test void theEndCanShareCoordinatesWhenItsLinkIsExplicitlyDeclared() {
        var links = List.of(new Link(OVERWORLD, END, 1));
        assertCenter(800.25, -1600.5, AtlasDimensionNavigation.centerForSwitch(links, OVERWORLD, END, 800.25, -1600.5));
        assertCenter(-800.25, 1600.5, AtlasDimensionNavigation.centerForSwitch(links, END, OVERWORLD, -800.25, 1600.5));
    }

    @Test void aDirectDeclarationTakesPriorityOverALongerPath() {
        var links = List.of(new Link("example:a", "example:c", 2), new Link("example:c", "example:b", 3),
                new Link("example:a", "example:b", 4));
        assertCenter(8, -12, AtlasDimensionNavigation.centerForSwitch(links, "example:a", "example:b", 2, -3));
    }

    @Test void invalidDeclarationsAreIgnoredWithoutDiscardingValidLinks() {
        List<Link> invalid = Arrays.asList(null, new Link(null, NETHER, 0.125), new Link(OVERWORLD, null, 0.125),
                new Link(" ", NETHER, 0.125), new Link(OVERWORLD, "", 0.125),
                new Link(OVERWORLD, NETHER, 0), new Link(OVERWORLD, NETHER, -1),
                new Link(OVERWORLD, NETHER, Double.NaN), new Link(OVERWORLD, NETHER, Double.POSITIVE_INFINITY));
        for (Link link : invalid) assertFalse(AtlasDimensionNavigation.isValidLink(link));
        assertTrue(AtlasDimensionNavigation.isValidLink(new Link(OVERWORLD, NETHER, 0.125)));
        assertCenter(0, 0, AtlasDimensionNavigation.centerForSwitch(invalid, OVERWORLD, NETHER, 800, -1600));

        var links = new java.util.ArrayList<>(invalid);
        links.addAll(AtlasDimensionNavigation.defaultLinks());
        assertCenter(100, -200, AtlasDimensionNavigation.centerForSwitch(links, OVERWORLD, NETHER, 800, -1600));
    }

    @Test void cyclesDoNotCreateCorrespondenceToAnUnlinkedDimension() {
        var links = List.of(new Link("example:a", "example:b", 2), new Link("example:b", "example:c", 3),
                new Link("example:c", "example:a", 1.0 / 6));
        assertCenter(0, 0, AtlasDimensionNavigation.centerForSwitch(links, "example:a", "example:missing", 2, -3));
    }

    @Test void nonFiniteConversionsFallBackToOrigin() {
        assertCenter(0, 0, AtlasDimensionNavigation.centerForSwitch(
                List.of(new Link("example:a", "example:b", Double.MAX_VALUE)), "example:a", "example:b", 2, -3));
        var overflowChain = List.of(new Link("example:a", "example:b", 1e200), new Link("example:b", "example:c", 1e200));
        assertCenter(0, 0, AtlasDimensionNavigation.centerForSwitch(overflowChain, "example:a", "example:c", 2, -3));
        assertCenter(0, 0, AtlasDimensionNavigation.centerForSwitch(
                List.of(new Link("example:a", "example:b", Double.MIN_VALUE)), "example:b", "example:a", 2, -3));
    }

    private static AtlasDimensionNavigation.Center switchDefault(String from, String to, double x, double z) {
        return AtlasDimensionNavigation.centerForSwitch(AtlasDimensionNavigation.defaultLinks(), from, to, x, z);
    }

    private static void assertCenter(double expectedX, double expectedZ, AtlasDimensionNavigation.Center center) {
        assertEquals(expectedX, center.x());
        assertEquals(expectedZ, center.z());
    }
}
