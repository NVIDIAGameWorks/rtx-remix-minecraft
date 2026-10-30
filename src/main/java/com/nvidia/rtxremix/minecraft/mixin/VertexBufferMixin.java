/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.nvidia.rtxremix.minecraft.gl.DynamicBufferUpload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.ByteBuffer;

/**
 * Routes the {@code glBufferData} re-specification of DYNAMIC buffers through
 * {@link DynamicBufferUpload}. STATIC uploads keep the original call.
 */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferMixin {
    private static final String rtxremix$GL_BUFFER_DATA =
        "Lcom/mojang/blaze3d/systems/RenderSystem;glBufferData(ILjava/nio/ByteBuffer;I)V";

    private final DynamicBufferUpload.Storage rtxremix$vertexStorage = new DynamicBufferUpload.Storage();
    private final DynamicBufferUpload.Storage rtxremix$indexStorage = new DynamicBufferUpload.Storage();

    @Redirect(method = "uploadVertexBuffer", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;glBufferData(ILjava/nio/ByteBuffer;I)V"))
    private void rtxremix$uploadVertexData(int target, ByteBuffer data, int usage) {
        rtxremix$upload(target, data, usage, rtxremix$vertexStorage);
    }

    // Both overloads are named explicitly: the refmap keys one selector string to one
    // intermediary method, so a bare overloaded name would reach only one of them in a
    // production (intermediary-mapped) client.
    @Redirect(
        method = {
            "uploadIndexBuffer(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder$Result;)V",
            "uploadIndexBuffer(Lcom/mojang/blaze3d/vertex/MeshData$DrawState;Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/systems/RenderSystem$AutoStorageIndexBuffer;"
        },
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;glBufferData(ILjava/nio/ByteBuffer;I)V"))
    private void rtxremix$uploadIndexData(int target, ByteBuffer data, int usage) {
        rtxremix$upload(target, data, usage, rtxremix$indexStorage);
    }

    private static void rtxremix$upload(int target, ByteBuffer data, int usage, DynamicBufferUpload.Storage storage) {
        if (DynamicBufferUpload.handles(usage)) {
            DynamicBufferUpload.upload(target, data, usage, storage);
        } else {
            RenderSystem.glBufferData(target, data, usage);
        }
    }
}
