/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases;

import java.io.*;
import java.util.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/** Explicitly invoked tests on detached maps inside the real Forge class loader. Never edits a world. */
public final class RuntimeChecks {
    private RuntimeChecks() {}
    public static Map<String, Object> run() throws IOException {
        List<String> passed = new ArrayList<>();
        ResourceLocation plains = ResourceLocation.tryParse("minecraft:plains");
        ResourceLocation swamp = ResourceLocation.tryParse("minecraft:swamp");
        MapItemSavedData first = MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, Level.OVERWORLD);
        MapLayer layer = ((MapLayerAccess) first).accurateMaps$getLayer(true);
        first.setColor(7, 0, (byte) 34);
        first.setColor(40, 70, (byte) 49);
        layer.set(7, Blocks.WHITE_WOOL.defaultBlockState(), plains, new BlockPos(-5021, 90, 3500).asLong());
        layer.set(9000, Blocks.GRASS_BLOCK.defaultBlockState(), swamp, new BlockPos(6000, 74, -3000).asLong());
        byte[] vanilla = first.colors.clone();
        CompoundTag mapTag = first.save(new CompoundTag());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(mapTag, bytes);
        MapItemSavedData reopened = MapItemSavedData.load(NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray())));
        MapLayer loaded = ((MapLayerAccess) reopened).accurateMaps$getLayer(false);
        require(loaded != null && loaded.validPixels() == 2 && loaded.save().equals(layer.save()), "compressed save/reload");
        require(Arrays.equals(vanilla, reopened.colors), "vanilla byte array retention");
        require(loaded.state(8) == null && swamp.equals(loaded.biome(9000)), "unvisited and saved biome");
        require("minecraft:white_wool".equals(mapTag.getCompound(MapLayer.TAG).getList("state_palette", Tag.TAG_COMPOUND).getCompound(0).getString("Name")), "stable disk palette");
        passed.add("compressed NBT roundtrip and exact vanilla byte retention");

        MapItemSavedData locked = first.locked();
        MapLayer frozen = ((MapLayerAccess) locked).accurateMaps$getLayer(false);
        require(frozen != layer && frozen.save().equals(layer.save()), "locked copy");
        MapItemSavedData second = MapItemSavedData.createFresh(500, 500, (byte) 0, true, false, Level.OVERWORLD);
        MapLayer other = ((MapLayerAccess) second).accurateMaps$getLayer(true);
        other.set(7, Blocks.BLUE_WOOL.defaultBlockState(), swamp, 6);
        long revision = layer.revision();
        require(!layer.set(7, layer.state(7), plains, layer.position(7)) && layer.revision() == revision, "unchanged sample");
        layer.set(7, Blocks.WHITE_CONCRETE.defaultBlockState(), plains, layer.position(7));
        require(layer.revision() > revision && frozen.state(7) == Blocks.WHITE_WOOL.defaultBlockState()
            && other.state(7) == Blocks.BLUE_WOOL.defaultBlockState(), "independent map pixels");
        require(((MapLayerAccess) first.scaled(1)).accurateMaps$getLayer(false) == null, "scaled map reset");
        passed.add("two independent maps, locked deep copy, scaled empty layer and same-colour state change");

        MapNetwork.Update full = roundTrip(MapNetwork.Update.from(19, layer, -1));
        require(full.reset() && full.mapId() == 19 && Arrays.equals(full.indices(), new int[] {7, 9000}), "full packet");
        revision = layer.revision();
        first.setColor(7, 0, (byte) 50);
        layer.set(55, Blocks.SAND.defaultBlockState(), plains, 999);
        require(layer.state(7) == null, "external vanilla edit invalidates layer");
        MapNetwork.Update delta = roundTrip(MapNetwork.Update.from(19, layer, revision));
        require(!delta.reset() && Arrays.equals(delta.indices(), new int[] {7, 55})
            && delta.states()[0] == 0 && delta.biomes()[0] == -1 && delta.positions()[1] == 999, "delta packet");
        passed.add("full/delta packet roundtrip and stale-pixel clearing");

        CompoundTag malformed = layer.save();
        malformed.putIntArray("states", new int[] {1});
        require(MapLayer.load(malformed).validPixels() == 0, "malformed storage fallback");
        FriendlyByteBuf invalid = new FriendlyByteBuf(Unpooled.buffer());
        boolean rejected = false;
        try {
            invalid.writeVarInt(0); invalid.writeBoolean(true); invalid.writeVarLong(0); invalid.writeVarInt(MapLayer.SIZE + 1);
            try { MapNetwork.Update.decode(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
        } finally { invalid.release(); }
        require(rejected, "packet bounds");
        passed.add("malformed NBT fallback and packet allocation bounds");

        MapLayer concurrent = new MapLayer();
        Thread writer = new Thread(() -> {
            for (int i = 1; i <= 2000; i++) {
                concurrent.set(1, Blocks.WHITE_WOOL.defaultBlockState(), plains, i);
            }
        }, "accurate-maps-detached-selftest");
        writer.start();
        for (int i = 0; i < 200; i++) {
            MapNetwork.Update snapshot = MapNetwork.Update.from(20, concurrent, -1);
            require(snapshot.indices().length == 0 ? snapshot.revision() == 0
                : snapshot.indices().length == 1 && snapshot.positions()[0] == snapshot.revision(),
                "atomic packet revision and pixel snapshot");
            concurrent.copy().save();
        }
        try { writer.join(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted detached map concurrency check", interrupted);
        }
        require(concurrent.revision() == 2000 && concurrent.position(1) == 2000, "all worker changes retained");
        passed.add("concurrent worker changes with atomic save/copy and packet revision snapshots");

        return Map.of("passed", true, "checks", passed, "compressedTestMapBytes", bytes.size());
    }
    private static MapNetwork.Update roundTrip(MapNetwork.Update update) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try { update.encode(buffer); return MapNetwork.Update.decode(buffer); }
        finally { buffer.release(); }
    }
    private static void require(boolean condition, String check) {
        if (!condition) throw new IllegalStateException("Accurate Maps self-test failed: " + check);
    }
}
