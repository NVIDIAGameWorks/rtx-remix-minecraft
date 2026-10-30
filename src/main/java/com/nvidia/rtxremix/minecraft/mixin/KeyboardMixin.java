/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.input.RemixInputGate;
import com.nvidia.rtxremix.minecraft.input.RemixKeyboardAccess;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes keyboard input through the Remix overlay gate and intercepts the mod's raw shortcuts:
 * Alt+X without Control toggles the overlay, and while its capture session is open every key
 * and character is withheld from Minecraft. Plain R toggles camera recording once an in-game
 * command has armed it, and = and - scrub time of day while recording. Injecting into
 * {@code Keyboard.onKey} runs this at GLFW key-event time, so the modifier mask is read the
 * instant the key is pressed. Matched shortcuts cancel the event so vanilla does not also
 * handle it.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardMixin implements RemixKeyboardAccess {
    @Shadow
    private long debugCrashKeyTime;

    @Shadow
    private long debugCrashKeyReportedTime;

    @Shadow
    private long debugCrashKeyReportedCount;

    @Shadow
    private boolean handledDebugKey;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void rtxremix$onKey(long window, int key, int scancode, int action, int mods, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        boolean altDown = action == GLFW.GLFW_PRESS
                && key == GLFW.GLFW_KEY_X
                && ((mods & GLFW.GLFW_MOD_ALT) != 0 || Screen.hasAltDown());
        boolean controlDown = action == GLFW.GLFW_PRESS
                && key == GLFW.GLFW_KEY_X
                && ((mods & GLFW.GLFW_MOD_CONTROL) != 0 || Screen.hasControlDown());
        RemixInputGate.KeyDecision decision = RtxRemixMod.getRemixInputGate().onKey(
                key, action, altDown, controlDown);

        if (decision == RemixInputGate.KeyDecision.BLOCK) {
            ci.cancel();
            return;
        }
        if (decision == RemixInputGate.KeyDecision.TOGGLE_MENU) {
            ci.cancel();
            RtxRemixMod.toggleRemixMenu();
            return;
        }
        if (client.screen != null
                || (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT)) {
            return;
        }

        if (action == GLFW.GLFW_PRESS
                && key == GLFW.GLFW_KEY_R
                && RtxRemixMod.shouldHandlePlainPathRecordingShortcut()) {
            ci.cancel();
            RtxRemixMod.togglePathRecording();
        } else if (RtxRemixMod.handleCameraTimeShortcut(key, action, mods)) {
            ci.cancel();
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void rtxremix$onChar(long window, int codePoint, int mods, CallbackInfo ci) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }

    @Override
    public void rtxremix$resetDebugKeyState() {
        debugCrashKeyTime = -1L;
        debugCrashKeyReportedTime = -1L;
        debugCrashKeyReportedCount = -1L;
        handledDebugKey = false;
    }
}
