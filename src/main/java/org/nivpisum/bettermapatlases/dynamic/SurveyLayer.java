/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Sparse observed world columns, independent of loaded worlds and of surface map pixels. */
public final class SurveyLayer {
    public static final String TAG = "accurate_maps_survey";
    public static final int FORMAT_VERSION = 1;
    // Per sample: observation min/max, geometry min/max, floor, state, biome, shade, kind, boundary flags.
    private static final int SAMPLE_STRIDE = 10;
    private static final Runnable NO_DIRTY = () -> {};

    /** Last actually observed view recorded on this physical map page, independent of its current reader. */
    public record Bookmark(boolean cave, int anchorY16, long revision) {
        public Bookmark {
            if (revision <= 0) throw new IllegalArgumentException("Bookmark revision must be positive");
        }
    }

    public record Metadata(int scale, int centerX, int centerZ, ResourceLocation dimension) {
        public Metadata {
            if (scale < 0 || scale > 4) throw new IllegalArgumentException("Map scale must be 0..4");
            Objects.requireNonNull(dimension, "dimension");
        }
        public int width() { return 128 << scale; }
        public long minX() { return (long) centerX - width() / 2; }
        public long minZ() { return (long) centerZ - width() / 2; }
        public int columnCount() { return width() * width(); }
        public int columnKey(int worldX, int worldZ) {
            long localX = worldX - minX();
            long localZ = worldZ - minZ();
            return localX < 0 || localZ < 0 || localX >= width() || localZ >= width()
                ? -1 : (int) (localX + localZ * width());
        }
        public int worldX(int column) {
            checkColumn(column);
            return Math.toIntExact(minX() + column % width());
        }
        public int worldZ(int column) {
            checkColumn(column);
            return Math.toIntExact(minZ() + column / width());
        }
        public void checkColumn(int column) {
            if (column < 0 || column >= columnCount()) throw new IllegalArgumentException("Column outside map footprint");
        }
    }

    /** Empty lists in a delta explicitly delete a column. The entire object is immutable. */
    public record Snapshot(Metadata metadata, long baseRevision, long revision, boolean reset,
                           Map<Integer, List<SurveySample>> columns) {
        public Snapshot {
            Objects.requireNonNull(metadata, "metadata");
            if (revision < 0 || (reset ? baseRevision != -1 : baseRevision < 0 || baseRevision > revision)) {
                throw new IllegalArgumentException("Invalid snapshot revision interval");
            }
            Objects.requireNonNull(columns, "columns");
            if (!(columns instanceof StoredColumns stored) || !stored.metadata.equals(metadata) || stored.reset != reset) {
                TreeMap<Integer, List<SurveySample>> immutable = new TreeMap<>();
                columns.forEach((key, values) -> {
                    metadata.checkColumn(Objects.requireNonNull(key, "column"));
                    List<SurveySample> samples = canonical(values);
                    if (!reset || !samples.isEmpty()) immutable.put(key, samples);
                });
                columns = Collections.unmodifiableMap(immutable);
            }
        }
    }
    /** Only this class can mark detached, already validated immutable column lists. */
    private static final class StoredColumns extends java.util.AbstractMap<Integer, List<SurveySample>> {
        private final Map<Integer, List<SurveySample>> values;
        private final Metadata metadata;
        private final boolean reset;
        private StoredColumns(Metadata metadata, boolean reset, Map<Integer, List<SurveySample>> detached) {
            this.metadata = metadata; this.reset = reset; values = Collections.unmodifiableMap(detached);
        }
        @Override public java.util.Set<Entry<Integer, List<SurveySample>>> entrySet() { return values.entrySet(); }
        @Override public List<SurveySample> get(Object key) { return values.get(key); }
        @Override public int size() { return values.size(); }
    }

