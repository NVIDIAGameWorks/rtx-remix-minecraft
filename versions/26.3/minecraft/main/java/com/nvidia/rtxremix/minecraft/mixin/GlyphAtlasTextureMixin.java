/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import net.minecraft.client.gui.font.FontTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gives every glyph atlas page a {@link GlyphPageMirror} and brackets each glyph upload
 * so the upload mixins know which page the pixels are for.
 *
 * <p>{@code add} is the one place a page is written: it calls the glyph's {@code upload}
 * with the slot it found and the page's texture. The glyph implementations reach the GPU
 * through different command-encoder calls, a {@code NativeImage} write for TrueType and
 * built-in glyphs, a raw buffer write for Unihex and a staging-buffer copy for bitmap
 * fonts, which is why the scope is opened here and the pixels are read where they are
 * uploaded ({@code FrontendCommandEncoderUploadMixin}, {@code BitmapGlyphUploadMixin}).
 */
@Mixin(FontTexture.class)
public abstract class GlyphAtlasTextureMixin implements GlyphPageMirror.Owner {

    @Shadow @Final private boolean colored;

    private GlyphPageMirror rtxremix$mirror;

    @Override
    public GlyphPageMirror rtxremix$glyphPageMirror() {
        // Created on first use: hasColor is assigned by the constructor, so a field
        // initialiser could not read it.
        if (rtxremix$mirror == null) {
            rtxremix$mirror = new GlyphPageMirror(!colored);
        }
        return rtxremix$mirror;
    }

    @WrapOperation(
        method = "add",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/font/GlyphBitmap;upload(IILcom/mojang/renderpearl/api/textures/GpuTexture;)V")
    )
    private void rtxremix$mirrorGlyphUpload(GlyphBitmap glyph, int x, int y, GpuTexture texture,
                                            Operation<Void> original) {
        GlyphPageMirror.beginUpload(rtxremix$glyphPageMirror());
        try {
            original.call(glyph, x, y, texture);
        } finally {
            GlyphPageMirror.endUpload();
        }
    }
}
