/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.RemixConsumerDispatch;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumers;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;

/** Preserves pose-aware dispatch while capturing, and vanilla defaults otherwise. */
@Mixin({VertexConsumers.Dual.class, VertexConsumers.Union.class})
public abstract class VertexConsumerUnionMixin implements VertexConsumer {
    @Override
    public void quad(MatrixStack.Entry entry, BakedQuad quad,
                     float red, float green, float blue, float alpha, int light, int overlay) {
        if (!RemixConsumerDispatch.quad(this, entry, quad, red, green, blue, alpha, light, overlay)) {
            VertexConsumer.super.quad(entry, quad, red, green, blue, alpha, light, overlay);
        }
    }

    @Override
    public void quad(MatrixStack.Entry entry, BakedQuad quad, float[] brightness,
                     float red, float green, float blue, float alpha, int[] lights, int overlay,
                     boolean colorize) {
        if (!RemixConsumerDispatch.quad(this, entry, quad, brightness, red, green, blue, alpha,
                lights, overlay, colorize)) {
            VertexConsumer.super.quad(entry, quad, brightness, red, green, blue, alpha, lights, overlay, colorize);
        }
    }

    @Override
    public VertexConsumer vertex(MatrixStack.Entry entry, float x, float y, float z) {
        return RemixConsumerDispatch.vertex(this, entry, x, y, z)
                ? this : VertexConsumer.super.vertex(entry, x, y, z);
    }

    @Override
    public VertexConsumer vertex(Matrix4f matrix, float x, float y, float z) {
        return RemixConsumerDispatch.vertex(this, matrix, x, y, z)
                ? this : VertexConsumer.super.vertex(matrix, x, y, z);
    }

    @Override
    public VertexConsumer normal(MatrixStack.Entry entry, float x, float y, float z) {
        return RemixConsumerDispatch.normal(this, entry, x, y, z)
                ? this : VertexConsumer.super.normal(entry, x, y, z);
    }
}
