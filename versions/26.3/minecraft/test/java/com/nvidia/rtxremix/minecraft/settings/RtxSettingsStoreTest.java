/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RtxSettingsStoreTest {

    private RtxSettings registryWith(double[] f, int[] i, boolean[] b) {
        return new RtxSettings(List.of(
                RtxSetting.ofFloat("f", SettingGroup.MATERIALS, "l", "t", 0.0, 10.0, 1.0,
                        () -> f[0], v -> f[0] = v),
                RtxSetting.ofInt("i", SettingGroup.RENDER_DISTANCE, "l", "t", 0, 100, 0,
                        () -> i[0], v -> i[0] = v),
                RtxSetting.ofBool("b", SettingGroup.SKY, "l", "t", true,
                        () -> b[0], v -> b[0] = v)));
    }

    @Test
    void savesAndLoadsRoundTrip(@TempDir Path dir) {
        Path file = dir.resolve("rtxremix.json");
        double[] f = {3.5}; int[] i = {42}; boolean[] b = {false};
        RtxSettingsStore store = new RtxSettingsStore(file);
        store.save(registryWith(f, i, b));
        assertTrue(Files.exists(file));

        double[] f2 = {0}; int[] i2 = {0}; boolean[] b2 = {true};
        store.load(registryWith(f2, i2, b2));
        assertEquals(3.5, f2[0]);
        assertEquals(42, i2[0]);
        assertFalse(b2[0]);
    }

    @Test
    void blockLightMultiplierPersistsAcrossTheFullRange(@TempDir Path dir) {
        for (double candidate : new double[]{0.0, 50.0}) {
            Path file = dir.resolve("rtxremix-" + (int) candidate + ".json");
            double[] source = {candidate};
            RtxSetting<Double> sourceSetting = RtxSetting.ofFloat(
                    "blockLights.torch",
                    SettingGroup.BLOCK_LIGHTS,
                    "label",
                    "tooltip",
                    0.0,
                    50.0,
                    1.0,
                    () -> source[0],
                    value -> source[0] = value);
            RtxSettingsStore store = new RtxSettingsStore(file);
            store.save(new RtxSettings(List.of(sourceSetting)));

            double[] restored = {1.0};
            RtxSetting<Double> restoredSetting = RtxSetting.ofFloat(
                    "blockLights.torch",
                    SettingGroup.BLOCK_LIGHTS,
                    "label",
                    "tooltip",
                    0.0,
                    50.0,
                    1.0,
                    () -> restored[0],
                    value -> restored[0] = value);
            store.load(new RtxSettings(List.of(restoredSetting)));

            assertEquals(candidate, restored[0]);
        }
    }

    @Test
    void saveWritesOnlyNonDefaultOverrides(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rtxremix.json");
        double[] f = {1.0}; int[] i = {42}; boolean[] b = {true};

        new RtxSettingsStore(file).save(registryWith(f, i, b));

        JsonObject values = JsonParser.parseString(Files.readString(file))
                .getAsJsonObject().getAsJsonObject("settings");
        assertFalse(values.has("f"));
        assertEquals(42, values.get("i").getAsInt());
        assertFalse(values.has("b"));
    }

    @Test
    void saveDeletesFileWhenNoOverridesRemain(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rtxremix.json");
        Files.writeString(file, "stale");
        double[] f = {1.0}; int[] i = {0}; boolean[] b = {true};

        new RtxSettingsStore(file).save(registryWith(f, i, b));

        assertFalse(Files.exists(file));
    }

    @Test
    void loadSkipsUnknownIdsAndOutOfRange(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rtxremix.json");
        Files.writeString(file,
                "{\"version\":2,\"settings\":{\"i\":9999,\"ghost\":5,\"f\":2.0}}");
        double[] f = {0}; int[] i = {7}; boolean[] b = {true};
        new RtxSettingsStore(file).load(registryWith(f, i, b));
        assertEquals(2.0, f[0]);   // applied
        assertEquals(7, i[0]);     // out of range (max 100) -> skipped, keeps live value
        assertTrue(b[0]); // "b" absent from JSON -> unchanged
    }

    @Test
    void loadSkipsDefaultValuedEntries(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rtxremix.json");
        Files.writeString(file,
                "{\"version\":2,\"settings\":{\"f\":1.0,\"i\":0,\"b\":true}}");
        double[] f = {8.0}; int[] i = {9}; boolean[] b = {false};

        new RtxSettingsStore(file).load(registryWith(f, i, b));

        assertEquals(8.0, f[0]);
        assertEquals(9, i[0]);
        assertFalse(b[0]);
    }

    @Test
    void loadTreatsDecimalJsonAsFloatBackedDefault(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rtxremix.json");
        Files.writeString(file, "{\"version\":2,\"settings\":{\"post.exposure\":0.45}}");
        int[] applies = {0};
        RtxSetting<Double> exposure = RtxSetting.ofFloat(
                "post.exposure", SettingGroup.TONEMAPPING, "l", "t",
                0.0, 2.0, (double) 0.45f, () -> (double) 0.45f,
                value -> applies[0]++);

        new RtxSettingsStore(file).load(new RtxSettings(List.of(exposure)));

        assertEquals(0, applies[0]);
    }

    @Test
    void loadMissingFileIsNoop(@TempDir Path dir) {
        double[] f = {1}; int[] i = {1}; boolean[] b = {true};
        new RtxSettingsStore(dir.resolve("nope.json")).load(registryWith(f, i, b));
        assertEquals(1.0, f[0]);
    }

    @Test
    void loadRejectsAnyVersionItDoesNotOwn(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rtxremix.json");
        Files.writeString(file,
                "{\"version\":3,\"settings\":{\"f\":9.0,\"i\":50,\"b\":false}}");
        double[] f = {1.0}; int[] i = {5}; boolean[] b = {true};
        new RtxSettingsStore(file).load(registryWith(f, i, b));
        assertEquals(1.0, f[0]);  // unsupported version -> nothing applied
        assertEquals(5, i[0]);
        assertTrue(b[0]);
    }
}
