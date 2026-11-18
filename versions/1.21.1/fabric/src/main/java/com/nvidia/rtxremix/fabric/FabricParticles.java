/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.fabric;

import com.nvidia.rtxremix.minecraft.particle.rapids.RapidFoamDotParticle;
import com.nvidia.rtxremix.minecraft.particle.rapids.RapidFoamParticle;
import com.nvidia.rtxremix.minecraft.particle.rapids.RapidFoamParticles;
import com.nvidia.rtxremix.minecraft.particle.rapids.RapidMistParticle;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;

/** Fabric particle-provider registrations for shared particle behavior. */
public final class FabricParticles {
    private FabricParticles() {
    }

    public static void registerProviders() {
        ParticleFactoryRegistry registry = ParticleFactoryRegistry.getInstance();
        registry.register(
                RapidFoamParticles.RAPID_FOAM, RapidFoamParticle.Factory::new);
        registry.register(
                RapidFoamParticles.RAPID_FOAM_DOT,
                RapidFoamDotParticle.Factory::new);
        registry.register(
                RapidFoamParticles.RAPID_MIST, RapidMistParticle.Factory::new);
    }
}
