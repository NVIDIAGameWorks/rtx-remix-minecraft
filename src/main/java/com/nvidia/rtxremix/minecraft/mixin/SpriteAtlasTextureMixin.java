/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public abstract class SpriteAtlasTextureMixin {

    @Shadow @Final private ResourceLocation location;

    @Inject(method = "cycleAnimationFrames", at = @At("HEAD"))
    private void rtxremix$onAnimatedSpriteTickStart(CallbackInfo ci) {
        AtlasAnimationForwarder.beginAtlasUpload(location);
    }

    @Inject(method = "cycleAnimationFrames", at = @At("RETURN"))
    private void rtxremix$onAnimatedSpriteTickEnd(CallbackInfo ci) {
        AtlasAnimationForwarder.endAtlasUpload();
    }

    @Inject(method = "upload", at = @At("RETURN"))
    private void rtxremix$onAtlasUploaded(SpriteLoader.Preparations stitchResult, CallbackInfo ci) {
        RemixEntitySubmitter.onMinecraftAtlasUploaded(location);
    }
}
