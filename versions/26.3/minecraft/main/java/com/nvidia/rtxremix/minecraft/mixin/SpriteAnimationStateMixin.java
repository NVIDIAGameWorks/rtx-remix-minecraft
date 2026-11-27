/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.texture.AnimatedSpriteOwner;
import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import com.nvidia.rtxremix.minecraft.texture.SpriteTexels;
import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards each animated sprite's new frame to Remix as the frame advances.
 *
 * <p>Minecraft animates sprites on the GPU: every frame is uploaded once into its
 * own texture and each tick a shader copies, or for an interpolated animation
 * blends, the current frame into the atlas, so no pixels pass through the CPU
 * after load. The patch is therefore built here from the sprite's original image:
 * a frame change points into that image, and an interpolated animation is blended
 * on the CPU into a buffer this state owns, the way the shader blends it.
 */
@Mixin(SpriteContents.AnimationState.class)
public abstract class SpriteAnimationStateMixin implements AnimatedSpriteOwner {

    @Shadow private int frame;
    @Shadow private int subFrame;
    @Shadow @Final private SpriteContents.AnimatedTexture animationInfo;
    @Shadow private boolean isDirty;

    private @Nullable TextureAtlasSprite rtxremix$sprite;
    private @Nullable ByteBuffer rtxremix$blend;

    @Override
    public void rtxremix$setSprite(TextureAtlasSprite sprite) {
        rtxremix$sprite = sprite;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void rtxremix$forwardFrame(CallbackInfo ci) {
        TextureAtlasSprite sprite = rtxremix$sprite;
        boolean interpolate = animationInfo.interpolateFrames;
        if (sprite == null || !(interpolate || isDirty) || !AtlasAnimationForwarder.isActive()) {
            return;
        }
        SpriteContents contents = sprite.contents();
        NativeImage image = ((SpriteContentsAccessor) contents).getImage();
        if (image == null) {
            return;
        }
        int width = contents.width();
        int height = contents.height();
        int imageWidth = image.getWidth();
        int rowPitch = imageWidth * 4;
        List<SpriteContents.FrameInfo> frames = animationInfo.frames;
        SpriteContents.FrameInfo current = frames.get(frame);
        long currentFirst = rtxremix$frameAddress(image, current.index, width, height, rowPitch);
        if (!interpolate) {
            int columns = Math.max(1, animationInfo.frameRowSize);
            AtlasAnimationForwarder.addPatch(contents.name(), SpriteTexels.x(sprite), SpriteTexels.y(sprite),
                    (current.index % columns) * width, (current.index / columns) * height,
                    width, height, imageWidth, image.getHeight(), currentFirst, rowPitch);
            return;
        }

        SpriteContents.FrameInfo next = frames.get((frame + 1) % frames.size());
        long nextFirst = rtxremix$frameAddress(image, next.index, width, height, rowPitch);
        float progress = (float) subFrame / current.time;
        ByteBuffer blend = rtxremix$blend;
        if (blend == null) {
            blend = MemoryUtil.memAlloc(width * height * 4);
            rtxremix$blend = blend;
        }
        long out = MemoryUtil.memAddress(blend);
        for (int y = 0; y < height; y++) {
            long a = currentFirst + (long) y * rowPitch;
            long b = nextFirst + (long) y * rowPitch;
            long o = out + (long) y * width * 4;
            for (int i = 0; i < width * 4; i++) {
                int from = MemoryUtil.memGetByte(a + i) & 0xFF;
                int to = MemoryUtil.memGetByte(b + i) & 0xFF;
                MemoryUtil.memPutByte(o + i, (byte) Math.round(from + (to - from) * progress));
            }
        }
        // A whole-sprite buffer, which is how the forwarder recognises an interpolated frame.
        AtlasAnimationForwarder.addPatch(contents.name(), SpriteTexels.x(sprite), SpriteTexels.y(sprite),
                0, 0, width, height, width, height, out, width * 4);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void rtxremix$freeBlend(CallbackInfo ci) {
        ByteBuffer blend = rtxremix$blend;
        if (blend != null) {
            rtxremix$blend = null;
            MemoryUtil.memFree(blend);
        }
    }

    private long rtxremix$frameAddress(NativeImage image, int index, int width, int height, int rowPitch) {
        int columns = Math.max(1, animationInfo.frameRowSize);
        int x = (index % columns) * width;
        int y = (index / columns) * height;
        return image.getPointer() + (long) y * rowPitch + (long) x * 4;
    }
}
