/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies a glyph's pixels into its {@link GlyphPageMirror} as they are written to the
 * page texture. Every texture write passes through here; only one made inside the
 * upload scope {@code GlyphAtlasTextureMixin} opens is a glyph.
 */
@Mixin(FrontendCommandEncoder.class)
public abstract class FrontendCommandEncoderUploadMixin {

    @Inject(
            method = "writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;IIII)V",
            at = @At("HEAD"))
    private void rtxremix$mirrorGlyphImage(GpuTexture destination, NativeImage source, int mipLevel,
                                           int depthOrLayer, int destX, int destY, CallbackInfo ci) {
        GlyphPageMirror page = GlyphPageMirror.uploadTarget();
        int width = source.getWidth();
        int height = source.getHeight();
        if (page == null || mipLevel != 0 || width <= 0 || height <= 0) {
            return;
        }
        int channels = source.format().components();
        int rowPitch = width * channels;
        page.blit(destX, destY, width, height, channels,
                MemoryUtil.memByteBuffer(source.getPointer(), height * rowPitch), rowPitch);
    }

    @Inject(
            method = "writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
            at = @At("HEAD"))
    private void rtxremix$mirrorGlyphBytes(GpuTexture destination, ByteBuffer source, int mipLevel,
                                           int depthOrLayer, int destX, int destY, int width, int height,
                                           CallbackInfo ci) {
        GlyphPageMirror page = GlyphPageMirror.uploadTarget();
        if (page == null || mipLevel != 0 || width <= 0 || height <= 0) {
            return;
        }
        // A raw write is in the destination's own layout, one byte per component.
        int channels = destination.getFormat().componentCount();
        page.blit(destX, destY, width, height, channels, source, width * channels);
    }
}
