/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core;

import com.nvidia.rtxremix.minecraft.cloud.CloudRenderer;
import com.nvidia.rtxremix.minecraft.entity.RemixBlockOutlinePass;
import com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter;
import com.nvidia.rtxremix.minecraft.particle.RemixParticleRenderer;
import com.nvidia.rtxremix.minecraft.sky.SkyLightRenderer;
import com.nvidia.rtxremix.minecraft.sky.SkyRenderer;
import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.texture.ParticleAtlasManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the loader-neutral Remix scene subsystems and their dependency-ordered
 * teardown.
 *
 * <p>Loader adapters capture Minecraft state and may own loader-specific GL
 * resources, but they must not independently decide how the canonical native
 * scene is cleared or closed.</p>
 */
public final class RendererScene implements AutoCloseable {
    public interface ResourceReloadHooks {
        void discardEntityPoseSnapshots();
        void cleanupHostRenderResources();
    }

    interface LifecycleResources {
        void clearTerrain();
        void clearSkyLights();
        void clearEntities();
        void clearBlockOutline();
        void cleanupParticles();
        void cleanupSky();
        void cleanupClouds();
        void reloadEntityMaterials();
        void cleanupParticleAtlas();
        void reloadBlockTextures();
        void reloadSkyResources();
        void shutdownSky();
        void closeSkyLights();
        void destroyTerrain();
        void closeEntities();
        void cleanupBlockTextures();
        void closeBlockOutline();
        void closeAssetStores();
        void collectGarbage();
        void flushAtlasAnimations();
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(RendererScene.class);

    private final RemixAssetStores assetStores;
    private final BlockTextureManager blockTextures;
    private final ChunkManager terrain;
    private final SkyLightRenderer skyLights;
    private final SkyRenderer sky;
    private final CloudRenderer clouds;
    private final ParticleAtlasManager particleAtlas;
    private final RemixEntitySubmitter entities;
    private final RemixParticleRenderer particles;
    private final RemixBlockOutlinePass blockOutline;
    private final LifecycleResources lifecycleResources;
    private boolean closed;

    public RendererScene(
            RemixAssetStores assetStores,
            BlockTextureManager blockTextures,
            ChunkManager terrain,
            SkyLightRenderer skyLights,
            SkyRenderer sky,
            CloudRenderer clouds,
            ParticleAtlasManager particleAtlas,
            RemixEntitySubmitter entities,
            RemixParticleRenderer particles,
            RemixBlockOutlinePass blockOutline) {
        this(
                assetStores,
                blockTextures,
                terrain,
                skyLights,
                sky,
                clouds,
                particleAtlas,
                entities,
                particles,
                blockOutline,
                true);
    }

    /**
     * Wraps the components created before an initialization failure so the
     * normal teardown policy also covers that exceptional path.
     */
    public static RendererScene partiallyInitialized(
            RemixAssetStores assetStores,
            BlockTextureManager blockTextures,
            ChunkManager terrain,
            SkyLightRenderer skyLights,
            SkyRenderer sky,
            CloudRenderer clouds,
            ParticleAtlasManager particleAtlas,
            RemixEntitySubmitter entities,
            RemixParticleRenderer particles,
            RemixBlockOutlinePass blockOutline) {
        return new RendererScene(
                assetStores,
                blockTextures,
                terrain,
                skyLights,
                sky,
                clouds,
                particleAtlas,
                entities,
                particles,
                blockOutline,
                false);
    }

    private RendererScene(
            RemixAssetStores assetStores,
            BlockTextureManager blockTextures,
            ChunkManager terrain,
            SkyLightRenderer skyLights,
            SkyRenderer sky,
            CloudRenderer clouds,
            ParticleAtlasManager particleAtlas,
            RemixEntitySubmitter entities,
            RemixParticleRenderer particles,
            RemixBlockOutlinePass blockOutline,
            boolean requireComplete) {
        this.assetStores = checked(assetStores, "assetStores", requireComplete);
        this.blockTextures = checked(blockTextures, "blockTextures", requireComplete);
        this.terrain = checked(terrain, "terrain", requireComplete);
        this.skyLights = checked(skyLights, "skyLights", requireComplete);
        this.sky = checked(sky, "sky", requireComplete);
        this.clouds = checked(clouds, "clouds", requireComplete);
        this.particleAtlas = checked(particleAtlas, "particleAtlas", requireComplete);
        this.entities = checked(entities, "entities", requireComplete);
        this.particles = checked(particles, "particles", requireComplete);
        this.blockOutline = checked(blockOutline, "blockOutline", requireComplete);
        this.lifecycleResources = new OwnedLifecycleResources(
                assetStores,
                blockTextures,
                terrain,
                skyLights,
                sky,
                clouds,
                particleAtlas,
                entities,
                particles,
                blockOutline);
    }

    RendererScene(LifecycleResources lifecycleResources) {
        this.assetStores = null;
        this.blockTextures = null;
        this.terrain = null;
        this.skyLights = null;
        this.sky = null;
        this.clouds = null;
        this.particleAtlas = null;
        this.entities = null;
        this.particles = null;
        this.blockOutline = null;
        this.lifecycleResources = checked(lifecycleResources, "lifecycleResources", true);
    }

    /**
     * Releases all world-scoped native resources while keeping reusable
     * renderer infrastructure alive for the next world.
     */
    public void clearWorld() {
        if (closed) {
            return;
        }
        lifecycleResources.clearTerrain();
        lifecycleResources.clearSkyLights();
        lifecycleResources.clearEntities();
        lifecycleResources.clearBlockOutline();
        lifecycleResources.cleanupParticles();
        lifecycleResources.cleanupSky();
        cleanupClouds("world cleanup");
    }

