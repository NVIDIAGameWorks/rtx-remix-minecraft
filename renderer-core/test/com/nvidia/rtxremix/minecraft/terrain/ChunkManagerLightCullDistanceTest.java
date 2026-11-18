/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.core.terrain.TerrainMaterialSet;
import com.nvidia.rtxremix.minecraft.asset.RemixLight;
import com.nvidia.rtxremix.minecraft.light.LightEntry;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkManagerLightCullDistanceTest {
    private static final float TORCH_REFERENCE_POWER = 12.0f;

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
        ChunkManager manager = manager();
        try {
            assertEquals(8, ChunkManager.DEFAULT_LIGHT_CULL_CHUNKS);
            assertEquals(8, manager.getLightCullDistance());
        } finally {
            manager.destroy();
        }
    }

    @Test
    void theUserValueIsKeptApartFromTheResolvedRadius() throws Exception {
        ChunkManager manager = manager();
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
        ChunkManager manager = manager();
        try {
            assertEquals(128, manager.getLightReach());
            manager.setLightReach(4);
            assertEquals(16, manager.getLightReach());
            manager.setLightReach(1000);
            assertEquals(256, manager.getLightReach());
            manager.setLightReach(48);
            assertEquals(48, manager.getLightReach());
            assertEquals(TerrainLight.thresholdFor(48, TORCH_REFERENCE_POWER),
                    manager.lightReachThreshold());
            manager.setLightReach(192);
            assertEquals(TerrainLight.thresholdFor(192, TORCH_REFERENCE_POWER),
                    manager.lightReachThreshold());
            assertTrue(manager.lightReachThreshold()
                            < TerrainLight.thresholdFor(48, TORCH_REFERENCE_POWER),
                    "a longer reach is a lower weight floor");
        } finally {
            manager.destroy();
        }
    }

    @Test
    void lightStatsGroupByRuleAndCountOnlyLightsDrawnThisFrame() throws Exception {
        ChunkManager manager = manager();
        try {
            TerrainState terrain = terrain(manager);
            TerrainState.SectionEntry entry = terrain.addSection(0, 0, 0, 1, "section");
            TerrainLight drawn = new TerrainLight(new RemixLight(null, null));
            TerrainLight skipped = new TerrainLight(new RemixLight(null, null));
            TerrainLight other = new TerrainLight(new RemixLight(null, null));
            entry.putLight(key(0, "torch"), drawn);
            entry.putLight(key(1, "torch"), skipped);
            entry.putLight(key(2, "lantern"), other);
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
            assertEquals("rtxremix:torch", stats.get(0).ruleId());
            assertEquals(2, stats.get(0).total());
            assertEquals(1, stats.get(0).drawn());
            assertEquals("rtxremix:lantern", stats.get(1).ruleId());
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

    private static BlockLightKey key(int x, String path) {
        return new BlockLightKey(x, "rtxremix:" + path, 0);
    }

    private static ChunkManager manager() {
        return new ChunkManager(new TestHost());
    }

    private static final class TestHost implements ChunkManager.Host {
        @Override public RemixAPI remixApi() { return null; }
        @Override public TerrainMaterialSet terrainMaterials() { return null; }
        @Override public boolean areTerrainMaterialsReady() { return false; }
        @Override public boolean hasWorld() { return false; }
        @Override public long worldEpoch() { return 0; }
        @Override public int viewDistanceChunks() { return 12; }
        @Override public boolean isChunkLoaded(int chunkX, int chunkZ) { return false; }
        @Override public boolean isSectionEmpty(
                int chunkX, int sectionY, int chunkZ) { return false; }
        @Override public int bottomSectionCoord() { return 0; }
        @Override public int topSectionCoord() { return 0; }
        @Override public void scheduleResidentSectionRebuild(
                int sectionX, int sectionY, int sectionZ) { }
        @Override public TerrainLightReconciler.Radiance resolveRadiance(
                LightEntry entry) {
            throw new AssertionError("not used");
        }
        @Override public float torchReferencePower() {
            return TORCH_REFERENCE_POWER;
        }
    }
}
