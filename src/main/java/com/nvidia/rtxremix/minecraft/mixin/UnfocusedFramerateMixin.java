/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.UnfocusedFramerateLimit;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Applies {@link UnfocusedFramerateLimit} while the window is unfocused, so a
 * game left behind other windows does not hold the GPU at path-tracing load.
 * Without an initialized Remix renderer the game draws through vanilla, so
 * the limit is left to vanilla too.
 *
 * <p>Minecraft's render loop waits for input only inside
 * {@code glfwWaitEventsTimeout}, which {@code RenderSystem.limitDisplayFPS}
 * reaches only when a frame finishes inside the cap's budget. At Unlimited, or
 * at any cap the frame rate does not reach, the loop never waits, and with the
 * Remix present path Windows then marks an unfocused window as not responding.
 * {@code rtx.conf} enables {@code rtx.enableInputQueuePing}, which keeps the
 * window responsive at every setting.
 */
@Mixin(Minecraft.class)
public abstract class UnfocusedFramerateMixin {

    @ModifyReturnValue(method = "getFramerateLimit()I", at = @At("RETURN"))
    private int rtxremix$capWhileUnfocused(int limit) {
        if (((Minecraft) (Object) this).isWindowActive()) {
            return limit;
        }
        // A benchmark measures renderer throughput and must not be throttled
        // because the user tabbed away while it ran.
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer == null || !renderer.isInitialized()
                || renderer.getBenchmarkController().isRunning()) {
            return limit;
        }
        return UnfocusedFramerateLimit.apply(limit);
    }
}
