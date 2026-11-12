/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import com.nvidia.rtxremix.HardcodedVertex;
import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class FluidMeshDecoderTest {
    private static final int STRIDE = 32;
    private static final int UV_OFFSET = 20;
    private static final float[][] BOUNDS = {{0, 0, 0.25f, 0.25f}, {0.5f, 0.5f, 0.75f, 0.75f}, {0.8f, 0.8f, 1, 1}};
    private static final float[] TOP = {0, 1, 0, 0, 1, 1, 1, 0.5f, 1, 1, 0.25f, 0};
    private static final float[] UV = {0.51f, 0.51f, 0.51f, 0.74f, 0.74f, 0.74f, 0.74f, 0.51f};
    private static final int[] FORWARD = {0, 1, 2, 3};
    private static final int[] REVERSE = {0, 3, 2, 1};

    private static void quad(ByteBuffer buffer, float[] positions, float[] uv, int[] order) {
        for (int v : order) {
            buffer.putFloat(positions[v * 3]).putFloat(positions[v * 3 + 1]).putFloat(positions[v * 3 + 2]);
            buffer.putInt(0xff111111).putInt(0); // Deliberately dark baked RGB and an unrelated format element.
            buffer.putFloat(uv[v * 2]).putFloat(uv[v * 2 + 1]).putInt(0x00f000f0);
        }
    }

    @Test public void preservesFlowingSurfaceGeometryUvAndRawBiomeTint() {
        ByteBuffer input = MeshPacking.direct(4 * STRIDE);
        quad(input, TOP, UV, FORWARD);
        input.flip();
        MeshPacking target = new MeshPacking();
        new FluidMeshDecoder().append(input, 4, STRIDE, UV_OFFSET, 0x2468ac, BOUNDS, target);
        ByteBuffer output = target.vertices();
        assertEquals(4, target.vertexCount());
        for (int v = 0; v < 4; v++) {
            int base = v * MeshPacking.STRIDE;
            assertEquals(TOP[v * 3], output.getFloat(base), 0);
            assertEquals(TOP[v * 3 + 1], output.getFloat(base + 4), 0);
            assertEquals(TOP[v * 3 + 2], output.getFloat(base + 8), 0);
            assertEquals(UV[v * 2], output.getFloat(base + 24), 0);
            assertEquals(UV[v * 2 + 1], output.getFloat(base + 28), 0);
            assertEquals(0xff2468ac, output.getInt(base + 32));
            assertEquals(HardcodedVertex.packHalf2x16(0.5f, 0.5f), output.getInt(base + 36));
            assertEquals(HardcodedVertex.packHalf2x16(0.75f, 0.75f), output.getInt(base + 40));
        }
        float nx = output.getFloat(12), ny = output.getFloat(16), nz = output.getFloat(20);
        assertTrue(nx > 0);
        assertTrue(ny > 0);
        assertEquals(1, nx * nx + ny * ny + nz * nz, 0.00001);
        assertEquals(0, input.position());
    }

    @Test public void removesExactReverseBackfacesForDoubleSidedNativeInstances() {
        ByteBuffer input = MeshPacking.direct(8 * STRIDE);
        quad(input, TOP, UV, FORWARD);
        quad(input, TOP, UV, REVERSE);
        input.flip();
        MeshPacking target = new MeshPacking();
        FluidMeshDecoder decoder = new FluidMeshDecoder();
        decoder.append(input, 8, STRIDE, UV_OFFSET, 0xffffff, BOUNDS, target);
        assertEquals(4, target.vertexCount());
    }

    @Test public void omitsDegenerateFluidQuads() {
        ByteBuffer input = MeshPacking.direct(4 * STRIDE);
        quad(input, new float[12], UV, FORWARD); input.flip();
        FluidMeshDecoder decoder = new FluidMeshDecoder(); MeshPacking target = new MeshPacking();
        decoder.append(input, 4, STRIDE, UV_OFFSET, 0xffffff, BOUNDS, target);
        assertEquals(0, target.vertexCount());
    }

    @Test public void keepsAnOverlayUsingDifferentTextureCoordinates() {
        float[] overlay = {0.81f, 0.81f, 0.81f, 0.99f, 0.99f, 0.99f, 0.99f, 0.81f};
        ByteBuffer input = MeshPacking.direct(8 * STRIDE);
        quad(input, TOP, UV, FORWARD);
        quad(input, TOP, overlay, REVERSE);
        input.flip();
        MeshPacking target = new MeshPacking();
        new FluidMeshDecoder().append(input, 8, STRIDE, UV_OFFSET, 0xffffff, BOUNDS, target);
        assertEquals(8, target.vertexCount());
        assertEquals(HardcodedVertex.packHalf2x16(0.8f, 0.8f), target.vertices().getInt(4 * MeshPacking.STRIDE + 36));
    }

    @Test public void doesNotDeduplicateAcrossIndependentBlocks() {
        ByteBuffer input = MeshPacking.direct(4 * STRIDE);
        quad(input, TOP, UV, FORWARD);
        input.flip();
        MeshPacking target = new MeshPacking();
        FluidMeshDecoder decoder = new FluidMeshDecoder();
        decoder.append(input, 4, STRIDE, UV_OFFSET, 0xffffff, BOUNDS, target);
        decoder.append(input, 4, STRIDE, UV_OFFSET, 0xffffff, BOUNDS, target);
        assertEquals(8, target.vertexCount());
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsIncompleteQuads() {
        new FluidMeshDecoder().append(MeshPacking.direct(3 * STRIDE), 3, STRIDE, UV_OFFSET, 0xffffff, BOUNDS, new MeshPacking());
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsUnboundedPerBlockOutput() {
        new FluidMeshDecoder().append(MeshPacking.direct(68 * STRIDE), 68, STRIDE, UV_OFFSET, 0xffffff, BOUNDS, new MeshPacking());
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsNonFiniteGeometry() {
        ByteBuffer input = MeshPacking.direct(4 * STRIDE);
        quad(input, TOP, UV, FORWARD);
        input.flip();
        input.putFloat(0, Float.NaN);
        new FluidMeshDecoder().append(input, 4, STRIDE, UV_OFFSET, 0xffffff, BOUNDS, new MeshPacking());
    }

    @Test public void acceptsFullyOccludedLiquidWithNoVertices() {
        MeshPacking target = new MeshPacking();
        new FluidMeshDecoder().append(MeshPacking.direct(0), 0, STRIDE, UV_OFFSET, 0xffffff, BOUNDS, target);
        assertEquals(0, target.vertexCount());
    }
}
