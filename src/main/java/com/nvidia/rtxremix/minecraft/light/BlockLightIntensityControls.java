/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

/** User-controlled intensity multipliers for the built-in block-light rules. */
public final class BlockLightIntensityControls {

    public static final double DEFAULT_MULTIPLIER = 1.0;
    public static final double MAX_MULTIPLIER = 50.0;

    private static final List<Control> CONTROLS = List.of(
            control("torch", "block.minecraft.torch"),
            control("soul_torch", "block.minecraft.soul_torch"),
            control("wall_torch", "block.minecraft.wall_torch"),
            control("soul_wall_torch", "block.minecraft.soul_wall_torch"),
            control("copper_torch", "block.minecraft.copper_torch"),
            control("copper_wall_torch", "block.minecraft.copper_wall_torch"),
            control("redstone_torch", "block.minecraft.redstone_torch"),
            control("redstone_wall_torch", "block.minecraft.redstone_wall_torch"),
            control("small_amethyst_bud", "block.minecraft.small_amethyst_bud"),
            control("medium_amethyst_bud", "block.minecraft.medium_amethyst_bud"),
            control("large_amethyst_bud", "block.minecraft.large_amethyst_bud"),
            control("amethyst_cluster", "block.minecraft.amethyst_cluster"),
            control("campfire", "block.minecraft.campfire"),
            control("soul_campfire", "block.minecraft.soul_campfire"),
            control("conduit", "block.minecraft.conduit"),
            control("light", "block.minecraft.light"),
            control("candles", "rtxremix.setting.blockLights.candles"),
            control("candle_cakes", "rtxremix.setting.blockLights.candleCakes"));
    private static final Map<Identifier, Control> BY_RULE_ID = indexByRuleId();

    private BlockLightIntensityControls() {}

    public static List<Control> controls() {
        return CONTROLS;
    }

    /**
     * Applies only the normalized user multiplier.
     *
     * <p>Authored tuning belongs to the resolved rule, so a resource-pack rule
     * replacing a built-in identifier retains its own intensity at the default 1x.
     */
    public static float apply(Identifier ruleId, float baseIntensity) {
        Control control = BY_RULE_ID.get(ruleId);
        return control == null ? baseIntensity : baseIntensity * (float) control.get();
    }

    static void validateBuiltInRules(Set<Identifier> ruleIds) {
        if (!BY_RULE_ID.keySet().equals(ruleIds)) {
            throw new IllegalStateException(
                    "Block-light controls do not match built-in rules: controls="
                            + BY_RULE_ID.keySet() + ", rules=" + ruleIds);
        }
    }

    private static Control control(String rulePath, String labelKey) {
        return control(
                rulePath,
                BlockLightRuleIds.builtIn(rulePath),
                labelKey);
    }

    private static Control control(
            String rulePath, Identifier ruleId, String labelKey) {
        return new Control(
                "blockLights." + rulePath,
                ruleId,
                labelKey);
    }

    private static Map<Identifier, Control> indexByRuleId() {
        Map<Identifier, Control> controls = new LinkedHashMap<>();
        for (Control control : CONTROLS) {
            if (controls.put(control.ruleId(), control) != null) {
                throw new IllegalStateException("Duplicate block-light rule " + control.ruleId());
            }
        }
        return Map.copyOf(controls);
    }

    public static final class Control {
        private final String settingId;
        private final Identifier ruleId;
        private final String labelKey;
        private volatile double multiplier = DEFAULT_MULTIPLIER;

        private Control(String settingId, Identifier ruleId, String labelKey) {
            this.settingId = settingId;
            this.ruleId = ruleId;
            this.labelKey = labelKey;
        }

        public String settingId() {
            return settingId;
        }

        public Identifier ruleId() {
            return ruleId;
        }

        public String labelKey() {
            return labelKey;
        }

        public double get() {
            return multiplier;
        }

        public void set(double multiplier) {
            if (!Double.isFinite(multiplier)
                    || multiplier < 0.0
                    || multiplier > MAX_MULTIPLIER) {
                throw new IllegalArgumentException("Block-light multiplier out of range");
            }
            this.multiplier = multiplier;
        }
    }
}
