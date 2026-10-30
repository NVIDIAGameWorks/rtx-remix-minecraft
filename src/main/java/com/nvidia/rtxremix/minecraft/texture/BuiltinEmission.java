/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/**
 * Emission the mod adds on top of what the resource pack authors, for the blocks that
 * cast no analytic light so the light list stays small enough for RTXDI to sample. A
 * block without a light reads as lit only through its emissive texels, so those have
 * to carry the look on their own.
 *
 * <p>{@link #gain} scales a sprite's authored LabPBR emission ({@code _s} alpha), applied
 * identically on the atlas build and on every animated frame so a flickering sprite does
 * not change brightness between the two paths. {@link #mask} supplies emission for a sprite
 * the pack authors none for, as a factor over the sprite's own UV space; an authored
 * {@code _s} with emission always wins over it, so a pack can replace these at any time.
 */
public final class BuiltinEmission {
    /**
     * Lanterns: the pack's glow (peak factor 0.39, mean 0.03) is authored to read beside
     * an analytic light. On its own, 2.5x brings the brightest texels to 0.97 without
     * clipping and keeps the body dark.
     */
    private static final float LANTERN_GAIN = 2.5f;

    private static final Map<ResourceLocation, Float> GAIN = Map.of(
            ResourceLocation.withDefaultNamespace("block/lantern"), LANTERN_GAIN,
            ResourceLocation.withDefaultNamespace("block/soul_lantern"), LANTERN_GAIN);

    /** Emission factor at a point of a sprite, {@code u} and {@code v} in [0, 1) across the sprite. */
    @FunctionalInterface
    public interface Mask {
        float factor(float u, float v);
    }

    /**
     * Sea pickle, laid out as vanilla's {@code sea_pickle} model maps its 16x16 texture:
     * the sprout quads read columns 1 to 3 and 13 to 15 of rows 0 to 5, tip at row 0.
     * Only the waterlogged models have sprout quads, so a dry pickle shows no glow at all
     * and keeps its regular texture, as its luminance of zero says it should.
     *
     * <p>The tip burns at full emission and falls off quadratically over the top rows,
     * the way a lit candle's wax glows hottest at the wick and dims within a few
     * millimetres: row factors 0.77, 0.39, 0.14, 0.02, then nothing. The body stays dark.
     * A few bright texels per sprout read as a point of light on the sea floor around
     * coral without turning the pickle into a lamp.
     */
    static final float SEA_PICKLE_TIP_PEAK = 1.0f;
    static final float SEA_PICKLE_FADE_ROWS = 4.0f;

    static final Mask SEA_PICKLE = (u, v) -> {
        float col = u * 16f, row = v * 16f;
        boolean sprout = (col >= 1f && col < 3f) || (col >= 13f && col < 15f);
        if (!sprout || row >= SEA_PICKLE_FADE_ROWS) {
            return 0f;
        }
        float fromTip = 1f - row / SEA_PICKLE_FADE_ROWS;
        return SEA_PICKLE_TIP_PEAK * fromTip * fromTip;
    };

    private static final Map<ResourceLocation, Mask> MASK = Map.of(
            ResourceLocation.withDefaultNamespace("block/sea_pickle"), SEA_PICKLE);

    private BuiltinEmission() {
    }

    /** Multiplier for the sprite's authored emission factor; 1 for every sprite not listed. */
    public static float gain(ResourceLocation spriteId) {
        Float gain = GAIN.get(spriteId);
        return gain != null ? gain : 1.0f;
    }

    /** Emission for a sprite the pack leaves unauthored, or null when the mod adds none. */
    public static Mask mask(ResourceLocation spriteId) {
        return MASK.get(spriteId);
    }
}
