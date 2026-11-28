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
import com.nvidia.rtxremix.minecraft.mixin.ParticleGroupAccessor;
import com.nvidia.rtxremix.minecraft.mixin.ParticleManagerAccessor;
import com.nvidia.rtxremix.minecraft.mixin.SpriteBillboardParticleAccessor;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.texture.ParticleAtlasManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import java.util.Map;
import java.util.Queue;

/** Reads Minecraft particle state into the canonical particle pipeline. */
public final class MinecraftParticleCapture {
    private static final boolean[] TERRAIN_PASSES = {true, false};

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
        Map<ParticleRenderType, ParticleGroup<?>> particleMap =
                manager.getParticles();
        if (particleMap == null || particleMap.isEmpty()) {
            return particles.finishPreparation(frame);
        }

        // Since 26.x every billboard particle shares the SINGLE_QUADS group; the
        // others (item pickup, elder guardian, no-render) are drawn as models or not
        // at all. A particle's sprite atlas now says whether it is a terrain particle.
        ParticleGroup<?> quads = particleMap.get(ParticleRenderType.SINGLE_QUADS);
        Queue<? extends Particle> queue =
                quads == null ? null : ((ParticleGroupAccessor) quads).getParticles();
        if (queue == null || queue.isEmpty()) {
            return particles.finishPreparation(frame);
        }

        BlockTextureManager blockTextures = renderer.getTextureManager();
        var cameraRotation = client.gameRenderer.mainCamera().rotation();
        ParticleQuadWriter.Basis billboardBasis =
                ParticleQuadWriter.basisFromCamera(
                        cameraRotation.x,
                        cameraRotation.y,
                        cameraRotation.z,
                        cameraRotation.w);

        for (boolean terrain : TERRAIN_PASSES) {
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
                TextureAtlasSprite sprite =
                        ((SpriteBillboardParticleAccessor) particle).getSprite();
                if (sprite == null) continue;
                Identifier atlas = sprite.atlasLocation();
                if (terrain != TextureAtlas.LOCATION_BLOCKS.equals(atlas)
                        || TextureAtlas.LOCATION_ITEMS.equals(atlas)) {
                    // Item-break particles sample the items atlas, which has no
                    // Remix material yet.
                    continue;
                }
                ParticleAccessor state = (ParticleAccessor) particle;
                if (state.getDead()) continue;

                BillboardParticleAccessor billboard =
                        (BillboardParticleAccessor) particle;
                float spriteMinU = sprite.getU0();
                float spriteMinV = sprite.getV0();
                float spriteMaxU = sprite.getU1();
                float spriteMaxV = sprite.getV1();
                int boundsMin = HardcodedVertex.packHalf2x16(
                        Math.min(spriteMinU, spriteMaxU),
                        Math.min(spriteMinV, spriteMaxV));
                int boundsMax = HardcodedVertex.packHalf2x16(
                        Math.max(spriteMinU, spriteMaxU),
                        Math.max(spriteMinV, spriteMaxV));

                // Alpha stays the particle's own; a held puff is drawn at a lower
                // alpha, see HeldItemSmoke.OPACITY for why the two are not one value.
                int color = ParticleTint.isSoot(particle)
                        ? ParticleQuadWriter.packColor(
                                ParticleTint.SOOT_GREY,
                                ParticleTint.SOOT_GREY,
                                ParticleTint.SOOT_GREY,
                                billboard.getAlpha())
                        : ParticleQuadWriter.packColor(
                                billboard.getRed(),
                                billboard.getGreen(),
                                billboard.getBlue(),
                                billboard.getAlpha());
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
                        billboard.getPrevAngle(),
                        billboard.getAngle(),
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
