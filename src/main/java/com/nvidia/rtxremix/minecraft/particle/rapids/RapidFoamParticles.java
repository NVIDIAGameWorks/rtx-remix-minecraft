/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle.rapids;

import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

public final class RapidFoamParticles {
    public static final SimpleParticleType RAPID_FOAM = FabricParticleTypes.simple(false);
    public static final SimpleParticleType RAPID_FOAM_DOT = FabricParticleTypes.simple(false);
    public static final SimpleParticleType RAPID_MIST = FabricParticleTypes.simple(false);

    private RapidFoamParticles() {}

    public static void register() {
        Registry.register(BuiltInRegistries.PARTICLE_TYPE, Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "rapid_foam"), RAPID_FOAM);
        Registry.register(BuiltInRegistries.PARTICLE_TYPE, Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "rapid_foam_dot"), RAPID_FOAM_DOT);
        Registry.register(BuiltInRegistries.PARTICLE_TYPE, Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "rapid_mist"), RAPID_MIST);
        ParticleProviderRegistry.getInstance().register(RAPID_FOAM, RapidFoamParticle.Factory::new);
        ParticleProviderRegistry.getInstance().register(RAPID_FOAM_DOT, RapidFoamDotParticle.Factory::new);
        ParticleProviderRegistry.getInstance().register(RAPID_MIST, RapidMistParticle.Factory::new);
    }
}
