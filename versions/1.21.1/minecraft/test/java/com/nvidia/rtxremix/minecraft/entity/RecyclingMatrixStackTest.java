/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;

class RecyclingMatrixStackTest {

    @Test
    void pooledModelPartTransformMatchesVanillaOperations() {
        float pivotX = 3.25F;
        float pivotY = -7.5F;
        float pivotZ = 12.75F;
        float pitch = 0.31F;
        float yaw = -0.52F;
        float roll = 0.73F;
        float xScale = 0.8F;
        float yScale = 1.1F;
        float zScale = 1.3F;

        PoseStack vanilla = new PoseStack();
        vanilla.translate(pivotX / 16.0F, pivotY / 16.0F, pivotZ / 16.0F);
        vanilla.mulPose(new Quaternionf().rotationZYX(roll, yaw, pitch));
        vanilla.scale(xScale, yScale, zScale);

        RecyclingMatrixStack recycling = new RecyclingMatrixStack();
        recycling.applyModelPartTransform(
                pivotX, pivotY, pivotZ,
                pitch, yaw, roll,
                xScale, yScale, zScale);

        assertTrue(vanilla.last().pose()
                .equals(recycling.last().pose(), 0.0F));
        assertTrue(vanilla.last().normal()
                .equals(recycling.last().normal(), 0.0F));
    }

    @Test
    void identityRotationAndScaleMatchVanillaTranslation() {
        PoseStack vanilla = new PoseStack();
        vanilla.translate(0.5F, -0.25F, 1.5F);

        RecyclingMatrixStack recycling = new RecyclingMatrixStack();
        recycling.applyModelPartTransform(
                8.0F, -4.0F, 24.0F,
                0.0F, 0.0F, 0.0F,
                1.0F, 1.0F, 1.0F);

        assertTrue(vanilla.last().pose()
                .equals(recycling.last().pose(), 0.0F));
        assertTrue(vanilla.last().normal()
                .equals(recycling.last().normal(), 0.0F));
    }

}
