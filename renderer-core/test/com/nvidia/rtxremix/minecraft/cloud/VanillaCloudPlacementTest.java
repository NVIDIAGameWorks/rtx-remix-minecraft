/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VanillaCloudPlacementTest {
    private static final int TEXTURE_SIZE = 256;

    @Test
    void preservesVanillaDriftAndZPhase() {
        List<VanillaCloudPlacement.Placement> placements = placements(0.0, 0.0, 10.0);
        VanillaCloudPlacement.Placement origin = placements.stream()
                .filter(placement -> placement.textureX() == 0
                        && placement.textureZ() == 0
                        && placement.x() <= 0.0
                        && placement.x() > -VanillaCloudPlacement.WORLD_PERIOD)
                .findFirst()
                .orElseThrow();

        assertEquals(-0.3, origin.x(), 1.0e-9);
        assertEquals(
                -VanillaCloudPlacement.Z_PHASE * VanillaCloudMeshGenerator.CELL_SIZE,
                origin.z(),
                1.0e-9);
    }

    @Test
    void wrapsTextureChunksWithoutChangingGlobalIdentity() {
        double worldTime = 10.0;
        double cameraX = VanillaCloudPlacement.WORLD_PERIOD
                - worldTime * VanillaCloudPlacement.DRIFT_PER_TICK;
        List<VanillaCloudPlacement.Placement> placements =
                placements(cameraX, 0.0, worldTime);
        VanillaCloudPlacement.Placement repeated = placements.stream()
                .filter(placement -> Math.abs(placement.x() - cameraX) < 1.0e-9
                        && placement.textureZ() == 0)
                .findFirst()
                .orElseThrow();
        VanillaCloudPlacement.Placement origin = placements(0.0, 0.0, worldTime).stream()
                .filter(placement -> placement.textureX() == 0
                        && placement.textureZ() == 0
                        && placement.x() <= 0.0
                        && placement.x() > -VanillaCloudPlacement.WORLD_PERIOD)
                .findFirst()
                .orElseThrow();

        assertEquals(0, repeated.textureX());
        assertEquals(origin.textureKey(), repeated.textureKey());
        assertTrue(origin.x() != repeated.x());
    }

    @Test
    void highResolutionTexturesKeepVanillasWorldPeriodAndBoundedIdentitySet() {
        List<VanillaCloudPlacement.Placement> placements =
                placements(0.0, 0.0, 0.0, 2048, 2048);

        assertTrue(placements.stream().allMatch(placement ->
                placement.windowWidth() == 256
                        && placement.windowHeight() == 256
                        && placement.cellSizeX() == 1.5f
                        && placement.cellSizeZ() == 1.5f));
        assertTrue(placements.stream()
                .map(VanillaCloudPlacement.Placement::textureKey)
                .distinct()
                .count() <= 64);
        VanillaCloudPlacement.Placement origin = placements.stream()
                .filter(placement -> placement.textureX() == 0
                        && placement.textureZ() == 0
                        && placement.x() == 0.0)
                .findFirst()
                .orElseThrow();
        VanillaCloudPlacement.Placement repeated =
                placements(VanillaCloudPlacement.WORLD_PERIOD, 0.0, 0.0, 2048, 2048)
                        .stream()
                        .filter(placement -> placement.textureKey() == origin.textureKey()
                                && placement.x() == VanillaCloudPlacement.WORLD_PERIOD)
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                VanillaCloudPlacement.WORLD_PERIOD,
                repeated.x() - origin.x(),
                1.0e-9);
    }

    private static List<VanillaCloudPlacement.Placement> placements(
            double cameraX, double cameraZ, double worldTime) {
        return placements(cameraX, cameraZ, worldTime, TEXTURE_SIZE, TEXTURE_SIZE);
    }

    private static List<VanillaCloudPlacement.Placement> placements(
            double cameraX,
            double cameraZ,
            double worldTime,
            int textureWidth,
            int textureHeight) {
        List<VanillaCloudPlacement.Placement> placements = new ArrayList<>();
        VanillaCloudPlacement.forEachVisible(
                cameraX,
                cameraZ,
                worldTime,
                textureWidth,
                textureHeight,
                cursor -> placements.add(cursor.snapshot()));
        return placements;
    }
}
