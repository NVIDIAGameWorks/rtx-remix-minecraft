/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.nvidia.rtxremix.minecraft.sky.StarVertexCapture;
import com.nvidia.rtxremix.minecraft.sky.WorldRendererStarAccess;
import net.minecraft.client.render.SkyRendering;
import net.minecraft.client.render.VertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Mirrors vanilla's generated star vertices without replacing their GPU upload. */
@Mixin(SkyRendering.class)
public abstract class SkyRenderingMixin implements WorldRendererStarAccess {
    @Unique
    private float[] rtxremix$starVertices;

    @WrapMethod(method = "tessellateStar")
    private void rtxremix$captureStars(VertexConsumer vertices, Operation<Void> original) {
        StarVertexCapture capture = new StarVertexCapture(vertices);
        original.call(capture);
        rtxremix$starVertices = capture.copyVertices();
    }

    @Override
    public float[] rtxremix$copyStarVertices() {
        return rtxremix$starVertices == null ? new float[0] : rtxremix$starVertices.clone();
    }
}
