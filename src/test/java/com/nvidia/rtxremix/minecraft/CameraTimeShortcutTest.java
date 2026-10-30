/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.nvidia.rtxremix.minecraft.camera.CameraController;
import org.junit.jupiter.api.Test;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.sdl.SDLScancode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CameraTimeShortcutTest {

    @Test
    void cameraTimeShortcutRecognizesEqualMinusAndFastModifier() {
        assertEquals(1, RtxRemixMod.cameraTimeAdjustmentDirection(InputConstants.KEY_EQUALS, 0));
        assertEquals(1, RtxRemixMod.cameraTimeAdjustmentDirection(
                InputConstants.KEY_EQUALS, InputConstants.MOD_SHIFT));
        assertEquals(-1, RtxRemixMod.cameraTimeAdjustmentDirection(InputConstants.KEY_MINUS, 0));
        assertEquals(0, RtxRemixMod.cameraTimeAdjustmentDirection(InputConstants.KEY_ADD, 0));
        assertEquals(0, RtxRemixMod.cameraTimeAdjustmentDirection(SDLScancode.SDL_SCANCODE_KP_MINUS, 0));
        assertEquals(10, RtxRemixMod.cameraTimeAdjustmentStep(0));
        assertEquals(250, RtxRemixMod.cameraTimeAdjustmentStep(InputConstants.MOD_CONTROL));
    }

    @Test
    void cameraTimeShortcutInactiveWithoutRecording() {
        assertFalse(RtxRemixMod.isCameraTimeShortcutActive(null));
        assertFalse(RtxRemixMod.isCameraTimeShortcutActive(new CameraController()));
    }
}
