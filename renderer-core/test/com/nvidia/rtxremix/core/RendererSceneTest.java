/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RendererSceneTest {
    @Test
    void clearWorldUsesCanonicalDependencyOrder() {
        RecordingResources resources = new RecordingResources();
        RendererScene scene = new RendererScene(resources);

        scene.clearWorld();

        assertEquals(Arrays.asList(
                "terrain.clear",
                "skyLights.clear",
                "entities.clear",
                "blockOutline.clear",
                "particles.cleanup",
                "sky.cleanup",
                "clouds.cleanup"), resources.events);
    }

    @Test
    void reloadInterleavesHostCleanupWithCanonicalResources() {
        RecordingResources resources = new RecordingResources();
        RendererScene scene = new RendererScene(resources);

        scene.reloadResources(new RendererScene.ResourceReloadHooks() {
            @Override
            public void discardEntityPoseSnapshots() {
                resources.record("host.discardEntityPoses");
            }

            @Override
            public void cleanupHostRenderResources() {
                resources.record("host.cleanupRenderResources");
            }
        });

        assertEquals(Arrays.asList(
                "entities.reloadMaterials",
                "host.discardEntityPoses",
                "particles.cleanup",
                "particleAtlas.cleanup",
                "host.cleanupRenderResources",
                "terrain.clear",
                "blockTextures.reload",
                "sky.reloadResources",
                "clouds.cleanup"), resources.events);
    }

    @Test
    void closeUsesDependencyOrderAndIsIdempotent() {
        RecordingResources resources = new RecordingResources();
        RendererScene scene = new RendererScene(resources);

        scene.close();
        scene.close();
        scene.clearWorld();
        scene.collectGarbage();
        scene.flushAtlasAnimations();

        assertEquals(Arrays.asList(
                "sky.shutdown",
                "skyLights.close",
                "terrain.destroy",
                "entities.close",
                "particles.cleanup",
                "particleAtlas.cleanup",
                "blockTextures.cleanup",
                "blockOutline.close",
                "assetStores.close"), resources.events);
    }

    @Test
    void cloudCleanupFailureStaysLocal() {
        RecordingResources resources = new RecordingResources();
        resources.failCloudCleanup = true;
        RendererScene scene = new RendererScene(resources);

        assertDoesNotThrow(scene::clearWorld);
        assertEquals("clouds.cleanup", resources.events.get(resources.events.size() - 1));
    }

    @Test
    void partiallyInitializedSceneToleratesEveryMissingResource() {
        RendererScene scene = RendererScene.partiallyInitialized(
                null, null, null, null, null, null, null, null, null, null);

        assertDoesNotThrow(scene::clearWorld);
        assertDoesNotThrow(scene::close);
    }

    private static final class RecordingResources implements RendererScene.LifecycleResources {
        private final List<String> events = new ArrayList<String>();
        private boolean failCloudCleanup;

        private void record(String event) {
            events.add(event);
        }

        @Override public void clearTerrain() { record("terrain.clear"); }
        @Override public void clearSkyLights() { record("skyLights.clear"); }
        @Override public void clearEntities() { record("entities.clear"); }
        @Override public void clearBlockOutline() { record("blockOutline.clear"); }
        @Override public void cleanupParticles() { record("particles.cleanup"); }
        @Override public void cleanupSky() { record("sky.cleanup"); }
        @Override public void cleanupClouds() {
            record("clouds.cleanup");
            if (failCloudCleanup) {
                throw new IllegalStateException("cloud cleanup failed");
            }
        }
        @Override public void reloadEntityMaterials() { record("entities.reloadMaterials"); }
        @Override public void cleanupParticleAtlas() { record("particleAtlas.cleanup"); }
        @Override public void reloadBlockTextures() { record("blockTextures.reload"); }
        @Override public void reloadSkyResources() { record("sky.reloadResources"); }
        @Override public void shutdownSky() { record("sky.shutdown"); }
        @Override public void closeSkyLights() { record("skyLights.close"); }
        @Override public void destroyTerrain() { record("terrain.destroy"); }
        @Override public void closeEntities() { record("entities.close"); }
        @Override public void cleanupBlockTextures() { record("blockTextures.cleanup"); }
        @Override public void closeBlockOutline() { record("blockOutline.close"); }
        @Override public void closeAssetStores() { record("assetStores.close"); }
        @Override public void collectGarbage() { record("assetStores.collectGarbage"); }
        @Override public void flushAtlasAnimations() { record("blockTextures.flushAnimations"); }
    }
}
