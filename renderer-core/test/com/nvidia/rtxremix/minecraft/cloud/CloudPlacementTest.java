/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudPlacementTest {
    @Test
    void placementIsDeterministic() {
        Map<Long, CloudPlacement.Placement> first = collect(1234.5, -678.25, 9000.5);
        Map<Long, CloudPlacement.Placement> second = collect(1234.5, -678.25, 9000.5);

        assertFalse(first.isEmpty());
        assertEquals(first, second);
    }

    @Test
    void windUsesAbsoluteWorldTimeWithoutChangingCloudIdentity() {
        double elapsedTicks = 120.0;
        double cameraX = 400.0;
        double cameraZ = -250.0;
        Map<Long, CloudPlacement.Placement> before = collect(cameraX, cameraZ, 1000.0);
        Map<Long, CloudPlacement.Placement> after = collect(
                cameraX + elapsedTicks * CloudPlacement.WIND_X_PER_TICK,
                cameraZ + elapsedTicks * CloudPlacement.WIND_Z_PER_TICK,
                1000.0 + elapsedTicks);

        assertEquals(before.keySet(), after.keySet());
        for (long key : before.keySet()) {
            assertEquals(
                    before.get(key).x() + elapsedTicks * CloudPlacement.WIND_X_PER_TICK,
                    after.get(key).x(),
                    1.0e-9);
            assertEquals(
                    before.get(key).z() + elapsedTicks * CloudPlacement.WIND_Z_PER_TICK,
                    after.get(key).z(),
                    1.0e-9);
            assertEquals(before.get(key).seed(), after.get(key).seed());
        }
    }

    @Test
    void teleportSelectionContainsOnlyNearbyClouds() {
        double cameraX = 1_000_000.0;
        double cameraZ = -1_000_000.0;
        Map<Long, CloudPlacement.Placement> placements = collect(cameraX, cameraZ, 50_000.0);

        assertFalse(placements.isEmpty());
        double maximumDistanceSquared =
                CloudPlacement.VIEW_DISTANCE * CloudPlacement.VIEW_DISTANCE;
        for (CloudPlacement.Placement placement : placements.values()) {
            double dx = placement.x() - cameraX;
            double dz = placement.z() - cameraZ;
            assertTrue(dx * dx + dz * dz <= maximumDistanceSquared);
            assertTrue(placement.altitudeOffset() >= 40.0f);
            assertTrue(placement.altitudeOffset() <= 100.0f);
            assertTrue(placement.scale() >= 1.625f);
            assertTrue(placement.scale() <= 2.275f);
        }
    }

    private static Map<Long, CloudPlacement.Placement> collect(
            double cameraX,
            double cameraZ,
            double worldTime) {
        Map<Long, CloudPlacement.Placement> result = new HashMap<>();
        CloudPlacement.forEachVisible(
                cameraX,
                cameraZ,
                worldTime,
                placement -> result.put(placement.key(), placement));
        return result;
    }
}
