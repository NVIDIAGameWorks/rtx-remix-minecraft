/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class RemixEntitySubmitterIndexTest {

    @Test
    void quadsFanEachGroupOfFourFromItsFirstVertex() {
        int[] indices = RemixEntitySubmitter.buildIndices(8, EntityTopology.QUADS);

        assertArrayEquals(new int[]{
                0, 2, 1, 0, 3, 2,
                4, 6, 5, 4, 7, 6,
        }, indices);
    }

    @Test
    void quadsIgnoreATrailingPartialQuad() {
        int[] indices = RemixEntitySubmitter.buildIndices(7, EntityTopology.QUADS);

        assertArrayEquals(new int[]{0, 2, 1, 0, 3, 2}, indices);
    }

    @Test
    void stripAdvancesOneVertexPerTriangleAndAlternatesWinding() {
        int[] indices = RemixEntitySubmitter.buildIndices(
                5, EntityTopology.TRIANGLE_STRIP);

        assertArrayEquals(new int[]{
                0, 1, 2,
                2, 1, 3,
                2, 3, 4,
        }, indices);
    }

    @Test
    void shortestStripIsASingleTriangle() {
        int[] indices = RemixEntitySubmitter.buildIndices(
                3, EntityTopology.TRIANGLE_STRIP);

        assertArrayEquals(new int[]{0, 1, 2}, indices);
    }
}
