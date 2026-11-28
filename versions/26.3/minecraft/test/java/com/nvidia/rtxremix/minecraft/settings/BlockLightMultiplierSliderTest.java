/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.minecraft.light.BlockLightIntensityControls;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockLightMultiplierSliderTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void mappingProvidesOffSentinelAndExponentialEndpoints() {
        assertEquals(0.0, BlockLightMultiplierSlider.multiplierFromPosition(0.0));
        assertEquals(
                0.0,
                BlockLightMultiplierSlider.multiplierFromPosition(
                        BlockLightMultiplierSlider.ZERO_SENTINEL_END / 2.0));
        assertEquals(
                BlockLightMultiplierSlider.MIN_NONZERO_MULTIPLIER,
                BlockLightMultiplierSlider.multiplierFromPosition(
                        BlockLightMultiplierSlider.ZERO_SENTINEL_END),
                EPSILON);
        assertEquals(
                BlockLightIntensityControls.MAX_MULTIPLIER,
                BlockLightMultiplierSlider.multiplierFromPosition(1.0),
                EPSILON);
    }

    @Test
    void mappingRoundTripsDefaultAndRepresentativeMultipliers() {
        for (double multiplier : new double[]{0.0, 0.02, 0.1, 1.0, 5.0, 25.0, 50.0}) {
            double position = BlockLightMultiplierSlider.positionFromMultiplier(multiplier);
            assertEquals(
                    multiplier,
                    BlockLightMultiplierSlider.multiplierFromPosition(position),
                    EPSILON,
                    "multiplier=" + multiplier);
        }
    }

    @Test
    void nonzeroRangeIsStrictlyIncreasing() {
        double previous = 0.0;
        for (int step = 4; step <= 100; step++) {
            double current = BlockLightMultiplierSlider.multiplierFromPosition(step / 100.0);
            assertTrue(current > previous, "step=" + step);
            previous = current;
        }
    }

    @Test
    void sliderDefersItsSettingUntilCommit() {
        double[] liveValue = {1.0};
        RtxSetting<Double> setting = RtxSetting.ofFloat(
                "blockLights.test",
                SettingGroup.BLOCK_LIGHTS,
                "label",
                "tooltip",
                0.0,
                50.0,
                1.0,
                () -> liveValue[0],
                value -> liveValue[0] = value);
        TestSlider slider = new TestSlider(setting);

        slider.moveTo(1.0);
        assertEquals(1.0, liveValue[0]);

        assertTrue(slider.commit());
        assertEquals(50.0, liveValue[0], EPSILON);
    }

    private static final class TestSlider extends BlockLightMultiplierSlider {
        private TestSlider(RtxSetting<Double> setting) {
            super(setting);
        }

        private void moveTo(double position) {
            value = position;
            applyValue();
            updateMessage();
        }
    }
}
