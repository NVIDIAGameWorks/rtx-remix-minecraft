/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies a glyph upload into its page mirror when {@link NativeImage} is the source.
 *
 * <p>Bitmap-font glyphs upload a window of the font's sheet, TrueType and built-in
 * glyphs a whole small image; both end in {@code uploadInternal}, whose arguments are
 * exactly the GL sub-image call: destination slot, source window and size. Only an upload
 * inside a glyph upload scope is mirrored, so every other texture upload in the game
 * costs one static read.
 */
@Mixin(NativeImage.class)
public abstract class NativeImageUploadMixin {

    @Shadow @Final private NativeImage.Format format;
    @Shadow private long pixels;

    @Shadow public abstract int getWidth();

    @Inject(method = "_upload", at = @At("HEAD"))
    private void rtxremix$mirrorGlyphUpload(int level, int offsetX, int offsetY,
                                             int unpackSkipPixels, int unpackSkipRows,
                                             int width, int height, boolean blur, boolean clamp,
                                             boolean mipmap, boolean close, CallbackInfo ci) {
        GlyphPageMirror page = GlyphPageMirror.uploadTarget();
        if (page == null || level != 0 || width <= 0 || height <= 0) {
            return;
        }
        int channels = format.components();
        int rowPitch = getWidth() * channels;
        long first = pixels + ((long) unpackSkipRows * getWidth() + unpackSkipPixels) * channels;
        // The window's rows stride the whole image; the buffer spans only the bytes the
        // window touches, from its first pixel to the end of its last row.
        int span = (height - 1) * rowPitch + width * channels;
        page.blit(offsetX, offsetY, width, height, channels,
                MemoryUtil.memByteBuffer(first, span), rowPitch);
    }
}
