/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkyLightSurfaceScanPolicyTest {

    @Test
    void seedingIsOnUnlessTheKillSwitchIsSet() {
        assertTrue(SkyLightSurfaceScanPolicy.seedAllowedFrom(null));
        assertTrue(SkyLightSurfaceScanPolicy.seedAllowedFrom("false"));
        assertFalse(SkyLightSurfaceScanPolicy.seedAllowedFrom("true"));
    }

    @Test
    void seedsOnlyWhenThePacketCarriesTheSurfaceHeightmap() {
        assertTrue(SkyLightSurfaceScanPolicy.seedFromHeightmap(true, true));
        assertFalse(SkyLightSurfaceScanPolicy.seedFromHeightmap(true, false));
        assertFalse(SkyLightSurfaceScanPolicy.seedFromHeightmap(false, true));
    }

    @Test
    void seededScansAreCountedAndDrained() {
        SkyLightSurfaceScanPolicy.drainSeeded();
        SkyLightSurfaceScanPolicy.recordSeeded();
        SkyLightSurfaceScanPolicy.recordSeeded();
        assertEquals(2, SkyLightSurfaceScanPolicy.drainSeeded());
        assertEquals(0, SkyLightSurfaceScanPolicy.drainSeeded());
    }

    @Test
    void aMissingHeightmapIsReportedOnce() {
        // The flag is process-wide and never reset; this is its only caller in the suite.
        assertTrue(SkyLightSurfaceScanPolicy.noteMissingHeightmap());
        assertFalse(SkyLightSurfaceScanPolicy.noteMissingHeightmap());
    }
}
