/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.texture.DynamicTextureCache;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts {@link DynamicTexture#upload()} to capture pixel data
 * for dynamic textures (maps, capes, etc.).
 *
 * <p>At the HEAD of {@code upload()}, the {@code NativeImage} is guaranteed
 * non-null: the method throws {@link IllegalStateException} if it is. This is
 * the correct capture point for mutable textures whose pixels can change after
 * registration.
 *
 * <p>Only stores pixels for textures that {@code TextureManagerMixin} registered
 * for tracking; all others are ignored with minimal overhead.
 */
@Mixin(DynamicTexture.class)
public class NativeImageBackedTextureMixin {

    @Shadow
    @Nullable
    protected NativeImage pixels;

    @Inject(method = "upload", at = @At("HEAD"))
    private void rtxremix$onUpload(CallbackInfo ci) {
        if (pixels == null) return;
        DynamicTextureCache.captureFromUpload((AbstractTexture) (Object) this, pixels);
    }
}
