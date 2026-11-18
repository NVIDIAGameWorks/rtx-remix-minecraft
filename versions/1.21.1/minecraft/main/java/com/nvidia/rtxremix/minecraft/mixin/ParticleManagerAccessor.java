/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.Queue;

@Mixin(ParticleEngine.class)
public interface ParticleManagerAccessor {
    @Accessor("particles")
    Map<ParticleRenderType, Queue<Particle>> getParticles();

    /**
     * Sprite providers keyed by particle type id. The value class is package
     * private in vanilla, so callers read it through the public
     * {@code SpriteProvider} interface it implements.
     */
    @Accessor("spriteSets")
    Map<ResourceLocation, ?> getSpriteAwareFactories();
}
