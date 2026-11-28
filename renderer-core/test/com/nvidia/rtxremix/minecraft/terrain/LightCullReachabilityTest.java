/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The flood must cover the whole Chebyshev cull box, corners included. A
 * step-count bound would only cover the inscribed diamond and cull the
 * diagonals at 0.707x the radius.
 */
class LightCullReachabilityTest {

    private static final long UNSLICED = Long.MAX_VALUE / 4;

    private static TerrainState openWorld(int radiusChunks) {
        TerrainState terrain = new TerrainState(radiusChunks * 16, UNSLICED, 0L);
        long open = -1L;
        int span = radiusChunks + 2;
        for (int cx = -span; cx <= span; cx++) {
            for (int cz = -span; cz <= span; cz++) {
                terrain.addSection(cx, 0, cz, 1, new Object(),
                        open, open, open, open, open, open, new long[64]);
            }
        }
        terrain.setPlayerPos(0, 0);
        terrain.setPlayerBlockPos(8, 8, 8);
        terrain.ensureReachabilityOnce();
        assertFalse(terrain.isFloodBuildInFlight());
        return terrain;
    }

    @Test
    void everyCornerOfTheRenderDistanceBoxIsReachable() {
        int r = 32;
        TerrainState terrain = openWorld(r);

        // isSectionReachableForLights takes (cx, cz, sy); every section here sits at sy 0.
        assertTrue(terrain.isSectionReachableForLights(r, r, 0));
        assertTrue(terrain.isSectionReachableForLights(-r, r, 0));
        assertTrue(terrain.isSectionReachableForLights(r, -r, 0));
        assertTrue(terrain.isSectionReachableForLights(-r, -r, 0));
        assertFalse(terrain.isSectionReachableForLights(r + 1, r + 1, 0));
        assertFalse(terrain.isColumnWithinLightCullRange(r + 1, 0));
        assertEquals((2 * r + 1) * (2 * r + 1), terrain.reachableCount());
    }

    @Test
    void aSealedRingCullsEverythingBeyondIt() {
        TerrainState terrain = new TerrainState(8 * 16, UNSLICED, 0L);
        long open = -1L;
        for (int cx = -8; cx <= 8; cx++) {
            for (int cz = -8; cz <= 8; cz++) {
                boolean ring = Math.max(Math.abs(cx), Math.abs(cz)) == 3;
                long faces = ring ? 0L : open;
                terrain.addSection(cx, 0, cz, 1, new Object(),
                        faces, faces, faces, faces, faces, faces, new long[64]);
            }
        }
        terrain.setPlayerPos(0, 0);
        terrain.setPlayerBlockPos(8, 8, 8);
        terrain.ensureReachabilityOnce();

        assertTrue(terrain.isSectionReachableForLights(2, 2, 0));
        assertFalse(terrain.isSectionReachableForLights(3, 0, 0), "the ring itself is sealed");
        assertFalse(terrain.isSectionReachableForLights(5, 5, 0), "nothing behind the ring is reached");
        assertEquals(25, terrain.reachableCount());
    }
}
