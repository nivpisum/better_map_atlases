/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.client;

import java.util.*;
import java.util.function.Predicate;
import com.mojang.blaze3d.platform.NativeImage;
import org.nivpisum.bettermapatlases.dynamic.*;
import org.nivpisum.bettermapatlases.BetterMapAtlases;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import pepjebs.mapatlases.client.MapAtlasesClient;
import pepjebs.mapatlases.item.MapAtlasItem;

/** Per-connection history cache and local projections. Never writes the shared vanilla colors array. */
public final class ClientSurvey {
    private static final int MAX_MAPS = 64, CACHE_SAMPLES = 600_000, MAX_TRANSFER_SAMPLES = 2_000_000;
    private static final Map<Integer, SurveyNetwork.Header> HEADERS = new HashMap<>();
    private static final LinkedHashMap<Integer, Received> MAPS = new LinkedHashMap<>(16, .75f, true);
    private static final Map<Integer, Assembly> ASSEMBLIES = new HashMap<>();
    private static final Map<Integer, Long> REQUESTED = new HashMap<>();
    private static final Set<Integer> RECOVERY = new HashSet<>();
    private static final Map<ResourceLocation, SurveyNetwork.View> VIEWS = new HashMap<>();
    private static final Map<Integer, SurveyNetwork.View> MANUAL = new HashMap<>();
    private static final Map<ResourceLocation, int[]> HEIGHT_BOUNDS = new HashMap<>();
    private static SurveyNetwork.View manualLabel;
    private static Predicate<MapItemSavedData> dynamicKind = data -> ((SurveyAccess) data).accurateMaps$getSurvey(false) != null;
    private static long epoch, committed, rejected, projectionComputations, indexComputations, unchangedTransfers;
    private static long geometryEpoch;
    private static final int ASYNC_FLOORS = 16_384;
    private static final int[] PENDING_PIXELS = new int[16384];
    private static final Map<List<Integer>, ProjectionSlot> PROJECTION_SCOPES = new HashMap<>();
    private static final ProjectionWorker<ProjectionScope> PROJECTION_WORKER = new ProjectionWorker<>(MAX_MAPS);
    private static long renderStamp, membershipEpoch, projectionSequence, backgroundIndexes;
    private static Object bookCollection;
    private static long bookRevision = Long.MIN_VALUE;
    private static List<Integer> bookPages = List.of();
    private static Set<Integer> bookPageSet = Set.of();
    private static List<Integer> manualPages = List.of();
    private ClientSurvey() {}
    public static void setDynamicPredicate(Predicate<MapItemSavedData> predicate) { dynamicKind = Objects.requireNonNull(predicate); }

