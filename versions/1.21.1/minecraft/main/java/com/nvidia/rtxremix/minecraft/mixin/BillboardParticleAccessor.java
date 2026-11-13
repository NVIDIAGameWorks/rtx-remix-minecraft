/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SingleQuadParticle.class)
public interface BillboardParticleAccessor {
    @Accessor("quadSize")
    float getScale();

    @Invoker("getU0")
    float invokeGetMinU();

    @Invoker("getU1")
    float invokeGetMaxU();

    @Invoker("getV0")
    float invokeGetMinV();

    @Invoker("getV1")
    float invokeGetMaxV();
}
