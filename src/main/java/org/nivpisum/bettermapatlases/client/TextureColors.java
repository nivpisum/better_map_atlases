/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.client;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.client.model.data.ModelData;

public final class TextureColors {
    private static final Map<BlockState, List<FaceColor>> STATES = new IdentityHashMap<>();
    private static final Map<TextureAtlasSprite, Integer> SPRITES = new IdentityHashMap<>();
    private static final Set<BlockState> FAILED = Collections.newSetFromMap(new IdentityHashMap<>());
    private TextureColors() {}
    public static void clear() { STATES.clear(); SPRITES.clear(); FAILED.clear(); }

    public static Integer color(BlockState state, BlockAndTintGetter view, BlockPos position) {
        if (FAILED.contains(state)) return null;
        try {
            if (state.getBlock() instanceof LiquidBlock) {
                var fluid = state.getFluidState();
                var extension = IClientFluidTypeExtensions.of(fluid);
                var location = extension.getStillTexture(fluid, view, position);
                if (location == null) return null;
                var sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(location);
                Integer rgb = average(sprite);
                if (rgb == null) return null;
                int tint = extension.getTintColor(fluid, view, position);
                return multiply(rgb, tint);
            }
            List<FaceColor> faces = STATES.computeIfAbsent(state, TextureColors::sampleState);
            if (faces.isEmpty()) return null;
            long red = 0, green = 0, blue = 0;
            for (FaceColor face : faces) {
                int tint = face.tintIndex < 0 ? 0xFFFFFF : Minecraft.getInstance().getBlockColors().getColor(state, view, position, face.tintIndex);
                if (tint == -1) tint = 0xFFFFFF;
                red += (face.rgb >> 16 & 255) * (tint >> 16 & 255) / 255;
                green += (face.rgb >> 8 & 255) * (tint >> 8 & 255) / 255;
                blue += (face.rgb & 255) * (tint & 255) / 255;
            }
            return (int) (red / faces.size()) << 16 | (int) (green / faces.size()) << 8 | (int) (blue / faces.size());
        } catch (RuntimeException exception) {
            // Modded dynamic models/tint providers may require a real world or block entity.
            // Keep the original pixel rather than make a black tile or crash the renderer.
            FAILED.add(state);
            return null;
        }
    }

    private static int multiply(int rgb, int tint) {
        return ((rgb >> 16 & 255) * (tint >> 16 & 255) / 255) << 16
            | ((rgb >> 8 & 255) * (tint >> 8 & 255) / 255) << 8
            | (rgb & 255) * (tint & 255) / 255;
    }

    private static List<FaceColor> sampleState(BlockState state) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        RandomSource random = RandomSource.create(42);
        List<BakedQuad> quads = new ArrayList<>(model.getQuads(state, Direction.UP, random, ModelData.EMPTY, null));
        if (quads.isEmpty()) {
            for (BakedQuad quad : model.getQuads(state, null, RandomSource.create(42), ModelData.EMPTY, null)) {
                if (quad.getDirection() == Direction.UP) quads.add(quad);
            }
        }
        List<FaceColor> result = new ArrayList<>();
        for (BakedQuad quad : quads) {
            Integer rgb = average(quad.getSprite());
            if (rgb != null) result.add(new FaceColor(rgb, quad.isTinted() ? quad.getTintIndex() : -1));
        }
        if (result.isEmpty()) {
            Integer rgb = average(model.getParticleIcon(ModelData.EMPTY));
            if (rgb != null) result.add(new FaceColor(rgb, 0));
        }
        return List.copyOf(result);
    }

    private static Integer average(TextureAtlasSprite sprite) {
        if (SPRITES.containsKey(sprite)) return SPRITES.get(sprite);
        int width = sprite.contents().width();
        int height = sprite.contents().height();
        // CPU source pixels survive resource packs and do not depend on OpenGL atlas readback.
        int[] frames = sprite.contents().getUniqueFrames().toArray();
        if (frames.length == 0) frames = new int[] {0};
        long red = 0, green = 0, blue = 0, weight = 0;
        for (int frame : frames) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int abgr = sprite.getPixelRGBA(frame, x, y);
                    int alpha = abgr >>> 24;
                    weight += alpha;
                    red += (long) (abgr & 255) * alpha;
                    green += (long) (abgr >> 8 & 255) * alpha;
                    blue += (long) (abgr >> 16 & 255) * alpha;
                }
            }
        }
        Integer rgb = weight == 0 ? null : (int) (red / weight) << 16 | (int) (green / weight) << 8 | (int) (blue / weight);
        SPRITES.put(sprite, rgb);
        return rgb;
    }

    /** NativeImage uses ABGR, while texture/tint arithmetic above uses RGB. */
    public static int shade(int rgb, int brightness) {
        int factor = switch (brightness) { case 0 -> 180; case 1 -> 220; case 2 -> 255; default -> 135; };
        int r = (rgb >> 16 & 255) * factor / 255;
        int g = (rgb >> 8 & 255) * factor / 255;
        int b = (rgb & 255) * factor / 255;
        return 0xFF000000 | b << 16 | g << 8 | r;
    }
    private record FaceColor(int rgb, int tintIndex) {}
}
