/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityPresetTest {

    @Test
    void idsAreListedCheapestFirst() {
        assertEquals(List.of("low", "medium", "high", "ultra"), QualityPreset.ids());
    }

    @Test
    void eachTierWritesOnlyRemixsGraphicsPreset() {
        // Remix's GraphicsPreset enumerators: Ultra 0, High 1, Medium 2, Low 3.
        Map<String, String> expected = Map.of("ultra", "0", "high", "1", "medium", "2", "low", "3");
        for (QualityPreset preset : QualityPreset.values()) {
            Map<String, String> writes = new LinkedHashMap<>();

            preset.writeTo(writes::put);

            assertEquals(Map.of("rtx.graphicsPreset", expected.get(preset.id())), writes);
        }
    }

    @Test
    void lookupMatchesTheIdExactly() {
        assertEquals(Optional.of(QualityPreset.HIGH), QualityPreset.byId("high"));
        assertTrue(QualityPreset.byId("High").isEmpty());
        assertTrue(QualityPreset.byId("Custom").isEmpty());
    }
}
