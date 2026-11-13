/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.logic;

/**
 * Maps Minecraft's render distance onto a conf-layer strength: the shipped
 * 2-chunk endpoint profile blends over the 32-chunk endpoints in
 * {@code rtx.conf}. Manual mode ({@code /rtx viewdistance auto off}) holds the
 * strength at zero, which leaves the 32-chunk baseline showing.
 */
public final class ViewDistanceFade {

    static final int MIN_RENDER_DISTANCE_CHUNKS = 2;
    static final int MAX_RENDER_DISTANCE_CHUNKS = 32;

    // Preserve the original 70%-to-80% fade-band calibration at every
    // supported Minecraft render distance.
    private static final float FADE_MIN_RATIO = 0.7f;
    private static final float FADE_MAX_RATIO = 0.8f;

    // Keep these endpoints aligned with packaging/rtx.conf and
    // confs/view_distance.conf respectively.
    static final float RESTORE_FADE_MIN = computeFadeMin(MAX_RENDER_DISTANCE_CHUNKS);
    static final float RESTORE_FADE_MAX = computeFadeMax(MAX_RENDER_DISTANCE_CHUNKS);
    static final float LAYER_FADE_MIN = computeFadeMin(MIN_RENDER_DISTANCE_CHUNKS);
    static final float LAYER_FADE_MAX = computeFadeMax(MIN_RENDER_DISTANCE_CHUNKS);

    private boolean auto = true;
    private float displayedFadeMin = RESTORE_FADE_MIN;
    private float displayedFadeMax = RESTORE_FADE_MAX;

    static float computeFadeMin(int renderDistanceChunks) {
        return renderDistanceChunks * 16 * FADE_MIN_RATIO;
    }

    static float computeFadeMax(int renderDistanceChunks) {
        return renderDistanceChunks * 16 * FADE_MAX_RATIO;
    }

    /**
     * Strength of the 2-chunk endpoint over the 32-chunk baseline. Both
     * endpoint values are linear in chunk distance, so this one blend
     * reconstructs the exact 70%-to-80% band for every vanilla setting.
     */
    public float layerStrength(int renderDistanceChunks) {
        if (!auto) {
            displayedFadeMin = RESTORE_FADE_MIN;
            displayedFadeMax = RESTORE_FADE_MAX;
            return 0.0f;
        }
        int clamped = Math.max(
                MIN_RENDER_DISTANCE_CHUNKS,
                Math.min(MAX_RENDER_DISTANCE_CHUNKS, renderDistanceChunks));
        displayedFadeMin = computeFadeMin(clamped);
        displayedFadeMax = computeFadeMax(clamped);
        return (float) (MAX_RENDER_DISTANCE_CHUNKS - clamped)
                / (MAX_RENDER_DISTANCE_CHUNKS - MIN_RENDER_DISTANCE_CHUNKS);
    }

    public boolean isAuto() {
        return auto;
    }

    public void setAuto(boolean auto) {
        this.auto = auto;
    }

    public String describe() {
        return (auto ? "auto (from render distance)" : "manual (32-chunk defaults)")
            + ", fade " + Math.round(displayedFadeMin) + " to " + Math.round(displayedFadeMax) + " blocks";
    }
}
