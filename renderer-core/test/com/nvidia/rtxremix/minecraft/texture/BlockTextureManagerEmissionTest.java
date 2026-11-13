/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlockTextureManagerEmissionTest {

    @Test
    void terrainEmissionMultiplierScalesTheAuthoredMaterialIntensity() {
        BlockTextureManager manager = new BlockTextureManager(null, null);

        assertEquals(1.0f, manager.getTerrainEmissionMultiplier());
        assertEquals(100.0f, manager.terrainEmissionIntensity());

        manager.setTerrainEmissionMultiplier(0.25f);
        assertEquals(0.25f, manager.getTerrainEmissionMultiplier());
        assertEquals(100.0f, manager.terrainEmissionIntensity());
        assertTrue(manager.commitTerrainEmissionMultiplier());
        assertEquals(25.0f, manager.terrainEmissionIntensity());
        assertFalse(manager.commitTerrainEmissionMultiplier());

        manager.setTerrainEmissionMultiplier(0.0f);
        assertEquals(25.0f, manager.terrainEmissionIntensity());
        assertTrue(manager.commitTerrainEmissionMultiplier());
        assertEquals(0.0f, manager.terrainEmissionIntensity());
    }

    @Test
    void terrainEmissionMultiplierStaysWithinItsSettingsRange() {
        BlockTextureManager manager = new BlockTextureManager(null, null);

        manager.setTerrainEmissionMultiplier(-1.0f);
        assertEquals(0.0f, manager.getTerrainEmissionMultiplier());

        manager.setTerrainEmissionMultiplier(100.0f);
        assertEquals(
                BlockTextureManager.MAX_TERRAIN_EMISSION_MULTIPLIER,
                manager.getTerrainEmissionMultiplier());
    }

    @Test
    void lensMaterialScalesItsEmissionIndependently() {
        assertEquals(0.0f, BlockTextureManager.scaledEmissionIntensity(100.0f, 0.0f));
        assertEquals(100.0f, BlockTextureManager.scaledEmissionIntensity(100.0f, 1.0f));
        assertEquals(
                100_000.0f,
                BlockTextureManager.scaledEmissionIntensity(100.0f, 1000.0f));
        assertEquals(0.0f, BlockTextureManager.scaledEmissionIntensity(100.0f, -1.0f));
    }
}
