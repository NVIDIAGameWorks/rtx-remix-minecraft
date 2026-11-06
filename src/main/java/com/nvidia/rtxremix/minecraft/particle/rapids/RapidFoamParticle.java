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
public class RapidFoamParticle extends TextureSheetParticle {
    private RapidFoamParticle(ClientLevel world, double x, double y, double z,
                              double velocityX, double velocityY, double velocityZ,
                              SpriteSet spriteProvider) {
        super(world, x, y, z);
        this.pickSprite(spriteProvider);
        this.setSize(0.015f, 0.015f);
        this.quadSize *= this.random.nextFloat() * 0.45f + 0.35f;
        this.lifetime = 14 + this.random.nextInt(22);
        this.gravity = 1.25f;
        this.friction = 0.95f;
        this.hasPhysics = true;
        this.xd = velocityX;
        this.yd = velocityY;
        this.zd = velocityZ;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.removed && this.onGround && this.random.nextFloat() < 0.35f) {
            this.remove();
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
            return new RapidFoamParticle(world, x, y, z, velocityX, velocityY, velocityZ, spriteProvider);
        }
    }
}
