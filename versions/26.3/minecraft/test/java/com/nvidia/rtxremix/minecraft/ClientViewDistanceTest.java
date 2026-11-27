/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClientViewDistanceTest {

    @Test
    void withoutAClientTheAccessorFallsBackToTwelveChunks() {
        // Headless tests have no MinecraftClient, so the accessor's fallback is what
        // every caller sees.
        assertEquals(12, ClientViewDistance.chunks());
        assertEquals(192.0, ClientViewDistance.blocks());
    }
}
