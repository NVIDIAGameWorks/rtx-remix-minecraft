/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.RemixVertexConsumerProvider;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets each entity render with a capture boundary on the thread-local
 * {@link EntityRenderContext}.
 *
 * <p>{@code EntityRenderDispatcher.render()} opens the boundary at HEAD with the
 * remix-pass flag and closes it at RETURN. Nesting is the context's concern: an
 * entity rendered from inside another render appends to the enclosing capture
 * instead of starting its own.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(
        method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("HEAD")
    )
    private <E extends Entity> void rtxremix$onRenderStart(
            E entity, double x, double y, double z,
            float yaw, float tickDelta,
            PoseStack matrices, MultiBufferSource vertexConsumers,
            int light, CallbackInfo ci) {
        EntityRenderContext.get().begin(vertexConsumers instanceof RemixVertexConsumerProvider);
    }

    @Inject(
        method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("RETURN")
    )
    private <E extends Entity> void rtxremix$onRenderEnd(
            E entity, double x, double y, double z,
            float yaw, float tickDelta,
            PoseStack matrices, MultiBufferSource vertexConsumers,
            int light, CallbackInfo ci) {
        EntityRenderContext.get().end();
    }
}
