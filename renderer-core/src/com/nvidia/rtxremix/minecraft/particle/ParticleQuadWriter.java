/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.nvidia.rtxremix.HardcodedVertex;

import java.nio.ByteBuffer;

/** Canonical CPU geometry writer for camera-facing particle quads. */
public final class ParticleQuadWriter {
    public static final int VERTICES_PER_PARTICLE = 4;
    public static final int INDICES_PER_PARTICLE = 6;
    public static final int STRIDE = HardcodedVertex.STRIDE;
    public static final int VERTEX_BYTES_PER_PARTICLE =
            VERTICES_PER_PARTICLE * STRIDE;

    private ParticleQuadWriter() {}

    public static Basis basisFromCamera(float qx, float qy, float qz, float qw) {
        float xx = qx * qx;
        float yy = qy * qy;
        float zz = qz * qz;
        float xy = qx * qy;
        float xz = qx * qz;
        float yz = qy * qz;
        float wx = qw * qx;
        float wy = qw * qy;
        float wz = qw * qz;

        float r00 = 1f - 2f * (yy + zz);
        float r01 = 2f * (xy - wz);
        float r02 = 2f * (xz + wy);
        float r10 = 2f * (xy + wz);
        float r11 = 1f - 2f * (xx + zz);
        float r12 = 2f * (yz - wx);
        float r20 = 2f * (xz - wy);
        float r21 = 2f * (yz + wx);
        float r22 = 1f - 2f * (xx + yy);

        return new Basis(r00, r01, r10, r11, r20, r21, -r02, -r12, -r22);
    }

    public static void write(
            ByteBuffer vertices,
            int slot,
            double previousX,
            double previousY,
            double previousZ,
            double x,
            double y,
            double z,
            float previousAngle,
            float angle,
            float scale,
            float tickDelta,
            Basis basis,
            float u0,
            float v0,
            float u1,
            float v1,
            int color,
            int boundsMin,
            int boundsMax) {
        double px = lerp(tickDelta, previousX, x);
        double py = lerp(tickDelta, previousY, y);
        double pz = lerp(tickDelta, previousZ, z);
        float interpolatedAngle = lerp(tickDelta, previousAngle, angle);
        float cosA = (float) Math.cos(interpolatedAngle);
        float sinA = (float) Math.sin(interpolatedAngle);

        float s00 = (cosA * basis.r00 + sinA * basis.r10) * scale;
        float s01 = (cosA * basis.r01 + sinA * basis.r11) * scale;
        float s10 = (-sinA * basis.r00 + cosA * basis.r10) * scale;
        float s11 = (-sinA * basis.r01 + cosA * basis.r11) * scale;
        float z00 = basis.r20 * scale;
        float z01 = basis.r21 * scale;

        int base = slot * VERTEX_BYTES_PER_PARTICLE;
        putActiveVertex(vertices, base,
                px - s00 - s01, py - s10 - s11, pz - z00 - z01,
                basis.nx, basis.ny, basis.nz, u0, v1, color);
        putActiveVertex(vertices, base + STRIDE,
                px + s00 - s01, py + s10 - s11, pz + z00 - z01,
                basis.nx, basis.ny, basis.nz, u1, v1, color);
        putActiveVertex(vertices, base + STRIDE * 2,
                px + s00 + s01, py + s10 + s11, pz + z00 + z01,
                basis.nx, basis.ny, basis.nz, u1, v0, color);
        putActiveVertex(vertices, base + STRIDE * 3,
                px - s00 + s01, py - s10 + s11, pz - z00 + z01,
                basis.nx, basis.ny, basis.nz, u0, v0, color);
        for (int vertex = 0; vertex < VERTICES_PER_PARTICLE; vertex++) {
            int offset = base + vertex * STRIDE;
            vertices.putInt(offset + 36, boundsMin);
            vertices.putInt(offset + 40, boundsMax);
        }
    }

    public static void writeDegenerate(ByteBuffer vertices, int slot) {
        int base = slot * VERTEX_BYTES_PER_PARTICLE;
        for (int vertex = 0; vertex < VERTICES_PER_PARTICLE; vertex++) {
            putVertex(vertices, base + vertex * STRIDE, 0, 0, 0, 0, 0, -1, 0, 0, 0);
        }
    }

    public static void copy(ByteBuffer vertices, int fromSlot, int toSlot) {
        int from = fromSlot * VERTEX_BYTES_PER_PARTICLE;
        int to = toSlot * VERTEX_BYTES_PER_PARTICLE;
        for (int offset = 0; offset < VERTEX_BYTES_PER_PARTICLE; offset += Long.BYTES) {
            vertices.putLong(to + offset, vertices.getLong(from + offset));
        }
    }

    public static int packColor(float r, float g, float b, float a) {
        int ri = (int) (clamp01(r) * 255f) & 0xFF;
        int gi = (int) (clamp01(g) * 255f) & 0xFF;
        int bi = (int) (clamp01(b) * 255f) & 0xFF;
        int ai = (int) (clamp01(a) * 255f) & 0xFF;
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }

    private static void putVertex(
            ByteBuffer buffer, int offset,
            double px, double py, double pz,
            float nx, float ny, float nz,
            float u, float v, int color) {
        putActiveVertex(buffer, offset, px, py, pz, nx, ny, nz, u, v, color);
        for (int attribute = 36; attribute < STRIDE; attribute += Integer.BYTES) {
            buffer.putInt(offset + attribute, 0);
        }
    }

    private static void putActiveVertex(
            ByteBuffer buffer, int offset,
            double px, double py, double pz,
            float nx, float ny, float nz,
            float u, float v, int color) {
        buffer.putFloat(offset, (float) px);
        buffer.putFloat(offset + 4, (float) py);
        buffer.putFloat(offset + 8, (float) pz);
        buffer.putFloat(offset + 12, nx);
        buffer.putFloat(offset + 16, ny);
        buffer.putFloat(offset + 20, nz);
        buffer.putFloat(offset + 24, u);
        buffer.putFloat(offset + 28, v);
        buffer.putInt(offset + 32, color);
    }

    private static double lerp(float delta, double start, double end) {
        return start + delta * (end - start);
    }

    private static float lerp(float delta, float start, float end) {
        return start + delta * (end - start);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    public static final class Basis {
        private final float r00;
        private final float r01;
        private final float r10;
        private final float r11;
        private final float r20;
        private final float r21;
        private final float nx;
        private final float ny;
        private final float nz;

        private Basis(
                float r00, float r01,
                float r10, float r11,
                float r20, float r21,
                float nx, float ny, float nz) {
            this.r00 = r00;
            this.r01 = r01;
            this.r10 = r10;
            this.r11 = r11;
            this.r20 = r20;
            this.r21 = r21;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
        }
    }
}
