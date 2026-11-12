/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import java.nio.ByteBuffer;

/** Converts captured fluid quads without retaining vanilla lighting or duplicate backfaces. */
final class FluidMeshDecoder {
    static final int MAX_VERTICES = 64;
    private final float[] positions = new float[12];
    private final float[] uv = new float[8];
    private final int[] colors = new int[4];
    private final float[] previousPositions = new float[12];
    private final float[] previousUv = new float[8];

    /** Bounds are minU,minV,maxU,maxV for the still, flowing, and optional overlay sprites. */
    void append(ByteBuffer source, int vertices, int stride, int uvOffset, int tint,
                float[][] bounds, MeshPacking target) {
        if (vertices < 0 || vertices > MAX_VERTICES || (vertices & 3) != 0
                || stride < 12 || uvOffset < 12 || uvOffset + 8 > stride
                || (long) vertices * stride > source.limit()) {
            throw new IllegalArgumentException("Invalid captured fluid vertex buffer");
        }
        for (int i = 0; i < colors.length; i++) colors[i] = 0xff000000 | (tint & 0xffffff);
        boolean previous = false;
        float previousX = 0, previousY = 0, previousZ = 0;
        for (int first = 0; first < vertices; first += 4) {
            for (int v = 0; v < 4; v++) {
                int base = (first + v) * stride;
                for (int axis = 0; axis < 3; axis++) positions[v * 3 + axis] = finite(source.getFloat(base + axis * 4));
                uv[v * 2] = finite(source.getFloat(base + uvOffset));
                uv[v * 2 + 1] = finite(source.getFloat(base + uvOffset + 4));
            }
            // Newell's normal averages both triangles of a sloping, non-planar liquid top.
            float nx = 0, ny = 0, nz = 0;
            for (int v = 0; v < 4; v++) {
                int a = v * 3, b = ((v + 1) & 3) * 3;
                nx += (positions[a + 1] - positions[b + 1]) * (positions[a + 2] + positions[b + 2]);
                ny += (positions[a + 2] - positions[b + 2]) * (positions[a] + positions[b]);
                nz += (positions[a] - positions[b]) * (positions[a + 1] + positions[b + 1]);
            }
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!Float.isFinite(length)) throw new IllegalArgumentException("Non-finite fluid normal");
            if (length < 0.000001f) continue;
            nx /= length; ny /= length; nz /= length;
            if (previous && nx * previousX + ny * previousY + nz * previousZ < -0.999f && sameQuad()) continue;
            float[] sprite = findBounds(bounds);
            target.quad(positions, uv, colors, nx, ny, nz, sprite[0], sprite[1], sprite[2], sprite[3]);
            System.arraycopy(positions, 0, previousPositions, 0, positions.length);
            System.arraycopy(uv, 0, previousUv, 0, uv.length);
            previousX = nx; previousY = ny; previousZ = nz;
            previous = true;
        }
    }

    private boolean sameQuad() {
        for (int v = 0; v < 4; v++) {
            boolean matched = false;
            for (int p = 0; p < 4; p++) {
                if (positions[v * 3] == previousPositions[p * 3]
                        && positions[v * 3 + 1] == previousPositions[p * 3 + 1]
                        && positions[v * 3 + 2] == previousPositions[p * 3 + 2]
                        && uv[v * 2] == previousUv[p * 2]
                        && uv[v * 2 + 1] == previousUv[p * 2 + 1]) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    private float[] findBounds(float[][] bounds) {
        for (float[] sprite : bounds) {
            boolean inside = true;
            for (int v = 0; v < 4; v++) {
                if (uv[v * 2] < sprite[0] - 0.000001f || uv[v * 2] > sprite[2] + 0.000001f
                        || uv[v * 2 + 1] < sprite[1] - 0.000001f || uv[v * 2 + 1] > sprite[3] + 0.000001f) {
                    inside = false;
                    break;
                }
            }
            if (inside) return sprite;
        }
        throw new IllegalArgumentException("Captured fluid UVs do not belong to its atlas sprites");
    }

    private static float finite(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) throw new IllegalArgumentException("Non-finite fluid vertex");
        return value;
    }
}
