/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContactFacesTest {

    /** A unit quad on the plane axis == value, with the block origin at (bx, by, bz). */
    private static float[] quadOn(int axis, float value, float bx, float by, float bz) {
        float[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        float[] origin = {bx, by, bz};
        float[] out = new float[12];
        for (int v = 0; v < 4; v++) {
            int a = 0;
            for (int i = 0; i < 3; i++) {
                if (i == axis) {
                    out[v * 3 + i] = value;
                } else {
                    out[v * 3 + i] = origin[i] + corners[v][a++];
                }
            }
        }
        return out;
    }

    @Test
    void downFaceMovesUpIntoTheBlock() {
        float[] quad = quadOn(1, 64.0f, 10, 64, 20);
        assertTrue(ContactFaces.insetOntoBlockFace(quad, 1, -1, 64.0f, ContactFaces.INSET));
        for (int v = 0; v < 4; v++) {
            assertEquals(64.0f + ContactFaces.INSET, quad[v * 3 + 1], 0.0f);
        }
    }

    @Test
    void upFaceMovesDownIntoTheBlock() {
        float[] quad = quadOn(1, 65.0f, 10, 64, 20);
        assertTrue(ContactFaces.insetOntoBlockFace(quad, 1, 1, 64.0f, ContactFaces.INSET));
        for (int v = 0; v < 4; v++) {
            assertEquals(65.0f - ContactFaces.INSET, quad[v * 3 + 1], 0.0f);
        }
    }

    @Test
    void sideFacesMoveAlongTheirOwnAxis() {
        float[] east = quadOn(0, 11.0f, 10, 64, 20);
        assertTrue(ContactFaces.insetOntoBlockFace(east, 0, 1, 10.0f, ContactFaces.INSET));
        assertEquals(11.0f - ContactFaces.INSET, east[0], 0.0f);

        float[] north = quadOn(2, 20.0f, 10, 64, 20);
        assertTrue(ContactFaces.insetOntoBlockFace(north, 2, -1, 20.0f, ContactFaces.INSET));
        assertEquals(20.0f + ContactFaces.INSET, north[2], 0.0f);
    }

    @Test
    void otherAxesAreUntouched() {
        float[] quad = quadOn(1, 64.0f, 10, 64, 20);
        float[] before = quad.clone();
        ContactFaces.insetOntoBlockFace(quad, 1, -1, 64.0f, ContactFaces.INSET);
        for (int v = 0; v < 4; v++) {
            assertEquals(before[v * 3], quad[v * 3], 0.0f);
            assertEquals(before[v * 3 + 2], quad[v * 3 + 2], 0.0f);
        }
    }

    @Test
    void quadOffTheFacePlaneIsLeftAlone() {
        float[] inner = quadOn(1, 64.25f, 10, 64, 20);
        float[] before = inner.clone();
        assertFalse(ContactFaces.insetOntoBlockFace(inner, 1, -1, 64.0f, ContactFaces.INSET));
        assertArrayEquals(before, inner, 0.0f);
    }

    @Test
    void quadWithOneVertexOffThePlaneIsLeftAlone() {
        float[] quad = quadOn(1, 64.0f, 10, 64, 20);
        quad[4] = 64.5f;
        float[] before = quad.clone();
        assertFalse(ContactFaces.insetOntoBlockFace(quad, 1, -1, 64.0f, ContactFaces.INSET));
        assertArrayEquals(before, quad, 0.0f);
    }

    @Test
    void wrongSideOfTheBlockIsLeftAlone() {
        float[] topFace = quadOn(1, 65.0f, 10, 64, 20);
        assertFalse(ContactFaces.insetOntoBlockFace(topFace, 1, -1, 64.0f, ContactFaces.INSET));
    }
}
