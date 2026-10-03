/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** One independently owned map layer. Runtime block IDs are never stored on disk. */
public final class MapLayer {
    public static final int SIZE = 128 * 128;
    public static final String TAG = "accurate_maps";
    private final BlockState[] states = new BlockState[SIZE];
    private final ResourceLocation[] biomes = new ResourceLocation[SIZE];
    private final long[] positions = new long[SIZE];
    private final long[] changedAt = new long[SIZE];
    private long revision;

    public synchronized long revision() { return revision; }
    public synchronized BlockState state(int index) { return states[index]; }
    public synchronized ResourceLocation biome(int index) { return biomes[index]; }
    public synchronized long position(int index) { return positions[index]; }
    public synchronized long changedAt(int index) { return changedAt[index]; }

    public synchronized boolean set(int index, BlockState state, ResourceLocation biome, long position) {
        if (state != null && state.isAir()) state = null;
        if (state == null) { biome = null; position = 0; }
        if (states[index] == state && Objects.equals(biomes[index], biome) && positions[index] == position) return false;
        states[index] = state;
        biomes[index] = biome;
        positions[index] = position;
        changedAt[index] = ++revision;
        return true;
    }

    public synchronized int validPixels() {
        int count = 0;
        for (BlockState state : states) if (state != null) count++;
        return count;
    }

    public synchronized MapLayer copy() {
        MapLayer copy = new MapLayer();
        System.arraycopy(states, 0, copy.states, 0, SIZE);
        System.arraycopy(biomes, 0, copy.biomes, 0, SIZE);
        System.arraycopy(positions, 0, copy.positions, 0, SIZE);
        System.arraycopy(changedAt, 0, copy.changedAt, 0, SIZE);
        copy.revision = revision;
        return copy;
    }

    public synchronized CompoundTag save() {
        CompoundTag out = new CompoundTag();
        out.putInt("version", 1);
        Map<BlockState, Integer> statePalette = new LinkedHashMap<>();
        Map<ResourceLocation, Integer> biomePalette = new LinkedHashMap<>();
        int[] stateIndices = new int[SIZE];
        int[] biomeIndices = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            if (states[i] == null || biomes[i] == null) continue;
            stateIndices[i] = statePalette.computeIfAbsent(states[i], s -> statePalette.size() + 1);
            biomeIndices[i] = biomePalette.computeIfAbsent(biomes[i], b -> biomePalette.size() + 1);
        }
        ListTag stateTags = new ListTag();
        statePalette.keySet().forEach(s -> stateTags.add(NbtUtils.writeBlockState(s)));
        ListTag biomeTags = new ListTag();
        biomePalette.keySet().forEach(b -> biomeTags.add(StringTag.valueOf(b.toString())));
        out.put("state_palette", stateTags);
        out.put("biome_palette", biomeTags);
        out.putIntArray("states", stateIndices);
        out.putIntArray("biomes", biomeIndices);
        out.putLongArray("positions", positions.clone());
        return out;
    }

    public static MapLayer load(CompoundTag tag) {
        MapLayer layer = new MapLayer();
        if (tag.getInt("version") != 1) return layer;
        int[] stateIndices = tag.getIntArray("states");
        int[] biomeIndices = tag.getIntArray("biomes");
        long[] pos = tag.getLongArray("positions");
        if (stateIndices.length != SIZE || biomeIndices.length != SIZE || pos.length != SIZE) return layer;
        ListTag stateTags = tag.getList("state_palette", Tag.TAG_COMPOUND);
        ListTag biomeTags = tag.getList("biome_palette", Tag.TAG_STRING);
        if (stateTags.size() > SIZE || biomeTags.size() > SIZE) return layer;
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < stateTags.size(); i++) {
            palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), stateTags.getCompound(i)));
        }
        List<ResourceLocation> biomePalette = new ArrayList<>();
        for (int i = 0; i < biomeTags.size(); i++) biomePalette.add(ResourceLocation.tryParse(biomeTags.getString(i)));
        for (int i = 0; i < SIZE; i++) {
            int s = stateIndices[i] - 1;
            int b = biomeIndices[i] - 1;
            if (s < 0 || s >= palette.size() || b < 0 || b >= biomePalette.size() || biomePalette.get(b) == null) continue;
            layer.set(i, palette.get(s), biomePalette.get(b), pos[i]);
        }
        return layer;
    }

    /** Read-only diagnostic entry, suitable for DebugBridge. */
    public synchronized Map<String, Object> describe() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", 1);
        result.put("validPixels", validPixels());
        result.put("revision", revision);
        result.put("statePaletteSize", Arrays.stream(states).filter(Objects::nonNull).distinct().count());
        result.put("biomePaletteSize", Arrays.stream(biomes).filter(Objects::nonNull).distinct().count());
        result.put("contentHash", save().hashCode());
        return result;
    }

    public synchronized Map<String, Object> pixel(int x, int z) {
        if (x < 0 || z < 0 || x >= 128 || z >= 128) throw new IllegalArgumentException("Pixel outside map");
        int i = x + z * 128;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", String.valueOf(states[i]));
        result.put("biome", String.valueOf(biomes[i]));
        result.put("position", states[i] == null ? null : BlockPos.of(positions[i]).toShortString());
        return result;
    }
}
