/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.input.RemixMouseAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the shared Remix overlay input policy to Minecraft's mouse callbacks.
 *
 * The gate blocks Minecraft only while the overlay capture session owns input.
 */
@Mixin(MouseHandler.class)
public abstract class MouseMixin implements RemixMouseAccess {
    private static final double rtxremix$HIDDEN_CURSOR_COORDINATE = -Double.MAX_VALUE;

    @Shadow @Final
    private Minecraft minecraft;

    @Shadow
    private int activeButton;

    @Shadow
    private int clickDepth;

    @Shadow
    private void onPress(long window, int button, int action, int mods) { }

    private boolean rtxremix$releasingActiveButton;

    @Override
    public void rtxremix$releaseActiveButton() {
        if (activeButton == -1) {
            return;
        }

        // Touchscreen mode counts outstanding presses and suppresses intermediate releases.
        clickDepth = minecraft.options.touchscreen().get() ? 1 : 0;
        rtxremix$releasingActiveButton = true;
        try {
            onPress(minecraft.getWindow().getWindow(), activeButton, GLFW.GLFW_RELEASE, 0);
        } finally {
            rtxremix$releasingActiveButton = false;
        }
    }

    @Shadow
    private double xpos;

    @Shadow
    private double ypos;

    @Shadow
    private boolean ignoreFirstMove;

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void rtxremix$blockMovementWhileMenuOpen(
            long window, double cursorX, double cursorY, CallbackInfo ci) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            xpos = cursorX;
            ypos = cursorY;
            ignoreFirstMove = false;
            ci.cancel();
        }
    }

    /* Screen rendering derives hover state from these coordinates even without a movement event. */
    @Inject(method = "xpos", at = @At("HEAD"), cancellable = true)
    private void rtxremix$hideXWhileMenuOpen(CallbackInfoReturnable<Double> cir) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            cir.setReturnValue(rtxremix$HIDDEN_CURSOR_COORDINATE);
        }
    }

    @Inject(method = "ypos", at = @At("HEAD"), cancellable = true)
    private void rtxremix$hideYWhileMenuOpen(CallbackInfoReturnable<Double> cir) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            cir.setReturnValue(rtxremix$HIDDEN_CURSOR_COORDINATE);
        }
    }

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void rtxremix$blockLockWhileMenuOpen(CallbackInfo ci) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void rtxremix$blockClickWhileMenuOpen(long window, int button, int action, int mods, CallbackInfo ci) {
        boolean syntheticRelease = rtxremix$releasingActiveButton
                && action == GLFW.GLFW_RELEASE
                && button == activeButton;
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen() && !syntheticRelease) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void rtxremix$blockScrollWhileMenuOpen(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }
}
