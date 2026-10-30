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
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.RandomSource;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.core.particles.SimpleParticleType;

@Environment(EnvType.CLIENT)
public class RapidFoamDotParticle extends SingleQuadParticle {
    private RapidFoamDotParticle(ClientLevel world, double x, double y, double z,
                                 double velocityX, double velocityY, double velocityZ,
                                 TextureAtlasSprite sprite) {
        super(world, x, y, z, sprite);
        this.setSize(0.01f, 0.01f);
        this.quadSize *= this.random.nextFloat() * 0.25f + 0.18f;
        this.lifetime = 16 + this.random.nextInt(24);
        this.gravity = 1.15f;
        this.friction = 0.94f;
        this.hasPhysics = true;
        this.xd = velocityX;
        this.yd = velocityY;
        this.zd = velocityZ;
    }

    @Override
    protected SingleQuadParticle.Layer getLayer() {
        return SingleQuadParticle.Layer.OPAQUE;
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
                                       double velocityX, double velocityY, double velocityZ,
                                       RandomSource random) {
            return new RapidFoamDotParticle(world, x, y, z, velocityX, velocityY, velocityZ, spriteProvider.get(random));
        }
    }
}
