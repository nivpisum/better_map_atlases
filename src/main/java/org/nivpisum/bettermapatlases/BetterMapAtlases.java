/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.nivpisum.bettermapatlases.compat.NetherMapCompat;
import org.nivpisum.bettermapatlases.dynamic.SurveyNetwork;
import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;
import org.nivpisum.bettermapatlases.client.ClientSurvey;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

@Mod(BetterMapAtlases.MOD_ID)
public final class BetterMapAtlases {
    public static final String MOD_ID = "better_map_atlases";
    public static final ForgeConfigSpec COMMON;
    public static final ForgeConfigSpec.IntValue SYNC_INTERVAL;
    public static final ForgeConfigSpec.BooleanValue SURVEY_ENABLED;
    public static final ForgeConfigSpec.IntValue SURVEY_RADIUS, SURVEY_OBSERVATION_WINDOW, SURVEY_SCAN_REACH;
    public static final ForgeConfigSpec.IntValue SURVEY_COLUMNS_PER_TICK, SURVEY_BUDGET_MICROS, SURVEY_STABILITY_TICKS;
    static {
        ForgeConfigSpec.Builder config = new ForgeConfigSpec.Builder();
        SYNC_INTERVAL = config.comment("Minimum ticks between changed map-layer updates to one player. First view is immediate.")
            .defineInRange("sync_interval_ticks", 5, 1, 100);
        config.push("dynamic_atlas");
        SURVEY_ENABLED = config.comment("Survey separate surface and cave pages in carried atlases.")
            .define("enabled", true);
        SURVEY_RADIUS = config.comment("Nearby cave observation radius in blocks; only visible, loaded columns are recorded.")
            .defineInRange("radius", 32, 4, 64);
        SURVEY_OBSERVATION_WINDOW = config.comment("Witness window around an observed local surface; this is not the displayed cave depth.")
            .defineInRange("observation_window", 4, 1, 4);
        SURVEY_SCAN_REACH = config.defineInRange("scan_reach", 32, 8, 32);
        SURVEY_COLUMNS_PER_TICK = config.comment("Cave candidate limit; cheap surface columns have their own 1024 cap within the same time budget.")
            .defineInRange("columns_per_tick", 128, 8, 512);
        SURVEY_BUDGET_MICROS = config.comment("Shared sampling time budget per player/tick, in microseconds.")
            .defineInRange("budget_micros", 2000, 250, 10000);
        SURVEY_STABILITY_TICKS = config.defineInRange("view_stability_ticks", 6, 0, 20);
        config.pop();
        COMMON = config.build();
    }

    public BetterMapAtlases() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, COMMON);
        MapNetwork.register();
        SurveyNetwork.register();
        ModList mods = ModList.get();
        if (mods.isLoaded("moonlight") && mods.isLoaded("map_atlases") && mods.isLoaded("betternethermap")) {
            NetherMapCompat.register();
            MapSampler.setHeightProvider(NetherMapCompat::sampleHeight);
        }
        if (mods.isLoaded("moonlight") && mods.isLoaded("map_atlases")) {
            SurveyAtlas.register();
            org.nivpisum.bettermapatlases.cartography.BannerPins.register();
            SurveyAtlas.configureAuto(p -> SURVEY_ENABLED.get() && SurveyNetwork.isRemotePresent(p));
            SurveyNetwork.setAtlasAccessCheck(SurveyAtlas::canAccessMap);
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                org.nivpisum.bettermapatlases.cartography.client.BannerPinsClient.register();
                SurveyAtlas.setViewYProvider(ClientSurvey::viewY);
                ClientSurvey.setDynamicPredicate(SurveyAtlas::isDynamic);
                SurveyAtlas.configureHistoryUi(new SurveyAtlas.HistoryUi() {
                    public int[] historyHeights(int[] ids) { return ClientSurvey.historyHeights(ids); }
                    public void showHistory(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, int[] ids, int y16) { ClientSurvey.showHistory(dimension, ids, y16); }
                    public void showSurface(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, int[] ids) { ClientSurvey.showSurface(dimension, ids); }
                    public void followPlayer() { ClientSurvey.followPlayer(); }
                });
            });
        }
    }
}
