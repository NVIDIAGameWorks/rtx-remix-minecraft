/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle.rapids;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

@Environment(EnvType.CLIENT)
public class RapidMistParticle extends TextureSheetParticle {
    private RapidMistParticle(ClientLevel world, double x, double y, double z,
                              double velocityX, double velocityY, double velocityZ,
                              SpriteSet spriteProvider) {
        super(world, x, y, z);
        this.pickSprite(spriteProvider);
        this.setSize(0.05f, 0.05f);
        this.quadSize *= this.random.nextFloat() * 0.42f + 0.32f;
        this.lifetime = 18 + this.random.nextInt(19);
        this.gravity = -0.012f;
        this.friction = 0.82f;
        this.hasPhysics = false;
        this.xd = velocityX;
        this.yd = velocityY;
        this.zd = velocityZ;
        this.setColor(0.62f, 0.68f, 0.72f);
        this.setAlpha(0.28f);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.removed) {
            float life = (float) this.age / (float) this.lifetime;
            this.quadSize *= 1.002f;
            this.setAlpha(0.28f * (1.0f - life));
        }
    }

    @Environment(EnvType.CLIENT)
    public static final class Factory implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet spriteProvider;

        public Factory(SpriteSet spriteProvider) {
            this.spriteProvider = spriteProvider;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel world,
                                       double x, double y, double z,
                                       double velocityX, double velocityY, double velocityZ) {
            return new RapidMistParticle(world, x, y, z, velocityX, velocityY, velocityZ, spriteProvider);
        }
    }
}
