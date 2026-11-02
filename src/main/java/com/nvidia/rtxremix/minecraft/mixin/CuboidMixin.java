/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.RemixConsumerDispatch;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts {@code ModelPart.Cuboid.renderCuboid()} so a cuboid reaches the Remix
 * capture as the immutable object it is rather than as expanded vertices.
 *
 * <p>When the active {@link VertexConsumer} reaches a Remix capture consumer
 * inside a Remix entity pass, directly or behind sprite wrappers and glint unions,
 * the cuboid's sides, sprite, folded tint and pose are handed to the consumer, which
 * registers its pivot and records a rigid part
 * keyed by the cuboid's identity; the vanilla vertex write is cancelled.
 */
@Mixin(ModelPart.Cuboid.class)
public class CuboidMixin {

    @Inject(method = "renderCuboid", at = @At("HEAD"), cancellable = true)
    private void rtxremix$onRenderCuboid(MatrixStack.Entry entry, VertexConsumer vertexConsumer,
                                          int light, int overlay, int color,
                                          CallbackInfo ci) {
        if (RemixConsumerDispatch.cuboid(vertexConsumer, (ModelPart.Cuboid) (Object) this,
                entry, light, overlay, color)) {
            ci.cancel();
        }
    }
}
