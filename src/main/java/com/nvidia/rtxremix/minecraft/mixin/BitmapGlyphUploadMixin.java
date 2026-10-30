/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import net.minecraft.client.gui.font.providers.BitmapProvider;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors a bitmap-font glyph from the font's sheet. The glyph reaches its page by a
 * GPU copy from a staging buffer, so its pixels never pass the command encoder; the
 * sheet image that filled the buffer is still on the CPU and is read instead.
 */
@Mixin(targets = "net.minecraft.client.gui.font.providers.BitmapProvider$Glyph$1")
public abstract class BitmapGlyphUploadMixin {

    @Shadow @Final BitmapProvider.Glyph this$0;

    @Inject(method = "upload", at = @At("HEAD"))
    private void rtxremix$mirrorGlyph(int x, int y, GpuTexture texture, CallbackInfo ci) {
        GlyphPageMirror page = GlyphPageMirror.uploadTarget();
        BitmapProvider.Glyph glyph = this$0;
        if (page == null || glyph.width() <= 0 || glyph.height() <= 0) {
            return;
        }
        NativeImage sheet = glyph.imageData().image;
        int channels = sheet.format().components();
        int rowPitch = sheet.getWidth() * channels;
        long first = sheet.getPointer() + (long) glyph.offsetY() * rowPitch + (long) glyph.offsetX() * channels;
        // The rows stride the whole sheet; the buffer spans only the bytes the glyph
        // touches, from its first pixel to the end of its last row.
        int span = (glyph.height() - 1) * rowPitch + glyph.width() * channels;
        page.blit(x, y, glyph.width(), glyph.height(), channels, MemoryUtil.memByteBuffer(first, span), rowPitch);
    }
}
