/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import net.minecraft.resources.ResourceLocation;

/** Translates Minecraft sprite identifiers for the canonical emission policy. */
public final class BuiltinEmission {
    @FunctionalInterface
    public interface Mask extends BuiltinEmissionPolicy.Mask {}

    static final float SEA_PICKLE_TIP_PEAK = BuiltinEmissionPolicy.SEA_PICKLE_TIP_PEAK;
    static final Mask SEA_PICKLE = BuiltinEmissionPolicy.SEA_PICKLE::factor;

    private BuiltinEmission() {}

    public static float gain(ResourceLocation spriteId) {
        return BuiltinEmissionPolicy.gain(spriteId.toString());
    }

    public static Mask mask(ResourceLocation spriteId) {
        return BuiltinEmissionPolicy.mask(spriteId.toString()) == null ? null : SEA_PICKLE;
    }
}
