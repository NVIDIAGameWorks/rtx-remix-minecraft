/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.mixin.BillboardParticleAccessor;
import com.nvidia.rtxremix.minecraft.mixin.ParticleAccessor;
import com.nvidia.rtxremix.minecraft.mixin.ParticleManagerAccessor;
import com.nvidia.rtxremix.minecraft.mixin.SpriteBillboardParticleAccessor;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.texture.ParticleAtlasManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import java.util.Map;
import java.util.Queue;

/** Reads Minecraft particle state into the canonical particle pipeline. */
public final class MinecraftParticleCapture {
    private final RemixRenderer renderer;
    private final ParticleAtlasManager atlasManager;
    private final RemixParticleRenderer particles;

    public MinecraftParticleCapture(
            RemixRenderer renderer,
            ParticleAtlasManager atlasManager,
            RemixParticleRenderer particles) {
        this.renderer = renderer;
        this.atlasManager = atlasManager;
        this.particles = particles;
    }

    public RemixParticleRenderer.PreparedFrame prepare(float tickDelta) {
        long frame = particles.beginPreparation();
        Minecraft client = Minecraft.getInstance();
        if (client.particleEngine == null) {
            return particles.finishPreparation(frame);
        }

        ParticleManagerAccessor manager =
                (ParticleManagerAccessor) client.particleEngine;
        Map<ParticleRenderType, Queue<Particle>> particleMap =
                manager.getParticles();
        if (particleMap == null || particleMap.isEmpty()) {
            return particles.finishPreparation(frame);
        }

        BlockTextureManager blockTextures = renderer.getTextureManager();
        var cameraRotation = client.gameRenderer.getMainCamera().rotation();
        ParticleQuadWriter.Basis billboardBasis =
                ParticleQuadWriter.basisFromCamera(
                        cameraRotation.x,
                        cameraRotation.y,
                        cameraRotation.z,
                        cameraRotation.w);

        for (Map.Entry<ParticleRenderType, Queue<Particle>> entry
                : particleMap.entrySet()) {
            ParticleRenderType sheet = entry.getKey();
            Queue<Particle> queue = entry.getValue();
            if (queue == null || queue.isEmpty()) continue;
            if (sheet == ParticleRenderType.NO_RENDER
                    || sheet == ParticleRenderType.CUSTOM) {
                continue;
            }

            boolean terrain = sheet == ParticleRenderType.TERRAIN_SHEET;
            RemixMaterial material = terrain
                    ? blockTextures != null
                            ? blockTextures.getAtlasMaterial(
                                    BlockTextureManager.L_CUTOUT)
                            : null
                    : atlasManager.getMaterial();
            if (material == null || !material.isAlive()) continue;
            RemixParticleRenderer.CaptureBucket captureBucket =
                    particles.captureBucket(material, terrain);
            if (captureBucket == null) continue;

            for (Particle particle : queue) {
                if (!(particle instanceof SingleQuadParticle)) continue;
                ParticleAccessor state = (ParticleAccessor) particle;
                if (state.getDead()) continue;

                BillboardParticleAccessor billboard =
                        (BillboardParticleAccessor) particle;
                int boundsMin = 0;
                int boundsMax = 0;
                if (particle instanceof TextureSheetParticle) {
                    TextureAtlasSprite sprite =
                            ((SpriteBillboardParticleAccessor) particle).getSprite();
                    if (sprite == null) continue;
                    float spriteMinU = sprite.getU0();
                    float spriteMinV = sprite.getV0();
                    float spriteMaxU = sprite.getU1();
                    float spriteMaxV = sprite.getV1();
                    boundsMin = HardcodedVertex.packHalf2x16(
                            Math.min(spriteMinU, spriteMaxU),
                            Math.min(spriteMinV, spriteMaxV));
                    boundsMax = HardcodedVertex.packHalf2x16(
                            Math.max(spriteMinU, spriteMaxU),
                            Math.max(spriteMinV, spriteMaxV));
                }

                int color = ParticleTint.isSoot(particle)
                        ? ParticleQuadWriter.packColor(
                                ParticleTint.SOOT_GREY,
                                ParticleTint.SOOT_GREY,
                                ParticleTint.SOOT_GREY,
                                state.getAlpha())
                        : ParticleQuadWriter.packColor(
                                state.getRed(),
                                state.getGreen(),
                                state.getBlue(),
                                state.getAlpha());
                particles.capture(
                        frame,
                        captureBucket,
                        (RemixParticleStateAccess) particle,
                        state.getPrevPosX(),
                        state.getPrevPosY(),
                        state.getPrevPosZ(),
                        state.getX(),
                        state.getY(),
                        state.getZ(),
                        state.getPrevAngle(),
                        state.getAngle(),
                        billboard.getScale(),
                        tickDelta,
                        billboardBasis,
                        billboard.invokeGetMinU(),
                        billboard.invokeGetMinV(),
                        billboard.invokeGetMaxU(),
                        billboard.invokeGetMaxV(),
                        color,
                        boundsMin,
                        boundsMax);
            }
        }
        return particles.finishPreparation(frame);
    }
}
