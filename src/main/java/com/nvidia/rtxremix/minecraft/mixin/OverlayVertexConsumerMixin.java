/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.RemixCapturingConsumer;
import net.minecraft.client.render.OverlayVertexConsumer;
import net.minecraft.client.render.VertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps the projected glint UV, which vanilla emits after the normal, on the same vertex. */
@Mixin(OverlayVertexConsumer.class)
public abstract class OverlayVertexConsumerMixin {
    @Redirect(method = "normal(FFF)Lnet/minecraft/client/render/VertexConsumer;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/VertexConsumer;normal(FFF)Lnet/minecraft/client/render/VertexConsumer;"))
    private VertexConsumer rtxremix$deferProjectedVertex(VertexConsumer delegate, float x, float y, float z) {
        if (delegate instanceof RemixCapturingConsumer capture && capture.isRemixCaptureActive()) {
            return capture.projectedNormal(x, y, z);
        }
        return delegate.normal(x, y, z);
    }
}
