/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.client;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import pepjebs.mapatlases.client.Anchoring;

/** Vanilla controls only; all changes are visible immediately and saved on leaving. */
public final class AtlasSettingsScreen extends Screen {
    private final Screen parent;
    public AtlasSettingsScreen(Screen parent) { super(Component.translatable("better_map_atlases.settings.title")); this.parent = parent; }
    @Override protected void init() {
        AtlasHudSettings settings = AtlasHudSettings.get();
        int w = Math.min(260, width - 24);
        // Eight 20px controls and the footer fit at Minecraft's 240px GUI height.
        int x = (width - w) / 2, y = Math.max(24, (height - 200) / 2);
        addRenderableWidget(new SettingSlider(x, y, w, 48, 320, settings.size(), 8,
                v -> Component.translatable("better_map_atlases.settings.size", (int) Math.round(v)), v -> settings.size((int) Math.round(v))));
        addRenderableWidget(new SettingSlider(x, y + 22, w, .25, 4, settings.zoom(), .25,
                v -> Component.translatable("better_map_atlases.settings.zoom", String.format(Locale.ROOT, "%.2f", 1 / v)), settings::zoom));
        addRenderableWidget(Button.builder(directionLabel(), b -> { settings.northUp(!settings.northUp()); b.setMessage(directionLabel()); })
                .bounds(x, y + 44, w, 20).build());
        addRenderableWidget(Button.builder(positionLabel(), b -> {
            Anchoring[] anchors = Anchoring.values();
            settings.position(anchors[(settings.position().ordinal() + 1) % anchors.length]); b.setMessage(positionLabel());
        }).bounds(x, y + 66, w, 20).build());
        addRenderableWidget(new SettingSlider(x, y + 88, w, .35, 1.5, settings.iconScale(), .05,
                v -> Component.translatable("better_map_atlases.settings.icons", (int) Math.round(v * 100)), settings::iconScale));
        addRenderableWidget(new SettingSlider(x, y + 110, w, .35, 1.5, settings.playerIconScale(), .05,
                v -> Component.translatable("better_map_atlases.settings.player_icon", (int) Math.round(v * 100)), settings::playerIconScale));
        addRenderableWidget(new SettingSlider(x, y + 132, w, .5, 1.5, settings.textScale(), .05,
                v -> Component.translatable("better_map_atlases.settings.text", (int) Math.round(v * 100)), settings::textScale));
        addRenderableWidget(Button.builder(borderLabel(), b -> { settings.pageBorders(!settings.pageBorders()); b.setMessage(borderLabel()); })
                .bounds(x, y + 154, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("controls.reset"), b -> { settings.reset(); rebuildWidgets(); })
                .bounds(x, y + 180, (w - 6) / 2, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(x + (w + 6) / 2, y + 180, (w - 6) / 2, 20).build());
    }
    private Component directionLabel() {
        return Component.translatable("better_map_atlases.settings.orientation", Component.translatable(AtlasHudSettings.get().northUp()
                ? "better_map_atlases.settings.north_up" : "better_map_atlases.settings.player_up"));
    }
    private Component positionLabel() {
        return Component.translatable("better_map_atlases.settings.position", Component.translatable("better_map_atlases.settings.position."
                + AtlasHudSettings.get().position().name().toLowerCase(Locale.ROOT)));
    }
    private Component borderLabel() {
        return Component.translatable("better_map_atlases.settings.page_borders", Component.translatable(
                AtlasHudSettings.get().pageBorders() ? "options.on" : "options.off"));
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, Math.max(8, (height - 200) / 2 - 18), 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, tick);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void removed() { AtlasHudSettings.get().save(); }
    @Override public boolean isPauseScreen() { return false; }

    private static final class SettingSlider extends AbstractSliderButton {
        private final double minimum, maximum, step;
        private final DoubleFunction<Component> label;
        private final DoubleConsumer setter;
        SettingSlider(int x, int y, int width, double min, double max, double initial, double step,
                      DoubleFunction<Component> label, DoubleConsumer setter) {
            super(x, y, width, 20, Component.empty(), (initial - min) / (max - min));
            this.minimum = min; this.maximum = max; this.step = step; this.label = label; this.setter = setter;
            updateMessage();
        }
        private double settingValue() { return Math.max(minimum, Math.min(maximum, Math.round((minimum + value * (maximum - minimum)) / step) * step)); }
        @Override protected void updateMessage() { setMessage(label.apply(settingValue())); }
        @Override protected void applyValue() { setter.accept(settingValue()); }
    }
}
