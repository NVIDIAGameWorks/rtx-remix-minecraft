/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtxSettingTest {

    @Test
    void aSteppedSettingRoundsAValueBetweenStepsDownAsVanillaDoes() {
        // Vanilla loads a hand-edited maxFps:144 as 140, so the value in use is
        // always one its slider can show. The settings file reaches the live
        // value through apply.
        int[] holder = {10};
        RtxSetting<Integer> setting = RtxSetting.ofInt("t.int", SettingGroup.FRAMERATE,
                "k", "k.tip", 10, 260, 10, () -> holder[0], value -> holder[0] = value)
                .withStep(10);

        setting.apply(144);
        assertEquals(140, holder[0]);
        setting.apply(19);
        assertEquals(10, holder[0]);
        setting.apply(260);
        assertEquals(260, holder[0]);
    }

    @Test
    void aStepMustDivideTheRangeAndTheDefault() {
        // Otherwise rounding down could leave the range, or Reset could apply a
        // value the slider cannot show.
        assertThrows(IllegalArgumentException.class, () -> RtxSetting.ofInt("t.int",
                SettingGroup.FRAMERATE, "k", "k.tip", 15, 260, 20, () -> 20, value -> { })
                .withStep(10));
        assertThrows(IllegalArgumentException.class, () -> RtxSetting.ofInt("t.int",
                SettingGroup.FRAMERATE, "k", "k.tip", 10, 260, 15, () -> 15, value -> { })
                .withStep(10));
    }

    @Test
    void floatSettingGetsAppliesAndResets() {
        double[] holder = {2.0};
        RtxSetting<Double> s = RtxSetting.ofFloat(
                "mat.waterIor", SettingGroup.MATERIALS, "label.k", "tip.k",
                1.0, 5.0, 1.5, () -> holder[0], v -> holder[0] = v);

        assertEquals(RtxSetting.Kind.FLOAT, s.kind());
        assertEquals(2.0, s.get());
        assertFalse(s.isDefault());
        assertTrue(s.isDefaultValue(1.5));
        assertFalse(s.isDefaultValue(1.5001));
        s.apply(3.0);
        assertEquals(3.0, holder[0]);
        s.reset();
        assertEquals(1.5, holder[0]);
        assertTrue(s.isDefault());
        assertTrue(s.inRange(1.0));
        assertTrue(s.inRange(5.0));
        assertFalse(s.inRange(0.9));
        assertFalse(s.inRange(5.1));
    }

    @Test
    void floatDefaultComparisonUsesRuntimePrecision() {
        RtxSetting<Double> setting = RtxSetting.ofFloat(
                "post.exposure", SettingGroup.TONEMAPPING, "l", "t",
                0.0, 2.0, (double) 0.45f, () -> (double) 0.45f, value -> { });

        assertTrue(setting.isDefaultValue(0.45));
        assertFalse(setting.isDefaultValue(0.46));
    }

    @Test
    void resetDoesNotApplyWhenAlreadyAtDefault() {
        int[] holder = {5};
        int[] applyCount = {0};
        RtxSetting<Integer> setting = RtxSetting.ofInt(
                "dist.light", SettingGroup.RENDER_DISTANCE, "l", "t",
                0, 2048, 5, () -> holder[0], value -> {
                    applyCount[0]++;
                    holder[0] = value;
                });

        setting.reset();
        assertEquals(0, applyCount[0]);

        holder[0] = 10;
        setting.reset();
        assertEquals(1, applyCount[0]);
        assertEquals(5, holder[0]);
    }
}
