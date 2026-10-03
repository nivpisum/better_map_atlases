/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraftforge.fml.loading.FMLPaths;
import pepjebs.mapatlases.client.Anchoring;
import pepjebs.mapatlases.config.MapAtlasesClientConfig;

/** Client display preferences, loaded lazily after Atlas' native config has loaded. */
public final class AtlasHudSettings {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static AtlasHudSettings instance;
    private int size = 96;
    private double zoom = .5;
    private boolean northUp = true;
    private Anchoring position = Anchoring.UPPER_LEFT;
    private double iconScale = .65;
    private double playerIconScale = .75;
    private double textScale = .75;
    private boolean pageBorders = true;
    private List<AtlasDimensionNavigation.Link> coordinateLinks = new ArrayList<>(AtlasDimensionNavigation.defaultLinks());
    private transient boolean dirty;

    public static AtlasHudSettings get() {
        if (instance == null) {
            instance = new AtlasHudSettings();
            instance.size = Mth.clamp((int) Math.round(MapAtlasesClientConfig.miniMapScale.get() * 64), 48, 320);
            instance.zoom = Mth.clamp(MapAtlasesClientConfig.miniMapZoomMultiplier.get(), .25, 4);
            instance.position = MapAtlasesClientConfig.miniMapAnchoring.get();
            if (Files.isRegularFile(file())) {
                try (var reader = Files.newBufferedReader(file(), StandardCharsets.UTF_8)) {
                    AtlasHudSettings loaded = JSON.fromJson(reader, AtlasHudSettings.class);
                    if (loaded != null) { loaded.validate(); instance = loaded; }
                } catch (IOException | RuntimeException exception) {
                    LogUtils.getLogger().warn("Could not read Better Map Atlases client display settings", exception);
                }
            }
        }
        return instance;
    }

    private static Path file() { return FMLPaths.CONFIGDIR.get().resolve("better_map_atlases-client.json"); }
    private void validate() {
        size = Mth.clamp(size, 48, 320);
        zoom = Double.isFinite(zoom) ? Mth.clamp(zoom, .25, 4) : .5;
        iconScale = Double.isFinite(iconScale) ? Mth.clamp(iconScale, .35, 1.5) : .65;
        playerIconScale = Double.isFinite(playerIconScale) ? Mth.clamp(playerIconScale, .35, 1.5) : .75;
        textScale = Double.isFinite(textScale) ? Mth.clamp(textScale, .5, 1.5) : .75;
        if (position == null) position = Anchoring.UPPER_LEFT;
        if (coordinateLinks == null) coordinateLinks = new ArrayList<>(AtlasDimensionNavigation.defaultLinks());
        else coordinateLinks.removeIf(link -> !AtlasDimensionNavigation.isValidLink(link));
    }
    public int size() { return size; }
    public double zoom() { return zoom; }
    public boolean northUp() { return northUp; }
    public Anchoring position() { return position; }
    public double iconScale() { return iconScale; }
    public double playerIconScale() { return playerIconScale; }
    public double textScale() { return textScale; }
    public boolean pageBorders() { return pageBorders; }
    public List<AtlasDimensionNavigation.Link> coordinateLinks() { return List.copyOf(coordinateLinks); }
    public int labelSpace() {
        return AtlasHudText.reservedHeight(MapAtlasesClientConfig.drawMinimapCoords.get(),
                MapAtlasesClientConfig.drawMinimapChunkCoords.get(), MapAtlasesClientConfig.drawMinimapBiome.get(), textScale);
    }
    public float displayScale(int width, int height) {
        int textSpace = labelSpace() + 10;
        return Math.max(24, Math.min(size, Math.min(width - 10, height - textSpace))) / 64f;
    }
    public void size(int value) { size = Mth.clamp(value, 48, 320); dirty = true; }
    public void zoom(double value) { zoom = Double.isFinite(value) ? Mth.clamp(value, .25, 4) : .5; dirty = true; }
    public void northUp(boolean value) { northUp = value; dirty = true; }
    public void position(Anchoring value) { position = value; dirty = true; }
    public void iconScale(double value) { iconScale = Double.isFinite(value) ? Mth.clamp(value, .35, 1.5) : .65; dirty = true; }
    public void playerIconScale(double value) { playerIconScale = Double.isFinite(value) ? Mth.clamp(value, .35, 1.5) : .75; dirty = true; }
    public void textScale(double value) { textScale = Double.isFinite(value) ? Mth.clamp(value, .5, 1.5) : .75; dirty = true; }
    public void pageBorders(boolean value) { pageBorders = value; dirty = true; }
    public void reset() {
        size = 96; zoom = .5; northUp = true; position = Anchoring.UPPER_LEFT;
        iconScale = .65; playerIconScale = .75; textScale = .75; pageBorders = true; dirty = true;
    }

    public void save() {
        if (!dirty) return;
        Path path = file();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, JSON.toJson(this) + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException exception) {
            LogUtils.getLogger().warn("Could not save Better Map Atlases client display settings", exception);
        }
    }
}
