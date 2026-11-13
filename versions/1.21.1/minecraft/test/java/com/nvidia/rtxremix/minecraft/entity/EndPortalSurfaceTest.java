/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how closely the baked end portal tile follows vanilla's shader and that it is
 * opaque. The lattice is what makes the tile repeat without a seam, so the test bounds
 * how far rounding it may pull each layer from vanilla's scale and rotation.
 */
class EndPortalSurfaceTest {

    @Test
    void layerLatticesMatchHandComputedSteps() {
        // Worked by hand from rendertype_end_portal.fsh over two screens: layer 1 is
        // 8.5 at 20 degrees, layer 2 is 8 at 44, layer 13 is 2.5 at 212, and layer 15 is
        // 1.5 at a whole number of turns, which rounds up to 2.
        assertArrayEquals(new int[] {8, 3}, EndPortalSurface.layerLattice(1));
        assertArrayEquals(new int[] {6, 6}, EndPortalSurface.layerLattice(2));
        assertArrayEquals(new int[] {-2, -1}, EndPortalSurface.layerLattice(13));
        assertArrayEquals(new int[] {2, 0}, EndPortalSurface.layerLattice(15));
    }

    @Test
    void everyLayerLatticeStaysCloseToVanillasScaleAndRotation() {
        for (int layer = 1; layer <= EndPortalSurface.GATEWAY_LAYERS; layer++) {
            int[] lattice = EndPortalSurface.layerLattice(layer);
            double scale = (4.5 - layer / 4.0) * EndPortalSurface.SCREENS_PER_TILE;
            double angle = Math.toRadians((layer * layer * 4321.0 + layer * 9.0) * 2.0);

            double length = Math.hypot(lattice[0], lattice[1]);
            assertTrue(length > 0, "layer " + layer + " must repeat at least once");
            assertTrue(Math.abs(length / scale - 1) <= 0.34,
                    "layer " + layer + " scale " + length + " vs " + scale);
            double turn = Math.atan2(lattice[1], lattice[0]) - angle;
            double wrapped = Math.toDegrees(Math.atan2(Math.sin(turn), Math.cos(turn)));
            assertTrue(Math.abs(wrapped) <= 15, "layer " + layer + " turned " + wrapped);
        }
    }

    @Test
    void bakeSumsEveryTintedLayerAndIsOpaque() {
        EndPortalSurface.Image black = new EndPortalSurface.Image(new int[] {0x000000}, 1, 1);
        EndPortalSurface.Image grey = new EndPortalSurface.Image(new int[] {0x404040}, 1, 1);
        byte[] rgba = EndPortalSurface.bake(black, grey, EndPortalSurface.PORTAL_LAYERS, 4);

        // Over a black sky, each pixel is the grey times the sum of COLORS[0..14], as
        // rendertype_end_portal.fsh adds the fifteen portal layers: red sums to
        // 1.275264, green to 2.347789 and blue to 2.499272, so 64/255 of them is 81.6,
        // 150.3 and 160.0 of 255.
        assertEquals(4 * 4 * 4, rgba.length);
        for (int i = 0; i < rgba.length; i += 4) {
            assertEquals(82, rgba[i] & 0xFF);
            assertEquals(150, rgba[i + 1] & 0xFF);
            assertEquals(160, rgba[i + 2] & 0xFF);
            assertEquals(255, rgba[i + 3] & 0xFF);
        }
    }

    @Test
    void desaturatePullsEachTexelTowardItsLumaAndKeepsAlpha() {
        // Luma of (0, 100, 200) is 0.7152 * 100 + 0.0722 * 200 = 85.96.
        byte[] rgba = {0, 100, (byte) 200, (byte) 0xFF};
        EndPortalSurface.desaturate(rgba, 0.5f);
        assertEquals(43, rgba[0] & 0xFF);
        assertEquals(93, rgba[1] & 0xFF);
        assertEquals(143, rgba[2] & 0xFF);
        assertEquals(255, rgba[3] & 0xFF);
    }

    @Test
    void bakeRepeatsWithoutASeam() {
        // A texture with a distinct value in every texel: a layer that does not repeat
        // a whole number of times across the tile shades a point and the same point
        // one tile on differently.
        int width = 16;
        int[] rgb = new int[width * width];
        for (int i = 0; i < rgb.length; i++) rgb[i] = (int) ((i * 2654435761L) & 0xFFFFFF);
        EndPortalSurface.Image noise = new EndPortalSurface.Image(rgb, width, width);

        // Points off the diagonal: where x equals y, a layer whose lattice terms cancel
        // lands on its offset exactly, which for several layers is a texel edge, and the
        // last bit of x + 1 decides which side it samples.
        double[][] points = {{0.0123, 0.2718}, {0.2718, 0.5772}, {0.5772, 0.9137},
                {0.9137, 0.0123}, {0.3141, 0.7071}};
        for (double[] p : points) {
            double x = p[0];
            double y = p[1];
            int here = EndPortalSurface.shade(noise, noise, EndPortalSurface.GATEWAY_LAYERS, x, y);
            assertEquals(here, EndPortalSurface.shade(
                    noise, noise, EndPortalSurface.GATEWAY_LAYERS, x + 1, y), "x " + x + ", y " + y);
            assertEquals(here, EndPortalSurface.shade(
                    noise, noise, EndPortalSurface.GATEWAY_LAYERS, x, y + 1), "x " + x + ", y " + y);
        }
    }
}
