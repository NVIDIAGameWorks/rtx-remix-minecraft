/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import com.nvidia.rtxremix.HardcodedVertex;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Packs the current Remix ABI, independently of Minecraft and an active renderer. */
final class MeshPacking implements BlockQuadSink {
    static final int STRIDE = HardcodedVertex.STRIDE;
    static final int MAX_VERTICES = 262144;
    private ByteBuffer vertices = direct(STRIDE * 256);
    private ByteBuffer indices = direct(256 / 4 * 6 * Integer.BYTES);
    private int vertexCount;

    @Override public void quad(float[] positions, float[] uv, int[] colors,
              float nx, float ny, float nz, float minU, float minV, float maxU, float maxV) {
        ensureVertices(4);
        int minimum = HardcodedVertex.packHalf2x16(minU, minV);
        int maximum = HardcodedVertex.packHalf2x16(maxU, maxV);
        for (int v = 0; v < 4; v++) {
            vertices.putFloat(positions[v * 3]);
            vertices.putFloat(positions[v * 3 + 1]);
            vertices.putFloat(positions[v * 3 + 2]);
            vertices.putFloat(nx).putFloat(ny).putFloat(nz);
            vertices.putFloat(uv[v * 2]).putFloat(uv[v * 2 + 1]);
            vertices.putInt(colors[v]);
            vertices.putInt(minimum).putInt(maximum);
            for (int pad = 0; pad < 5; pad++) vertices.putInt(0);
        }
        vertexCount += 4;
    }

    int vertexCount() { return vertexCount; }

    void reset() { truncate(0); }

    void truncate(int count) {
        if (count < 0 || count > vertexCount || (count & 3) != 0) throw new IllegalArgumentException("Invalid quad checkpoint");
        vertexCount = count;
        vertices.position(count * STRIDE);
    }

    ByteBuffer vertices() {
        ByteBuffer view = vertices.duplicate().order(ByteOrder.nativeOrder());
        view.flip();
        return view;
    }

    ByteBuffer indices() {
        return indices(vertexCount);
    }

    ByteBuffer vertices(int first, int count) {
        if (first < 0 || count < 0 || first + count > vertexCount) throw new IllegalArgumentException("Invalid vertex page");
        ByteBuffer view = vertices.duplicate().order(ByteOrder.nativeOrder());
        view.position(first * STRIDE).limit((first + count) * STRIDE);
        return view.slice().order(ByteOrder.nativeOrder());
    }

    ByteBuffer indices(int count) {
        int bytes = count / 4 * 6 * Integer.BYTES;
        if (indices.capacity() < bytes) indices = direct(bytes);
        indices.clear();
        for (int base = 0; base < count; base += 4) {
            // Minecraft's baked-quad winding is reversed by the Remix terrain convention.
            indices.putInt(base).putInt(base + 2).putInt(base + 1);
            indices.putInt(base).putInt(base + 3).putInt(base + 2);
        }
        indices.flip();
        return indices;
    }

    /** Minecraft vertex bytes are RGBA; Remix's packed color is 0xAARRGGBB. */
    static int tintRgba(int red, int green, int blue, int alpha, int tintRgb) {
        red = red * ((tintRgb >>> 16) & 255) / 255;
        green = green * ((tintRgb >>> 8) & 255) / 255;
        blue = blue * (tintRgb & 255) / 255;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private void ensureVertices(int additional) {
        int needed = vertexCount + additional;
        if (needed > MAX_VERTICES) {
            throw new CapacityExceededException();
        }
        int bytes = needed * STRIDE;
        if (bytes <= vertices.capacity()) return;
        ByteBuffer replacement = direct(Math.max(bytes, Math.min(MAX_VERTICES * STRIDE,
                vertices.capacity() * 2)));
        vertices.flip();
        replacement.put(vertices);
        vertices = replacement;
    }

    static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    static final class CapacityExceededException extends IllegalStateException {
        CapacityExceededException() { super("One terrain surface exceeded " + MAX_VERTICES + " vertices"); }
    }
}