    /**
     * Rebuilds material-bearing scene resources in dependency order.
     *
     * <p>The host hooks release captured Minecraft poses and host GL resources;
     * all native Remix ownership and ordering remains canonical here.</p>
     */
    public void reloadResources(ResourceReloadHooks hooks) {
        if (closed) {
            return;
        }
        if (hooks == null) {
            throw new NullPointerException("hooks");
        }
        lifecycleResources.reloadEntityMaterials();
        hooks.discardEntityPoseSnapshots();
        lifecycleResources.cleanupParticles();
        lifecycleResources.cleanupParticleAtlas();
        hooks.cleanupHostRenderResources();
        lifecycleResources.clearTerrain();
        lifecycleResources.reloadBlockTextures();
        lifecycleResources.reloadSkyResources();
        cleanupClouds("resource reload");
    }

    /**
     * Permanently closes canonical scene infrastructure in dependency order.
     *
     * <p>Retained entity meshes go before either atlas because they may borrow
     * materials from both. The caller clears world-scoped state first.</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        lifecycleResources.shutdownSky();
        lifecycleResources.closeSkyLights();
        lifecycleResources.destroyTerrain();
        lifecycleResources.closeEntities();
        lifecycleResources.cleanupParticles();
        lifecycleResources.cleanupParticleAtlas();
        lifecycleResources.cleanupBlockTextures();
        lifecycleResources.closeBlockOutline();
        lifecycleResources.closeAssetStores();
    }

    public void collectGarbage() {
        if (!closed) {
            lifecycleResources.collectGarbage();
        }
    }

    /** Flushes queued block-atlas animation patches to their canonical textures. */
    public void flushAtlasAnimations() {
        if (closed) {
            return;
        }
        lifecycleResources.flushAtlasAnimations();
    }

    public BlockTextureManager blockTextures() {
        return blockTextures;
    }

    public ChunkManager terrain() {
        return terrain;
    }

    public SkyLightRenderer skyLights() {
        return skyLights;
    }

    public SkyRenderer sky() {
        return sky;
    }

    public CloudRenderer clouds() {
        return clouds;
    }

    public ParticleAtlasManager particleAtlas() {
        return particleAtlas;
    }

    public RemixEntitySubmitter entities() {
        return entities;
    }

    public RemixParticleRenderer particles() {
        return particles;
    }

    public RemixBlockOutlinePass blockOutline() {
        return blockOutline;
    }

    private void cleanupClouds(String operation) {
        try {
            lifecycleResources.cleanupClouds();
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to release cloud resources during {}", operation, exception);
        }
    }

    private static final class OwnedLifecycleResources implements LifecycleResources {
        private final RemixAssetStores assetStores;
        private final BlockTextureManager blockTextures;
        private final ChunkManager terrain;
        private final SkyLightRenderer skyLights;
        private final SkyRenderer sky;
        private final CloudRenderer clouds;
        private final ParticleAtlasManager particleAtlas;
        private final RemixEntitySubmitter entities;
        private final RemixParticleRenderer particles;
        private final RemixBlockOutlinePass blockOutline;

        private OwnedLifecycleResources(
                RemixAssetStores assetStores,
                BlockTextureManager blockTextures,
                ChunkManager terrain,
                SkyLightRenderer skyLights,
                SkyRenderer sky,
                CloudRenderer clouds,
                ParticleAtlasManager particleAtlas,
                RemixEntitySubmitter entities,
                RemixParticleRenderer particles,
                RemixBlockOutlinePass blockOutline) {
            this.assetStores = assetStores;
            this.blockTextures = blockTextures;
            this.terrain = terrain;
            this.skyLights = skyLights;
            this.sky = sky;
            this.clouds = clouds;
            this.particleAtlas = particleAtlas;
            this.entities = entities;
            this.particles = particles;
            this.blockOutline = blockOutline;
        }

        @Override public void clearTerrain() { if (terrain != null) terrain.clear(); }
        @Override public void clearSkyLights() { if (skyLights != null) skyLights.clear(); }
        @Override public void clearEntities() { if (entities != null) entities.clear(); }
        @Override public void clearBlockOutline() { if (blockOutline != null) blockOutline.clear(); }
        @Override public void cleanupParticles() { if (particles != null) particles.cleanup(); }
        @Override public void cleanupSky() { if (sky != null) sky.cleanup(); }
        @Override public void cleanupClouds() { if (clouds != null) clouds.cleanup(); }
        @Override public void reloadEntityMaterials() { if (entities != null) entities.reloadMaterials(); }
        @Override public void cleanupParticleAtlas() { if (particleAtlas != null) particleAtlas.cleanup(); }
        @Override public void reloadBlockTextures() { if (blockTextures != null) blockTextures.reload(); }
        @Override public void reloadSkyResources() { if (sky != null) sky.reloadResources(); }
        @Override public void shutdownSky() { if (sky != null) sky.shutdown(); }
        @Override public void closeSkyLights() { if (skyLights != null) skyLights.close(); }
        @Override public void destroyTerrain() { if (terrain != null) terrain.destroy(); }
        @Override public void closeEntities() { if (entities != null) entities.close(); }
        @Override public void cleanupBlockTextures() { if (blockTextures != null) blockTextures.cleanup(); }
        @Override public void closeBlockOutline() { if (blockOutline != null) blockOutline.close(); }
        @Override public void closeAssetStores() { if (assetStores != null) assetStores.close(); }
        @Override public void collectGarbage() { if (assetStores != null) assetStores.collectGarbage(); }
        @Override public void flushAtlasAnimations() {
            if (blockTextures != null) blockTextures.flushAnimationPatches();
        }
    }

    private static <T> T checked(T value, String name, boolean required) {
        if (required && value == null) {
            throw new NullPointerException(name);
        }
        return value;
    }
}
