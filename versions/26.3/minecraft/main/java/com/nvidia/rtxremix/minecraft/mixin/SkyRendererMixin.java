/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.MeshData;
import com.nvidia.rtxremix.minecraft.sky.SkyStarCapture;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Copies the star field vanilla generates, so the Remix sky can place the same
 * stars. The field is position-only quads built once per sky renderer.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {

    @WrapOperation(
            method = "buildStars",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/MeshData;vertexBuffer()Ljava/nio/ByteBuffer;"))
    private ByteBuffer rtxremix$captureStarVertices(MeshData mesh, Operation<ByteBuffer> original) {
        ByteBuffer buffer = original.call(mesh);
        MeshData.DrawState state = mesh.drawState();
        int vertexCount = state.vertexCount();
        int stride = state.format().getVertexSize();
        ByteBuffer vertices = buffer.duplicate().order(ByteOrder.nativeOrder());
        float[] positions = new float[vertexCount * 3];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int source = vertex * stride;
            int destination = vertex * 3;
            positions[destination] = vertices.getFloat(source);
            positions[destination + 1] = vertices.getFloat(source + Float.BYTES);
            positions[destination + 2] = vertices.getFloat(source + 2 * Float.BYTES);
        }
        SkyStarCapture.record(positions);
        return buffer;
    }
}
