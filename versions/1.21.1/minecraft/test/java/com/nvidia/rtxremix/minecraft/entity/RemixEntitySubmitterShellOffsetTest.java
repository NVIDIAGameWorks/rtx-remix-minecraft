/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A part on a shell layer stands off the layer below, but a closed shell must stay closed
 * while it does: a beacon held behind another layer showed its emissive lens as a bright
 * hairline around every edge when each face moved along its own normal alone, and a torch
 * card showed the inside of its back face along every texel run when only shared corners
 * were welded.
 */
class RemixEntitySubmitterShellOffsetTest {

    private static final float DEPTH = 0.0005f;
    private static final float EPSILON = 1.0e-9f;
    private static final int NO_CARD = RemixCapturingConsumer.NO_VERTICES;

    /** Six axis-aligned quads of a unit cube, each with its face normal, as cuboids and baked quads arrive. */
    private static float[][] unitCube() {
        float[] px = new float[24], py = new float[24], pz = new float[24];
        float[] nx = new float[24], ny = new float[24], nz = new float[24];
        int v = 0;
        for (int axis = 0; axis < 3; axis++) {
            for (int side = 0; side < 2; side++) {
                for (int corner = 0; corner < 4; corner++) {
                    float a = (corner & 1) == 0 ? 0f : 1f;
                    float b = (corner & 2) == 0 ? 0f : 1f;
                    float fixed = side == 0 ? 0f : 1f;
                    float[] p = new float[3];
                    p[axis] = fixed;
                    p[(axis + 1) % 3] = a;
                    p[(axis + 2) % 3] = b;
                    px[v] = p[0]; py[v] = p[1]; pz[v] = p[2];
                    nx[v] = axis == 0 ? (side == 0 ? -1f : 1f) : 0f;
                    ny[v] = axis == 1 ? (side == 0 ? -1f : 1f) : 0f;
                    nz[v] = axis == 2 ? (side == 0 ? -1f : 1f) : 0f;
                    v++;
                }
            }
        }
        return new float[][] {px, py, pz, nx, ny, nz};
    }

    private static float[][] offsets(float[][] cube, int start, int count, float depth) {
        float[] ox = new float[count], oy = new float[count], oz = new float[count];
        RemixEntitySubmitter.shellOffsets(cube[0], cube[1], cube[2], cube[3], cube[4], cube[5],
                start, count, depth, EntityTopology.QUADS, NO_CARD, ox, oy, oz);
        return new float[][] {ox, oy, oz};
    }

    @Test
    void sharedCornersOfABoxStayTogether() {
        float[][] cube = unitCube();
        float[][] o = offsets(cube, 0, 24, DEPTH);
        // A corner at (x, y, z) in {0, 1}^3 moves diagonally outward: the offset is the
        // same for the three vertices that meet there, so no edge opens.
        for (int i = 0; i < 24; i++) {
            float sx = cube[0][i] == 0f ? -DEPTH : DEPTH;
            float sy = cube[1][i] == 0f ? -DEPTH : DEPTH;
            float sz = cube[2][i] == 0f ? -DEPTH : DEPTH;
            assertEquals(sx, o[0][i], EPSILON, "x of vertex " + i);
            assertEquals(sy, o[1][i], EPSILON, "y of vertex " + i);
            assertEquals(sz, o[2][i], EPSILON, "z of vertex " + i);
        }
    }

    @Test
    void negativeZeroMeetsPositiveZero() {
        float[][] cube = unitCube();
        // A cuboid emitter can hand over -0f where a baked quad has 0f.
        for (int i = 0; i < 24; i++) if (cube[0][i] == 0f && i % 2 == 0) cube[0][i] = -0f;
        float[][] o = offsets(cube, 0, 24, DEPTH);
        for (int i = 0; i < 24; i++) {
            float sx = cube[0][i] == 0f ? -DEPTH : DEPTH;
            assertEquals(sx, o[0][i], EPSILON, "x of vertex " + i);
        }
    }

    @Test
    void aLoneQuadMovesAlongItsNormal() {
        float[][] cube = unitCube();
        // Vertices 16..19 are the -Z face on its own.
        float[][] o = offsets(cube, 16, 4, DEPTH);
        for (int i = 0; i < 4; i++) {
            assertEquals(0f, o[0][i], EPSILON);
            assertEquals(0f, o[1][i], EPSILON);
            assertEquals(-DEPTH, o[2][i], EPSILON);
        }
    }

