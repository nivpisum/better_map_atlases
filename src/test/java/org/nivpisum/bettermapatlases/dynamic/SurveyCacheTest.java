/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic;

import java.util.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurveyCacheTest {
    private static final ResourceLocation DIM = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation BIOME = new ResourceLocation("minecraft", "plains");
    private static SurveySample cue(long position) {
        return new SurveySample(0, 32, 0, 160, 0, null, BIOME, position, (byte) 1,
            SurveySample.Kind.OPEN_DEPTH, false, true);
    }
    @Test void repeatedFullCommitKeepsContentAndTopologyIdentities() {
        SurveyLayer source = new SurveyLayer(0, 0, 0, DIM);
        source.observe(3, cue(1));
        SurveyLayer target = new SurveyLayer(source.metadata());
        assertTrue(target.apply(source.fullSnapshot()));
        long content = target.contentVersion(), topology = target.topologyRevision();
        assertTrue(target.apply(source.fullSnapshot()));
        assertEquals(content, target.contentVersion());
        assertEquals(topology, target.topologyRevision());
        assertEquals(1, target.sampleCount());
        // A forced authoritative reset with changed contents must still replace them,
        // even if a reused ID has the same numerical revision.
        assertTrue(target.apply(new SurveyLayer.Snapshot(target.metadata(), -1, source.revision(), true, Map.of(3, List.of(cue(2))))));
        assertTrue(target.contentVersion() > content);
        assertEquals(2, target.lookup(3, 0).position());
    }
    @Test void cachedSnapshotsAreDetachedAndShareOnlyImmutableLists() {
        SurveyLayer source = new SurveyLayer(0, 0, 0, DIM);
        source.observe(3, cue(1)); source.observe(4, cue(2));
        var before = source.fullSnapshot();
        assertSame(before, source.fullSnapshot());
        source.rememberView(true, 16);
        assertSame(before, source.fullSnapshot());
        source.observe(4, cue(3));
        var after = source.fullSnapshot();
        assertSame(before.columns().get(3), after.columns().get(3));
        assertEquals(2, before.columns().get(4).get(0).position());
        assertThrows(UnsupportedOperationException.class, () -> after.columns().get(3).clear());
        assertThrows(UnsupportedOperationException.class, () -> after.columns().entrySet().iterator().next().setValue(List.of()));
    }
    @Test void trustedColumnsCannotBypassAnotherMetadataBounds() {
        SurveyLayer wide = new SurveyLayer(1, 0, 0, DIM); wide.observe(20000, cue(1));
        assertThrows(IllegalArgumentException.class, () -> new SurveyLayer.Snapshot(
            new SurveyLayer.Metadata(0, 0, 0, DIM), -1, 1, true, wide.fullSnapshot().columns()));
    }
    @Test void sampleCountsSurviveDeltaDeletionCopyAndSavedRoundtrip() {
        SurveyLayer source = new SurveyLayer(0, 0, 0, DIM);
        source.observe(3, cue(1)); source.observe(4, cue(2));
        var target = source.copy();
        assertTrue(target.apply(new SurveyLayer.Snapshot(target.metadata(), target.revision(), target.revision() + 1,
            false, Map.of(3, List.of()))));
        assertEquals(1, target.sampleCount()); assertEquals(2, source.sampleCount());
        assertEquals(1, SurveyLayer.load(target.save()).sampleCount());
        assertNull(target.lookup(3, 0)); assertEquals(2, target.lookup(4, 0).position());
    }
    @Test void densePageGeometryRetainsRoofSeparationAfterHashAndIndexChanges() {
        List<SurveyProjection.Floor> floors = new ArrayList<>();
        Set<Integer> hashes = new HashSet<>();
        for (int page = 0; page < 8; page++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            int wx = page % 4 * 32 + x, wz = page / 4 * 32 + z, column = x + 128 * z;
            floors.add(new SurveyProjection.Floor(page, column, wx, wz, 0, 0, 160));
            floors.add(new SurveyProjection.Floor(page, column, wx, wz, -288, -288, -128));
            hashes.add(floors.get(floors.size() - 2).hashCode());
        }
        assertTrue(hashes.size() > 8000, "structured grids must not collapse into a few hash buckets");
        var index = new SurveyProjection.Index(floors);
        var selected = index.select(0, 512, 512, new SurveyProjection.Support(0, 0, 0));
        for (var floor : floors) {
            assertEquals(floor.floor() == 0, selected.allows(floor));
            assertEquals(floor.floor() == 0 ? 255 : 0, selected.alpha(floor));
        }
        assertSame(selected, index.select(0, 512, 512, new SurveyProjection.Support(10, 0, 0)));
    }
}
