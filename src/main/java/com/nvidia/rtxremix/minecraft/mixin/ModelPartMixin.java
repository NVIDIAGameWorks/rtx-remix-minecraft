/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.entity.RecyclingMatrixStack;
import com.nvidia.rtxremix.minecraft.entity.RemixCapturingConsumer;
import com.nvidia.rtxremix.minecraft.entity.RemixConsumerDispatch;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.List;


/**
 * Routes vanilla model-part rendering through the capture's allocation-free paths.
 *
 * <p>{@code rotate} on a {@link RecyclingMatrixStack} applies the part transform through
 * the stack's pooled quaternion. The math is vanilla's, read from the same pivot,
 * rotation and scale fields, so a renderer that sets a pivot directly, as the banner
 * does for its flag, is honoured. The entity pass and the block-entity pass both
 * render on such a stack.
 *
 * <p>{@code renderCuboids} on a single-cuboid part borrows the capturing consumer's
 * singleton iterator instead of allocating vanilla's. The consumer is read directly
 * or from the cached leaf of the sprite wrappers and unions that {@code CuboidMixin} captures.
 */
@Mixin(ModelPart.class)
public abstract class ModelPartMixin {

    @Shadow public float pivotX;
    @Shadow public float pivotY;
    @Shadow public float pivotZ;
    @Shadow public float pitch;
    @Shadow public float yaw;
    @Shadow public float roll;
    @Shadow public float xScale;
    @Shadow public float yScale;
    @Shadow public float zScale;

    @Inject(
            method = "rotate(Lnet/minecraft/client/util/math/MatrixStack;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void rtxremix$applyPooledRotation(MatrixStack matrices, CallbackInfo ci) {
        if (!(matrices instanceof RecyclingMatrixStack recyclingMatrices)) {
            return;
        }

        recyclingMatrices.applyModelPartTransform(
                pivotX, pivotY, pivotZ,
                pitch, yaw, roll,
                xScale, yScale, zScale);
        ci.cancel();
    }

    @WrapOperation(
            method = "renderCuboids(Lnet/minecraft/client/util/math/MatrixStack$Entry;Lnet/minecraft/client/render/VertexConsumer;III)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
    private Iterator<ModelPart.Cuboid> rtxremix$reuseSingletonCuboidIterator(
            List<ModelPart.Cuboid> cuboids,
            Operation<Iterator<ModelPart.Cuboid>> original,
            MatrixStack.Entry entry,
            VertexConsumer vertexConsumer,
            int light,
            int overlay,
            int color) {
        // Reject group and multi-cuboid parts before querying the cached capture leaf.
        if (cuboids.size() != 1 || !(cuboids instanceof ImmutableList<?>)) return original.call(cuboids);
        RemixCapturingConsumer consumer = RemixConsumerDispatch.findCapture(vertexConsumer);
        if (consumer != null && consumer.isRemixCaptureActive()) {
            Iterator<ModelPart.Cuboid> iterator =
                    consumer.acquireSingletonCuboidIterator(cuboids.getFirst());
            if (iterator != null) {
                return iterator;
            }
        }
        return original.call(cuboids);
    }
}
