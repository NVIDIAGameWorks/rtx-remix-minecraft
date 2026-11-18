/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudRendererTest {
    @Test
    void defaultsToVanillaClouds() {
        assertFalse(new CloudRenderer(null, null).isProceduralClouds());
    }

    @Test
    void visibilityRefreshesOnScheduleOrSignificantCameraMovement() {
        assertTrue(CloudRenderer.needsVisibilityRefresh(
                4L, 3L, 0.0, 0.0, 0.0, 0.0));
        assertTrue(CloudRenderer.needsVisibilityRefresh(
                3L, 3L, 0.0, 0.0, Double.NaN, Double.NaN));
        assertFalse(CloudRenderer.needsVisibilityRefresh(
                3L, 3L, CloudPlacement.CELL_SIZE - 1.0, 0.0, 0.0, 0.0));
        assertTrue(CloudRenderer.needsVisibilityRefresh(
                3L, 3L, CloudPlacement.CELL_SIZE, 0.0, 0.0, 0.0));
        assertTrue(CloudRenderer.needsVisibilityRefresh(
                3L, 3L, 10_000.0, -10_000.0, 0.0, 0.0));
    }

    @Test
    void cloudColorKeepsVanillaTintSubtleAndQuantized() {
        assertEquals(0x00cc_cccc, CloudRenderer.packCloudColor(0.0, 0.0, 0.0));
        assertEquals(0x00ff_e6cc, CloudRenderer.packCloudColor(2.0, 0.5, -1.0));
        assertEquals(0x00ff_ccff, CloudRenderer.packCloudColor(
                Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY));
    }

    @Test
    void everyPaletteEntryIsReachableByVisibleClouds() {
        boolean[] reached = new boolean[CloudRenderer.PALETTE_SIZE];
        for (int z = -128; z <= 128; z++) {
            for (int x = -128; x <= 128; x++) {
                long seed = CloudPlacement.seed(x, z);
                if ((CloudPlacement.mix(seed) & 7L) != 0L) {
                    reached[CloudRenderer.paletteIndex(seed)] = true;
                }
            }
        }

        for (int index = 0; index < reached.length; index++) {
            assertTrue(reached[index], "Palette entry " + index + " is unreachable");
        }
    }

    @Test
    void paletteFailureFallsBackToVanillaUntilCleanupResetsIt() {
        CloudRenderer renderer = new CloudRenderer(null, null);
        renderer.setProceduralClouds(true);
        long failedEpoch = renderer.paletteGenerationEpoch();

        assertThrows(IllegalStateException.class, () -> renderer.publishPaletteResult(
                new CloudRenderer.FailedPaletteGeneration(
                        failedEpoch, new OutOfMemoryError("generation failed"))));
        assertFalse(renderer.shouldSuppressVanillaClouds());

        renderer.cleanup();

        assertTrue(renderer.shouldSuppressVanillaClouds());
        assertFalse(renderer.publishPaletteResult(
                new CloudRenderer.FailedPaletteGeneration(
                        failedEpoch, new AssertionError("stale result was consumed"))));
        assertTrue(renderer.shouldSuppressVanillaClouds());
    }

    @Test
    void vanillaFailureFallsBackUntilChunkInvalidationResetsIt() {
        CloudRenderer renderer = new CloudRenderer(null, null);
        renderer.setProceduralClouds(false);
        long failedEpoch = renderer.vanillaGenerationEpoch();

        assertThrows(IllegalStateException.class, () -> renderer.publishVanillaResult(
                new CloudRenderer.FailedVanillaChunk(
                        failedEpoch, 7L, new OutOfMemoryError("generation failed"))));
        assertFalse(renderer.shouldSuppressVanillaClouds());

        renderer.setVanillaEdgeSmoothness(renderer.getVanillaEdgeSmoothness() + 0.25f);

        assertTrue(renderer.shouldSuppressVanillaClouds());
        renderer.publishVanillaResult(new CloudRenderer.FailedVanillaChunk(
                failedEpoch, 7L, new AssertionError("stale result was consumed")));
        assertTrue(renderer.shouldSuppressVanillaClouds());
    }

    @Test
    void meshPublicationFailureLatchesTheActiveCloudMode() {
        RuntimeException creationFailure = new IllegalStateException("mesh creation failed");

        CloudRenderer procedural = new CloudRenderer(
                null, null, (vertices, indices) -> {
                    throw creationFailure;
                });
        procedural.setProceduralClouds(true);
        long paletteEpoch = procedural.paletteGenerationEpoch();
        IllegalStateException paletteFailure = assertThrows(
                IllegalStateException.class,
                () -> procedural.publishPaletteResult(new CloudRenderer.GeneratedPaletteMesh(
                        paletteEpoch,
                        0,
                        new CloudMeshGenerator.GeneratedMesh(
                                new float[0], new int[]{0, 0, 0}, 0L))));
        assertEquals(creationFailure, paletteFailure.getCause());
        assertFalse(procedural.shouldSuppressVanillaClouds());
        assertFalse(procedural.publishPaletteResult(
                new CloudRenderer.CompletedPaletteGeneration(paletteEpoch)));

        CloudRenderer vanilla = new CloudRenderer(
                null, null, (vertices, indices) -> {
                    throw creationFailure;
                });
        vanilla.setProceduralClouds(false);
        long vanillaEpoch = vanilla.vanillaGenerationEpoch();
        IllegalStateException vanillaFailure = assertThrows(
                IllegalStateException.class,
                () -> vanilla.publishVanillaResult(new CloudRenderer.GeneratedVanillaChunk(
                        vanillaEpoch,
                        11L,
                        new VanillaCloudMeshGenerator.GeneratedMesh(
                                new float[0], new int[]{0, 0, 0}, 0L))));
        assertEquals(creationFailure, vanillaFailure.getCause());
        assertFalse(vanilla.shouldSuppressVanillaClouds());
    }
}
