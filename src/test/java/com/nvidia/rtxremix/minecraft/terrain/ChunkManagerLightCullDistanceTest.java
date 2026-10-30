/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nvidia.rtxremix.minecraft.asset.RemixLight;
import com.nvidia.rtxremix.minecraft.light.BlockLightKey;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ChunkManagerLightCullDistanceTest {

    @Test
    void autoFollowsTheRenderDistanceAndExplicitValuesNeverExceedIt() {
        assertEquals(32, ChunkManager.effectiveLightCullChunks(0, 32));
        assertEquals(12, ChunkManager.effectiveLightCullChunks(0, 12));
        assertEquals(12, ChunkManager.effectiveLightCullChunks(12, 32));
        assertEquals(12, ChunkManager.effectiveLightCullChunks(32, 12));
        assertEquals(5, ChunkManager.effectiveLightCullChunks(5, 8));
    }

    @Test
    void theShippedDefaultStaysEightChunks() {
        ChunkManager manager = new ChunkManager(null);
        try {
            assertEquals(8, ChunkManager.DEFAULT_LIGHT_CULL_CHUNKS);
            assertEquals(8, manager.getLightCullDistance());
        } finally {
            manager.destroy();
        }
    }

    @Test
    void theUserValueIsKeptApartFromTheResolvedRadius() throws Exception {
        ChunkManager manager = new ChunkManager(null);
        try {
            TerrainState terrain = terrain(manager);
            manager.setLightCullDistance(0);
            manager.syncLightCullDistance();
            // Headless, the client view distance falls back to 12 chunks.
            assertEquals(0, manager.getLightCullDistance());
            assertEquals(12 * 16, terrain.getLightCullBlocks());

            manager.setLightCullDistance(32);
            manager.syncLightCullDistance();
            assertEquals(32, manager.getLightCullDistance());
            assertEquals(12 * 16, terrain.getLightCullBlocks());

            manager.setLightCullDistance(3);
            manager.syncLightCullDistance();
            assertEquals(3 * 16, terrain.getLightCullBlocks());
        } finally {
            manager.destroy();
        }
    }

    @Test
    void lightReachClampsToItsRangeAndDefaultsToOneHundredTwentyEightBlocks() {
        ChunkManager manager = new ChunkManager(null);
        try {
            assertEquals(128, manager.getLightReach());
            manager.setLightReach(4);
            assertEquals(16, manager.getLightReach());
            manager.setLightReach(1000);
            assertEquals(256, manager.getLightReach());
            manager.setLightReach(48);
            assertEquals(48, manager.getLightReach());
            assertEquals(TerrainLight.thresholdFor(48), manager.lightReachThreshold());
            manager.setLightReach(192);
            assertEquals(TerrainLight.thresholdFor(192), manager.lightReachThreshold());
            assertTrue(manager.lightReachThreshold() < TerrainLight.thresholdFor(48),
                    "a longer reach is a lower weight floor");
        } finally {
            manager.destroy();
        }
    }

    @Test
    void lightStatsGroupByRuleAndCountOnlyLightsDrawnThisFrame() throws Exception {
        ChunkManager manager = new ChunkManager(null);
        try {
            TerrainState terrain = terrain(manager);
            TerrainState.SectionEntry entry = terrain.addSection(0, 0, 0, 1, "section");
            TerrainLight drawn = new TerrainLight(new RemixLight(null, null));
            TerrainLight skipped = new TerrainLight(new RemixLight(null, null));
            TerrainLight other = new TerrainLight(new RemixLight(null, null));
            entry.putLight(new BlockLightKey(new BlockPos(0, 0, 0), ResourceLocation.fromNamespaceAndPath("rtxremix", "torch"), 0), drawn);
            entry.putLight(new BlockLightKey(new BlockPos(1, 0, 0), ResourceLocation.fromNamespaceAndPath("rtxremix", "torch"), 0), skipped);
            entry.putLight(new BlockLightKey(new BlockPos(2, 0, 0), ResourceLocation.fromNamespaceAndPath("rtxremix", "lantern"), 0), other);
            drawn.place(0, 0, 0, 1f);
            skipped.place(0, 0, 0, 1f);
            other.place(0, 0, 0, 1f);

            Field frame = ChunkManager.class.getDeclaredField("terrainFrame");
            frame.setAccessible(true);
            frame.setLong(manager, 7L);
            drawn.shouldDraw(0, 0, 0, 0f, 7L);
            skipped.shouldDraw(500, 0, 0, Float.MAX_VALUE, 7L);

            List<ChunkManager.LightRuleStats> stats = manager.lightStatsByRule();
            assertEquals(2, stats.size());
            assertEquals(ResourceLocation.fromNamespaceAndPath("rtxremix", "torch"), stats.get(0).ruleId());
            assertEquals(2, stats.get(0).total());
            assertEquals(1, stats.get(0).drawn());
            assertEquals(ResourceLocation.fromNamespaceAndPath("rtxremix", "lantern"), stats.get(1).ruleId());
            assertEquals(1, stats.get(1).total());
            assertEquals(0, stats.get(1).drawn());
        } finally {
            manager.destroy();
        }
    }

    private static TerrainState terrain(ChunkManager manager) throws Exception {
        Field field = ChunkManager.class.getDeclaredField("terrain");
        field.setAccessible(true);
        return (TerrainState) field.get(manager);
    }
}
