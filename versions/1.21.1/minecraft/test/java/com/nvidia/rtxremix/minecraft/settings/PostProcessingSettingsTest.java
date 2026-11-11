/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostProcessingSettingsTest {

    @Test
    void constructionDoesNotWriteDefaults() {
        Map<String, String> values = new HashMap<>();

        new PostProcessingSettings(values::put);

        assertTrue(values.isEmpty());
    }

    @Test
    void javaDefaultsMatchAuthoredBaseline() throws Exception {
        Map<String, String> baseline = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("packaging", "rtx.conf"))) {
            String[] parts = line.split("=", 2);
            if (parts.length == 2) {
                baseline.put(parts[0].trim(), parts[1].trim());
            }
        }
        PostProcessingSettings settings = new PostProcessingSettings((key, value) -> { });

        assertEquals(settings.getExposure(),
                Float.parseFloat(baseline.get("rtx.localtonemap.exposure")));
        assertEquals(settings.getShadowLevel(),
                Float.parseFloat(baseline.get("rtx.localtonemap.shadows")));
        assertEquals(settings.getHighlightsLevel(),
                Float.parseFloat(baseline.get("rtx.localtonemap.highlights")));
        assertEquals(settings.getLowLightAdaptation(),
                Float.parseFloat(baseline.get("rtx.autoExposure.evMinValue")));
        assertEquals(settings.getHighLightAdaptation(),
                Float.parseFloat(baseline.get("rtx.autoExposure.evMaxValue")));
        assertFalse(baseline.containsKey("rtx.userBrightness.userBrightness"));
    }

    @Test
    void controlsExposeNativeValues() {
        Map<String, String> values = new HashMap<>();
        PostProcessingSettings settings = new PostProcessingSettings(values::put);

        settings.setExposure(0.75f);
        settings.setShadowLevel(2.0f);
        settings.setHighlightsLevel(6.0f);
        settings.setLowLightAdaptation(-2.5f);
        settings.setHighLightAdaptation(7.0f);

        assertEquals(0.75f, settings.getExposure());
        assertEquals(2.0f, settings.getShadowLevel());
        assertEquals(6.0f, settings.getHighlightsLevel());
        assertEquals(-2.5f, settings.getLowLightAdaptation());
        assertEquals(7.0f, settings.getHighLightAdaptation());
        assertEquals("0.75", values.get("rtx.localtonemap.exposure"));
        assertEquals("2.0", values.get("rtx.localtonemap.shadows"));
        assertEquals("6.0", values.get("rtx.localtonemap.highlights"));
        assertEquals("-2.5", values.get("rtx.autoExposure.evMinValue"));
        assertEquals("7.0", values.get("rtx.autoExposure.evMaxValue"));
    }
}
