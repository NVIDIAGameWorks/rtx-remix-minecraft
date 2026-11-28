/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle.rapids;

import net.minecraft.core.particles.SimpleParticleType;

public final class RapidFoamParticles {
    public static final SimpleParticleType RAPID_FOAM = new Type();
    public static final SimpleParticleType RAPID_FOAM_DOT = new Type();
    public static final SimpleParticleType RAPID_MIST = new Type();

    private RapidFoamParticles() {}

    private static final class Type extends SimpleParticleType {
        private Type() {
            super(false);
        }
    }

}
