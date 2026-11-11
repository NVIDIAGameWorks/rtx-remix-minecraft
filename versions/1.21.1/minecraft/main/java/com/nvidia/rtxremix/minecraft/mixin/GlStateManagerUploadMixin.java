/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;
import java.util.function.Consumer;

/**
 * Copies a glyph upload into its page mirror when a raw pixel buffer is the source.
 *
 * <p>Unihex glyphs are rasterised into an {@code IntBuffer} and handed straight to
 * {@code GlStateManager.upload}, bypassing {@link NativeImage}; {@code _upload} is where
 * that buffer meets GL, with rows packed at the glyph's width. Only an upload inside a
 * glyph upload scope is mirrored.
 */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerUploadMixin {

    @Inject(method = "_upload", at = @At("HEAD"))
    private static void rtxremix$mirrorGlyphUpload(int level, int offsetX, int offsetY,
                                                    int width, int height, NativeImage.Format format,
                                                    IntBuffer pixels, Consumer<IntBuffer> closer,
                                                    CallbackInfo ci) {
        GlyphPageMirror page = GlyphPageMirror.uploadTarget();
        if (page == null || level != 0 || width <= 0 || height <= 0) {
            return;
        }
        int channels = format.components();
        int rowPitch = width * channels;
        page.blit(offsetX, offsetY, width, height, channels,
                MemoryUtil.memByteBuffer(MemoryUtil.memAddress(pixels), height * rowPitch), rowPitch);
    }
}
