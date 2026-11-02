/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

import net.minecraft.client.texture.NativeImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeImageColorsTest {
    @Test
    void preservesAlphaAndGreenWhileReturningRedInTheLowByte() {
        try (NativeImage image = new NativeImage(1, 1, false)) {
            image.setColorArgb(0, 0, 0x7F123456);
            assertEquals(0x7F563412, NativeImageColors.getAbgr(image, 0, 0));
        }
    }
}
