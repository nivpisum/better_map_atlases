/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.client;

import java.util.*;
import org.nivpisum.bettermapatlases.*;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

public final class ClientMaps {
    private static final Map<Integer, Received> MAPS = new HashMap<>();
    private static long resourceEpoch;
    private ClientMaps() {}

    public static void accept(MapNetwork.Update update) {
        Received received = update.reset() ? new Received(new MapLayer(), -1) : MAPS.get(update.mapId());
        if (received == null || !update.reset() && update.revision() <= received.revision) return;
        MapLayer layer = received.layer;
        for (int n = 0; n < update.indices().length; n++) {
            int biome = update.biomes()[n];
            layer.set(update.indices()[n], Block.stateById(update.states()[n]),
                biome < 0 ? null : update.palette().get(biome), update.positions()[n]);
        }
        MAPS.put(update.mapId(), new Received(layer, update.revision()));
    }

    public static long revision(int id) {
        Received received = MAPS.get(id);
        return received == null ? -1 : received.revision;
    }
    public static long resourceEpoch() { return resourceEpoch; }

    /** Paints over only known pixels, after vanilla has prepared its normal texture. */
    public static void overlay(int id, MapItemSavedData map, NativeImage image) {
        Received received = MAPS.get(id);
        Minecraft minecraft = Minecraft.getInstance();
        if (received == null || minecraft.level == null || image == null) return;
        MapLayer layer = received.layer;
        var biomes = minecraft.level.registryAccess().registryOrThrow(Registries.BIOME);
        StoredRenderView view = new StoredRenderView(minecraft.level.getMinBuildHeight(), minecraft.level.getHeight());
        for (int i = 0; i < MapLayer.SIZE; i++) {
            // Do not reveal unvisited or erased vanilla pixels.
            if (layer.state(i) == null || (map.colors[i] & 255) / 4 == 0 || layer.biome(i) == null) continue;
            Biome biome = biomes.get(layer.biome(i));
            if (biome == null) continue;
            BlockPos pos = BlockPos.of(layer.position(i));
            view.set(layer.state(i), pos, biome);
            Integer rgb = TextureColors.color(layer.state(i), view, pos);
            if (rgb != null) image.setPixelRGBA(i & 127, i >> 7, TextureColors.shade(rgb, map.colors[i] & 3));
        }
    }

    public static Map<String, Object> describe(int id) {
        Received received = MAPS.get(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("present", received != null);
        result.put("revision", revision(id));
        result.put("resourceEpoch", resourceEpoch);
        result.put("layer", received == null ? null : received.layer.describe());
        return result;
    }

    /** Call on the client render/main thread, like all client colour API calls. */
    public static Map<String, Object> pixel(int id, int x, int z) {
        Received received = MAPS.get(id);
        Minecraft minecraft = Minecraft.getInstance();
        if (received == null || minecraft.level == null) return Map.of("present", false);
        Map<String, Object> result = new LinkedHashMap<>(received.layer.pixel(x, z));
        int index = x + z * 128;
        MapLayer layer = received.layer;
        if (layer.state(index) != null && layer.biome(index) != null) {
            Biome biome = minecraft.level.registryAccess().registryOrThrow(Registries.BIOME).get(layer.biome(index));
            if (biome != null) {
                BlockPos pos = BlockPos.of(layer.position(index));
                StoredRenderView view = new StoredRenderView(minecraft.level.getMinBuildHeight(), minecraft.level.getHeight());
                view.set(layer.state(index), pos, biome);
                Integer rgb = TextureColors.color(layer.state(index), view, pos);
                result.put("textureTintRgb", rgb == null ? null : String.format("#%06x", rgb));
                MapItemSavedData map = minecraft.level.getMapData(MapItem.makeKey(id));
                if (map != null) {
                    result.put("vanillaByte", map.colors[index] & 255);
                    result.put("accurateAbgr", rgb == null ? null : String.format("%08x", TextureColors.shade(rgb, map.colors[index] & 3)));
                }
            }
        }
        return result;
    }

    private static void reset() {
        MAPS.clear();
        ClientSurvey.reset();
        TextureColors.clear();
        resourceEpoch++;
    }
    private record Received(MapLayer layer, long revision) {}

    @Mod.EventBusSubscriber(modid = BetterMapAtlases.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ReloadEvents {
        @SubscribeEvent public static void register(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) manager -> {
                TextureColors.clear();
                resourceEpoch++;
            });
        }
    }
    @Mod.EventBusSubscriber(modid = BetterMapAtlases.MOD_ID, value = Dist.CLIENT)
    public static final class ConnectionEvents {
        @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { reset(); }
        @SubscribeEvent public static void login(ClientPlayerNetworkEvent.LoggingIn event) { reset(); }
    }
}