    @Test
    void coplanarNeighboursCountTheirSharedNormalOnce() {
        // Two quads in the plane z = 0 sharing the edge x = 1.
        float[] px = {0, 1, 1, 0, 1, 2, 2, 1};
        float[] py = {0, 0, 1, 1, 0, 0, 1, 1};
        float[] pz = new float[8];
        float[] nx = new float[8], ny = new float[8];
        float[] nz = {1, 1, 1, 1, 1, 1, 1, 1};
        float[] ox = new float[8], oy = new float[8], oz = new float[8];
        RemixEntitySubmitter.shellOffsets(px, py, pz, nx, ny, nz, 0, 8, DEPTH, EntityTopology.QUADS, NO_CARD, ox, oy, oz);
        for (int i = 0; i < 8; i++) {
            assertEquals(DEPTH, oz[i], EPSILON, "vertex " + i);
        }
    }

    @Test
    void aZeroThicknessSheetGrowsIntoAThinBox() {
        // A cuboid with no extent along z: its two large faces coincide with opposite normals,
        // as vanilla builds fish fins, and its four side quads have no area.
        float[] px = {0, 1, 1, 0,  0, 1, 1, 0,  0, 0, 0, 0,  1, 1, 1, 1,  0, 1, 1, 0,  0, 1, 1, 0};
        float[] py = {0, 0, 1, 1,  0, 0, 1, 1,  0, 1, 1, 0,  0, 1, 1, 0,  0, 0, 0, 0,  1, 1, 1, 1};
        float[] pz = new float[24];
        float[] nx = {0, 0, 0, 0,  0, 0, 0, 0,  -1, -1, -1, -1,  1, 1, 1, 1,  0, 0, 0, 0,  0, 0, 0, 0};
        float[] ny = {0, 0, 0, 0,  0, 0, 0, 0,  0, 0, 0, 0,  0, 0, 0, 0,  -1, -1, -1, -1,  1, 1, 1, 1};
        float[] nz = {1, 1, 1, 1,  -1, -1, -1, -1,  0, 0, 0, 0,  0, 0, 0, 0,  0, 0, 0, 0,  0, 0, 0, 0};
        float[] ox = new float[24], oy = new float[24], oz = new float[24];
        RemixEntitySubmitter.shellOffsets(px, py, pz, nx, ny, nz, 0, 24, DEPTH, EntityTopology.QUADS, NO_CARD, ox, oy, oz);
        // Each large face still travels its own offset, so the sheet becomes a box 2 x DEPTH thick.
        for (int i = 0; i < 8; i++) {
            assertEquals(nz[i] * DEPTH, oz[i], EPSILON, "z of vertex " + i);
            assertEquals(px[i] == 0f ? -DEPTH : DEPTH, ox[i], EPSILON, "x of vertex " + i);
            assertEquals(py[i] == 0f ? -DEPTH : DEPTH, oy[i], EPSILON, "y of vertex " + i);
        }
    }

    @Test
    void facesMeetingAtAnAngleKeepTheirSharedEdgeSealed() {
        // Two quads sharing the edge x = 1, one facing +x and one leaning back at (-0.6, 0.8, 0):
        // an obtuse pair, whose shared vertices must still move together.
        float[] px = {1, 1, 1, 1,  1, 1, 0.4f, 0.4f};
        float[] py = {0, 1, 1, 0,  0, 1, 1.8f, 0.8f};
        float[] pz = {0, 0, 1, 1,  0, 0, 0, 0};
        float[] nx = {1, 1, 1, 1,  -0.6f, -0.6f, -0.6f, -0.6f};
        float[] ny = {0, 0, 0, 0,  0.8f, 0.8f, 0.8f, 0.8f};
        float[] nz = new float[8];
        float[] ox = new float[8], oy = new float[8], oz = new float[8];
        RemixEntitySubmitter.shellOffsets(px, py, pz, nx, ny, nz, 0, 8, DEPTH, EntityTopology.QUADS, NO_CARD, ox, oy, oz);
        // Vertices 0 and 4 share (1, 0, 0); vertices 1 and 5 share (1, 1, 0).
        assertEquals(ox[0], ox[4], EPSILON);
        assertEquals(oy[0], oy[4], EPSILON);
        assertEquals(ox[1], ox[5], EPSILON);
        assertEquals(oy[1], oy[5], EPSILON);
        assertEquals(0.4f * DEPTH, ox[0], EPSILON);
        assertEquals(0.8f * DEPTH, oy[0], EPSILON);
    }

