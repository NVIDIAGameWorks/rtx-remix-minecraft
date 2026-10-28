/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the lights along a vanilla beacon's beam go, and their tuning.
 *
 * <p>Vanilla draws the beam as a stack of segments from the beacon block up,
 * one per run of blocks with the same tint, the last stretched to
 * {@code BeaconBlockEntityRenderer.MAX_BEAM_HEIGHT}. Each segment becomes one
 * {@code cylinder} light in its colour. The lights start at the beacon's top
 * face because the built-in {@code beacon} rule already lights the block
 * itself, and stop at {@link #MAX_LIGHT_TOP}: the light far up the beam only
 * reaches sky, yet a long emitter's power still draws light samples from
 * everything nearer the player.
 */
final class VanillaBeaconBeam {

    /** Radius of vanilla's opaque inner beam. */
    static final float RADIUS = 0.2f;

    /** Height above the beacon's block position where beam light starts: its top face. */
    static final int LIGHT_BOTTOM = 1;

    /** Height above the beacon's block position where beam light stops. */
    static final int MAX_LIGHT_TOP = 64;

    /** Beam radiance before the beacon's user multiplier and the segment colour. */
    static final float RADIANCE = 15.0f * BlockLightRuleManager.RADIANCE_PER_LIGHT_LEVEL * 2.0f;

    /** God-ray strength, so the beam reads as a shaft of light in the volumetrics. */
    static final float VOLUMETRIC_SCALE = 0.5f;

    private VanillaBeaconBeam() {}

    /**
     * One lit stretch of beam, in blocks above the beacon's block position.
     *
     * @param color the segment's ARGB colour
     */
    record Span(int bottom, int top, int color) {}

    /**
     * Splits a beam into the stretches that carry light.
     *
     * @param heights each segment's height in blocks, bottom first; the last
     *                one is treated as reaching the top of the beam
     * @param colors  each segment's ARGB colour
     * @return the lit stretches, bottom first, clipped to
     *         [{@link #LIGHT_BOTTOM}, {@link #MAX_LIGHT_TOP}]
     */
    static List<Span> spans(int[] heights, int[] colors) {
        List<Span> spans = new ArrayList<>(heights.length);
        int offset = 0;
        for (int i = 0; i < heights.length; i++) {
            boolean last = i == heights.length - 1;
            int bottom = Math.max(offset, LIGHT_BOTTOM);
            int top = last ? MAX_LIGHT_TOP : Math.min(offset + heights[i], MAX_LIGHT_TOP);
            if (top > bottom) {
                spans.add(new Span(bottom, top, colors[i]));
            }
            offset += heights[i];
        }
        return spans;
    }
}
