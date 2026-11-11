/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.input;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemixInputConfigurationTest {

    @Test
    void runtimeMenuBindingUsesAnUnreachableFallbackChord() throws Exception {
        Map<String, String> config = readConfig();
        String binding = config.get("rtx.remixMenuKeyBinds");
        assertNotNull(binding);
        assertFalse(binding.isBlank());

        Set<String> chord = canonicalChord(binding);
        assertTrue(chord.containsAll(Set.of("CTRL", "PAUSE")));
        assertFalse(Set.of("ALT", "X").containsAll(chord));
    }

    @Test
    void runtimeSplashMessageIsDisabled() throws Exception {
        String value = readConfig().get("rtx.hideSplashMessage");
        assertNotNull(value);
        assertTrue(Set.of("true", "1").contains(value.strip().toLowerCase(Locale.ROOT)));
    }

    private static Map<String, String> readConfig() throws Exception {
        Map<String, String> config = new HashMap<>();
        for (String rawLine : Files.readAllLines(Path.of("packaging", "rtx.conf"))) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int delimiter = line.indexOf('=');
            if (delimiter < 0) {
                continue;
            }
            String option = line.substring(0, delimiter).strip();
            String value = line.substring(delimiter + 1).strip();
            assertNull(config.put(option, value), "Duplicate configuration option: " + option);
        }
        return config;
    }

    private static Set<String> canonicalChord(String binding) {
        Set<String> chord = new HashSet<>();
        for (String rawToken : binding.split(",")) {
            String token = rawToken.strip().toUpperCase(Locale.ROOT);
            assertFalse(token.isEmpty(), "Empty key in runtime menu binding");
            String canonical = switch (token) {
                case "CTRL", "CONTROL" -> "CTRL";
                case "SHIFT", "SHFT" -> "SHIFT";
                case "ALT", "MENU" -> "ALT";
                case "PAUSE" -> "PAUSE";
                case "X" -> "X";
                default -> null;
            };
            assertNotNull(canonical, "Unknown key in runtime menu binding: " + token);
            assertTrue(chord.add(canonical), "Duplicate key in runtime menu binding: " + token);
        }
        return chord;
    }
}
