/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteContents.class)
public abstract class SpriteContentsUploadMixin {

    @Shadow @Final private ResourceLocation name;
    @Shadow @Final int width;
    @Shadow @Final int height;

    @Inject(method = "upload(IIII[Lcom/mojang/blaze3d/platform/NativeImage;)V",
            at = @At("HEAD"))
    private void rtxremix$onAnimationUpload(int texX, int texY, int srcX, int srcY,
                                    NativeImage[] mips, CallbackInfo ci) {
        // Guard: only forward patches for animated sprites.
        // upload() is also called during initial atlas stitching for ALL sprites.
        if (((SpriteContentsAccessor) (Object) this).getAnimation() == null) return;

        if (mips == null || mips.length == 0 || mips[0] == null) return;

        NativeImage mip0 = mips[0];
        long basePtr = ((NativeImageAccessor) (Object) mip0).getPointer();
        int imgWidth = mip0.getWidth();
        int imgHeight = mip0.getHeight();
        long pixelPtr = basePtr + ((long) srcY * imgWidth + srcX) * 4;
        int rowPitch = imgWidth * 4;

        AtlasAnimationForwarder.addPatch(name, texX, texY, srcX, srcY, width, height,
                                         imgWidth, imgHeight, pixelPtr, rowPitch);
    }
}
