/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases;

import java.util.*;
import java.util.function.Supplier;
import org.nivpisum.bettermapatlases.client.ClientMaps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

public final class MapNetwork {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(BetterMapAtlases.MOD_ID, "layer"), () -> PROTOCOL,
        MapNetwork::compatible, MapNetwork::compatible);
    // Owners are weak and connection-scoped; reconnecting always gets a fresh snapshot.
    private static final Map<ServerPlayer, Map<Integer, Sent>> SENT = new WeakHashMap<>();
    private static boolean compatible(String version) {
        return PROTOCOL.equals(version);
    }
    public static void register() {
        CHANNEL.registerMessage(0, Update.class, Update::encode, Update::decode, Update::handle,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    static void forget(ServerPlayer player) { SENT.remove(player); }
    static void clear() { SENT.clear(); }

    public static void sync(int mapId, ServerPlayer player, MapItemSavedData map) {
        org.nivpisum.bettermapatlases.dynamic.SurveyNetwork.sync(mapId, player, map);
        MapLayer layer = ((MapLayerAccess) map).accurateMaps$getLayer(false);
        if (layer == null || !CHANNEL.isRemotePresent(player.connection.connection)) return;
        Map<Integer, Sent> playerMaps = SENT.computeIfAbsent(player, p -> new HashMap<>());
        Sent sent = playerMaps.get(mapId);
        long tick = player.serverLevel().getGameTime();
        boolean fresh = sent == null || sent.layer != layer;
        if (!fresh && (sent.revision == layer.revision() || tick >= sent.tick && tick - sent.tick < BetterMapAtlases.SYNC_INTERVAL.get())) return;
        Update packet = Update.from(mapId, layer, fresh ? -1 : sent.revision);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        // Workers can invalidate pixels after the snapshot. Never acknowledge a revision we did not send.
        playerMaps.put(mapId, new Sent(layer, packet.revision(), tick));
    }

    private record Sent(MapLayer layer, long revision, long tick) {}

    public record Update(int mapId, boolean reset, long revision, int[] indices, int[] states,
                         long[] positions, List<ResourceLocation> palette, int[] biomes) {
        public static Update from(int id, MapLayer layer, long after) {
            synchronized (layer) { return snapshot(id, layer, after); }
        }

        private static Update snapshot(int id, MapLayer layer, long after) {
            int count = 0;
            for (int i = 0; i < MapLayer.SIZE; i++) if (after < 0 ? layer.state(i) != null : layer.changedAt(i) > after) count++;
            int[] indices = new int[count];
            int[] states = new int[count];
            long[] positions = new long[count];
            int[] biomes = new int[count];
            Map<ResourceLocation, Integer> palette = new LinkedHashMap<>();
            for (int i = 0, n = 0; i < MapLayer.SIZE; i++) {
                if (!(after < 0 ? layer.state(i) != null : layer.changedAt(i) > after)) continue;
                indices[n] = i;
                states[n] = layer.state(i) == null ? 0 : Block.getId(layer.state(i));
                positions[n] = layer.position(i);
                ResourceLocation biome = layer.biome(i);
                biomes[n] = biome == null ? -1 : palette.computeIfAbsent(biome, b -> palette.size());
                n++;
            }
            return new Update(id, after < 0, layer.revision(), indices, states, positions, List.copyOf(palette.keySet()), biomes);
        }

        public void encode(FriendlyByteBuf out) {
            out.writeVarInt(mapId); out.writeBoolean(reset); out.writeVarLong(revision);
            out.writeVarInt(palette.size());
            palette.forEach(out::writeResourceLocation);
            out.writeVarInt(indices.length);
            for (int n = 0; n < indices.length; n++) {
                out.writeShort(indices[n]); out.writeVarInt(states[n]);
                out.writeLong(positions[n]); out.writeVarInt(biomes[n] + 1);
            }
        }

        public static Update decode(FriendlyByteBuf in) {
            int id = in.readVarInt(); boolean reset = in.readBoolean(); long revision = in.readVarLong();
            int paletteCount = bound(in.readVarInt(), MapLayer.SIZE);
            List<ResourceLocation> palette = new ArrayList<>(paletteCount);
            for (int n = 0; n < paletteCount; n++) palette.add(in.readResourceLocation());
            int count = bound(in.readVarInt(), MapLayer.SIZE);
            int[] indices = new int[count]; int[] states = new int[count];
            long[] positions = new long[count]; int[] biomes = new int[count];
            for (int n = 0; n < count; n++) {
                indices[n] = bound(in.readUnsignedShort(), MapLayer.SIZE - 1);
                states[n] = bound(in.readVarInt(), Block.BLOCK_STATE_REGISTRY.size() - 1);
                positions[n] = in.readLong(); biomes[n] = bound(in.readVarInt(), paletteCount) - 1;
            }
            return new Update(id, reset, revision, indices, states, positions, palette, biomes);
        }

        private static int bound(int value, int max) {
            if (value < 0 || value > max) throw new IllegalArgumentException("Invalid accurate map packet");
            return value;
        }

        public static void handle(Update update, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientMaps.accept(update)));
            context.setPacketHandled(true);
        }
    }
}
