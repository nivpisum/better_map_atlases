/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import org.nivpisum.bettermapatlases.BetterMapAtlases;
import org.nivpisum.bettermapatlases.client.ClientSurvey;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

/** Requested pages only, bounded packets/tick and atomic revisioned transfers. No client observations. */
@Mod.EventBusSubscriber(modid = BetterMapAtlases.MOD_ID)
public final class SurveyNetwork {
    private static final String PROTOCOL = "3";
    private static final int RECORDS_PER_PART = SurveyPacketSize.MAX_RECORDS, PARTS_PER_TICK = 2, MAX_ACTIVE = 64;
    private static final SurveySample.Kind[] SAMPLE_KINDS = SurveySample.Kind.values();
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(BetterMapAtlases.MOD_ID, "survey"), () -> PROTOCOL,
        SurveyNetwork::compatible, SurveyNetwork::compatible);
    private static final Map<ServerPlayer, PlayerState> PLAYERS = new WeakHashMap<>();
    private static BiPredicate<ServerPlayer, Integer> atlasAccess = (p, id) -> false;
    private static long transfers;
    private SurveyNetwork() {}
    private static boolean compatible(String v) {
        return PROTOCOL.equals(v);
    }
    public static void register() {
        CHANNEL.registerMessage(0, Header.class, Header::encode, Header::decode, Header::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, Part.class, Part::encode, Part::decode, Part::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, View.class, View::encode, View::decode, View::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(3, Request.class, Request::encode, Request::decode, Request::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
    public static boolean isRemotePresent(ServerPlayer player) { return CHANNEL.isRemotePresent(player.connection.connection); }
    public static void setAtlasAccessCheck(BiPredicate<ServerPlayer, Integer> check) { atlasAccess = Objects.requireNonNull(check); }
    public static void request(int id, boolean full) { request(id, -1, full); }
    public static void request(int id, long knownRevision, boolean full) { CHANNEL.sendToServer(new Request(id, knownRevision, full)); }
    private static void send(ServerPlayer player, Object packet) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet); }

    public static void sync(int id, ServerPlayer player, MapItemSavedData data) {
        SurveyLayer layer = ((SurveyAccess) data).accurateMaps$getSurvey(false);
        if (layer == null || !isRemotePresent(player)) return;
        PlayerState state = PLAYERS.computeIfAbsent(player, p -> new PlayerState());
        Header header = new Header(id, layer.metadata(), layer.bookmark());
        if (!header.equals(state.headers.put(id, header))) send(player, header);
    }
    private static boolean accessible(ServerPlayer player, int id) {
        for (ItemStack stack : player.getInventory().items) if (looseMap(stack, id)) return true;
        for (ItemStack stack : player.getInventory().offhand) if (looseMap(stack, id)) return true;
        for (ItemFrame frame : player.serverLevel().getEntitiesOfClass(ItemFrame.class, player.getBoundingBox().inflate(16)))
            if (looseMap(frame.getItem(), id) && player.hasLineOfSight(frame)) return true;
        return atlasAccess.test(player, id);
    }
    private static boolean looseMap(ItemStack stack, int id) {
        return stack.getItem() instanceof MapItem && Objects.equals(MapItem.getMapId(stack), id);
    }
    private static void requested(ServerPlayer player, Request request) {
        if (player == null || request.id < 0 || !isRemotePresent(player)) return;
        PlayerState state = PLAYERS.computeIfAbsent(player, p -> new PlayerState());
        long tick = player.server.getTickCount();
        if (tick - state.requestWindow >= 20) { state.requestWindow = tick; state.requests = 0; }
        if (++state.requests > MAX_ACTIVE * 2 || !accessible(player, request.id)) return;
        MapItemSavedData map = player.server.overworld().getMapData(MapItem.makeKey(request.id));
        if (map == null) return;
        SurveyLayer layer = ((SurveyAccess) map).accurateMaps$getSurvey(false);
        if (layer == null) return;
        sync(request.id, player, map);
        Stream stream = state.active.get(request.id);
        long known = request.knownRevision;
        if (stream == null || stream.layer != layer) {
            // A replacement layer must not inherit a revision from the old page identity.
            if (stream != null) { known = -1; release(state, stream); }
            if (stream == null && state.active.size() >= MAX_ACTIVE) {
                var oldest = state.active.next(); release(state, oldest.getValue()); state.active.remove(oldest.getKey());
            }
            stream = new Stream(layer, known);
            state.active.put(request.id, stream);
        }
        if (stream.schedule.request(tick, known, request.full, layer.revision())) {
            stream.deferredAt = -1; state.retries++;
        }
    }

    @SubscribeEvent public static void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player) || !isRemotePresent(player)) return;
        PlayerState state = PLAYERS.computeIfAbsent(player, p -> new PlayerState());
        long tick = player.server.getTickCount();
        for (SurveyView view : SurveyRecorder.viewsFor(player)) {
            if (!view.equals(state.views.get(view.dimension()))) {
                send(player, new View(view.dimension(), view.cave(), view.anchorY16(), view.revision()));
                state.views.put(view.dimension(), view);
            }
        }
        SurveyPacketSize.Burst budget = new SurveyPacketSize.Burst(PARTS_PER_TICK);
        // Rotate only candidates examined before the budget stops. Rotating all IDs
        // after exhaustion restores the original order and starves the later pages.
        int candidates = state.active.size();
        while (budget.available() && candidates-- > 0) {
            var candidate = state.active.next();
            int id = candidate.getKey(); Stream stream = candidate.getValue();
            if (stream.schedule.expired(tick)) {
                state.active.remove(id); state.leaseExpirations++; continue;
            }
            boolean due = stream.schedule.due(stream.layer.revision(), tick, BetterMapAtlases.SYNC_INTERVAL.get());
            if (stream.transfer == null && !due) continue;
            // Check immediately before each packet, including unfinished snapshots.
            // Revoking a carried/viewable page cannot leak another 40 ticks of history.
            if (!accessible(player, id)) {
                release(state, stream);
                state.active.remove(id); state.headers.remove(id); state.revoked++; continue;
            }
            if (stream.transfer == null) {
                // Count full/delta entries (including deletions) before copying;
                // do not rescan while unchanged outstanding transfers hold capacity.
                if (stream.deferredAt == state.snapshots.version()) continue;
                int columns = stream.layer.snapshotColumnCount(stream.schedule.sent);
                if (!state.snapshots.canStart(columns)) {
                    stream.deferredAt = state.snapshots.version(); state.snapshotDeferrals++; continue;
                }
                SurveyLayer.Snapshot snapshot = stream.layer.snapshot(stream.schedule.sent);
                if (!state.snapshots.canStart(snapshot.columns().size())) {
                    stream.deferredAt = state.snapshots.version(); state.snapshotDeferrals++; continue;
                }
                state.snapshots.retain(snapshot.columns().size());
                stream.transfer = new Transfer(++transfers, snapshot);
                stream.schedule.started();
                if (stream.transfer.snapshot.reset()) state.fullTransfers++; else state.deltaTransfers++;
            }
            if (stream.transfer != null) {
                Part part = stream.transfer.next(id, budget.remainingBytes());
                if (part == null) {
                    // Keep this candidate first next tick; otherwise equally large
                    // packets can repeatedly lose to the same half of the queue.
                    state.active.defer(id); break;
                }
                int bytes = part.encodedBytes();
                budget.sent(bytes);
                send(player, part); state.parts++; state.records += part.fragments.stream().mapToInt(f -> f.samples.size()).sum();
                state.bytes += bytes; state.largestPart = Math.max(state.largestPart, bytes);
                if (part.last) {
                    stream.schedule.completed(part.revision, tick); release(state, stream); stream.transfer = null;
                }
            }
        }
    }
    private static void release(PlayerState state, Stream stream) {
        if (stream.transfer != null) state.snapshots.release(stream.transfer.snapshot.columns().size());
    }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) PLAYERS.remove(player);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) { PLAYERS.remove(player); SurveyRecorder.forget(player); }
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { PLAYERS.clear(); SurveyRecorder.clear(); }
    public static Map<String, Object> describe(ServerPlayer player) {
        PlayerState state = PLAYERS.get(player);
        if (state == null) return Map.of("present", false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("present", true); result.put("headers", state.headers.size()); result.put("active", state.active.size());
        result.put("parts", state.parts); result.put("records", state.records); result.put("bytes", state.bytes);
        result.put("fullTransfers", state.fullTransfers); result.put("deltaTransfers", state.deltaTransfers);
        result.put("leaseExpirations", state.leaseExpirations); result.put("revoked", state.revoked);
        result.put("retries", state.retries); result.put("snapshotDeferrals", state.snapshotDeferrals);
        result.put("snapshotColumns", state.snapshots.columns()); result.put("snapshotTransfers", state.snapshots.transfers());
        result.put("maxSnapshotColumns", SurveySnapshotBudget.MAX_COLUMNS);
        result.put("partsPerPlayerTick", PARTS_PER_TICK); result.put("recordsPerPart", RECORDS_PER_PART);
        result.put("targetPartBytes", SurveyPacketSize.TARGET_BYTES); result.put("largestPartBytes", state.largestPart);
        result.put("maxBytesPerPlayerTick", SurveyPacketSize.MAX_BYTES);
        return result;
    }
    /** Detached transformed-runtime check: fragmentation, codec, exact deltas and atomic assembly. */
    public static Map<String, Object> runtimeChecks() {
        ResourceLocation dimension = new ResourceLocation("minecraft", "overworld"), biome = new ResourceLocation("minecraft", "plains");
        SurveyLayer source = new SurveyLayer(0, 0, 0, dimension);
        for (int i = 0; i < 2200; i++) source.observe(7, new SurveySample(i * 2, i * 2 + 1, -1024, 8192, -1024,
            null, biome, 0L, (byte) 1, SurveySample.Kind.OPEN_DEPTH, false, false));
        for (int key = 8; key < 1000; key++) source.observe(key, new SurveySample(65 * 16, 69 * 16, 64 * 16, 73 * 16, 64 * 16,
            net.minecraft.world.level.block.Blocks.BLUE_WOOL.defaultBlockState(), biome, 0L, (byte) 1, SurveySample.Kind.FLOOR, true, true));
        source.observe(1000, new SurveySample(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE,
            Integer.MIN_VALUE, null, biome, Long.MIN_VALUE, (byte) 3, SurveySample.Kind.OPEN_DEPTH, true, true));
        ResourceLocation longBiome = new ResourceLocation("bma", "x".repeat(32760));
        source.observe(1001, new SurveySample(-16, 16, -32, 32, -16, null, longBiome,
            Long.MAX_VALUE, (byte) 0, SurveySample.Kind.OPEN_DEPTH, false, true));
        source.observe(1002, new SurveySample(0, 16, 0, 48, 0, net.minecraft.world.level.block.Blocks.BLUE_WOOL.defaultBlockState(),
            biome, 0L, (byte) 2, SurveySample.Kind.WALL, false, true));
        source.observe(1003, new SurveySample(0, 16, 0, 32, 0, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(),
            biome, 0L, (byte) 0, SurveySample.Kind.FLUID, true, false));
        SurveyLayer target = new SurveyLayer(source.metadata());
        int parts = 0, largest = 0;
        long base = -1;
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) source.observe(8, new SurveySample(65 * 16, 69 * 16, 64 * 16, 73 * 16, 64 * 16,
                net.minecraft.world.level.block.Blocks.RED_WOOL.defaultBlockState(), biome, 0L, (byte) 2, SurveySample.Kind.FLOOR, true, true));
            Transfer transfer = new Transfer(pass + 1, source.snapshot(base));
            Map<Integer, List<SurveySample>> assembled = new TreeMap<>();
            Part part;
            do {
                part = transfer.next(12);
                FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                Part decoded;
                try {
                    part.encode(buffer); largest = Math.max(largest, buffer.readableBytes()); decoded = Part.decode(buffer);
                    if (buffer.isReadable() || !part.equals(decoded) || part.encodedBytes() != buffer.writerIndex())
                        throw new IllegalStateException("Survey packet roundtrip or byte accounting");
                    if (part.fragments.stream().mapToInt(f -> Math.max(1, f.samples.size())).sum() > RECORDS_PER_PART
                        || part.encodedBytes() > SurveyPacketSize.MAX_BYTES) throw new IllegalStateException("Unbounded survey part");
                } finally { buffer.release(); }
                for (Fragment fragment : decoded.fragments) {
                    if (fragment.start) {
                        if (assembled.put(fragment.column, new ArrayList<>()) != null) throw new IllegalStateException("Duplicate column start");
                    }
                    List<SurveySample> samples = assembled.get(fragment.column);
                    if (samples == null) throw new IllegalStateException("Missing column fragment base");
                    samples.addAll(fragment.samples);
                }
                parts++;
            } while (!part.last);
            SurveyLayer.Snapshot snapshot = new SurveyLayer.Snapshot(part.metadata, part.baseRevision, part.revision, part.reset, assembled);
            if (!target.apply(snapshot) || !target.fullSnapshot().columns().equals(source.fullSnapshot().columns()))
                throw new IllegalStateException("Fragmented history differs after commit");
            if (pass == 1 && new SurveyLayer(source.metadata()).apply(snapshot)) throw new IllegalStateException("Missing delta base accepted");
            base = source.revision();
        }
        SurveyLayer.Snapshot deletion = new SurveyLayer.Snapshot(source.metadata(), base, base + 1, false, Map.of(7, List.of()));
        Part deletionPart = new Transfer(3, deletion).next(12);
        FriendlyByteBuf deletionBuffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            deletionPart.encode(deletionBuffer); Part decoded = Part.decode(deletionBuffer);
            if (!deletionPart.equals(decoded) || !decoded.last || decoded.fragments.size() != 1
                || !decoded.fragments.get(0).start || !decoded.fragments.get(0).samples.isEmpty()
                || !target.apply(deletion) || !target.column(7).isEmpty()) throw new IllegalStateException("Delta deletion lost");
        } finally { deletionBuffer.release(); }
        // Legal large identifiers in both metadata and a sample still fit the hard
        // bound. They must never be silently truncated to meet the usual target.
        SurveyLayer huge = new SurveyLayer(new SurveyLayer.Metadata(0, 0, 0, new ResourceLocation("bma", "y".repeat(32760))));
        huge.observe(0, source.column(1001).get(0));
        Part largePart = new Transfer(4, huge.fullSnapshot()).next(13);
        FriendlyByteBuf largeBuffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            largePart.encode(largeBuffer);
            if (!largePart.equals(Part.decode(largeBuffer)) || largeBuffer.writerIndex() != largePart.encodedBytes()
                || largePart.encodedBytes() > SurveyPacketSize.MAX_BYTES) throw new IllegalStateException("Large identifier lost");
            largest = Math.max(largest, largePart.encodedBytes());
        } finally { largeBuffer.release(); }
        // 32768 records formerly required 256 player ticks for this one page.
        SurveyLayer bulk = new SurveyLayer(0, 0, 0, dimension);
        for (int key = 0; key < 16384; key++) {
            bulk.observe(key, new SurveySample(0, 16, 0, 16, 0, null, biome, key, (byte) 1, SurveySample.Kind.OPEN_DEPTH, false, false));
            bulk.observe(key, new SurveySample(32, 48, 32, 48, 32, null, biome, key, (byte) 1, SurveySample.Kind.OPEN_DEPTH, false, false));
        }
        Transfer bulkTransfer = new Transfer(5, bulk.fullSnapshot()); int bulkParts = 0, bulkRecords = 0;
        Part bulkPart;
        do {
            bulkPart = bulkTransfer.next(14); bulkParts++;
            bulkRecords += bulkPart.fragments.stream().mapToInt(f -> f.samples.size()).sum();
            if (bulkPart.encodedBytes() > SurveyPacketSize.TARGET_BYTES) throw new IllegalStateException("Typical payload exceeds target");
        } while (!bulkPart.last);
        if (bulkRecords != 32768 || bulkParts >= 64) throw new IllegalStateException("Bulk geometry loss or slow fragmentation");
        return Map.of("passed", true, "parts", parts, "largestPacketBytes", largest, "columns", source.observedColumns(),
            "samples", source.sampleCount(), "bulkParts", bulkParts, "bulkRecords", bulkRecords,
            "checks", List.of("split column assembly", "block state/biome palette codec", "signed height and position extrema",
                "full then delta", "exact base rejection", "empty-column deletion", "byte and record bounds", "legal large identifiers", "bulk throughput"));
    }
    private static final class PlayerState {
        final Map<Integer, Header> headers = new HashMap<>();
        final SurveyStreamSchedule.Queue<Stream> active = new SurveyStreamSchedule.Queue<>();
        final SurveySnapshotBudget snapshots = new SurveySnapshotBudget();
        final Map<ResourceLocation, SurveyView> views = new HashMap<>();
        long requestWindow, parts, records, bytes, fullTransfers, deltaTransfers, leaseExpirations, revoked, retries, snapshotDeferrals;
        int requests, largestPart;
    }
    private static final class Stream {
        final SurveyLayer layer; final SurveyStreamSchedule schedule; Transfer transfer; long deferredAt = -1;
        Stream(SurveyLayer layer, long known) { this.layer = layer; schedule = new SurveyStreamSchedule(known, layer.revision()); }
    }
    private static final class Transfer {
        final long id; final SurveyLayer.Snapshot snapshot;
        final Iterator<Map.Entry<Integer, List<SurveySample>>> columns;
        Map.Entry<Integer, List<SurveySample>> column; int offset, part;
        Transfer(long id, SurveyLayer.Snapshot snapshot) { this.id = id; this.snapshot = snapshot; columns = snapshot.columns().entrySet().iterator(); }
        Part next(int mapId) { return next(mapId, SurveyPacketSize.MAX_BYTES); }
        Part next(int mapId, int byteLimit) {
            List<Fragment> fragments = new ArrayList<>(); Map<ResourceLocation, Integer> biomes = new LinkedHashMap<>();
            int left = RECORDS_PER_PART, units = 0;
            // Two-byte count reserves are exact upper bounds at <=1024 fragments/biomes.
            int bytes = partHeaderBytes(mapId, id, part, snapshot.metadata()) + 4;
            if (bytes > byteLimit) return null;
            int target = Math.min(SurveyPacketSize.TARGET_BYTES, byteLimit);
            boolean filled = false;
            while (!filled && left > 0 && (column != null || columns.hasNext())) {
                if (column == null) { column = columns.next(); offset = 0; }
                int fragmentBytes = SurveyPacketSize.varInt(column.getKey()) + 1 + 2;
                int n = 0;
                while (n < left && offset + n < column.getValue().size()) {
                    SurveySample sample = column.getValue().get(offset + n);
                    Integer biome = biomes.get(sample.biome());
                    int paletteBytes = biome == null ? SurveyPacketSize.utf(sample.biome().toString()) : 0;
                    int candidate = bytes + (n == 0 ? fragmentBytes : 0) + paletteBytes
                        + sampleBytes(sample, biome == null ? biomes.size() : biome);
                    if (candidate > byteLimit || candidate > target && units + n > 0) { filled = true; break; }
                    if (biome == null) biomes.put(sample.biome(), biomes.size());
                    bytes = candidate; n++;
                }
                // Empty delta columns are deletion records and also consume the record budget.
                if (column.getValue().isEmpty()) {
                    int candidate = bytes + fragmentBytes;
                    if (candidate > byteLimit || candidate > target && units > 0) break;
                    bytes = candidate;
                } else if (n == 0) break;
                fragments.add(new Fragment(column.getKey(), offset == 0, List.copyOf(column.getValue().subList(offset, offset + n))));
                offset += n; left -= Math.max(1, n);
                units += Math.max(1, n);
                if (offset == column.getValue().size()) column = null;
            }
            if (fragments.isEmpty() && (column != null || columns.hasNext())) return null;
            return new Part(mapId, id, part++, column == null && !columns.hasNext(), snapshot.metadata(),
                snapshot.baseRevision(), snapshot.revision(), snapshot.reset(), List.copyOf(fragments));
        }
    }

    public record Header(int id, SurveyLayer.Metadata metadata, SurveyLayer.Bookmark bookmark) {
        void encode(FriendlyByteBuf out) {
            out.writeVarInt(id); writeMetadata(out, metadata); out.writeBoolean(bookmark != null);
            if (bookmark != null) { out.writeBoolean(bookmark.cave()); out.writeInt(bookmark.anchorY16()); out.writeLong(bookmark.revision()); }
        }
        static Header decode(FriendlyByteBuf in) {
            int id = bound(in.readVarInt(), Integer.MAX_VALUE); SurveyLayer.Metadata metadata = readMetadata(in);
            return new Header(id, metadata, in.readBoolean() ? new SurveyLayer.Bookmark(in.readBoolean(), in.readInt(), in.readLong()) : null);
        }
        static void handle(Header packet, Supplier<NetworkEvent.Context> ctx) { client(ctx, () -> ClientSurvey.header(packet)); }
    }
    public record Fragment(int column, boolean start, List<SurveySample> samples) {}
    public record Part(int mapId, long transfer, int part, boolean last, SurveyLayer.Metadata metadata,
                       long baseRevision, long revision, boolean reset, List<Fragment> fragments) {
        public void encode(FriendlyByteBuf out) {
            if (encodedBytes() > SurveyPacketSize.MAX_BYTES) throw new IllegalArgumentException("Oversized survey part");
            out.writeVarInt(mapId); out.writeVarLong(transfer); out.writeVarInt(part); out.writeBoolean(last);
            writeMetadata(out, metadata); out.writeLong(baseRevision); out.writeLong(revision); out.writeBoolean(reset);
            Map<ResourceLocation, Integer> biomes = palette(fragments);
            out.writeVarInt(biomes.size());
            for (ResourceLocation biome : biomes.keySet()) out.writeResourceLocation(biome);
            out.writeVarInt(fragments.size());
            for (Fragment fragment : fragments) {
                out.writeVarInt(fragment.column); out.writeBoolean(fragment.start); out.writeVarInt(fragment.samples.size());
                for (SurveySample sample : fragment.samples) writeSample(out, sample, biomes.get(sample.biome()));
            }
        }
        public int encodedBytes() {
            Map<ResourceLocation, Integer> biomes = palette(fragments);
            int bytes = partHeaderBytes(mapId, transfer, part, metadata) + SurveyPacketSize.varInt(biomes.size())
                + SurveyPacketSize.varInt(fragments.size());
            for (ResourceLocation biome : biomes.keySet()) bytes += SurveyPacketSize.utf(biome.toString());
            for (Fragment fragment : fragments) {
                bytes += SurveyPacketSize.varInt(fragment.column) + 1 + SurveyPacketSize.varInt(fragment.samples.size());
                for (SurveySample sample : fragment.samples) bytes += sampleBytes(sample, biomes.get(sample.biome()));
            }
            return bytes;
        }
        public static Part decode(FriendlyByteBuf in) {
            if (in.readableBytes() > SurveyPacketSize.MAX_BYTES) throw new IllegalArgumentException("Oversized survey part");
            int map = bound(in.readVarInt(), Integer.MAX_VALUE); long transfer = in.readVarLong();
            int part = bound(in.readVarInt(), 1_000_000); boolean last = in.readBoolean();
            SurveyLayer.Metadata metadata = readMetadata(in);
            long base = in.readLong(), revision = in.readLong(); boolean reset = in.readBoolean();
            if (transfer < 0 || revision < 0 || (reset ? base != -1 : base < 0 || base > revision)) throw new IllegalArgumentException("Invalid survey revisions");
            int paletteSize = bound(in.readVarInt(), RECORDS_PER_PART);
            List<ResourceLocation> biomes = new ArrayList<>(paletteSize);
            for (int i = 0; i < paletteSize; i++) biomes.add(in.readResourceLocation());
            int count = bound(in.readVarInt(), RECORDS_PER_PART), records = 0;
            List<Fragment> fragments = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int key = in.readVarInt(); metadata.checkColumn(key); boolean start = in.readBoolean();
                int size = bound(in.readVarInt(), RECORDS_PER_PART); records += Math.max(1, size);
                if (records > RECORDS_PER_PART) throw new IllegalArgumentException("Oversized survey part");
                List<SurveySample> samples = new ArrayList<>(size);
                for (int j = 0; j < size; j++) samples.add(readSample(in, biomes));
                fragments.add(new Fragment(key, start, List.copyOf(samples)));
            }
            return new Part(map, transfer, part, last, metadata, base, revision, reset, List.copyOf(fragments));
        }
        static void handle(Part packet, Supplier<NetworkEvent.Context> ctx) { client(ctx, () -> ClientSurvey.accept(packet)); }
    }
    public record View(ResourceLocation dimension, boolean cave, int anchorY16, long revision) {
        void encode(FriendlyByteBuf out) { out.writeResourceLocation(dimension); out.writeBoolean(cave); out.writeInt(anchorY16); out.writeLong(revision); }
        static View decode(FriendlyByteBuf in) { return new View(in.readResourceLocation(), in.readBoolean(), in.readInt(), in.readLong()); }
        static void handle(View packet, Supplier<NetworkEvent.Context> ctx) { client(ctx, () -> ClientSurvey.view(packet)); }
    }
    public record Request(int id, long knownRevision, boolean full) {
        public Request(int id, boolean full) { this(id, -1, full); }
        void encode(FriendlyByteBuf out) { out.writeVarInt(id); out.writeLong(knownRevision); out.writeBoolean(full); }
        static Request decode(FriendlyByteBuf in) {
            int id = bound(in.readVarInt(), Integer.MAX_VALUE); long known = in.readLong();
            if (known < -1) throw new IllegalArgumentException("Invalid known survey revision");
            return new Request(id, known, in.readBoolean());
        }
        static void handle(Request packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get(); context.enqueueWork(() -> requested(context.getSender(), packet)); context.setPacketHandled(true);
        }
    }
    private static void client(Supplier<NetworkEvent.Context> supplier, Runnable action) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> action)); context.setPacketHandled(true);
    }
    private static void writeMetadata(FriendlyByteBuf out, SurveyLayer.Metadata metadata) {
        out.writeByte(metadata.scale()); out.writeInt(metadata.centerX()); out.writeInt(metadata.centerZ()); out.writeResourceLocation(metadata.dimension());
    }
    private static SurveyLayer.Metadata readMetadata(FriendlyByteBuf in) { return new SurveyLayer.Metadata(in.readUnsignedByte(), in.readInt(), in.readInt(), in.readResourceLocation()); }
    private static Map<ResourceLocation, Integer> palette(List<Fragment> fragments) {
        Map<ResourceLocation, Integer> biomes = new LinkedHashMap<>();
        for (Fragment fragment : fragments) for (SurveySample sample : fragment.samples)
            if (!biomes.containsKey(sample.biome())) biomes.put(sample.biome(), biomes.size());
        return biomes;
    }
    private static int partHeaderBytes(int map, long transfer, int part, SurveyLayer.Metadata metadata) {
        return SurveyPacketSize.varInt(map) + SurveyPacketSize.varLong(transfer) + SurveyPacketSize.varInt(part)
            + 1 + 9 + SurveyPacketSize.utf(metadata.dimension().toString()) + 16 + 1;
    }
    private static int sampleBytes(SurveySample sample, int biome) {
        return 9 + SurveyPacketSize.varInt(sample.state() == null ? 0 : Block.getId(sample.state()) + 1)
            + SurveyPacketSize.varInt(biome) + SurveyPacketSize.varInt(SurveyPacketSize.zigzag(sample.observedMinY16()))
            + SurveyPacketSize.varInt(SurveyPacketSize.zigzag(sample.observedMaxY16()))
            + SurveyPacketSize.varInt(SurveyPacketSize.zigzag(sample.geometryMinY16()))
            + SurveyPacketSize.varInt(SurveyPacketSize.zigzag(sample.geometryMaxY16()))
            + SurveyPacketSize.varInt(SurveyPacketSize.zigzag(sample.floorY16()));
    }
    private static void writeSample(FriendlyByteBuf out, SurveySample s, int biome) {
        out.writeVarInt(SurveyPacketSize.zigzag(s.observedMinY16())); out.writeVarInt(SurveyPacketSize.zigzag(s.observedMaxY16()));
        out.writeVarInt(SurveyPacketSize.zigzag(s.geometryMinY16())); out.writeVarInt(SurveyPacketSize.zigzag(s.geometryMaxY16()));
        out.writeVarInt(SurveyPacketSize.zigzag(s.floorY16()));
        out.writeVarInt(s.state() == null ? 0 : Block.getId(s.state()) + 1); out.writeVarInt(biome);
        out.writeLong(s.position()); out.writeByte(s.vanillaShade() | s.kind().ordinal() << 2
            | (s.lowerBoundaryKnown() ? 16 : 0) | (s.upperBoundaryKnown() ? 32 : 0));
    }
    private static SurveySample readSample(FriendlyByteBuf in, List<ResourceLocation> biomes) {
        int min = SurveyPacketSize.unzigzag(in.readVarInt()), max = SurveyPacketSize.unzigzag(in.readVarInt());
        int geometryMin = SurveyPacketSize.unzigzag(in.readVarInt()), geometryMax = SurveyPacketSize.unzigzag(in.readVarInt());
        int floor = SurveyPacketSize.unzigzag(in.readVarInt());
        int state = bound(in.readVarInt(), Block.BLOCK_STATE_REGISTRY.size());
        if (biomes.isEmpty()) throw new IllegalArgumentException("Missing survey biome palette");
        ResourceLocation biome = biomes.get(bound(in.readVarInt(), biomes.size() - 1));
        long position = in.readLong(); int flags = bound(in.readUnsignedByte(), 63); byte shade = (byte) (flags & 3);
        SurveySample.Kind kind = SAMPLE_KINDS[(flags >>> 2) & 3];
        return new SurveySample(min, max, geometryMin, geometryMax, floor, state == 0 ? null : Block.stateById(state - 1), biome,
            position, shade, kind, (flags & 16) != 0, (flags & 32) != 0);
    }
    private static int bound(int n, int maximum) { if (n < 0 || n > maximum) throw new IllegalArgumentException("Invalid survey packet bound"); return n; }
}
