/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One curated, tweakable value. The getter reads live state and the applier
 * pushes a value to the live system. min/max are the slider/validation bounds
 * (0..1 unused for BOOL).
 */
public final class RtxSetting<T> {

    public enum Kind { BOOL, INT, FLOAT }

    private final String id;
    private final SettingGroup group;
    private final String labelKey;
    private final String tooltipKey;
    private final Kind kind;
    private final double min;
    private final double max;
    private final T defaultValue;
    private final Supplier<T> getter;
    private final Consumer<T> applier;
    private boolean applySliderValueImmediately = true;
    private int step = 1;

    private RtxSetting(String id, SettingGroup group, String labelKey, String tooltipKey,
                       Kind kind, double min, double max, T defaultValue,
                       Supplier<T> getter, Consumer<T> applier) {
        this.id = id;
        this.group = group;
        this.labelKey = labelKey;
        this.tooltipKey = tooltipKey;
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.defaultValue = defaultValue;
        this.getter = getter;
        this.applier = applier;
    }

    public static RtxSetting<Boolean> ofBool(String id, SettingGroup group, String labelKey,
            String tooltipKey, boolean def, Supplier<Boolean> getter, Consumer<Boolean> applier) {
        return new RtxSetting<>(id, group, labelKey, tooltipKey, Kind.BOOL, 0, 1, def, getter, applier);
    }

    public static RtxSetting<Integer> ofInt(String id, SettingGroup group, String labelKey,
            String tooltipKey, int min, int max, int def, Supplier<Integer> getter, Consumer<Integer> applier) {
        return new RtxSetting<>(id, group, labelKey, tooltipKey, Kind.INT, min, max, def, getter, applier);
    }

    public static RtxSetting<Double> ofFloat(String id, SettingGroup group, String labelKey,
            String tooltipKey, double min, double max, double def, Supplier<Double> getter, Consumer<Double> applier) {
        return new RtxSetting<>(id, group, labelKey, tooltipKey, Kind.FLOAT, min, max, def, getter, applier);
    }

    public String id() { return id; }
    public SettingGroup group() { return group; }
    public String labelKey() { return labelKey; }
    public String tooltipKey() { return tooltipKey; }
    public Kind kind() { return kind; }
    public double min() { return min; }
    public double max() { return max; }
    public T defaultValue() { return defaultValue; }
    public boolean applySliderValueImmediately() { return applySliderValueImmediately; }

    /** The spacing between the values an INT setting takes; 1 unless {@link #withStep}. */
    public int step() { return step; }

    /**
     * Defers an expensive slider applier until Minecraft's slider settles or
     * the options screen commits its pending values.
     */
    public RtxSetting<T> deferSliderApply() {
        applySliderValueImmediately = false;
        return this;
    }

    /**
     * Restricts an INT setting to multiples of {@code step}, as vanilla's
     * stepped sliders are. Like vanilla, a value between steps, such as one
     * from the settings file, rounds down when applied, so the live value
     * is always one the slider can show.
     */
    public RtxSetting<T> withStep(int step) {
        if (kind != Kind.INT || step < 1 || min % step != 0 || max % step != 0
                || (Integer) defaultValue % step != 0) {
            throw new IllegalArgumentException(
                    "Step " + step + " does not divide the range and default of " + id);
        }
        this.step = step;
        return this;
    }

    public T get() { return getter.get(); }

    public void apply(T value) {
        applier.accept(onStep(value));
    }

    @SuppressWarnings("unchecked")
    private T onStep(T value) {
        if (step == 1) {
            return value;
        }
        return (T) Integer.valueOf(Math.floorDiv((Integer) value, step) * step);
    }

    public void reset() {
        if (!isDefault()) {
            applier.accept(defaultValue);
        }
    }

    public boolean isDefault() {
        return isDefaultValue(get());
    }

    public boolean isDefaultValue(T value) {
        if (kind == Kind.FLOAT) {
            // FLOAT settings ultimately feed float-backed renderer values.
            // Normalize both sides to that precision so a JSON value such as
            // 0.45 matches a default captured by widening 0.45f to double.
            return Float.compare(
                    ((Number) value).floatValue(),
                    ((Number) defaultValue).floatValue()) == 0;
        }
        return Objects.equals(value, defaultValue);
    }

    public boolean inRange(double numeric) {
        return numeric >= min && numeric <= max;
    }
}
