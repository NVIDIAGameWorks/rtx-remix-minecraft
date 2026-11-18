/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Deque;

/** Exposes the backing deque so the Remix capture stack can recycle entries. */
@Mixin(PoseStack.class)
public interface MatrixStackAccessor {

    @Accessor("poseStack")
    Deque<PoseStack.Pose> rtxremix$getStack();
}
