/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.UnfocusedFramerateLimit;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Applies {@link UnfocusedFramerateLimit} while the window is unfocused, so a
 * game left behind other windows does not hold the GPU at path-tracing load.
 * Without an initialized Remix renderer the game draws through vanilla, so
 * the limit is left to vanilla too.
 *
 * <p>The limit is applied to {@link FramerateLimitTracker#getFramerateLimit},
 * which already folds in vanilla's own iconified, AFK and menu throttles, so
 * the lower of the two wins. {@code FramerateLimiter.limitDisplayFPS} sleeps
 * only when a frame finishes inside the cap's budget; {@code rtx.conf} enables
 * {@code rtx.enableInputQueuePing}, which keeps the window responsive when it
 * does not.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class UnfocusedFramerateMixin {

    @ModifyReturnValue(method = "getFramerateLimit()I", at = @At("RETURN"))
    private int rtxremix$capWhileUnfocused(int limit) {
        if (Minecraft.getInstance().isWindowActive()) {
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
