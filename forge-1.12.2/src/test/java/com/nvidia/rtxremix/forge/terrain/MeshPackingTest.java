/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import com.nvidia.rtxremix.HardcodedVertex;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class MeshPackingTest {
    private static final float[] POSITIONS = {0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1};
    private static final float[] UV = {0.25f, 0.5f, 0.5f, 0.5f, 0.5f, 0.75f, 0.25f, 0.75f};
    private static final int[] COLORS = {0xff112233, 0xff445566, 0xff778899, 0xffaabbcc};

    private static void append(MeshPacking packed) {
        packed.quad(POSITIONS, UV, COLORS, 0, 0, 1, 0.25f, 0.5f, 0.5f, 0.75f);
    }

    @Test public void packsTheNative64ByteVertexAbi() {
        MeshPacking packed = new MeshPacking();
        append(packed);
        ByteBuffer bytes = packed.vertices();
        assertTrue(bytes.isDirect());
        assertEquals(ByteOrder.nativeOrder(), bytes.order());
        assertEquals(64, MeshPacking.STRIDE);
        assertEquals(4 * 64, bytes.remaining());
        assertEquals(1f, bytes.getFloat(8), 0);
        assertEquals(1f, bytes.getFloat(20), 0);
        assertEquals(0.25f, bytes.getFloat(24), 0);
        assertEquals(0.5f, bytes.getFloat(28), 0);
        assertEquals(COLORS[0], bytes.getInt(32));
        assertEquals(HardcodedVertex.packHalf2x16(0.25f, 0.5f), bytes.getInt(36));
        assertEquals(HardcodedVertex.packHalf2x16(0.5f, 0.75f), bytes.getInt(40));
        for (int byteOffset = 44; byteOffset < 64; byteOffset += 4) assertEquals(0, bytes.getInt(byteOffset));
        assertEquals(COLORS[3], bytes.getInt(3 * 64 + 32));
    }

    @Test public void triangulatesEveryQuadWithRemixWindingAndRebasedIndices() {
        MeshPacking packed = new MeshPacking();
        append(packed);
        append(packed);
        ByteBuffer bytes = packed.indices();
        assertTrue(bytes.isDirect());
        int[] expected = {0, 2, 1, 0, 3, 2, 4, 6, 5, 4, 7, 6};
        assertEquals(expected.length * 4, bytes.remaining());
        for (int index : expected) assertEquals(index, bytes.getInt());
        // The first triangle faces -Z: a deliberate reversal of this Minecraft quad's +Z winding.
        float crossZ = (POSITIONS[6] - POSITIONS[0]) * (POSITIONS[4] - POSITIONS[1])
                - (POSITIONS[7] - POSITIONS[1]) * (POSITIONS[3] - POSITIONS[0]);
        assertTrue(crossZ < 0);
    }

    @Test public void preservesVertexDataWhenTheDirectBufferGrows() {
        MeshPacking packed = new MeshPacking();
        for (int i = 0; i < 300; i++) append(packed);
        ByteBuffer bytes = packed.vertices();
        assertEquals(1200, packed.vertexCount());
        assertEquals(1200 * 64, bytes.remaining());
        assertEquals(COLORS[0], bytes.getInt(32));
        assertEquals(COLORS[3], bytes.getInt(1199 * 64 + 32));
        // Reading a view must not change the writer's cursor.
        append(packed);
        assertEquals(1204, packed.vertexCount());
    }

    @Test public void convertsRgbaBytesToArgbAndAppliesTintWithoutChangingAlpha() {
        assertEquals(0x80112233, MeshPacking.tintRgba(0x11, 0x22, 0x33, 0x80, 0xffffff));
        assertEquals(0xff804000, MeshPacking.tintRgba(255, 128, 64, 255, 0x808000));
        assertEquals(0x7f336699, MeshPacking.tintRgba(255, 255, 255, 127, 0x336699));
    }

    @Test public void rollsBackPartialBlocksAndReusesStorageForTheNextSection() {
        MeshPacking packed = new MeshPacking();
        append(packed);
        int checkpoint = packed.vertexCount();
        append(packed);
        packed.truncate(checkpoint);
        assertEquals(4, packed.vertexCount());
        assertEquals(4 * MeshPacking.STRIDE, packed.vertices().remaining());
        packed.reset();
        assertEquals(0, packed.vertexCount());
        append(packed);
        assertEquals(COLORS[0], packed.vertices().getInt(32));
        assertEquals(6 * 4, packed.indices().remaining());
    }

    @Test public void pageViewsRebaseIndicesAndDoNotMutateCapturedVertices() {
        MeshPacking packed = new MeshPacking();
        append(packed);
        append(packed);
        ByteBuffer second = packed.vertices(4, 4);
        assertEquals(256, second.remaining());
        assertEquals(COLORS[0], second.getInt(32));
        ByteBuffer indices = packed.indices(4);
        assertEquals(0, indices.getInt());
        assertEquals(2, indices.getInt());
        assertEquals(8, packed.vertexCount());
        assertEquals(512, packed.vertices().remaining());
    }
}
