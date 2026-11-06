/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;


/**
 * Matrix stack dedicated to Remix entity capture: one long-lived stack the
 * capture passes reset between renders, with an allocation-free model-part
 * transform.
 */
public final class RecyclingMatrixStack extends PoseStack {

    // Since 26.x vanilla's PoseStack keeps its Pose objects and reuses them on
    // push, so this stack no longer pools entries of its own.
    private final Quaternionf modelPartRotation = new Quaternionf();

    /** Restores the identity root after a normal render or renderer exception. */
    void reset() {
        while (!isEmpty()) {
            popPose();
        }
        setIdentity();
    }

    /** Applies vanilla's {@code ModelPart.translateAndRotate} transform without allocating a quaternion. */
    public void applyModelPartTransform(float pivotX, float pivotY, float pivotZ,
                                        float pitch, float yaw, float roll,
                                        float xScale, float yScale, float zScale) {
        translate(pivotX / 16.0F, pivotY / 16.0F, pivotZ / 16.0F);
        if (pitch != 0.0F || yaw != 0.0F || roll != 0.0F) {
            rotate(modelPartRotation.rotationZYX(roll, yaw, pitch));
        }
        if (xScale != 1.0F || yScale != 1.0F || zScale != 1.0F) {
            scale(xScale, yScale, zScale);
        }
    }
}
