/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import com.nvidia.rtxremix.forge.texture.SpriteOpacity;
import java.util.Arrays;

/** Exact coincident baked faces, staged once per model without per-quad allocation. */
final class BlockOverlays implements BlockQuadSink {
    static final int MAX_QUADS = 1024;
    private static final int STRIDE = 27;
    private final float[] data = new float[MAX_QUADS * STRIDE];
    private final int[] colors = new int[MAX_QUADS * 4], coverage = new int[MAX_QUADS];
    private final int[] first = new int[MAX_QUADS], hashes = new int[MAX_QUADS];
    private final int[] entries = new int[MAX_QUADS * 2], epochs = new int[MAX_QUADS * 2];
    private final float[] positions = new float[12], uv = new float[8];
    private final int[] tint = new int[4];
    private int count, nextCoverage, epoch;

    void begin() { count = 0; nextCoverage = SpriteOpacity.UNKNOWN; }
    void coverage(int value) { nextCoverage = value; }

    @Override public void quad(float[] positions, float[] uv, int[] colors, float nx, float ny, float nz,
                               float minU, float minV, float maxU, float maxV) {
        if (count == MAX_QUADS) throw new IllegalStateException("Baked block exceeds bounded quad staging");
        int offset = count * STRIDE;
        System.arraycopy(positions, 0, data, offset, 12);
        System.arraycopy(uv, 0, data, offset + 12, 8);
        System.arraycopy(colors, 0, this.colors, count * 4, 4);
        data[offset + 20] = nx; data[offset + 21] = ny; data[offset + 22] = nz;
        data[offset + 23] = minU; data[offset + 24] = minV;
        data[offset + 25] = maxU; data[offset + 26] = maxV;
        boolean valid = minU < maxU && minV < maxV;
        for (int v = 0; v < 4; v++) {
            valid &= Float.isFinite(uv[v * 2]) && Float.isFinite(uv[v * 2 + 1])
                    && uv[v * 2] >= minU && uv[v * 2] <= maxU
                    && uv[v * 2 + 1] >= minV && uv[v * 2 + 1] <= maxV;
            // Neither a tinted transparent base nor a faded candidate proves the
            // binary cutout-over-opaque relationship this category represents.
            valid &= colors[v] >>> 24 == 255;
            for (int axis = 0; axis < 3; axis++) valid &= Float.isFinite(positions[v * 3 + axis]);
        }
        coverage[count] = valid ? nextCoverage : SpriteOpacity.UNKNOWN;
        first[count] = firstVertex(offset);
        hashes[count] = hash(offset, first[count]);
        count++;
    }

    void append(BlockQuadSink target) {
        if (++epoch == 0) { Arrays.fill(epochs, 0); epoch = 1; }
        for (int i = 0; i < count; i++) if (coverage[i] == SpriteOpacity.OPAQUE) {
            int slot = slot(i);
            if (epochs[slot] != epoch) { epochs[slot] = epoch; entries[slot] = i; }
        }
        try {
            for (int i = 0; i < count; i++) {
                boolean overlay = coverage[i] == SpriteOpacity.CUTOUT && epochs[slot(i)] == epoch;
                int offset = i * STRIDE;
                System.arraycopy(data, offset, positions, 0, 12);
                System.arraycopy(data, offset + 12, uv, 0, 8);
                System.arraycopy(colors, i * 4, tint, 0, 4);
                target.overlay(overlay);
                target.quad(positions, uv, tint, data[offset + 20], data[offset + 21], data[offset + 22],
                        data[offset + 23], data[offset + 24], data[offset + 25], data[offset + 26]);
            }
        } finally { target.overlay(false); }
    }

    private int slot(int quad) {
        int slot = hashes[quad] & (entries.length - 1);
        while (epochs[slot] == epoch && !sameFace(quad, entries[slot])) slot = (slot + 1) & (entries.length - 1);
        return slot;
    }

    private boolean sameFace(int a, int b) {
        if (hashes[a] != hashes[b]) return false;
        int ao = a * STRIDE, bo = b * STRIDE;
        float dot = data[ao + 20] * data[bo + 20] + data[ao + 21] * data[bo + 21] + data[ao + 22] * data[bo + 22];
        if (!(dot > 0.9999f)) return false;
        for (int v = 0; v < 4; v++) for (int axis = 0; axis < 3; axis++) {
            if (data[ao + ((first[a] + v) & 3) * 3 + axis]
                    != data[bo + ((first[b] + v) & 3) * 3 + axis]) return false;
        }
        return true;
    }

    private int firstVertex(int offset) {
        int first = 0;
        for (int v = 1; v < 4; v++) for (int axis = 0; axis < 3; axis++) {
            float a = data[offset + v * 3 + axis], b = data[offset + first * 3 + axis];
            if (a < b) { first = v; break; }
            if (a > b) break;
        }
        return first;
    }

    private int hash(int offset, int start) {
        int hash = 0x811c9dc5;
        for (int v = 0; v < 4; v++) for (int axis = 0; axis < 3; axis++) {
            float value = data[offset + ((start + v) & 3) * 3 + axis];
            hash = (hash ^ (value == 0 ? 0 : Float.floatToIntBits(value))) * 0x01000193;
        }
        return hash ^ hash >>> 16;
    }
}
