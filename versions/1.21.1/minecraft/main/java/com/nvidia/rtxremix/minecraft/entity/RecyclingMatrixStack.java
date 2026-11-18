/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.mixin.MatrixStackAccessor;
import com.nvidia.rtxremix.minecraft.mixin.MatrixStackEntryAccessor;
import org.joml.Quaternionf;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Matrix stack dedicated to Remix entity capture.
 *
 * <p>Vanilla allocates an entry, a position matrix, and a normal matrix for
 * every {@link #pushPose()}. Entity models repeat that traversal every frame, so
 * popped entries are retained here and copied back into service on the next
 * push. The pool belongs to this stack and therefore never changes vanilla
 * rendering outside the capture pass.
 */
public final class RecyclingMatrixStack extends PoseStack {

    private final Deque<Pose> recycledEntries = new ArrayDeque<>();
    private final Quaternionf modelPartRotation = new Quaternionf();

    @Override
    public void pushPose() {
        Deque<Pose> stack = stack();
        Pose previous = stack.getLast();
        Pose next = recycledEntries.pollLast();
        if (next == null) {
            super.pushPose();
            return;
        }

        next.pose().set(previous.pose());
        next.normal().set(previous.normal());
        boolean canSkipNormalization = ((MatrixStackEntryAccessor) (Object) previous)
                .rtxremix$getCanSkipNormalization();
        ((MatrixStackEntryAccessor) (Object) next)
                .rtxremix$setCanSkipNormalization(canSkipNormalization);
        stack.addLast(next);
    }

    @Override
    public void popPose() {
        recycledEntries.addLast(stack().removeLast());
    }

    /** Restores the identity root after a normal render or renderer exception. */
    void reset() {
        Deque<Pose> stack = stack();
        while (stack.size() > 1) {
            recycledEntries.addLast(stack.removeLast());
        }
        setIdentity();
    }

    /** Applies vanilla's {@code ModelPart.rotate} transform without allocating a quaternion. */
    public void applyModelPartTransform(float pivotX, float pivotY, float pivotZ,
                                        float pitch, float yaw, float roll,
                                        float xScale, float yScale, float zScale) {
        translate(pivotX / 16.0F, pivotY / 16.0F, pivotZ / 16.0F);
        if (pitch != 0.0F || yaw != 0.0F || roll != 0.0F) {
            mulPose(modelPartRotation.rotationZYX(roll, yaw, pitch));
        }
        if (xScale != 1.0F || yScale != 1.0F || zScale != 1.0F) {
            scale(xScale, yScale, zScale);
        }
    }

    private Deque<Pose> stack() {
        return ((MatrixStackAccessor) (Object) this).rtxremix$getStack();
    }
}
