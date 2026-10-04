/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.cartography.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.mehvahdjukaar.moonlight.api.map.client.MapDecorationClientManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import org.joml.Matrix4f;
import org.nivpisum.bettermapatlases.cartography.BannerPins;
import pepjebs.mapatlases.integration.moonlight.PinDecorationRenderer;

/** Uses the game's banner icons; no Minecraft texture is copied into this add-on. */
public final class BannerPinsClient {
    private static final RenderType BANNERS = RenderType.text(new ResourceLocation("textures/map/map_icons.png"));

    private BannerPinsClient() {}

    public static void register() {
        MapDecorationClientManager.registerCustomRenderer(BannerPins.FACTORY, BannerPinRenderer::new);
    }

    private static final class BannerPinRenderer extends PinDecorationRenderer {
        private final int icon;

        private BannerPinRenderer(ResourceLocation texture) {
            super(texture);
            DyeColor selected = DyeColor.WHITE;
            for (DyeColor color : DyeColor.values()) {
                if (texture.getPath().endsWith("banner_pin_" + color.getName())) {
                    selected = color;
                    break;
                }
            }
            icon = Byte.toUnsignedInt(new MapBanner(BlockPos.ZERO, selected, null).getDecoration().getIcon());
        }

        @Override public void renderDecorationSprite(PoseStack pose, MultiBufferSource buffers,
                VertexConsumer ignored, int light, int index, int color, int alpha, boolean outline) {
            if (alpha == 0) return;
            VertexConsumer vertices = buffers.getBuffer(BANNERS);
            if (outline) {
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    pose.pushPose();
                    pose.translate(dx * 0.10, dy * 0.10, 0.001);
                    quad(pose.last().pose(), vertices, light, index, 0xFF202020, alpha);
                    pose.popPose();
                }
            }
            quad(pose.last().pose(), vertices, light, index, color, alpha);
        }

        private void quad(Matrix4f matrix, VertexConsumer vertices, int light, int index, int color, int alpha) {
            float u0 = (icon % 16) / 16f, v0 = (icon / 16) / 16f;
            float u1 = u0 + 1 / 16f, v1 = v0 + 1 / 16f;
            int red = (color >>> 16) & 255, green = (color >>> 8) & 255, blue = color & 255;
            float z = -index * 0.001f;
            // Match Moonlight's map-facing winding. RenderType.text culls the reverse face,
            // including in atlas widgets and bookmarks, even though their tooltip still renders.
            vertices.vertex(matrix, -1, 1, z).color(red, green, blue, alpha).uv(u0, v1).uv2(light).endVertex();
            vertices.vertex(matrix, 1, 1, z).color(red, green, blue, alpha).uv(u1, v1).uv2(light).endVertex();
            vertices.vertex(matrix, 1, -1, z).color(red, green, blue, alpha).uv(u1, v0).uv2(light).endVertex();
            vertices.vertex(matrix, -1, -1, z).color(red, green, blue, alpha).uv(u0, v0).uv2(light).endVertex();
        }
    }
}