    public static void header(SurveyNetwork.Header header) {
        SurveyNetwork.Header previous = HEADERS.put(header.id(), header);
        Received received = MAPS.get(header.id());
        if (received != null && !received.layer.metadata().equals(header.metadata())) {
            MAPS.remove(header.id()); ASSEMBLIES.remove(header.id()); REQUESTED.remove(header.id());
            membershipChanged();
        }
        if (!header.equals(previous)) epoch++;
    }
    public static void view(SurveyNetwork.View view) {
        rememberHeightBounds();
        if (!view.equals(VIEWS.put(view.dimension(), view))) epoch++;
    }
    private static void rememberHeightBounds() {
        var level = Minecraft.getInstance().level;
        if (level != null) HEIGHT_BOUNDS.put(level.dimension().location(), new int[]{level.getMinBuildHeight(), level.getMaxBuildHeight() - 1});
    }
    /** A view step is a block coordinate, bounded by the selected dimension rather than the reader's dimension. */
    public static int clampBlockY(ResourceKey<Level> dimension, int y) {
        rememberHeightBounds();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() != null) {
            var level = minecraft.getSingleplayerServer().getLevel(dimension);
            if (level != null) return Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() - 1, y));
        }
        int[] bounds = HEIGHT_BOUNDS.get(dimension.location());
        if (bounds != null) return Math.max(bounds[0], Math.min(bounds[1], y));
        if (minecraft.level != null) {
            var type = minecraft.level.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE).get(dimension.location());
            if (type != null) return Math.max(type.minY(), Math.min(type.minY() + type.height() - 1, y));
        }
        // Unvisited custom remote dimensions may use a differently named type; their registry-wide legal bounds apply.
        return Math.max(net.minecraft.world.level.dimension.DimensionType.MIN_Y,
                Math.min(net.minecraft.world.level.dimension.DimensionType.MAX_Y, y));
    }
    public static int bookmarkBlockY(int[] ids, int fallback) {
        return Math.floorDiv(bookmarkY16(ids, fallback * 16), 16);
    }
    public static int bookmarkY16(int[] ids, int fallback) {
        SurveyLayer.Bookmark newest = null;
        for (int id : ids) {
            SurveyNetwork.Header header = HEADERS.get(id);
            SurveyLayer.Bookmark candidate = header == null ? null : header.bookmark();
            if (candidate != null && (newest == null || candidate.revision() > newest.revision())) newest = candidate;
        }
        return newest == null ? fallback : newest.anchorY16();
    }
    /** Actual viewer context, independent of the atlas's temporary browsing choice. */
    public static Integer liveY16(ResourceKey<Level> dimension) {
        SurveyNetwork.View view = VIEWS.get(dimension.location());
        return view == null ? null : view.anchorY16();
    }
    /** Atlas needs a height only to choose its Auto slice; the page projects independently below. */
    public static double viewY(ResourceKey<Level> dimension) {
        if (manualLabel != null && manualLabel.dimension().equals(dimension.location()))
            return manualLabel.cave() ? manualLabel.anchorY16() / 16.0 : Double.POSITIVE_INFINITY;
        SurveyNetwork.View view = VIEWS.get(dimension.location());
        if (view != null) return view.cave() ? view.anchorY16() / 16.0 : Double.POSITIVE_INFINITY;
        return Double.NaN;
    }
    private static SurveyNetwork.View pageView(int id, MapItemSavedData data) {
        SurveyNetwork.View chosen = MANUAL.get(id);
        if (chosen != null) return chosen;
        Minecraft minecraft = Minecraft.getInstance();
        SurveyNetwork.View view = VIEWS.get(data.dimension.location());
        // A different dimension uses the layer recorded in the physical page, including after handoff.
        if (minecraft.level != null && minecraft.level.dimension().equals(data.dimension) && view != null && view.cave()) return view;
        SurveyNetwork.Header header = HEADERS.get(id);
        SurveyLayer.Bookmark bookmark = header == null ? null : header.bookmark();
        return bookmark == null ? null : new SurveyNetwork.View(data.dimension.location(), true, bookmark.anchorY16(), bookmark.revision());
    }
    /** Heights come only from the IDs in this open book, never from a player-global exploration journal. */
    public static int[] historyHeights(int[] ids) {
        TreeSet<Integer> heights = new TreeSet<>();
        for (int id : ids) {
            Received received = MAPS.get(id);
            if (received == null) {
                // Visible MapRenderer pages request their history. Listing a book must not stream every distant page.
                SurveyNetwork.Header header = HEADERS.get(id);
                if (header != null && header.bookmark() != null && header.bookmark().cave()) heights.add(header.bookmark().anchorY16());
                continue;
            }
            if (received.heights == null) {
                TreeSet<Integer> pageHeights = new TreeSet<>();
                for (List<SurveySample> column : received.layer.fullSnapshot().columns().values()) for (SurveySample sample : column) {
                    if (sample.kind() == SurveySample.Kind.WALL) continue;
                    int anchor = sample.kind() == SurveySample.Kind.OPEN_DEPTH
                        ? (int) Math.floorDiv((long) sample.observedMinY16() + sample.observedMaxY16() - 1, 2)
                        : Math.max(sample.observedMinY16(), Math.min(sample.observedMaxY16() - 1, sample.displayY16()));
                    pageHeights.add(anchor);
                }
                received.heights = pageHeights.stream().mapToInt(Integer::intValue).toArray();
            }
            for (int height : received.heights) heights.add(height);
        }
        return heights.stream().mapToInt(Integer::intValue).toArray();
    }
    public static void showHistory(ResourceKey<Level> dimension, int[] ids, int y16) { choose(dimension, ids, true, y16); }
    public static void showSurface(ResourceKey<Level> dimension, int[] ids) { choose(dimension, ids, false, 0); }
    private static void choose(ResourceKey<Level> dimension, int[] ids, boolean cave, int y16) {
        SurveyNetwork.View previous = manualLabel == null ? VIEWS.get(dimension.location()) : manualLabel;
        if (previous == null || !previous.dimension().equals(dimension.location()) || previous.cave() != cave
                || cave && previous.anchorY16() != y16) epoch++;
        MANUAL.clear(); manualLabel = new SurveyNetwork.View(dimension.location(), cave, y16, epoch);
        for (int id : ids) MANUAL.put(id, manualLabel);
        List<Integer> nextPages = MANUAL.keySet().stream().sorted().toList();
        if (!nextPages.equals(manualPages)) manualPages = nextPages;
    }
    public static void followPlayer() {
        if (manualLabel == null && MANUAL.isEmpty()) return;
        SurveyNetwork.View actual = VIEWS.get(manualLabel == null ? null : manualLabel.dimension());
        if (actual == null || manualLabel == null || actual.cave() != manualLabel.cave()
                || actual.cave() && actual.anchorY16() != manualLabel.anchorY16()) epoch++;
        MANUAL.clear(); manualLabel = null;
        if (!manualPages.isEmpty()) manualPages = List.of();
    }
    public static void accept(SurveyNetwork.Part part) {
        Assembly assembly = ASSEMBLIES.get(part.mapId());
        if (part.part() == 0) {
            if (assembly != null && assembly.transfer >= part.transfer()) return;
            Received current = MAPS.get(part.mapId());
            if (current != null && current.transfer >= part.transfer()) return;
            assembly = new Assembly(part);
            ASSEMBLIES.put(part.mapId(), assembly);
        }
        if (assembly == null || !assembly.add(part)) {
            ASSEMBLIES.remove(part.mapId()); rejected++; request(part.mapId(), true, true); return;
        }
        if (!part.last()) return;
        ASSEMBLIES.remove(part.mapId());
        Received received = MAPS.get(part.mapId());
        SurveyLayer layer = received == null ? (part.reset() ? new SurveyLayer(part.metadata()) : null) : received.layer;
        if (layer == null || !layer.metadata().equals(part.metadata())) { rejected++; request(part.mapId(), true, true); return; }
        try {
            SurveyLayer.Snapshot snapshot = new SurveyLayer.Snapshot(part.metadata(), part.baseRevision(), part.revision(), part.reset(), assembly.columns);
            long content = layer.contentVersion(), topology = layer.topologyRevision();
            // Preserve the latest colours on the previous geometry before a new
            // shape replaces them. A pending rebuild must never revert old colours.
            SurveyLayer previous = received != null && !PROJECTION_SCOPES.isEmpty() && layer.changesTopology(snapshot) ? layer.copy() : null;
            if (!layer.apply(snapshot)) { rejected++; request(part.mapId(), true, true); return; }
            if (previous != null) for (ProjectionSlot slot : PROJECTION_SCOPES.values()) {
                if (slot.ready != null) slot.ready.advance(part.mapId(), previous);
                for (ProjectionCapture capture : slot.captures) if (!capture.ticket.cancelled) capture.advance(part.mapId(), previous);
            }
            boolean fresh = received == null;
            if (fresh) { received = new Received(layer, part.transfer()); MAPS.put(part.mapId(), received); membershipChanged(); }
            else received.transfer = part.transfer();
            if (content != layer.contentVersion()) { received.heights = null; epoch++; }
            else if (!fresh) unchangedTransfers++;
            if (topology != layer.topologyRevision()) geometryEpoch++;
            committed++;
            RECOVERY.remove(part.mapId());
            trim(part.mapId());
        } catch (IllegalArgumentException malformed) { rejected++; request(part.mapId(), true, true); }
    }
    private static void trim(int keep) {
        int total = MAPS.values().stream().mapToInt(r -> r.layer.sampleCount()).sum();
        Iterator<Map.Entry<Integer, Received>> it = MAPS.entrySet().iterator();
        while ((MAPS.size() > MAX_MAPS || total > CACHE_SAMPLES) && it.hasNext()) {
            Map.Entry<Integer, Received> entry = it.next();
            if (entry.getKey() == keep) continue;
            total -= entry.getValue().layer.sampleCount(); it.remove(); epoch++; geometryEpoch++; membershipChanged();
        }
    }
    private static void membershipChanged() {
        membershipEpoch++; PROJECTION_SCOPES.clear(); PROJECTION_WORKER.reset();
        for (Received page : MAPS.values()) page.visibilitySource = null;
    }
    private static void request(int id, boolean full, boolean urgent) {
        if (full && urgent) RECOVERY.add(id);
        if (RECOVERY.contains(id)) { full = true; urgent = true; }
        long now = System.nanoTime(), previous = REQUESTED.getOrDefault(id, 0L);
        long interval = urgent ? 500_000_000L : 4_000_000_000L;
        if (now - previous < interval || Minecraft.getInstance().getConnection() == null) return;
        Received received = MAPS.get(id);
        REQUESTED.put(id, now); SurveyNetwork.request(id, received == null ? -1 : received.layer.revision(), full);
    }
    public static long renderEpoch(int id, MapItemSavedData data) {
        boolean dynamic = HEADERS.containsKey(id) || dynamicKind.test(data);
        if (!dynamic) return -1;
        // Renew demand even while a long transfer is progressing. The server
        // retains its immutable in-flight snapshot and the client's known base.
        request(id, false, false);
        Received received = MAPS.get(id);
        SurveyNetwork.View view = pageView(id, data);
        SurveyProjection.Selection selection = null;
        SurveyLayer rendered = received == null ? null : received.layer;
        if (received != null && view != null) {
            int context = BetterMapAtlases.SURVEY_SCAN_REACH.get() * 16;
            ProjectionScope scope = scope(id, received);
            if (scope == null) {
                if (!cachedFor(received, view.anchorY16(), ClientMaps.resourceEpoch())) {
                    if (received.renderEpoch != Long.MIN_VALUE || !sameView(received.renderView, view)) {
                        received.renderEpoch = Long.MIN_VALUE; received.renderView = view;
                        received.renderVisibility = null; received.renderStamp = ++renderStamp;
                    }
                } else if (received.renderEpoch == Long.MIN_VALUE || !sameView(received.renderView, view)) {
                    received.renderEpoch = received.geometry; received.renderView = view;
                    received.renderVisibility = received.pixelVisibility; received.renderStamp = ++renderStamp;
                }
                return received.renderStamp == 0 ? -(epoch + 1) : received.renderStamp;
            }
            rendered = scope.layer(id, received);
            selection = scope.at(view.anchorY16(), context, support(id, received, view.anchorY16()));
        }
        if (received == null) return -(epoch + 1);
        short[] visibility = selection == null ? null : visibility(id, received, selection);
        long content = rendered.contentVersion();
        if (received.renderEpoch != content || !sameView(received.renderView, view) || received.renderVisibility != visibility) {
            received.renderEpoch = content; received.renderView = view;
            received.renderVisibility = visibility; received.renderStamp = ++renderStamp;
        }
        if (received.uploadedContentVersion == content && received.uploadedVisibility == visibility
                && view != null && received.uploadedY16 == view.anchorY16()) received.uploadedRevision = rendered.revision();
        return received.renderStamp;
    }
    private static boolean sameView(SurveyNetwork.View a, SurveyNetwork.View b) {
        return a == b || a != null && b != null && a.dimension().equals(b.dimension())
            && a.cave() == b.cave() && a.anchorY16() == b.anchorY16();
    }
    private static short[] visibility(int id, Received received, SurveyProjection.Selection selection) {
        if (received.visibilitySource != selection) {
            short[] next = selection.pageVisibility(id);
            if (!Arrays.equals(received.visibility, next)) received.visibility = next;
            received.visibilitySource = selection;
        }
        return received.visibility;
    }
    private static boolean cachedFor(Received received, int y16, long resources) {
        return received.pixels != null && received.y16 == y16 && received.resources == resources
            && received.pixelScopeIds.equals(received.scopeIds);
    }
    private static SurveyProjection.Support support(int id, Received received, int y16) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null
                || !minecraft.level.dimension().location().equals(received.layer.dimension())) return null;
        SurveyNetwork.View view = VIEWS.get(received.layer.dimension());
        int feet = SurveyGeometry.fixed16(minecraft.player.getY());
        return SurveyProjection.readerSupport(y16, view == null ? feet : view.anchorY16(),
            new SurveyProjection.Support(minecraft.player.getBlockX(), minecraft.player.getBlockZ(), feet));
    }
    public static void overlay(int id, MapItemSavedData data, NativeImage image) {
        if (image == null || !(HEADERS.containsKey(id) || dynamicKind.test(data))) return;
        SurveyNetwork.View view = pageView(id, data);
        // Unknown cave observations must not borrow the ordinary surface map.
        for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) image.setPixelRGBA(x, z, 0);
        Received received = MAPS.get(id);
        if (received == null) return;
        if (view == null) { received.uploadedRevision = received.uploadedContentVersion = -1; return; }
        int[] pixels = project(id, received, view.anchorY16());
        if (pixels == PENDING_PIXELS) { received.uploadedRevision = received.uploadedContentVersion = -1; received.uploadedVisibility = null; return; }
        for (int i = 0; i < pixels.length; i++) image.setPixelRGBA(i & 127, i >> 7, pixels[i]);
        received.uploadedRevision = received.pixelRevision;
        received.uploadedContentVersion = received.geometry;
        received.uploadedVisibility = received.pixelVisibility; received.uploadedY16 = received.y16;
    }
    private static ProjectionScope scope(int id, Received received) {
        refreshBook();
        publishProjections();
        List<Integer> source = MANUAL.containsKey(id) ? manualPages : bookPageSet.contains(id) ? bookPages : List.of(id);
        if (received.scopeEpoch != membershipEpoch || received.sourcePages != source) {
            List<Integer> ids = new ArrayList<>();
            for (int page : source) {
                Received other = MAPS.get(page);
                if (other != null && other.layer.scale() == received.layer.scale()
                        && other.layer.dimension().equals(received.layer.dimension())) ids.add(page);
            }
            if (!ids.contains(id)) ids.add(id);
            ids.sort(Integer::compareTo);
            received.scopeIds = List.copyOf(ids); received.sourcePages = source; received.scopeEpoch = membershipEpoch;
        }
        List<Integer> ids = received.scopeIds;
        ProjectionSlot slot = PROJECTION_SCOPES.computeIfAbsent(ids, ignored -> new ProjectionSlot());
        slot.captures.removeIf(capture -> capture.ticket.cancelled);
        ProjectionScope scope = slot.ready;
        if ((scope == null || !scope.current()) && (!slot.matches(ids) || slot.ticket != null && slot.ticket.cancelled)) {
            List<SurveyProjection.Floor> floors = new ArrayList<>();
            Map<Integer, SurveyLayer> captured = new HashMap<>();
            slot.requested = new long[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                int page = ids.get(i); Received current = MAPS.get(page);
                floors.addAll(current.layer.projectionFloors(page));
                captured.put(page, current.freeze()); slot.requested[i] = current.layer.topologyRevision();
            }
            long sequence = ++projectionSequence;
            if (floors.size() < ASYNC_FLOORS) {
                if (slot.ticket != null) slot.ticket.cancelled = true;
                scope = new ProjectionScope(ids, captured, new SurveyProjection.Index(floors), sequence);
                publish(slot, scope); slot.ticket = null;
            } else {
                Map<Integer, SurveyLayer> frozen = Map.copyOf(captured);
                ProjectionCapture capture = new ProjectionCapture(sequence, frozen);
                slot.ticket = PROJECTION_WORKER.submit(ids, cancelled ->
                    new ProjectionScope(ids, frozen, new SurveyProjection.Index(floors, cancelled), sequence));
                capture.ticket = slot.ticket; slot.captures.add(capture);
            }
        }
        return scope;
    }
    private static void publishProjections() {
        for (ProjectionSlot slot : PROJECTION_SCOPES.values()) {
            slot.captures.removeIf(capture -> capture.ticket.cancelled);
            if (slot.ticket != null && slot.ticket.cancelled) { slot.ticket = null; slot.requested = null; }
        }
        ProjectionWorker.Ticket<ProjectionScope> completed;
        while ((completed = PROJECTION_WORKER.poll()) != null) {
            if (completed.cancelled) continue;
            if (completed.failure != null) throw new IllegalStateException("Detached atlas projection failed", completed.failure);
            ProjectionSlot slot = PROJECTION_SCOPES.get(completed.key);
            if (slot != null && (slot.ready == null || completed.value.sequence > slot.ready.sequence)) {
                for (ProjectionCapture capture : slot.captures) if (capture.sequence == completed.value.sequence)
                    capture.appearance.forEach(completed.value::advance);
                publish(slot, completed.value); backgroundIndexes++;
            }
            ProjectionWorker.Ticket<ProjectionScope> applied = completed;
            if (slot != null) slot.captures.removeIf(capture -> capture.ticket == applied || capture.ticket.cancelled);
        }
    }
    private static void publish(ProjectionSlot slot, ProjectionScope scope) {
        if (slot.ready != null) for (int page : scope.ids) {
            SurveyLayer previous = slot.ready.layers.get(page);
            if (previous != null) scope.advance(page, previous);
        }
        slot.ready = scope; indexComputations++;
        // The masks are exact, detached page results; idle pages retain no old graph.
        for (int page : scope.ids) { Received received = MAPS.get(page); if (received != null) received.visibilitySource = null; }
    }
    private static void refreshBook() {
        Minecraft minecraft = Minecraft.getInstance(); var book = MapAtlasesClient.getCurrentActiveAtlas();
        Object collection = minecraft.level != null && book.getItem() instanceof MapAtlasItem
            ? MapAtlasItem.getMaps(book, minecraft.level) : null;
        long version = collection instanceof AtlasCollectionLookup lookup ? lookup.betterMapAtlases$revision() : -1;
        if (collection == bookCollection && version == bookRevision) return;
        List<Integer> ids = collection instanceof AtlasCollectionLookup lookup ? lookup.betterMapAtlases$ids()
            : collection instanceof pepjebs.mapatlases.map_collection.IMapCollection maps
                ? Arrays.stream(maps.getAllIds()).boxed().sorted().toList() : List.of();
        bookCollection = collection; bookRevision = version;
        if (!ids.equals(bookPages)) {
            bookPages = ids; bookPageSet = Set.copyOf(ids); membershipChanged();
        }
    }
    private static int[] project(int id, Received received, int y16) {
        long resources = ClientMaps.resourceEpoch();
        ProjectionScope scope = scope(id, received);
        if (scope == null) return cachedFor(received, y16, resources) ? received.pixels : PENDING_PIXELS;
        List<Integer> scopeIds = scope.ids;
        SurveyLayer layer = scope.layer(id, received);
        int context = BetterMapAtlases.SURVEY_SCAN_REACH.get() * 16;
        SurveyProjection.Selection selection = scope.at(y16, context, support(id, received, y16));
        short[] visibility = visibility(id, received, selection);
        if (received.pixels != null && received.y16 == y16 && received.resources == resources
                && received.geometry == layer.contentVersion()
                && received.pixelVisibility == visibility) { received.pixelRevision = layer.revision(); return received.pixels; }
        Minecraft minecraft = Minecraft.getInstance(); int[] pixels = new int[16384];
        if (minecraft.level == null) return pixels;
        projectionComputations++;
        SurveyLayer.Metadata metadata = layer.metadata();
        var biomes = minecraft.level.registryAccess().registryOrThrow(Registries.BIOME);
        StoredRenderView renderView = new StoredRenderView(minecraft.level.getMinBuildHeight(), minecraft.level.getHeight());
        int[] weight = new int[16384], red = new int[16384], green = new int[16384], blue = new int[16384];
        // Only stored columns are visited, including coarse maps: a single known column never fills its neighbours.
        Map<Integer, SurveySample> projection = layer.overviewProjection(y16, context, context, id, selection);
        for (Map.Entry<Integer, SurveySample> entry : projection.entrySet()) {
            SurveySample sample = entry.getValue();
            int color;
            if (sample.kind() == SurveySample.Kind.OPEN_DEPTH) color = 0xff292421;
            else {
                var biome = biomes.get(sample.biome()); if (biome == null) continue;
                BlockPos pos = BlockPos.of(sample.position()); renderView.set(sample.state(), pos, biome);
                Integer rgb = TextureColors.color(sample.state(), renderView, pos);
                if (rgb == null) color = sample.state().getMapColor(renderView, pos).calculateRGBColor(net.minecraft.world.level.material.MapColor.Brightness.byId(sample.vanillaShade()));
                else color = TextureColors.shade(rgb, sample.vanillaShade());
            }
            if (sample.kind() == SurveySample.Kind.FLOOR) {
                int brightness = cliffBrightness(entry.getKey(), sample.displayY16(), metadata.width(), projection);
                if (brightness != 255) color = (color & 0xff000000)
                    | ((color & 255) * brightness / 255)
                    | (((color >>> 8) & 255) * brightness / 255) << 8
                    | (((color >>> 16) & 255) * brightness / 255) << 16;
            }
            int x = (entry.getKey() % metadata.width()) >> metadata.scale();
            int z = (entry.getKey() / metadata.width()) >> metadata.scale(); int pixel = x + 128 * z;
            // A seen opening without a seen floor is unknown depth, not opaque walkable land.
            int opacity = sample.kind() == SurveySample.Kind.OPEN_DEPTH ? 40 : sample.kind() == SurveySample.Kind.WALL
                ? 255 : selection.alpha(id, entry.getKey(), sample.displayY16(), sample.geometryMinY16(), sample.geometryMaxY16());
            if (opacity == 0) continue;
            weight[pixel] += opacity; red[pixel] += (color & 255) * opacity;
            green[pixel] += ((color >>> 8) & 255) * opacity; blue[pixel] += ((color >>> 16) & 255) * opacity;
        }
        int area = 1 << (2 * metadata.scale());
        for (int i = 0; i < pixels.length; i++) if (weight[i] != 0) {
            int alpha = Math.max(1, Math.min(255, weight[i] / area));
            pixels[i] = alpha << 24 | (blue[i] / weight[i]) << 16 | (green[i] / weight[i]) << 8 | red[i] / weight[i];
        }
        received.y16 = y16; received.resources = resources; received.geometry = layer.contentVersion(); received.pixelRevision = layer.revision();
        received.pixelScopeIds = scopeIds; received.pixelVisibility = visibility; received.pixels = pixels; return pixels;
    }
    /** A one-column relief edge preserves the terrain texture while distinguishing a two-block cliff. */
    private static int cliffBrightness(int key, int height, int width, Map<Integer, SurveySample> projection) {
        int brightness = 255, x = key % width, z = key / width;
        int[] neighbours = {x > 0 ? key - 1 : -1, x + 1 < width ? key + 1 : -1,
            z > 0 ? key - width : -1, z + 1 < width ? key + width : -1};
        for (int neighbour : neighbours) {
            SurveySample other = projection.get(neighbour);
            if (other == null || other.kind() != SurveySample.Kind.FLOOR) continue;
            long delta = (long) other.displayY16() - height;
            if (delta > 24) brightness = Math.min(brightness, 185);
            else if (delta < -24) brightness = Math.min(brightness, 215);
        }
        return brightness;
    }
    public static Map<String, Object> describe(int id) {
        Map<String, Object> result = new LinkedHashMap<>(); Received received = MAPS.get(id); Assembly pending = ASSEMBLIES.get(id);
        result.put("dynamic", HEADERS.containsKey(id)); result.put("present", received != null);
        result.put("layer", received == null ? null : received.layer.describe());
        result.put("pendingRecords", pending == null ? 0 : pending.records); result.put("epoch", epoch);
        result.put("cachedMaps", MAPS.size()); result.put("committedTransfers", committed); result.put("rejectedTransfers", rejected);
        result.put("projectionComputations", projectionComputations);
        result.put("indexComputations", indexComputations); result.put("unchangedTransfers", unchangedTransfers);
        result.put("backgroundIndexes", backgroundIndexes);
        result.put("uploadedRevision", received == null ? -1 : received.uploadedRevision);
        SurveyNetwork.Header header = HEADERS.get(id); result.put("bookmark", header == null ? null : header.bookmark());
        result.put("views", Map.copyOf(VIEWS)); return result;
    }
    /** Diagnostic projection for two independent heights; does not alter the current view. */
    public static Map<String, Object> pixel(int id, int x, int z, int y16) {
        if (x < 0 || x > 127 || z < 0 || z > 127) throw new IllegalArgumentException("Map pixel out of bounds");
        Received received = MAPS.get(id); if (received == null) return Map.of("present", false);
        int abgr = project(id, received, y16)[x + z * 128];
        return Map.of("present", true, "abgr", String.format("%08x", abgr), "coverage", (abgr >>> 24) / 255.0, "height", y16 / 16.0);
    }
    public static void reset() { HEADERS.clear(); MAPS.clear(); ASSEMBLIES.clear(); REQUESTED.clear(); RECOVERY.clear(); VIEWS.clear(); MANUAL.clear(); HEIGHT_BOUNDS.clear(); manualLabel = null; epoch++; geometryEpoch++; membershipChanged(); bookCollection = null; bookRevision = Long.MIN_VALUE; bookPages = manualPages = List.of(); bookPageSet = Set.of(); committed = rejected = projectionComputations = indexComputations = unchangedTransfers = backgroundIndexes = 0; }
    private static final class Received {
        final SurveyLayer layer; long transfer; int[] pixels, heights; int y16; long resources = -1, geometry = -1;
        List<Integer> scopeIds = List.of();
        List<Integer> pixelScopeIds = List.of();
        List<Integer> sourcePages;
        long scopeEpoch = -1;
        SurveyProjection.Selection visibilitySource;
        short[] visibility, pixelVisibility, renderVisibility, uploadedVisibility;
        SurveyLayer frozen;
        long pixelRevision = -1, uploadedRevision = -1, uploadedContentVersion = -1;
        int uploadedY16;
        long renderEpoch = -1, renderStamp;
        SurveyNetwork.View renderView;
        Received(SurveyLayer layer, long transfer) { this.layer = layer; this.transfer = transfer; }
        SurveyLayer freeze() {
            if (frozen == null || frozen.contentVersion() != layer.contentVersion()) frozen = layer.copy();
            return frozen;
        }
    }
    private static final class ProjectionSlot {
        ProjectionScope ready;
        long[] requested;
        ProjectionWorker.Ticket<ProjectionScope> ticket;
        final List<ProjectionCapture> captures = new ArrayList<>();
        boolean matches(List<Integer> ids) {
            if (requested == null || requested.length != ids.size()) return false;
            for (int i = 0; i < ids.size(); i++) if (requested[i] != MAPS.get(ids.get(i)).layer.topologyRevision()) return false;
            return true;
        }
    }
    /** Only the render thread updates this holder; workers see its immutable base separately. */
    private static final class ProjectionCapture {
        final long sequence;
        final Map<Integer, SurveyLayer> appearance;
        ProjectionWorker.Ticket<ProjectionScope> ticket;
        ProjectionCapture(long sequence, Map<Integer, SurveyLayer> frozen) {
            this.sequence = sequence; appearance = new HashMap<>(frozen);
        }
        void advance(int id, SurveyLayer previous) {
            SurveyLayer captured = appearance.get(id);
            if (captured != null && captured.topologyRevision() == previous.topologyRevision()
                    && captured.contentVersion() < previous.contentVersion()) appearance.put(id, previous);
        }
    }
    private static final class ProjectionScope {
        final List<Integer> ids; final SurveyProjection.Index index;
        final Map<Integer, SurveyLayer> layers;
        final long sequence;
        final long[] topology;
        long checkedGeometryEpoch = -1;
        ProjectionScope(List<Integer> ids, Map<Integer, SurveyLayer> layers, SurveyProjection.Index index, long sequence) {
            this.ids = ids; this.layers = new HashMap<>(layers); this.index = index; this.sequence = sequence; topology = new long[ids.size()];
            for (int i = 0; i < ids.size(); i++) topology[i] = layers.get(ids.get(i)).topologyRevision();
        }
        /** Render-thread publication may advance appearance on the same geometry. */
        void advance(int id, SurveyLayer previous) {
            SurveyLayer captured = layers.get(id);
            if (captured != null && captured.topologyRevision() == previous.topologyRevision()
                    && captured.contentVersion() < previous.contentVersion()) layers.put(id, previous);
        }
        SurveyLayer layer(int id, Received received) {
            SurveyLayer captured = layers.get(id);
            // Colours can advance immediately when this graph still describes the
            // current geometry. Otherwise render one complete computed snapshot.
            return captured.topologyRevision() == received.layer.topologyRevision() ? received.layer : captured;
        }
        boolean current() {
            if (checkedGeometryEpoch == geometryEpoch) return true;
            for (int i = 0; i < ids.size(); i++) {
                Received page = MAPS.get(ids.get(i));
                if (page == null || topology[i] != page.layer.topologyRevision()) return false;
            }
            checkedGeometryEpoch = geometryEpoch; return true;
        }
        SurveyProjection.Selection at(int height, int reach, SurveyProjection.Support support) {
            return index.select(height, reach, reach, support);
        }
    }
    private static final class Assembly {
        final long transfer, base, revision; final boolean reset; final SurveyLayer.Metadata metadata;
        long lastPartAt = System.nanoTime();
        final Map<Integer, List<SurveySample>> columns = new TreeMap<>(); int nextPart, records;
        Assembly(SurveyNetwork.Part first) { transfer = first.transfer(); base = first.baseRevision(); revision = first.revision(); reset = first.reset(); metadata = first.metadata(); }
        boolean add(SurveyNetwork.Part part) {
            if (transfer != part.transfer() || nextPart != part.part() || base != part.baseRevision() || revision != part.revision()
                || reset != part.reset() || !metadata.equals(part.metadata())) return false;
            for (SurveyNetwork.Fragment fragment : part.fragments()) {
                List<SurveySample> column = columns.get(fragment.column());
                if (fragment.start()) { if (column != null) return false; column = new ArrayList<>(); columns.put(fragment.column(), column); }
                if (column == null || (records += fragment.samples().size()) > MAX_TRANSFER_SAMPLES) return false;
                column.addAll(fragment.samples());
            }
            nextPart++; lastPartAt = System.nanoTime(); return true;
        }
    }
}