    private final Metadata metadata;
    private final Runnable dirty;
    private final TreeMap<Integer, List<SurveySample>> columns = new TreeMap<>();
    private final TreeMap<Integer, Long> changedAt = new TreeMap<>();
    private long revision;
    private long contentVersion, topologyRevision;
    private int sampleCount;
    private Bookmark bookmark;
    private SurveyProjection.Index projectionIndex;
    private Snapshot cachedFullSnapshot;
    private int cachedFloorPage = Integer.MIN_VALUE;
    private List<SurveyProjection.Floor> cachedFloors;
    // Unreadable history is preserved rather than rewritten as an empty layer on the next autosave.
    private CompoundTag preserved;
    private String preservedReason;

    public SurveyLayer(int scale, int centerX, int centerZ, ResourceLocation dimension) {
        this(new Metadata(scale, centerX, centerZ, dimension), NO_DIRTY);
    }
    public SurveyLayer(int scale, int centerX, int centerZ, ResourceLocation dimension, Runnable dirty) {
        this(new Metadata(scale, centerX, centerZ, dimension), dirty);
    }
    public SurveyLayer(Metadata metadata) { this(metadata, NO_DIRTY); }
    public SurveyLayer(Metadata metadata, Runnable dirty) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.dirty = Objects.requireNonNull(dirty, "dirty");
    }

    public Metadata metadata() { return metadata; }
    public int scale() { return metadata.scale(); }
    public int centerX() { return metadata.centerX(); }
    public int centerZ() { return metadata.centerZ(); }
    public ResourceLocation dimension() { return metadata.dimension(); }
    public int width() { return metadata.width(); }
    public int columnKey(int worldX, int worldZ) { return metadata.columnKey(worldX, worldZ); }
    public synchronized long revision() { return revision; }
    public synchronized Bookmark bookmark() { return bookmark; }
    public synchronized long bookmarkRevision() { return bookmark == null ? 0 : bookmark.revision(); }
    public synchronized boolean writable() { return preserved == null; }
    public synchronized String preservedReason() { return preservedReason; }
    public synchronized int observedColumns() { return columns.size(); }
    public synchronized int sampleCount() { return sampleCount; }
    /** In-memory cache identities; neither changes the serialized observation format. */
    public synchronized long contentVersion() { return contentVersion; }
    public synchronized long topologyRevision() { return topologyRevision; }
    public synchronized long changedAt(int column) {
        metadata.checkColumn(column);
        return changedAt.getOrDefault(column, 0L);
    }

    /** Records the page's view without generating a changed-column revision or copying a player journal. */
    public synchronized boolean rememberView(boolean cave, int anchorY16) {
        if (preserved != null || (bookmark != null && bookmark.cave() == cave && bookmark.anchorY16() == anchorY16)) {
            return false;
        }
        bookmark = new Bookmark(cave, anchorY16, Math.incrementExact(bookmarkRevision()));
        dirty.run();
        return true;
    }

    /** Only the new observation's Y interval replaces history; unseen gaps are never filled. */
    public synchronized boolean observe(int column, SurveySample observation) {
        metadata.checkColumn(column);
        Objects.requireNonNull(observation, "observation");
        if (preserved != null) return false;
        List<SurveySample> previous = columns.getOrDefault(column, List.of());
        List<SurveySample> next = new ArrayList<>(previous.size() + 2);
        for (SurveySample old : previous) {
            if (old.observedMaxY16() <= observation.observedMinY16()
                || old.observedMinY16() >= observation.observedMaxY16()) {
                next.add(old);
            } else {
                if (old.observedMinY16() < observation.observedMinY16()) {
                    next.add(old.withObservedRange(old.observedMinY16(), observation.observedMinY16()));
                }
                if (old.observedMaxY16() > observation.observedMaxY16()) {
                    next.add(old.withObservedRange(observation.observedMaxY16(), old.observedMaxY16()));
                }
            }
        }
        next.add(observation);
        List<SurveySample> normalized = canonical(next);
        if (normalized.equals(previous)) return false;
        long nextRevision = Math.incrementExact(revision);
        replaceColumn(column, normalized);
        changedAt.put(column, nextRevision);
        revision = nextRevision;
        dirty.run();
        return true;
    }

    public boolean observeWorld(int worldX, int worldZ, SurveySample observation) {
        int key = columnKey(worldX, worldZ);
        return key >= 0 && observe(key, observation);
    }

    /** Returns only an actually observed range, never the nearest floor or another subcolumn. */
    public synchronized SurveySample lookup(int column, int viewY16) {
        metadata.checkColumn(column);
        List<SurveySample> samples = columns.get(column);
        if (samples == null) return null;
        return findAtHeight(samples, viewY16);
    }

    /** Selects one observed sample per matching column without copying or normalizing vertical history. */
    public synchronized Map<Integer, SurveySample> projection(int viewY16) {
        Map<Integer, SurveySample> result = new LinkedHashMap<>();
        columns.forEach((key, samples) -> {
            SurveySample sample = findAtHeight(samples, viewY16);
            if (sample != null) result.put(key, sample);
        });
        return Collections.unmodifiableMap(result);
    }

    /** Paint only recorded surfaces, with vertical context; observer Y is not a destructive display mask. */
    public synchronized Map<Integer, SurveySample> overviewProjection(int viewY16, int aboveY16, int belowY16) {
        if (projectionIndex == null) projectionIndex = new SurveyProjection.Index(projectionFloors(0));
        return overviewProjection(viewY16, aboveY16, belowY16, 0, projectionIndex.select(viewY16, aboveY16, belowY16));
    }

    public synchronized List<SurveyProjection.Floor> projectionFloors(int page) {
        if (cachedFloors != null && cachedFloorPage == page) return cachedFloors;
        List<SurveyProjection.Floor> result = new ArrayList<>();
        columns.forEach((key, samples) -> {
            for (SurveySample sample : samples) if (sample.kind() == SurveySample.Kind.FLOOR || sample.kind() == SurveySample.Kind.FLUID)
                result.add(new SurveyProjection.Floor(page, key, metadata.worldX(key), metadata.worldZ(key),
                    sample.displayY16(), sample.geometryMinY16(), sample.geometryMaxY16(),
                    sample.kind() == SurveySample.Kind.FLUID, sample.upperBoundaryKnown()));
        });
        cachedFloorPage = page; cachedFloors = List.copyOf(result); return cachedFloors;
    }

    /** The client passes an index of this book's received pages so slopes continue across paper edges. */
    public synchronized Map<Integer, SurveySample> overviewProjection(int viewY16, int aboveY16, int belowY16,
            int page, SurveyProjection.Selection selection) {
        Map<Integer, SurveySample> result = new LinkedHashMap<>();
        columns.forEach((key, samples) -> {
            SurveySample exact = findAtHeight(samples, viewY16);
            // A recently observed wall at this height must not be replaced by an older floor behind it.
            if (exact != null && exact.kind() == SurveySample.Kind.WALL) { result.put(key, exact); return; }
            SurveySample best = null; long bestRank = Long.MAX_VALUE;
            for (SurveySample sample : samples) {
                if (sample.kind() != SurveySample.Kind.FLOOR && sample.kind() != SurveySample.Kind.FLUID) continue;
                if (!selection.allows(page, key, sample.displayY16(), sample.geometryMinY16(), sample.geometryMaxY16())) continue;
                long rank = SurveyProjection.rank(sample.displayY16(), sample.geometryMinY16(), sample.geometryMaxY16(),
                    viewY16, aboveY16, belowY16, sample.observedMinY16(), sample.observedMaxY16());
                if (rank < bestRank) { best = sample; bestRank = rank; }
            }
            if (best != null) result.put(key, best);
            else if (exact != null && exact.kind() != SurveySample.Kind.FLOOR && exact.kind() != SurveySample.Kind.FLUID)
                result.put(key, exact);
        });
        return Collections.unmodifiableMap(result);
    }

    private static SurveySample findAtHeight(List<SurveySample> samples, int viewY16) {
        int low = 0, high = samples.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            SurveySample sample = samples.get(middle);
            if (viewY16 < sample.observedMinY16()) high = middle - 1;
            else if (viewY16 >= sample.observedMaxY16()) low = middle + 1;
            else return sample;
        }
        return null;
    }
    public SurveySample lookupWorld(int worldX, int worldZ, int viewY16) {
        int key = columnKey(worldX, worldZ);
        return key < 0 ? null : lookup(key, viewY16);
    }
    public synchronized List<SurveySample> column(int key) {
        metadata.checkColumn(key);
        return columns.getOrDefault(key, List.of());
    }

    public synchronized Snapshot fullSnapshot() { return snapshot(-1); }
    /** Includes deletion entries in deltas, without retaining another snapshot. */
    public synchronized int snapshotColumnCount(long sinceRevision) {
        if (sinceRevision < 0 || sinceRevision > revision) return columns.size();
        int count = 0;
        for (long changed : changedAt.values()) if (changed > sinceRevision) count++;
        return count;
    }
    public synchronized Snapshot snapshot(long sinceRevision) {
        boolean reset = sinceRevision < 0 || sinceRevision > revision;
        if (reset && cachedFullSnapshot != null && cachedFullSnapshot.revision() == revision) return cachedFullSnapshot;
        Map<Integer, List<SurveySample>> result = new TreeMap<>();
        if (reset) result.putAll(columns);
        else changedAt.forEach((column, changed) -> {
            if (changed > sinceRevision) result.put(column, columns.getOrDefault(column, List.of()));
        });
        Snapshot snapshot = new Snapshot(metadata, reset ? -1 : sinceRevision, revision, reset, new StoredColumns(metadata, reset, result));
        if (reset) cachedFullSnapshot = snapshot;
        return snapshot;
    }

    /** Predict a graph change before replacing the last displayed geometry snapshot. */
    public synchronized boolean changesTopology(Snapshot update) {
        if (!metadata.equals(update.metadata()) || !update.reset() && update.baseRevision() != revision) return false;
        if (update.reset()) for (var entry : columns.entrySet())
            if (!update.columns().containsKey(entry.getKey()) && !sameTopology(entry.getValue(), List.of())) return true;
        for (var entry : update.columns().entrySet())
            if (!sameTopology(columns.getOrDefault(entry.getKey(), List.of()), entry.getValue())) return true;
        return false;
    }
    /** Atomic client commit. A missing delta base is rejected without modifying any data. */
    public synchronized boolean apply(Snapshot update) {
        Objects.requireNonNull(update, "update");
        if (!metadata.equals(update.metadata())) throw new IllegalArgumentException("Survey metadata mismatch");
        if (preserved != null || (!update.reset() && update.baseRevision() != revision)) return false;
        // Snapshot construction has validated every entry. Commit under this lock,
        // changing only supplied columns instead of copying the entire history twice.
        if (update.reset()) {
            for (int column : new ArrayList<>(columns.keySet()))
                if (!update.columns().containsKey(column)) replaceColumn(column, List.of());
            changedAt.clear();
        }
        update.columns().forEach((column, samples) -> {
            replaceColumn(column, samples);
            changedAt.put(column, update.revision());
        });
        revision = update.revision();
        cachedFullSnapshot = null;
        return true;
    }

    private void replaceColumn(int column, List<SurveySample> next) {
        List<SurveySample> previous = columns.getOrDefault(column, List.of());
        if (previous.equals(next)) return;
        if (!sameTopology(previous, next)) { topologyRevision++; projectionIndex = null; cachedFloors = null; }
        sampleCount += next.size() - previous.size();
        if (next.isEmpty()) columns.remove(column); else columns.put(column, next);
        contentVersion++; cachedFullSnapshot = null;
    }
    /** Observation intervals, colour and biome are rendering inputs, not graph edges. */
    private static boolean sameTopology(List<SurveySample> left, List<SurveySample> right) {
        for (SurveySample sample : left) if (graphFloor(sample) && !containsTopology(right, sample)) return false;
        for (SurveySample sample : right) if (graphFloor(sample) && !containsTopology(left, sample)) return false;
        return true;
    }
    private static boolean graphFloor(SurveySample sample) {
        return sample.kind() == SurveySample.Kind.FLUID || sample.kind() == SurveySample.Kind.FLOOR
            && (!sample.upperBoundaryKnown() || (long) sample.geometryMaxY16() - sample.displayY16() >= SurveyProjection.MIN_PASSAGE_Y16);
    }
    private static boolean containsTopology(List<SurveySample> values, SurveySample sample) {
        for (SurveySample other : values) if (graphFloor(other) && other.kind() == sample.kind()
            && other.displayY16() == sample.displayY16()
            && other.geometryMinY16() == sample.geometryMinY16()
            && other.geometryMaxY16() == sample.geometryMaxY16()
            && other.upperBoundaryKnown() == sample.upperBoundaryKnown()) return true;
        return false;
    }

    public synchronized SurveyLayer copy() { return copy(NO_DIRTY); }
    public synchronized SurveyLayer copy(Runnable newDirty) {
        SurveyLayer copy = new SurveyLayer(metadata, newDirty);
        copy.columns.putAll(columns); // Lists and samples are immutable; mutable indexes are independent.
        copy.changedAt.putAll(changedAt);
        copy.revision = revision;
        copy.sampleCount = sampleCount; copy.contentVersion = contentVersion; copy.topologyRevision = topologyRevision;
        copy.bookmark = bookmark;
        copy.preserved = preserved == null ? null : preserved.copy();
        copy.preservedReason = preservedReason;
        return copy;
    }

    /** Scaling preserves page identity, but none of the old grid's observations are fabricated. */
    public SurveyLayer emptyScaled(Metadata target, Runnable newDirty) {
        return new SurveyLayer(target, newDirty);
    }

    public synchronized CompoundTag save() {
        if (preserved != null) return preserved.copy();
        CompoundTag out = new CompoundTag();
        out.putInt("version", FORMAT_VERSION);
        out.putInt("scale", scale());
        out.putInt("center_x", centerX());
        out.putInt("center_z", centerZ());
        out.putString("dimension", dimension().toString());
        out.putLong("revision", revision);
        if (bookmark != null) {
            CompoundTag view = new CompoundTag();
            view.putBoolean("cave", bookmark.cave());
            view.putInt("anchor_y16", bookmark.anchorY16());
            view.putLong("revision", bookmark.revision());
            out.put("bookmark", view);
        }
        Map<BlockState, Integer> states = new LinkedHashMap<>();
        Map<ResourceLocation, Integer> biomes = new LinkedHashMap<>();
        ListTag columnTags = new ListTag();
        columns.forEach((key, samples) -> {
            CompoundTag column = new CompoundTag();
            column.putInt("key", key);
            column.putLong("changed", changedAt.getOrDefault(key, revision));
            int[] data = new int[Math.multiplyExact(samples.size(), SAMPLE_STRIDE)];
            long[] positions = new long[samples.size()];
            for (int i = 0; i < samples.size(); i++) {
                SurveySample sample = samples.get(i);
                int offset = i * SAMPLE_STRIDE;
                data[offset] = sample.observedMinY16();
                data[offset + 1] = sample.observedMaxY16();
                data[offset + 2] = sample.geometryMinY16();
                data[offset + 3] = sample.geometryMaxY16();
                data[offset + 4] = sample.floorY16();
                data[offset + 5] = sample.state() == null ? -1 : states.computeIfAbsent(sample.state(), s -> states.size());
                data[offset + 6] = biomes.computeIfAbsent(sample.biome(), b -> biomes.size());
                data[offset + 7] = sample.vanillaShade();
                data[offset + 8] = sample.kind().ordinal();
                data[offset + 9] = (sample.lowerBoundaryKnown() ? 1 : 0) | (sample.upperBoundaryKnown() ? 2 : 0);
                positions[i] = sample.position();
            }
            column.putIntArray("samples", data);
            column.putLongArray("positions", positions);
            columnTags.add(column);
        });
        ListTag stateTags = new ListTag();
        states.keySet().forEach(state -> stateTags.add(NbtUtils.writeBlockState(state)));
        ListTag biomeTags = new ListTag();
        biomes.keySet().forEach(biome -> biomeTags.add(StringTag.valueOf(biome.toString())));
        out.put("state_palette", stateTags);
        out.put("biome_palette", biomeTags);
        out.put("columns", columnTags);
        return out;
    }

    /** Strict decoder: never guesses a partial history or silently drops an invalid column. */
    public static SurveyLayer load(CompoundTag tag) {
        require(tag, "version", Tag.TAG_INT);
        if (tag.getInt("version") != FORMAT_VERSION) throw new IllegalArgumentException("Unsupported survey format");
        require(tag, "scale", Tag.TAG_INT);
        require(tag, "center_x", Tag.TAG_INT);
        require(tag, "center_z", Tag.TAG_INT);
        require(tag, "dimension", Tag.TAG_STRING);
        require(tag, "revision", Tag.TAG_LONG);
        Metadata metadata = new Metadata(tag.getInt("scale"), tag.getInt("center_x"), tag.getInt("center_z"),
            resource(tag.getString("dimension"), "dimension"));
        SurveyLayer layer = new SurveyLayer(metadata);
        layer.revision = tag.getLong("revision");
        if (layer.revision < 0) throw new IllegalArgumentException("Negative survey revision");
        if (tag.contains("bookmark")) {
            require(tag, "bookmark", Tag.TAG_COMPOUND);
            CompoundTag view = tag.getCompound("bookmark");
            require(view, "cave", Tag.TAG_BYTE);
            require(view, "anchor_y16", Tag.TAG_INT);
            require(view, "revision", Tag.TAG_LONG);
            byte cave = view.getByte("cave");
            if (cave < 0 || cave > 1) throw new IllegalArgumentException("Invalid bookmark cave flag");
            layer.bookmark = new Bookmark(cave == 1, view.getInt("anchor_y16"), view.getLong("revision"));
        }
        ListTag stateTags = list(tag, "state_palette", Tag.TAG_COMPOUND);
        ListTag biomeTags = list(tag, "biome_palette", Tag.TAG_STRING);
        ListTag columnTags = list(tag, "columns", Tag.TAG_COMPOUND);
        if (columnTags.size() > metadata.columnCount()) throw new IllegalArgumentException("Too many survey columns");
        List<BlockState> states = new ArrayList<>(stateTags.size());
        for (int i = 0; i < stateTags.size(); i++) states.add(readState(stateTags.getCompound(i)));
        List<ResourceLocation> biomes = new ArrayList<>(biomeTags.size());
        for (int i = 0; i < biomeTags.size(); i++) biomes.add(resource(biomeTags.getString(i), "biome"));
        for (int i = 0; i < columnTags.size(); i++) {
            CompoundTag column = columnTags.getCompound(i);
            require(column, "key", Tag.TAG_INT);
            require(column, "changed", Tag.TAG_LONG);
            require(column, "samples", Tag.TAG_INT_ARRAY);
            require(column, "positions", Tag.TAG_LONG_ARRAY);
            int key = column.getInt("key");
            metadata.checkColumn(key);
            if (layer.columns.containsKey(key)) throw new IllegalArgumentException("Duplicate survey column");
            long changed = column.getLong("changed");
            if (changed < 0 || changed > layer.revision) throw new IllegalArgumentException("Invalid column revision");
            int[] data = column.getIntArray("samples");
            long[] positions = column.getLongArray("positions");
            if (data.length == 0 || data.length % SAMPLE_STRIDE != 0 || data.length / SAMPLE_STRIDE != positions.length) {
                throw new IllegalArgumentException("Invalid survey sample array lengths");
            }
            List<SurveySample> samples = new ArrayList<>(positions.length);
            for (int j = 0; j < positions.length; j++) {
                int offset = j * SAMPLE_STRIDE;
                int stateIndex = data[offset + 5], biomeIndex = data[offset + 6];
                int shade = data[offset + 7], kind = data[offset + 8], flags = data[offset + 9];
                if (stateIndex < -1 || stateIndex >= states.size() || biomeIndex < 0 || biomeIndex >= biomes.size()
                    || shade < 0 || shade > 3 || kind < 0 || kind >= SurveySample.Kind.values().length
                    || flags < 0 || flags > 3) throw new IllegalArgumentException("Invalid survey sample palette or flags");
                samples.add(new SurveySample(data[offset], data[offset + 1], data[offset + 2], data[offset + 3],
                    data[offset + 4], stateIndex < 0 ? null : states.get(stateIndex), biomes.get(biomeIndex), positions[j],
                    (byte) shade, SurveySample.Kind.values()[kind], (flags & 1) != 0, (flags & 2) != 0));
            }
            layer.replaceColumn(key, canonical(samples));
            layer.changedAt.put(key, changed);
        }
        return layer;
    }

    /** Keeps unreadable/future history opaque and read-only, including its dynamic identity. */
    public static SurveyLayer loadPreserving(CompoundTag tag, Metadata expected, Runnable dirty) {
        try {
            SurveyLayer decoded = load(tag);
            if (!decoded.metadata.equals(expected)) throw new IllegalArgumentException("Saved survey metadata does not match map");
            return decoded.copy(dirty);
        } catch (RuntimeException invalid) {
            SurveyLayer opaque = new SurveyLayer(expected, dirty);
            opaque.preserved = tag.copy();
            opaque.preservedReason = invalid.getClass().getSimpleName() + ": " + invalid.getMessage();
            return opaque;
        }
    }

    /** Stable decoded-history diagnostic, excluding book view and revisions. Null means opaque unreadable data. */
    public synchronized String contentHash() {
        if (preserved != null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                hashString(out, "accurate_maps_survey_content_v1");
                out.writeInt(scale());
                out.writeInt(centerX());
                out.writeInt(centerZ());
                hashString(out, dimension().toString());
                out.writeInt(columns.size());
                for (Map.Entry<Integer, List<SurveySample>> column : columns.entrySet()) {
                    out.writeInt(column.getKey());
                    out.writeInt(column.getValue().size());
                    for (SurveySample sample : column.getValue()) {
                        out.writeInt(sample.observedMinY16());
                        out.writeInt(sample.observedMaxY16());
                        out.writeInt(sample.geometryMinY16());
                        out.writeInt(sample.geometryMaxY16());
                        out.writeInt(sample.floorY16());
                        out.writeBoolean(sample.lowerBoundaryKnown());
                        out.writeBoolean(sample.upperBoundaryKnown());
                        out.writeByte(sample.vanillaShade());
                        hashString(out, sample.kind().name());
                        out.writeLong(sample.position());
                        hashString(out, sample.biome().toString());
                        out.writeBoolean(sample.state() != null);
                        if (sample.state() != null) {
                            CompoundTag state = NbtUtils.writeBlockState(sample.state());
                            hashString(out, state.getString("Name"));
                            CompoundTag properties = state.getCompound("Properties");
                            out.writeInt(properties.size());
                            for (String name : new TreeSet<>(properties.getAllKeys())) {
                                hashString(out, name);
                                hashString(out, properties.getString(name));
                            }
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Cannot calculate survey content digest", unavailable);
        }
    }

    private static void hashString(DataOutputStream out, String text) throws IOException {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    /** Compact, read-only output for bridge diagnostics. */
    public synchronized Map<String, Object> describe() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", FORMAT_VERSION);
        result.put("scale", scale());
        result.put("centerX", centerX());
        result.put("centerZ", centerZ());
        result.put("dimension", dimension().toString());
        result.put("revision", revision);
        result.put("bookmarkRevision", bookmarkRevision());
        if (bookmark != null) {
            result.put("bookmarkCave", bookmark.cave());
            result.put("bookmarkAnchorY16", bookmark.anchorY16());
        }
        result.put("columns", columns.size());
        result.put("samples", sampleCount());
        result.put("writable", writable());
        result.put("contentHash", contentHash());
        if (preservedReason != null) result.put("preservedReason", preservedReason);
        return Collections.unmodifiableMap(result);
    }

    private static List<SurveySample> canonical(List<SurveySample> source) {
        Objects.requireNonNull(source, "samples");
        boolean ordered = true, merge = false;
        SurveySample last = null;
        for (SurveySample sample : source) {
            Objects.requireNonNull(sample, "sample");
            if (last != null) {
                if (sample.observedMinY16() < last.observedMinY16()) { ordered = false; break; }
                if (last.observedMaxY16() > sample.observedMinY16()) throw new IllegalArgumentException("Overlapping stored observations");
                merge |= last.observedMaxY16() == sample.observedMinY16() && last.sameGeometryAndAppearance(sample);
            }
            last = sample;
        }
        if (ordered && !merge) return List.copyOf(source);
        List<SurveySample> sorted = new ArrayList<>(source.size());
        for (SurveySample sample : source) sorted.add(Objects.requireNonNull(sample, "sample"));
        sorted.sort(Comparator.comparingInt(SurveySample::observedMinY16));
        List<SurveySample> result = new ArrayList<>(sorted.size());
        for (SurveySample sample : sorted) {
            if (!result.isEmpty()) {
                SurveySample previous = result.get(result.size() - 1);
                if (previous.observedMaxY16() > sample.observedMinY16()) {
                    throw new IllegalArgumentException("Overlapping stored observations");
                }
                if (previous.observedMaxY16() == sample.observedMinY16() && previous.sameGeometryAndAppearance(sample)) {
                    result.set(result.size() - 1, previous.withObservedRange(previous.observedMinY16(), sample.observedMaxY16()));
                    continue;
                }
            }
            result.add(sample);
        }
        return List.copyOf(result);
    }

    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("Missing or malformed survey field: " + key);
    }
    private static ListTag list(CompoundTag tag, String key, int elementType) {
        require(tag, key, Tag.TAG_LIST);
        ListTag list = (ListTag) tag.get(key);
        if (!list.isEmpty() && list.getElementType() != elementType) throw new IllegalArgumentException("Invalid list type: " + key);
        return list;
    }
    private static ResourceLocation resource(String text, String field) {
        ResourceLocation parsed = ResourceLocation.tryParse(text);
        if (parsed == null) throw new IllegalArgumentException("Invalid " + field + " resource key");
        return parsed;
    }
    private static BlockState readState(CompoundTag tag) {
        require(tag, "Name", Tag.TAG_STRING);
        ResourceLocation id = resource(tag.getString("Name"), "block");
        Block block = BuiltInRegistries.BLOCK.getOptional(id)
            .orElseThrow(() -> new IllegalArgumentException("Unavailable survey block: " + id));
        if (tag.contains("Properties")) {
            require(tag, "Properties", Tag.TAG_COMPOUND);
            CompoundTag properties = tag.getCompound("Properties");
            for (String key : properties.getAllKeys()) {
                require(properties, key, Tag.TAG_STRING);
                Property<?> property = block.getStateDefinition().getProperty(key);
                if (property == null || property.getValue(properties.getString(key)).isEmpty()) {
                    throw new IllegalArgumentException("Unavailable property " + id + "." + key);
                }
            }
        }
        return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag);
    }
}
