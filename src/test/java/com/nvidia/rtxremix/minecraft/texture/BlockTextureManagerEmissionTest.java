/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class BlockTextureManagerEmissionTest {

    private static final long ALBEDO = 1, NORMAL = 2, EMISSIVE = 6;

    @Test
    void workerMaterialLookupNeverCreatesResources() {
        BlockTextureManager manager = new BlockTextureManager(null);
        CompletableFuture.runAsync(() -> {
            for (RedstoneEmission emission : RedstoneEmission.values()) {
                for (int layer = 0; layer < BlockTextureManager.LAYER_COUNT; layer++) {
                    assertNull(manager.getAtlasMaterial(layer, false, emission));
                    assertNull(manager.getAtlasMaterial(layer, true, emission));
                }
            }
        }).join();
    }

    @Test
    void dustStatesShareTheAtlasTexturesAndDifferOnlyInEmission() throws Exception {
        BlockTextureManager manager = new BlockTextureManager(null);
        String[] textures = {"atlasAlbedo", "atlasNormalTex", "atlasHeightTex", "atlasRoughnessTex",
                "atlasMetallicTex", "atlasEmissive", "atlasSssTex", "atlasSssAlbedoTex"};
        for (int i = 0; i < textures.length; i++) {
            Field field = BlockTextureManager.class.getDeclaredField(textures[i]);
            field.setAccessible(true);
            field.set(manager, new TestTexture(i + 1));
        }
        manager.setTerrainEmissionMultiplier(0.25f);
        manager.commitTerrainEmissionMultiplier();
        for (int layer : new int[] {BlockTextureManager.L_SOLID, BlockTextureManager.L_CUTOUT,
                BlockTextureManager.L_CUTOUT_MIPPED}) {
            for (boolean sss : new boolean[] {false, true}) {
                MaterialInfo ordinary = manager.atlasMaterialInfo(layer, sss, RedstoneEmission.NONE);
                MaterialInfo unpowered = manager.atlasMaterialInfo(layer, sss, RedstoneEmission.UNPOWERED);
                MaterialInfo powered = manager.atlasMaterialInfo(layer, sss, RedstoneEmission.POWERED);
                // Shared emission would light every other use of the dust sprites; dust glows
                // from its own albedo, which the runtime tints by the power colour.
                assertEquals(EMISSIVE, ordinary.emissiveTexture);
                assertEquals(0L, unpowered.emissiveTexture);
                assertEquals(ALBEDO, powered.emissiveTexture);
                assertEquals(25f, powered.emissiveIntensity, 0.00001f);
                for (MaterialInfo info : new MaterialInfo[] {ordinary, unpowered, powered}) {
                    assertEquals(ALBEDO, info.albedoTexture);
                    assertEquals(NORMAL, info.normalTexture);
                    assertEquals(0, info.filterMode);
                    assertNull(info.translucentExt);
                    assertEquals(3L, info.opaqueExt.heightTexture);
                    assertEquals(4L, info.opaqueExt.roughnessTexture);
                    assertEquals(5L, info.opaqueExt.metallicTexture);
                    assertEquals(1, info.opaqueExt.useDrawCallAlphaState);
                    if (sss) assertNotNull(info.opaqueExt.subsurfaceExt);
                    else assertNull(info.opaqueExt.subsurfaceExt);
                }
            }
        }
    }

    private static final class TestTexture extends RemixTexture {
        TestTexture(long textureHandle) {
            super(1, 1, 0, 0, null);
            handle = textureHandle;
        }
    }

    @Test
    void terrainEmissionMultiplierScalesTheAuthoredMaterialIntensity() {
        BlockTextureManager manager = new BlockTextureManager(null);

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
        BlockTextureManager manager = new BlockTextureManager(null);

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
