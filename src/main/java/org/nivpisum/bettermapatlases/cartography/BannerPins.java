/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.cartography;

import net.mehvahdjukaar.moonlight.api.map.MapDataRegistry;
import net.mehvahdjukaar.moonlight.api.map.type.CustomDecorationType;
import net.mehvahdjukaar.moonlight.api.map.type.MapDecorationType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.Nullable;
import pepjebs.mapatlases.integration.moonlight.PinDecoration;
import pepjebs.mapatlases.integration.moonlight.PinMarker;

/** Native Moonlight pins with colour encoded in the registry ID, not the wire format. */
public final class BannerPins {
    public static final ResourceLocation FACTORY = new ResourceLocation("better_map_atlases", "banner_pin");
    private static boolean registered;

    private BannerPins() {}

    public static void register() {
        if (registered) return;
        registered = true;
        MapDataRegistry.registerCustomType(FACTORY,
                () -> CustomDecorationType.simple(BannerMarker::new, BannerDecoration::new));
    }

    public static ResourceLocation id(DyeColor color) {
        return new ResourceLocation(FACTORY.getNamespace(), "banner_pin_" + color.getName());
    }

    public static final class BannerMarker extends PinMarker {
        public BannerMarker(MapDecorationType<PinDecoration, ?> type) {
            super(type);
            setPersistent(true);
        }

        @Override public PinDecoration doCreateDecoration(byte x, byte y, byte rotation) {
            BannerDecoration decoration = new BannerDecoration(this, x, y, rotation, getName());
            decoration.forceFocused(isFocused());
            return decoration;
        }
    }

    /** Upstream's packet constructor has no marker object; focusing must tolerate that. */
    public static final class BannerDecoration extends PinDecoration {
        private boolean focused;

        public BannerDecoration(PinMarker marker, byte x, byte y, byte rotation, @Nullable Component name) {
            super(marker, x, y, rotation, name);
        }

        public BannerDecoration(MapDecorationType<?, ?> type, FriendlyByteBuf buffer) {
            super(type, buffer);
        }

        @Override public boolean isFocused() { return focused; }

        @Override public void forceFocused(boolean value) {
            focused = value;
            if (marker != null) marker.setFocused(value);
        }
    }
}
