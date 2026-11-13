/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.capture.SectionBuildConcurrency;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Caps the section-builder buffer pool, and with it the number of chunk
 * sections that build concurrently. Vanilla passes the logical CPU count;
 * {@link SectionBuildConcurrency} decides how many of those to allocate.
 */
@Mixin(SectionBufferBuilderPool.class)
public abstract class BlockBufferBuilderPoolMixin {

    @ModifyVariable(method = "allocate", at = @At("HEAD"), argsOnly = true)
    private static int rtxremix$capSectionBuilders(int max) {
        return SectionBuildConcurrency.cap(max);
    }
}
