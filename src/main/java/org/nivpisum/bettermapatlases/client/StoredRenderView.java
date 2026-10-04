/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/** Tint providers see saved metadata, never the player's current chunk or dimension. */
public final class StoredRenderView implements BlockAndTintGetter {
    private BlockState state = Blocks.AIR.defaultBlockState();
    private BlockPos position = BlockPos.ZERO;
    private Biome biome;
    private final int minY;
    private final int height;
    private final LevelLightEngine lighting = new LevelLightEngine(new LightChunkGetter() {
        @Override public LightChunk getChunkForLighting(int x, int z) { return null; }
        @Override public BlockGetter getLevel() { return StoredRenderView.this; }
    }, false, false);

    public StoredRenderView(int minY, int height) { this.minY = minY; this.height = height; }
    public void set(BlockState state, BlockPos position, Biome biome) {
        this.state = state; this.position = position; this.biome = biome;
    }
    @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return resolver.getColor(biome, pos.getX(), pos.getZ());
    }
    @Override public BlockState getBlockState(BlockPos pos) { return position.equals(pos) ? state : Blocks.AIR.defaultBlockState(); }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public float getShade(Direction direction, boolean shaded) { return 1.0F; }
    @Override public LevelLightEngine getLightEngine() { return lighting; }
    @Override public int getHeight() { return height; }
    @Override public int getMinBuildHeight() { return minY; }
}
