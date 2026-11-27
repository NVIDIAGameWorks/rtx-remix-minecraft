/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.renderpearl.backend.opengl.GlSurface;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects the OpenGL buffer swap inside {@code GlSurface.present} to Remix's
 * present, so Vulkan owns the swapchain whenever the renderer is initialized.
 * Before that, and if initialization fails, the SDL swap runs unchanged.
 *
 * <p>By the time {@code present} runs, {@code GlSurface.blitFromTexture} has
 * copied the frame into the default framebuffer, which is where the GL interop
 * reads the composited GUI from.</p>
 */
@Mixin(GlSurface.class)
public abstract class GlSurfaceMixin {

    @Redirect(
        method = "present",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_SwapWindow(J)Z")
    )
    private boolean rtxremix$redirectSwapBuffers(long window) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null && renderer.isInitialized()) {
            renderer.presentFrameVk();
            return true;
        }
        return SDLVideo.SDL_GL_SwapWindow(window);
    }
}
