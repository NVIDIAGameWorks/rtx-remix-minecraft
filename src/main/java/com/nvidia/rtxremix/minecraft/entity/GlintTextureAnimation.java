/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import net.minecraft.util.Identifier;

import java.nio.ByteBuffer;

/**
 * Vanilla's glint texture matrix, split into a fixed mesh UV transform and a
 * scrolling texture. Updating one shared texture keeps item meshes and their
 * transform history stable. All state belongs to the render thread.
 */
final class GlintTextureAnimation implements AutoCloseable {
    private static final Identifier ITEM = Identifier.ofVanilla("textures/misc/enchanted_glint_item.png");
    private static final Identifier ENTITY = Identifier.ofVanilla("textures/misc/enchanted_glint_entity.png");
    private static final float COS = (float) Math.cos(Math.toRadians(10));
    private static final float SIN = (float) Math.sin(Math.toRadians(10));
    private static final long PERIOD = 330_000;

    /** Vanilla's texture-matrix scale for its two glint textures; zero for any other texture. */
    static float uvScale(Identifier texture) {
        return ITEM.equals(texture) ? 8f : ENTITY.equals(texture) ? 0.16f : 0f;
    }

    /** Whether {@code texture} is one of vanilla's glint textures, which alone scroll and use the tuned brightness. */
    static boolean isVanillaGlint(Identifier texture) {
        return uvScale(texture) != 0;
    }

    static float transformU(float u, float v, float scale) { return (COS * u - SIN * v) * scale; }
    static float transformV(float u, float v, float scale) { return (SIN * u + COS * v) * scale; }

    private final byte[] source;
    private final int width;
    private final int height;
    private final ByteBuffer pixels;
    private final TexturePatchInfo update = new TexturePatchInfo();
    private final TextureBatchEntry entry = new TextureBatchEntry();
    private long lastPhase = Long.MIN_VALUE;
    private long uploadedPhase = Long.MIN_VALUE;

    GlintTextureAnimation(byte[] source, int width, int height) {
        this.source = source;
        this.width = width;
        this.height = height;
        pixels = DirectBufferPool.acquire(source.length);
        TexturePatch patch = new TexturePatch();
        patch.width = width;
        patch.height = height;
        patch.rowPitch = width * 4;
        patch.format = TextureFormat.RGBA8_UNORM;
        patch.pixelData = pixels;
        entry.patches = new TexturePatch[] {patch};
        update.entries = new TextureBatchEntry[] {entry};
    }

    void upload(RemixAPI api, long texture, long scaledTime) {
        long phase = Math.floorMod(scaledTime, PERIOD);
        if (phase == uploadedPhase) return;
        prepare(scaledTime);
        entry.handle = texture;
        api.updateTextureFromMemory(update);
        uploadedPhase = phase;
    }

    /** Samples the scrolling texture at texel centers with repeating bilinear filtering. */
    boolean prepare(long scaledTime) {
        long phase = Math.floorMod(scaledTime, PERIOD);
        if (phase == lastPhase) return false;
        float x = -(phase % 110_000) / 110_000f * width;
        float y = (phase % 30_000) / 30_000f * height;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        int firstX = Math.floorMod(ix, width);
        int row = Math.floorMod(iy, height);
        pixels.clear();
        for (int v = 0; v < height; v++) {
            int nextRow = row + 1 == height ? 0 : row + 1;
            int column = firstX;
            for (int u = 0; u < width; u++) {
                int nextColumn = column + 1 == width ? 0 : column + 1;
                int a = (row * width + column) * 4;
                int b = (row * width + nextColumn) * 4;
                int c = (nextRow * width + column) * 4;
                int d = (nextRow * width + nextColumn) * 4;
                for (int channel = 0; channel < 4; channel++) {
                    float top = (source[a + channel] & 255) * (1 - fx) + (source[b + channel] & 255) * fx;
                    float bottom = (source[c + channel] & 255) * (1 - fx) + (source[d + channel] & 255) * fx;
                    pixels.put((byte) Math.round(top * (1 - fy) + bottom * fy));
                }
                column = nextColumn;
            }
            row = nextRow;
        }
        pixels.flip();
        lastPhase = phase;
        return true;
    }

    ByteBuffer pixels() { return pixels; }

    @Override
    public void close() { DirectBufferPool.release(pixels); }
}
