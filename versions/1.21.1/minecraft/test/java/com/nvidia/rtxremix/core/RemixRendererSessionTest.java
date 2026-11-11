/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemixRendererSessionTest {
    @Test
    void configOverridesPreserveOrderAndUseTheLastDuplicate() {
        Map<String, String> overrides = RemixRendererSession.parseConfigOverrides(
                " rtx.a = 1 ; malformed ; rtx.b=true;rtx.a=2;=missing");

        assertEquals(2, overrides.size());
        assertEquals("2", overrides.get("rtx.a"));
        assertEquals("true", overrides.get("rtx.b"));
        assertEquals("rtx.a", overrides.keySet().iterator().next());
    }

    @Test
    void absentOverridesAreEmpty() {
        assertTrue(RemixRendererSession.parseConfigOverrides(null).isEmpty());
        assertTrue(RemixRendererSession.parseConfigOverrides("  ").isEmpty());
    }
}
