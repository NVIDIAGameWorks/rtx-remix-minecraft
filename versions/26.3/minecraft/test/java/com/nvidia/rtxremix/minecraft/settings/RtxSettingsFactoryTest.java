/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nvidia.rtxremix.ErrorCode;
import com.nvidia.rtxremix.RemixException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RtxSettingsFactoryTest {

    private static final String OPTION = "rtx.materials.smoothReliefMapFiltering";

    private final Map<String, String> writes = new HashMap<>();

    @Test
    void aRemixBoolStartsFromTheResolvedValueWithoutWriting() {
        RtxSetting<Boolean> setting = remixBool(option -> "True");

        assertTrue(setting.get());
        assertTrue(setting.defaultValue());
        assertTrue(writes.isEmpty());
    }

    @Test
    void aRemixBoolReportsItsWriteBeforeRemixResolvesIt() {
        RtxSetting<Boolean> setting = remixBool(option -> "False");

        setting.apply(true);

        assertTrue(setting.get());
        assertFalse(setting.isDefault());
        assertEquals("true", writes.get(OPTION));
    }

    @Test
    void resettingARemixBoolWritesTheStartupValue() {
        RtxSetting<Boolean> setting = remixBool(option -> "False");
        setting.apply(true);

        setting.reset();

        assertFalse(setting.get());
        assertEquals("false", writes.get(OPTION));
    }

    @Test
    void aRemixBoolIsLeftOutWhenTheRuntimeCannotResolveTheOption() {
        Optional<RtxSetting<Boolean>> setting = RtxSettingsFactory.remixBool("t.bool",
                SettingGroup.MATERIALS, "k", OPTION,
                option -> {
                    throw new RemixException("GetConfigVariableResolved failed for " + option,
                            ErrorCode.GENERAL_FAILURE);
                },
                writes::put);

        assertTrue(setting.isEmpty());
        assertTrue(writes.isEmpty());
    }

    private RtxSetting<Boolean> remixBool(Function<String, String> configReader) {
        return RtxSettingsFactory.remixBool("t.bool", SettingGroup.MATERIALS, "k", OPTION,
                configReader, writes::put).orElseThrow();
    }
}
