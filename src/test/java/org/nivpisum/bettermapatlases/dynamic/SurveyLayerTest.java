/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class SurveyLayerTest {
    private static final ResourceLocation DIMENSION = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation BIOME = new ResourceLocation("minecraft", "plains");

    @Test void stackedRoomsAndHalfSlabGapRemainDistinct() {
        SurveyLayer layer = layer();
        SurveySample lower = cue(-32, 16, -32, 16, 1);
        SurveySample upper = cue(24, 80, 24, 96, 2);
        layer.observe(0, lower);
        layer.observe(0, upper);
        assertSame(lower, layer.lookup(0, 15));
        assertNull(layer.lookup(0, 16));
        assertNull(layer.lookup(0, 23));
        assertSame(upper, layer.lookup(0, 24));
        assertNull(layer.lookup(0, 80), "geometry alone must not disclose unobserved height");
        assertNull(layer.lookup(1, 24), "sealed neighboring column must remain unknown");
    }

    @Test void aRevisitReplacesOnlyOverlappingKnowledge() {
        SurveyLayer layer = layer();
        SurveySample old = cue(0, 80, 0, 160, 1);
        SurveySample changed = cue(32, 48, 16, 96, 2);
        layer.observe(7, old);
        layer.observe(7, changed);
        assertEquals(List.of(old.withObservedRange(0, 32), changed, old.withObservedRange(48, 80)), layer.column(7));
        assertEquals(1, layer.lookup(7, 31).position());
        assertEquals(2, layer.lookup(7, 32).position());
        assertEquals(1, layer.lookup(7, 48).position());
        assertNull(layer.lookup(7, 80));
    }

    @Test void normalizationMergesOnlyAdjacentIdenticalGeometryAndSample() {
        AtomicInteger dirty = new AtomicInteger();
        SurveyLayer layer = new SurveyLayer(0, 0, 0, DIMENSION, dirty::incrementAndGet);
        layer.observe(1, cue(0, 16, 0, 160, 1));
        layer.observe(1, cue(32, 48, 0, 160, 1));
        assertEquals(2, layer.column(1).size());
        assertNull(layer.lookup(1, 16));
        layer.observe(1, cue(16, 32, 0, 160, 1));
        assertEquals(List.of(cue(0, 48, 0, 160, 1)), layer.column(1));
        long revision = layer.revision();
        assertFalse(layer.observe(1, cue(8, 40, 0, 160, 1)));
        assertEquals(revision, layer.revision());
        assertEquals(3, dirty.get());
        layer.observe(1, cue(48, 64, 0, 176, 1));
        assertEquals(2, layer.column(1).size(), "different geometry must survive adjacency");
        SurveySample flagged = new SurveySample(64, 80, 0, 176, 0, null, BIOME, 1,
            (byte) 1, SurveySample.Kind.OPEN_DEPTH, false, true);
        layer.observe(1, flagged);
        assertEquals(3, layer.column(1).size(), "boundary certainty is part of geometry identity");
    }

    @Test void coarseMapsNeverTreatOtherSubcolumnsAsExplored() {
        SurveyLayer layer = new SurveyLayer(4, 1024, -1024, DIMENSION);
        assertEquals(2048, layer.width());
        assertEquals(0, layer.columnKey(0, -2048));
        assertEquals(2048 * 2048 - 1, layer.columnKey(2047, -1));
        assertEquals(-1, layer.columnKey(-1, -2048));
        assertEquals(-1, layer.columnKey(2048, -1));
        assertEquals(-1, layer.columnKey(0, 0));
        layer.observeWorld(100, -100, cue(0, 16, 0, 16, 1));
        assertNotNull(layer.lookupWorld(100, -100, 0));
        assertNull(layer.lookupWorld(101, -100, 0));
        int column = layer.columnKey(100, -100);
        assertEquals(100, layer.metadata().worldX(column));
        assertEquals(-100, layer.metadata().worldZ(column));
    }

    @Test void projectionUsesObservedHeightOnlyAndRemainsImmutable() {
        SurveyLayer page = layer();
        SurveySample lower = cue(0, 16, 0, 128, 1);
        SurveySample upper = cue(48, 64, 32, 128, 2);
        SurveySample neighbor = cue(0, 16, 0, 16, 3);
        page.observe(7, lower);
        page.observe(7, upper);
        page.observe(9, neighbor);
        Map<Integer, SurveySample> lowerView = page.projection(8);
        assertEquals(Map.of(7, lower, 9, neighbor), lowerView);
        assertTrue(page.projection(16).isEmpty());
        assertEquals(Map.of(7, upper), page.projection(48));
        assertTrue(page.projection(64).isEmpty());
        assertThrows(UnsupportedOperationException.class, lowerView::clear);
        page.observe(7, cue(0, 16, 0, 128, 4));
        assertEquals(lower, lowerView.get(7), "a returned projection is detached from subsequent observations");
    }

    @Test void contentDigestUsesStableHistoryAndMetadataButNoBookmarkOrRevision() {
        SurveyLayer first = layer();
        SurveyLayer second = layer();
        SurveySample a = cue(0, 16, 0, 128, 1);
        SurveySample b = cue(32, 48, 0, 128, 2);
        first.observe(7, a);
        first.observe(9, b);
        second.observe(9, b);
        second.observe(7, cue(0, 16, 0, 128, 999));
        second.observe(7, a);
        first.rememberView(true, 8);
        second.rememberView(false, 1024);
        assertNotEquals(first.revision(), second.revision());
        String hash = first.contentHash();
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertEquals(hash, second.contentHash());
        assertEquals(hash, first.describe().get("contentHash"));
        assertEquals(hash, SurveyLayer.load(first.save()).contentHash());
        SurveyLayer synced = layer();
        assertTrue(synced.apply(first.fullSnapshot()));
        assertEquals(hash, synced.contentHash());
        first.rememberView(false, -48);
        assertEquals(hash, first.contentHash());
        second.observe(9, cue(32, 48, 0, 128, 3));
        assertNotEquals(hash, second.contentHash());
        SurveyLayer differentCenter = new SurveyLayer(0, 1, 0, DIMENSION);
        assertNotEquals(layer().contentHash(), differentCenter.contentHash());
        CompoundTag unsupported = first.save();
        unsupported.putInt("version", 999);
        assertNull(SurveyLayer.loadPreserving(unsupported, first.metadata(), () -> {}).contentHash());
    }

    @Test void snapshotsAreImmutableAndDeltasReplaceColumnsAtomically() {
        SurveyLayer server = layer();
        server.observe(2, cue(0, 16, 0, 160, 1));
        SurveyLayer.Snapshot full = server.fullSnapshot();
        SurveyLayer client = layer();
        assertTrue(client.apply(full));
        server.observe(2, cue(32, 48, 0, 160, 2));
        server.observe(3, cue(16, 32, 0, 160, 3));
        SurveyLayer.Snapshot delta = server.snapshot(full.revision());
        assertEquals(1, full.columns().get(2).size());
        assertEquals(2, delta.columns().get(2).size(), "changed columns carry all retained intervals");
        assertThrows(UnsupportedOperationException.class, () -> full.columns().clear());
        assertThrows(UnsupportedOperationException.class, () -> full.columns().get(2).clear());
        assertTrue(client.apply(delta));
        assertEquals(server.fullSnapshot().columns(), client.fullSnapshot().columns());
        assertFalse(client.apply(delta), "a repeated or skipped delta base must not mutate the client");
        assertEquals(server.revision(), client.revision());
        SurveyLayer.Snapshot removal = new SurveyLayer.Snapshot(client.metadata(), client.revision(), client.revision() + 1,
            false, Map.of(2, List.of()));
        assertTrue(client.apply(removal));
        assertNull(client.lookup(2, 0));
        assertNotNull(client.lookup(3, 16));
        assertEquals(List.of(), client.snapshot(removal.baseRevision()).columns().get(2));
    }

    @Test void copiesAndScaledIdentityHaveIndependentMutableIndexes() {
        SurveyLayer original = layer();
        original.observe(9, cue(0, 16, 0, 160, 1));
        original.rememberView(true, 8);
        AtomicInteger copiedDirty = new AtomicInteger();
        SurveyLayer copy = original.copy(copiedDirty::incrementAndGet);
        copy.observe(9, cue(0, 16, 0, 160, 2));
        assertEquals(1, original.lookup(9, 0).position());
        assertEquals(2, copy.lookup(9, 0).position());
        assertEquals(1, copiedDirty.get());
        assertEquals(original.bookmark(), copy.bookmark());
        assertTrue(copy.rememberView(false, 1024));
        assertEquals(new SurveyLayer.Bookmark(true, 8, 1), original.bookmark());
        assertEquals(new SurveyLayer.Bookmark(false, 1024, 2), copy.bookmark());
        SurveyLayer scaled = original.emptyScaled(new SurveyLayer.Metadata(1, 64, 64, DIMENSION), () -> {});
        assertEquals(1, scaled.scale());
        assertEquals(0, scaled.observedColumns());
        assertEquals(0, scaled.revision());
        assertNull(scaled.bookmark());
        assertTrue(scaled.writable());
    }

    @Test void pageBookmarksStayIndependentOfHistoryAndOtherBooks() {
        AtomicInteger dirty = new AtomicInteger();
        SurveyLayer page = new SurveyLayer(0, 0, 0, DIMENSION, dirty::incrementAndGet);
        SurveyLayer independentBook = layer();
        assertNull(page.bookmark());
        assertEquals(0, page.bookmarkRevision());
        page.observe(1, cue(0, 16, 0, 160, 1));
        SurveyLayer.Snapshot history = page.fullSnapshot();
        assertTrue(page.rememberView(true, 8));
        assertEquals(new SurveyLayer.Bookmark(true, 8, 1), page.bookmark());
        assertFalse(page.rememberView(true, 8));
        assertEquals(2, dirty.get(), "unchanged view must not dirty the owner");
        assertTrue(page.rememberView(false, 8));
        assertTrue(page.rememberView(false, 16));
        assertEquals(new SurveyLayer.Bookmark(false, 16, 3), page.bookmark());
        assertEquals(4, dirty.get());
        assertEquals(history, page.fullSnapshot(), "bookmark updates must not churn column history");
        assertTrue(page.snapshot(history.revision()).columns().isEmpty());
        assertNull(independentBook.bookmark(), "another book does not inherit a player's last view");
        assertEquals(0, independentBook.observedColumns());
        assertThrows(IllegalArgumentException.class, () -> new SurveyLayer.Bookmark(true, 0, 0));
    }

    @Test void optionalBookmarkNbtIsBackwardCompatibleAndValidated() {
        SurveyLayer original = layer();
        original.observe(1, cue(0, 16, 0, 160, 1));
        CompoundTag oldFormatOne = original.save();
        assertFalse(oldFormatOne.contains("bookmark"));
        SurveyLayer oldLoaded = SurveyLayer.load(oldFormatOne);
        assertNull(oldLoaded.bookmark());
        assertEquals(oldFormatOne, oldLoaded.save());
        original.rememberView(true, -56);
        original.rememberView(true, -48);
        CompoundTag encoded = original.save();
        assertEquals(1, encoded.getInt("version"));
        SurveyLayer loaded = SurveyLayer.load(encoded);
        assertEquals(new SurveyLayer.Bookmark(true, -48, 2), loaded.bookmark());
        assertEquals(encoded, loaded.save());
        assertEquals(original.fullSnapshot(), loaded.fullSnapshot());
        CompoundTag malformed = encoded.copy();
        malformed.getCompound("bookmark").putByte("cave", (byte) 2);
        assertThrows(IllegalArgumentException.class, () -> SurveyLayer.load(malformed));
        SurveyLayer protectedPage = SurveyLayer.loadPreserving(malformed, original.metadata(), () -> {});
        assertFalse(protectedPage.rememberView(false, 100));
        assertEquals(malformed, protectedPage.save());
    }

    @Test void fixedPointAndBoundsRejectInvalidKnowledge() {
        assertEquals(8, SurveySample.fixed16(0.5));
        assertEquals(-1, SurveySample.fixed16(-0.001));
        assertThrows(IllegalArgumentException.class, () -> SurveySample.fixed16(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> SurveySample.fixed16(Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> cue(0, 17, 0, 16, 1));
        assertThrows(IllegalArgumentException.class, () -> cue(0, 0, 0, 16, 1));
        assertThrows(IllegalArgumentException.class, () -> new SurveyLayer(5, 0, 0, DIMENSION));
        assertThrows(IllegalArgumentException.class, () -> layer().observe(-1, cue(0, 16, 0, 16, 1)));
        assertThrows(IllegalArgumentException.class, () -> new SurveySample(0, 16, 0, 16, 0, null, BIOME, 1,
            (byte) 4, SurveySample.Kind.OPEN_DEPTH, false, false));
        assertThrows(IllegalArgumentException.class, () -> new SurveySample(0, 16, 0, 16, 0, null, BIOME, 1,
            (byte) 1, SurveySample.Kind.FLOOR, false, false));
    }

    @Test void stableNbtRoundtripRetainsGapsAndExactRevision() {
        SurveyLayer original = layer();
        original.observe(2, cue(-16, -8, -64, 160, 32));
        original.observe(2, cue(32, 40, -64, 160, 33));
        original.observe(999, cue(0, 16, 0, 32, 40));
        CompoundTag encoded = original.save();
        assertEquals("minecraft:plains", encoded.getList("biome_palette", Tag.TAG_STRING).getString(0));
        SurveyLayer decoded = SurveyLayer.load(encoded);
        assertEquals(encoded, decoded.save());
        assertEquals(original.fullSnapshot(), decoded.fullSnapshot());
        assertNull(decoded.lookup(2, 0));
        assertEquals(original.snapshot(1), decoded.snapshot(1));
    }

    @Test void invalidOrFutureNbtRemainsOpaqueAndSurvivesSaving() {
        SurveyLayer original = layer();
        original.observe(2, cue(0, 16, 0, 32, 1));
        CompoundTag future = original.save();
        future.putInt("version", 100);
        SurveyLayer opaque = SurveyLayer.loadPreserving(future, original.metadata(), () -> {});
        assertFalse(opaque.writable());
        assertNotNull(opaque.preservedReason());
        assertFalse(opaque.observe(3, cue(0, 16, 0, 32, 2)));
        assertEquals(future, opaque.save());
        assertEquals(future, opaque.copy().save());
        CompoundTag corrupt = original.save();
        CompoundTag column = corrupt.getList("columns", Tag.TAG_COMPOUND).getCompound(0);
        int[] values = column.getIntArray("samples");
        values[1] = 33;
        column.putIntArray("samples", values);
        assertThrows(IllegalArgumentException.class, () -> SurveyLayer.load(corrupt));
        assertEquals(corrupt, SurveyLayer.loadPreserving(corrupt, original.metadata(), () -> {}).save());
        CompoundTag changedMetadata = original.save();
        changedMetadata.putInt("center_x", 9999);
        assertFalse(SurveyLayer.loadPreserving(changedMetadata, original.metadata(), () -> {}).writable());
    }

    @Test void manyDisjointVisitsHaveNoArbitraryHistoryLimit() {
        SurveyLayer layer = layer();
        for (int i = 0; i < 200; i++) layer.observe(4, cue(i * 32, i * 32 + 8, 0, 6400, i));
        assertEquals(200, layer.sampleCount());
        SurveyLayer reloaded = SurveyLayer.load(layer.save());
        for (int i = 0; i < 200; i++) {
            assertEquals(i, reloaded.lookup(4, i * 32).position());
            assertNull(reloaded.lookup(4, i * 32 + 8));
        }
    }

    @Test void invalidSnapshotCannotPartiallyReplaceHistory() {
        SurveyLayer layer = layer();
        layer.observe(1, cue(0, 16, 0, 160, 1));
        SurveyLayer.Snapshot before = layer.fullSnapshot();
        assertThrows(IllegalArgumentException.class, () -> new SurveyLayer.Snapshot(layer.metadata(), -1, 9, true,
            Map.of(1, List.of(cue(0, 32, 0, 160, 2), cue(16, 48, 0, 160, 3)))));
        SurveyLayer.Snapshot differentMap = new SurveyLayer.Snapshot(new SurveyLayer.Metadata(1, 0, 0, DIMENSION),
            -1, 0, true, Map.of());
        assertThrows(IllegalArgumentException.class, () -> layer.apply(differentMap));
        assertEquals(before, layer.fullSnapshot());
        ArrayList<SurveySample> mutable = new ArrayList<>();
        mutable.add(cue(0, 16, 0, 160, 1));
        SurveyLayer.Snapshot detached = new SurveyLayer.Snapshot(layer.metadata(), -1, 1, true, Map.of(1, mutable));
        mutable.clear();
        assertEquals(1, detached.columns().get(1).size());
    }

    @Test void revisionAndColumnSnapshotsStayConsistentDuringConcurrentReads() throws InterruptedException {
        SurveyLayer layer = layer();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                for (int i = 1; i <= 1000; i++) layer.observe(1, cue(0, 16, 0, 160, i));
            } catch (Throwable error) { failure.set(error); }
        });
        writer.start();
        for (int i = 0; i < 500; i++) {
            SurveyLayer.Snapshot snapshot = layer.fullSnapshot();
            if (!snapshot.columns().isEmpty()) assertEquals(snapshot.revision(), snapshot.columns().get(1).get(0).position());
            layer.copy();
        }
        writer.join();
        assertNull(failure.get());
        assertEquals(1000, layer.revision());
    }

    private static SurveyLayer layer() { return new SurveyLayer(0, 0, 0, DIMENSION); }
    private static SurveySample cue(int min, int max, int geometryMin, int geometryMax, long position) {
        return new SurveySample(min, max, geometryMin, geometryMax, geometryMin, null, BIOME, position,
            (byte) 1, SurveySample.Kind.OPEN_DEPTH, false, false);
    }
}
