/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.item.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the capture who is holding the item being rendered, and for the
 * first-person view which vanilla hand it is in.
 *
 * <p>Direct and first-person held-item renders establish a fallback holder here.
 * Deferred third-person items carry their holder through {@code ItemModelManagerMixin}
 * and {@code ItemRenderStateMixin}; they need not pass through this renderer.
 */
@Mixin(HeldItemRenderer.class)
public class HeldItemRendererMixin {

    private static final String rtxremix$RENDER_HELD_ITEM =
            "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;"
            + "Lnet/minecraft/item/ModelTransformationMode;Z"
            + "Lnet/minecraft/client/util/math/MatrixStack;"
            + "Lnet/minecraft/client/render/VertexConsumerProvider;I)V";

    @Inject(method = rtxremix$RENDER_HELD_ITEM, at = @At("HEAD"))
    private void rtxremix$beginHeldItem(LivingEntity entity, ItemStack stack, ModelTransformationMode mode,
                                        boolean leftHanded, MatrixStack matrices,
                                        VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) context.setHeldItemHolder(entity);
    }

    @Inject(method = rtxremix$RENDER_HELD_ITEM, at = @At("RETURN"))
    private void rtxremix$endHeldItem(LivingEntity entity, ItemStack stack, ModelTransformationMode mode,
                                      boolean leftHanded, MatrixStack matrices,
                                      VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) context.clearHeldItemHolder();
    }

    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"))
    private void rtxremix$beginFirstPersonHand(
            AbstractClientPlayerEntity player,
            float tickDelta,
            float pitch,
            Hand hand,
            float swingProgress,
            ItemStack item,
            float equipProgress,
            MatrixStack matrices,
            VertexConsumerProvider vertexConsumers,
            int light,
            CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) context.setActiveHand(hand);
    }

    @Inject(method = "renderFirstPersonItem", at = @At("RETURN"))
    private void rtxremix$endFirstPersonHand(
            AbstractClientPlayerEntity player,
            float tickDelta,
            float pitch,
            Hand hand,
            float swingProgress,
            ItemStack item,
            float equipProgress,
            MatrixStack matrices,
            VertexConsumerProvider vertexConsumers,
            int light,
            CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) context.clearActiveHand();
    }
}
