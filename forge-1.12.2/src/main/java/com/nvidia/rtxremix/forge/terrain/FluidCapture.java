/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;
import org.lwjgl.opengl.GL11;

/** Captures the installed Minecraft liquid tessellator into a private reusable CPU buffer. */
final class FluidCapture {
    private final Minecraft mc;
    private final BufferBuilder buffer = new BoundedBuffer();
    private final FluidMeshDecoder decoder = new FluidMeshDecoder();
    private final float[][] water = new float[3][4];
    private final float[][] lava = new float[2][4];

    FluidCapture(Minecraft mc) { this.mc = mc; }

    void refreshSprites() {
        TextureMap atlas = mc.getTextureMapBlocks();
        bounds(water[0], atlas.getAtlasSprite("minecraft:blocks/water_still"));
        bounds(water[1], atlas.getAtlasSprite("minecraft:blocks/water_flow"));
        bounds(water[2], atlas.getAtlasSprite("minecraft:blocks/water_overlay"));
        bounds(lava[0], atlas.getAtlasSprite("minecraft:blocks/lava_still"));
        bounds(lava[1], atlas.getAtlasSprite("minecraft:blocks/lava_flow"));
    }

    static boolean supported(IBlockState state) {
        return state.getBlock() instanceof BlockLiquid
                && (state.getMaterial() == Material.WATER || state.getMaterial() == Material.LAVA);
    }

    void capture(IBlockState state, IBlockAccess world, BlockPos pos,
                 int sectionX, int sectionY, int sectionZ, MeshPacking target) {
        boolean isLava = state.getMaterial() == Material.LAVA;
        BlockRenderLayer previous = MinecraftForgeClient.getRenderLayer();
        ForgeHooksClient.setRenderLayer(isLava ? BlockRenderLayer.SOLID : BlockRenderLayer.TRANSLUCENT);
        try {
            buffer.setTranslation(-sectionX * 16.0, -sectionY * 16.0, -sectionZ * 16.0);
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
            try { mc.getBlockRendererDispatcher().renderBlock(state, pos, world, buffer); }
            finally { buffer.finishDrawing(); }
            // Vanilla's fluid RGB contains directional face lighting; Remix supplies that lighting.
            int tint = mc.getBlockColors().colorMultiplier(state, world, pos, 0);
            decoder.append(buffer.getByteBuffer(), buffer.getVertexCount(), buffer.getVertexFormat().getSize(),
                    buffer.getVertexFormat().getUvOffsetById(0), tint, isLava ? lava : water, target);
        } finally {
            buffer.reset();
            buffer.setTranslation(0, 0, 0);
            ForgeHooksClient.setRenderLayer(previous);
        }
    }

    private static void bounds(float[] target, TextureAtlasSprite sprite) {
        target[0] = sprite.getMinU(); target[1] = sprite.getMinV();
        target[2] = sprite.getMaxU(); target[3] = sprite.getMaxV();
    }

    private static final class BoundedBuffer extends BufferBuilder {
        BoundedBuffer() { super(2048); }
        @Override public void endVertex() {
            if (getVertexCount() >= FluidMeshDecoder.MAX_VERTICES) {
                throw new IllegalStateException("Liquid renderer exceeded its per-block vertex budget");
            }
            super.endVertex();
        }
    }
}
