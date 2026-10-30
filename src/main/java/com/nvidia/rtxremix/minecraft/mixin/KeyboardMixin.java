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
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.PreeditEvent;
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
 * {@code KeyboardHandler.keyPress} runs this when Minecraft drains the SDL key event, so the
 * modifier mask is the one SDL recorded when the key was pressed. Matched shortcuts cancel the event so vanilla does not also
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
    private boolean usedDebugKeyAsModifier;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void rtxremix$onKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        int key = event.key();
        int mods = event.modifiers();
        boolean altDown = action == InputConstants.PRESS
                && key == InputConstants.KEY_X
                && event.hasAltDown();
        boolean controlDown = action == InputConstants.PRESS
                && key == InputConstants.KEY_X
                && event.hasControlDown();
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
        if (client.gui.screen() != null
                || (action != InputConstants.PRESS && action != InputConstants.REPEAT)) {
            return;
        }

        if (action == InputConstants.PRESS
                && key == InputConstants.KEY_R
                && RtxRemixMod.shouldHandlePlainPathRecordingShortcut()) {
            ci.cancel();
            RtxRemixMod.togglePathRecording();
        } else if (RtxRemixMod.handleCameraTimeShortcut(key, action, mods)) {
            ci.cancel();
        }
    }

    @Inject(method = "textInput", at = @At("HEAD"), cancellable = true)
    private void rtxremix$onText(long window, String text, CallbackInfo ci) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }

    @Inject(method = "textEditing", at = @At("HEAD"), cancellable = true)
    private void rtxremix$onTextEditing(long window, PreeditEvent event, CallbackInfo ci) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }

    @Override
    public void rtxremix$resetDebugKeyState() {
        debugCrashKeyTime = -1L;
        debugCrashKeyReportedTime = -1L;
        debugCrashKeyReportedCount = -1L;
        usedDebugKeyAsModifier = false;
    }
}
