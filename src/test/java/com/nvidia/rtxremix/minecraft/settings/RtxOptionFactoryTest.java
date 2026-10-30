/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.contents.TranslatableContents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtxOptionFactoryTest {

    private static final String LABEL_KEY = "rtxremix.test.label";

    private static RtxSetting<?> settingOf(RtxSetting.Kind kind) {
        return switch (kind) {
            case BOOL -> RtxSetting.ofBool("t.bool", SettingGroup.RENDER_DISTANCE, LABEL_KEY, "k.tip",
                    false, () -> false, v -> { });
            case INT -> RtxSetting.ofInt("t.int", SettingGroup.RENDER_DISTANCE, LABEL_KEY, "k.tip",
                    0, 10, 5, () -> 5, v -> { });
            case FLOAT -> RtxSetting.ofFloat("t.float", SettingGroup.RENDER_DISTANCE, LABEL_KEY, "k.tip",
                    0.0, 1.0, 0.5, () -> 0.5, v -> { });
        };
    }

    private static RtxSetting<Integer> intSetting(String id, int min, int max) {
        return RtxSetting.ofInt(id, SettingGroup.RENDER_DISTANCE, LABEL_KEY, "k.tip",
                min, max, min, () -> min, v -> { });
    }

    private static OptionInstance<?> toOption(RtxSetting<?> setting) {
        return RtxOptionFactory.toOption(setting);
    }

    @Test
    void everySettingKindShowsItsLabelOnce() {
        // Buttons compose "<label>: <value>" themselves, while sliders show
        // their value text as is, so the same value text doubles the label on
        // one and drops it from the other. The built widget's message is what
        // the screen shows, whichever way a kind renders.
        for (RtxSetting.Kind kind : RtxSetting.Kind.values()) {
            // GameOptions is only written when a widget's value changes.
            String message = toOption(settingOf(kind))
                    .createButton(null).getMessage().getString();

            assertEquals(1, occurrences(message, LABEL_KEY), kind + " shows \"" + message + "\"");
        }
    }

    private static int occurrences(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }

    @Test
    void proceduralCloudToggleUsesCloudStyleNames() {
        assertEquals(
                "rtxremix.settings.value.procedural",
                ((TranslatableContents) RtxOptionFactory.booleanValueText(
                        RtxOptionFactory.PROCEDURAL_CLOUDS_ID, true).getContents()).getKey());
        assertEquals(
                "rtxremix.settings.value.classic",
                ((TranslatableContents) RtxOptionFactory.booleanValueText(
                        RtxOptionFactory.PROCEDURAL_CLOUDS_ID, false).getContents()).getKey());
    }

    @Test
    void bothDistanceSlidersShareOneSentinelAboveThirtyTwoChunks() {
        // Equal ranges are the point: one sentinel position serves both, where
        // the per-setting constants existed only because the maxima differed.
        for (String id : List.of("dist.entity", "dist.light")) {
            RtxSetting<Integer> distance = intSetting(id, 0, 32);
            assertEquals(32, RtxOptionFactory.intValueFromSlider(distance, 32), id);
            assertEquals(0, RtxOptionFactory.intValueFromSlider(distance, 33), id);

            assertEquals(33, RtxOptionFactory.sliderValueFromInt(distance, 0), id);
            assertEquals(32, RtxOptionFactory.sliderValueFromInt(distance, 32), id);
            // Out of range clamps onto the slider rather than onto the sentinel,
            // so a stale value cannot read as Auto.
            assertEquals(32, RtxOptionFactory.sliderValueFromInt(distance, 104), id);
        }
    }

    @Test
    void unfocusedFramerateSliderMirrorsVanillaMaxFramerate() {
        RtxSetting<Integer> framerate = RtxSettingsFactory.unfocusedFramerate();
        String id = framerate.id();

        assertEquals(10, RtxOptionFactory.intValueFromSlider(framerate, 1));
        assertEquals(250, RtxOptionFactory.intValueFromSlider(framerate, 25));
        assertEquals(260, RtxOptionFactory.intValueFromSlider(framerate, 26));
        assertEquals(1, RtxOptionFactory.sliderValueFromInt(framerate, 10));
        assertEquals(26, RtxOptionFactory.sliderValueFromInt(framerate, 260));

        assertEquals("options.framerateLimit.max",
                ((TranslatableContents) RtxOptionFactory.intValueText(id, 260)
                        .getContents()).getKey());
        TranslatableContents capped = (TranslatableContents)
                RtxOptionFactory.intValueText(id, 120).getContents();
        assertEquals("options.framerate", capped.getKey());
        assertEquals(120, capped.getArgs()[0]);
    }

    @Test
    void ordinaryIntegerSlidersKeepIdentityMapping() {
        RtxSetting<Integer> ordinary = intSetting("debug.example", 0, 128);
        assertEquals(0, RtxOptionFactory.intValueFromSlider(ordinary, 0));
        assertEquals(128, RtxOptionFactory.intValueFromSlider(ordinary, 128));
        assertEquals(0, RtxOptionFactory.sliderValueFromInt(ordinary, 0));
        assertEquals(128, RtxOptionFactory.sliderValueFromInt(ordinary, 128));
    }

    @Test
    void factoryPreservesDeferredApplyForIntegerAndFloatSliders() {
        RtxSetting<Integer> integerSetting =
                RtxSetting.ofInt("t.int", SettingGroup.RENDER_DISTANCE, "k", "k.tip",
                        0, 10, 5, () -> 5, value -> { })
                        .deferSliderApply();
        RtxSetting<Double> floatSetting =
                RtxSetting.ofFloat("t.float", SettingGroup.RENDER_DISTANCE, "k", "k.tip",
                        0.0, 1.0, 0.5, () -> 0.5, value -> { })
                        .deferSliderApply();

        assertFalse(sliderCallbacks(toOption(integerSetting))
                .applyValueImmediately());
        assertFalse(sliderCallbacks(toOption(floatSetting))
                .applyValueImmediately());
    }

    @Test
    void immediateSliderPolicyPreservesTheOriginalCallbacks() {
        var callbacks = OptionInstance.UnitDouble.INSTANCE;

        assertTrue(callbacks.applyValueImmediately());
        assertSame(callbacks, RtxOptionFactory.sliderApplyPolicy(callbacks, true));
    }

    private static OptionInstance.SliderableValueSet<?> sliderCallbacks(OptionInstance<?> option) {
        try {
            Field callbacksField = OptionInstance.class.getDeclaredField("values");
            callbacksField.setAccessible(true);
            return (OptionInstance.SliderableValueSet<?>) callbacksField.get(option);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not inspect SimpleOption callbacks", e);
        }
    }

    @ParameterizedTest
    @CsvSource({
            RtxOptionFactory.SUN_INTENSITY_ID + ", 100.0, 0.51",
            RtxOptionFactory.TERRAIN_EMISSION_MULTIPLIER_ID + ", 50.0, 0.52"
    })
    void exponentialSlidersBalanceRangesAroundOne(String id, double max, double expectedOnePosition) {
        double onePosition = RtxOptionFactory.sliderValueFromFloat(
                id, 1.0, 0.0, max);

        assertEquals(expectedOnePosition, onePosition, 1.0e-9);
        assertEquals(
                1.0,
                RtxOptionFactory.floatValueFromSlider(id, onePosition, 0.0, max),
                1.0e-9);
        assertEquals(
                0.0,
                RtxOptionFactory.floatValueFromSlider(id, 0.0, 0.0, max));
        assertEquals(
                max,
                RtxOptionFactory.floatValueFromSlider(id, 1.0, 0.0, max),
                1.0e-9);
    }

    @Test
    void ordinaryFloatSlidersRemainLinear() {
        assertEquals(
                25.0,
                RtxOptionFactory.floatValueFromSlider("debug.example", 0.25, 0.0, 100.0));
        assertEquals(
                0.25,
                RtxOptionFactory.sliderValueFromFloat(
                        "debug.example", 25.0, 0.0, 100.0));
    }

    @Test
    void zeroRendersAsAutoForBothDistanceSliders() {
        for (String id : List.of("dist.entity", "dist.light")) {
            assertEquals("rtxremix.settings.value.auto",
                    ((TranslatableContents) RtxOptionFactory.intValueText(id, 0).getContents()).getKey(), id);
        }
        assertEquals("7", RtxOptionFactory.intValueText("dist.entity", 7).getString());
        assertEquals("0", RtxOptionFactory.intValueText("light.reach", 0).getString(),
                "only sentinel-backed sliders render Auto");
    }
}
