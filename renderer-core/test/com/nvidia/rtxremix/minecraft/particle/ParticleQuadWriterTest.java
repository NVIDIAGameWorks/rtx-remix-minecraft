/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParticleQuadWriterTest {
    @Test
    void identityCameraWritesExpectedWorldSpaceQuad() {
        ByteBuffer vertices = ByteBuffer
                .allocate(ParticleQuadWriter.VERTEX_BYTES_PER_PARTICLE)
                .order(ByteOrder.nativeOrder());
        ParticleQuadWriter.write(
                vertices,
                0,
                1.0, 2.0, 3.0,
                1.0, 2.0, 3.0,
                0.0f, 0.0f, 1.0f, 0.5f,
                ParticleQuadWriter.basisFromCamera(0, 0, 0, 1),
                0.0f, 0.0f, 1.0f, 1.0f,
                0xFFFFFFFF,
                17,
                23);

        assertEquals(0.0f, vertices.getFloat(0));
        assertEquals(1.0f, vertices.getFloat(4));
        assertEquals(3.0f, vertices.getFloat(8));
        assertEquals(-1.0f, vertices.getFloat(20));
        assertEquals(17, vertices.getInt(36));
        assertEquals(23, vertices.getInt(40));
    }

    @Test
    void colorPackingClampsToD3dColor() {
        assertEquals(
                0xFF00FF7F,
                ParticleQuadWriter.packColor(-1.0f, 2.0f, 0.5f, 1.0f));
    }
}
