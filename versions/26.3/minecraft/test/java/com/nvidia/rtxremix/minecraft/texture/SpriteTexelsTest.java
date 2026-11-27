/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpriteTexelsTest {

    @Test
    void paddingRepeatsTheNearestEdgeTexel() {
        // A 2x2 sprite at (2, 2) with a 2-texel border in an 8x8 single-channel layer.
        byte[] layer = new byte[64];
        layer[2 * 8 + 2] = 1;
        layer[2 * 8 + 3] = 2;
        layer[3 * 8 + 2] = 3;
        layer[3 * 8 + 3] = 4;

        SpriteTexels.fillPadding(layer, 8, 8, 1, 2, 2, 2, 2, 2);

        // Rows and columns 0-1 and 4-5 are the border; each repeats its nearest edge texel.
        String[] expected = {
                "11122200",
                "11122200",
                "11122200",
                "33344400",
                "33344400",
                "33344400",
                "00000000",
                "00000000"};
        for (int y = 0; y < 8; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < 8; x++) row.append(layer[y * 8 + x]);
            assertEquals(expected[y], row.toString(), "row " + y);
        }
    }

    @Test
    void paddingIsClippedToTheAtlas() {
        byte[] layer = new byte[4 * 4 * 4];
        for (int i = 0; i < 4; i++) layer[i] = (byte) (10 + i);

        // A 1x1 RGBA sprite in the corner: the border above and left falls outside.
        SpriteTexels.fillPadding(layer, 4, 4, 4, 0, 0, 1, 1, 1);

        for (int i = 0; i < 4; i++) {
            assertEquals(10 + i, layer[4 + i], "right neighbour channel " + i);
            assertEquals(10 + i, layer[16 + i], "lower neighbour channel " + i);
            assertEquals(10 + i, layer[20 + i], "diagonal neighbour channel " + i);
        }
    }
}
