/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.gl.VanillaDrawSuppression;
import net.minecraft.client.gl.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Omits only the indexed draw; buffer binding, uniforms and shader cleanup still run. */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferMixin {
    @WrapOperation(method = "draw()V", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;drawElements(III)V"))
    private void rtxremix$drawUnlessSuppressed(int mode, int count, int type, Operation<Void> original) {
        if (!VanillaDrawSuppression.isActive()) {
            original.call(mode, count, type);
        }
    }
}
