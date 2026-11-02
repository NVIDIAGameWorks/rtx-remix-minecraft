/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.gl.BufferResizePolicy;
import net.minecraft.client.gl.GlUsage;
import net.minecraft.client.gl.GlBufferTarget;
import net.minecraft.client.gl.GpuBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps initialized dynamic vertex storage when an upload fits its existing allocation.
 * Vanilla's following copyFrom then takes its glBufferSubData branch. Growing,
 * first-use, non-vertex, static and closed buffers retain vanilla's allocation and error paths.
 */
@Mixin(GpuBuffer.class)
public abstract class GpuBufferMixin {
    @Shadow @Final private GlUsage usage;
    @Shadow @Final private GlBufferTarget target;
    @Shadow private boolean initialized;
    @Shadow private boolean closed;
    @Shadow public int size;

    @Inject(method = "resize", at = @At("HEAD"), cancellable = true)
    private void rtxremix$retainDynamicStorage(int requested, CallbackInfo ci) {
        if (BufferResizePolicy.canRetain(
                target == GlBufferTarget.VERTICES && usage == GlUsage.DYNAMIC_WRITE,
                initialized, closed, size, requested)) {
            ci.cancel();
        }
    }
}