    @Test
    void aSliverStandingMidFaceTravelsWithThatFace() {
        // A T-junction: a thin element stands on two larger faces mid-face, sharing no
        // vertex with them. Its edges must follow the faces or a gap opens between them.
        float thickness = 1f / 16;
        float[] px = {0, 1, 1, 0,  0, 1, 1, 0,  0.4f, 0.6f, 0.6f, 0.4f};
        float[] py = {0, 0, 1, 1,  0, 0, 1, 1,  0.5f, 0.5f, 0.5f, 0.5f};
        float[] pz = {0, 0, 0, 0,  thickness, thickness, thickness, thickness,  0, 0, thickness, thickness};
        float[] nx = new float[12];
        float[] ny = {0, 0, 0, 0,  0, 0, 0, 0,  1, 1, 1, 1};
        float[] nz = {-1, -1, -1, -1,  1, 1, 1, 1,  0, 0, 0, 0};
        float[] ox = new float[12], oy = new float[12], oz = new float[12];
        RemixEntitySubmitter.shellOffsets(px, py, pz, nx, ny, nz, 0, 12, DEPTH, EntityTopology.QUADS, NO_CARD, ox, oy, oz);
        for (int i = 0; i < 4; i++) assertEquals(-DEPTH, oz[i], EPSILON, "front vertex " + i);
        for (int i = 4; i < 8; i++) assertEquals(DEPTH, oz[i], EPSILON, "back vertex " + i);
        // The sliver rises with its own normal and its edges travel with the face each stands on.
        for (int i = 8; i < 12; i++) {
            assertEquals(DEPTH, oy[i], EPSILON, "sliver vertex " + i);
            assertEquals(0f, ox[i], EPSILON, "sliver vertex " + i);
        }
        assertEquals(-DEPTH, oz[8], EPSILON);
        assertEquals(-DEPTH, oz[9], EPSILON);
        assertEquals(DEPTH, oz[10], EPSILON);
        assertEquals(DEPTH, oz[11], EPSILON);
    }

    @Test
    void aCardThickensButNeverMovesInItsPlane() {
        // The same geometry flagged as an item card by its front face. The slivers' edges
        // still travel with the faces they stand on, but the sliver no longer rises with
        // its own normal: the texture's alpha edge it stands on would not rise with it, and
        // a hairline of whatever lies behind the card showed between the two.
        float thickness = 1f / 16;
        float[] px = {0, 1, 1, 0,  0, 1, 1, 0,  0.4f, 0.6f, 0.6f, 0.4f};
        float[] py = {0, 0, 1, 1,  0, 0, 1, 1,  0.5f, 0.5f, 0.5f, 0.5f};
        float[] pz = {0, 0, 0, 0,  thickness, thickness, thickness, thickness,  0, 0, thickness, thickness};
        float[] nx = new float[12];
        float[] ny = {0, 0, 0, 0,  0, 0, 0, 0,  1, 1, 1, 1};
        float[] nz = {-1, -1, -1, -1,  1, 1, 1, 1,  0, 0, 0, 0};
        float[] ox = new float[12], oy = new float[12], oz = new float[12];
        RemixEntitySubmitter.shellOffsets(px, py, pz, nx, ny, nz, 0, 12, DEPTH,
                EntityTopology.QUADS, 0, ox, oy, oz);
        for (int i = 0; i < 12; i++) {
            assertEquals(0f, ox[i], EPSILON, "x of vertex " + i);
            assertEquals(0f, oy[i], EPSILON, "y of vertex " + i);
        }
        for (int i = 0; i < 4; i++) assertEquals(-DEPTH, oz[i], EPSILON, "front vertex " + i);
        for (int i = 4; i < 8; i++) assertEquals(DEPTH, oz[i], EPSILON, "back vertex " + i);
        assertEquals(-DEPTH, oz[8], EPSILON);
        assertEquals(-DEPTH, oz[9], EPSILON);
        assertEquals(DEPTH, oz[10], EPSILON);
        assertEquals(DEPTH, oz[11], EPSILON);
    }

    @Test
    void aStripWeldsSharedPositionsOnly() {
        // A strip has no quads to stand on. Fed the cube's vertices, it still welds the
        // corners that coincide exactly, so a closed tube of strips stays closed.
        float[][] cube = unitCube();
        float[] ox = new float[24], oy = new float[24], oz = new float[24];
        RemixEntitySubmitter.shellOffsets(cube[0], cube[1], cube[2], cube[3], cube[4], cube[5],
                0, 24, DEPTH, EntityTopology.TRIANGLE_STRIP, NO_CARD, ox, oy, oz);
        for (int i = 0; i < 24; i++) {
            assertEquals(cube[0][i] == 0f ? -DEPTH : DEPTH, ox[i], EPSILON, "x of vertex " + i);
            assertEquals(cube[1][i] == 0f ? -DEPTH : DEPTH, oy[i], EPSILON, "y of vertex " + i);
            assertEquals(cube[2][i] == 0f ? -DEPTH : DEPTH, oz[i], EPSILON, "z of vertex " + i);
        }
    }

    @Test
    void theBaseLayerDoesNotMove() {
        float[][] cube = unitCube();
        float[][] o = offsets(cube, 0, 24, 0f);
        for (int i = 0; i < 24; i++) {
            assertEquals(0f, o[0][i]);
            assertEquals(0f, o[1][i]);
            assertEquals(0f, o[2][i]);
        }
    }
}
