/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.neoforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoForgeTerrainHooksTest {
    @Test
    void everyBlockModelUsesNeoForgesExtendedRenderingPath() {
        assertTrue(new NeoForgeTerrainHooks().isCustomBlockModel(null));
    }
}
