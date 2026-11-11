/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.entity.EntityTextureSource;

/** Exposes a Minecraft native image through the canonical packed-pixel contract. */
public record NativeImagePixels(NativeImage image)
        implements BlockTextureManager.ImageResource, EntityTextureSource.PixelImage {
    public static NativeImagePixels of(NativeImage image) {
        return new NativeImagePixels(image);
    }

    @Override
    public int width() {
        return image.getWidth();
    }

    @Override
    public int height() {
        return image.getHeight();
    }

    @Override
    public int color(int x, int y) {
        return image.getPixelRGBA(x, y);
    }

    @Override
    public void close() {
        image.close();
    }
}
