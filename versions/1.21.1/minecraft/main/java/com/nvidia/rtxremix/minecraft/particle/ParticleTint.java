/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SmokeParticle;

/** Classifies Minecraft particles for the canonical tint policy. */
public final class ParticleTint {
    public static final float SOOT_GREY = ParticleTintPolicy.SOOT_GREY;

    private ParticleTint() {}

    /**
     * Fire smoke is vanilla's dark-speck particle. Campfire smoke deliberately
     * remains outside because vanilla draws it white over a grey sprite.
     */
    public static boolean isSoot(Particle particle) {
        return particle instanceof SmokeParticle;
    }
}
