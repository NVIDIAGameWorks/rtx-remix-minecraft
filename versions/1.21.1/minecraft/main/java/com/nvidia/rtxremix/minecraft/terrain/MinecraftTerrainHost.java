/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.core.terrain.TerrainMaterialSet;
import com.nvidia.rtxremix.minecraft.ClientViewDistance;
import com.nvidia.rtxremix.minecraft.light.BlockLightIntensityControls;
import com.nvidia.rtxremix.minecraft.light.BlockLightRuleManager;
import com.nvidia.rtxremix.minecraft.light.LightEntry;
import com.nvidia.rtxremix.minecraft.light.LightPower;
import com.nvidia.rtxremix.minecraft.light.LightPowerPolicy;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;

/** Minecraft 1.21 access for the canonical terrain driver. */
public final class MinecraftTerrainHost implements ChunkManager.Host {
    private final RemixAPI remixApi;
    private final BlockTextureManager textures;
    private ClientLevel observedWorld;
    private long worldEpoch;

    public MinecraftTerrainHost(RemixAPI remixApi, BlockTextureManager textures) {
        this.remixApi = Objects.requireNonNull(remixApi, "remixApi");
        this.textures = Objects.requireNonNull(textures, "textures");
        TerrainProfileAdapter.install();
    }

    @Override
    public RemixAPI remixApi() {
        return remixApi;
    }

    @Override
    public TerrainMaterialSet terrainMaterials() {
        return textures;
    }

    @Override
    public boolean areTerrainMaterialsReady() {
        return textures.isInitialized();
    }

    @Override
    public boolean hasWorld() {
        return world() != null;
    }

    @Override
    public long worldEpoch() {
        ClientLevel world = world();
        if (world != observedWorld) {
            observedWorld = world;
            worldEpoch++;
        }
        return worldEpoch;
    }

    @Override
    public int viewDistanceChunks() {
        return ClientViewDistance.chunks();
    }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        ClientLevel world = world();
        return world != null
                && world.getChunkSource().getChunk(chunkX, chunkZ, false) != null;
    }

    @Override
    public boolean isSectionEmpty(int chunkX, int sectionY, int chunkZ) {
        ClientLevel world = world();
        if (world == null) {
            return false;
        }
        LevelChunk chunk =
                world.getChunkSource().getChunk(chunkX, chunkZ, false);
        return chunk != null && chunk.isSectionEmpty(sectionY);
    }

    @Override
    public int bottomSectionCoord() {
        ClientLevel world = world();
        return world == null ? 0 : world.getMinSection();
    }

    @Override
    public int topSectionCoord() {
        ClientLevel world = world();
        return world == null ? 0 : world.getMaxSection();
    }

    @Override
    public void scheduleResidentSectionRebuild(
            int sectionX, int sectionY, int sectionZ) {
        Minecraft client = Minecraft.getInstance();
        if (client.levelRenderer instanceof WorldRendererTerrainAccess access) {
            access.rtxremix$scheduleResidentSectionRebuild(
                    sectionX, sectionY, sectionZ);
        }
    }

    @Override
    public TerrainLightReconciler.Radiance resolveRadiance(LightEntry entry) {
        Block source = BuiltInRegistries.BLOCK.byId(entry.sourceToken());
        ResourceLocation sourceId = BuiltInRegistries.BLOCK.getKey(source);
        float[] blockColor = textures.getLightColorForBlock(
                sourceId.getNamespace(), sourceId.getPath());
        float r = entry.hasColor() ? entry.colorR() : blockColor[0];
        float g = entry.hasColor() ? entry.colorG() : blockColor[1];
        float b = entry.hasColor() ? entry.colorB() : blockColor[2];
        float baseIntensity = entry.hasIntensity()
                ? entry.intensity()
                : entry.lightLevel()
                        * LightPowerPolicy.RADIANCE_PER_LIGHT_LEVEL;
        baseIntensity *= entry.authoredIntensityMultiplier();
        float intensity = BlockLightIntensityControls.apply(
                ResourceLocation.parse(entry.key().ruleId()), baseIntensity);
        return new TerrainLightReconciler.Radiance(
                r * intensity, g * intensity, b * intensity);
    }

    @Override
    public float torchReferencePower() {
        return LightPower.torchReference();
    }

    private static ClientLevel world() {
        return Minecraft.getInstance().level;
    }
}
