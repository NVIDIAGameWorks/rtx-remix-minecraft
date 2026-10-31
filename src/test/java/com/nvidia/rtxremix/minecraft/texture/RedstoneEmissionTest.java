/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedstoneEmissionTest {
    @Test
    void onlyUnpoweredDustStaysDark() {
        assertEquals(RedstoneEmission.UNPOWERED, RedstoneEmission.forPower(0));
        for (int power = 1; power <= 15; power++) {
            assertEquals(RedstoneEmission.POWERED, RedstoneEmission.forPower(power));
        }
    }
}
