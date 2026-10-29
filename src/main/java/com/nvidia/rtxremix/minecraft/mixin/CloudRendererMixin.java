/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.gl.VanillaDrawSuppression;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.CloudRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps the cloud framegraph pass and GL state while Remix replaces the cloud geometry. */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {
    @WrapOperation(method = "renderClouds(Lnet/minecraft/client/render/RenderLayer;"
            + "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FFF)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gl/VertexBuffer;draw(Lorg/joml/Matrix4f;"
                    + "Lorg/joml/Matrix4f;Lnet/minecraft/client/gl/ShaderProgram;)V"))
    private void rtxremix$drawClouds(VertexBuffer buffer, Matrix4f view, Matrix4f projection,
                                     ShaderProgram shader, Operation<Void> original) {
        boolean suppress = RtxRemixMod.isRemixWorldRendering()
                && RtxRemixMod.getInstance().getRenderer().getCloudRenderer().shouldSuppressVanillaClouds();
        if (!suppress) {
            original.call(buffer, view, projection, shader);
            return;
        }
        VanillaDrawSuppression.begin();
        try {
            original.call(buffer, view, projection, shader);
        } finally {
            VanillaDrawSuppression.end();
        }
    }
}
