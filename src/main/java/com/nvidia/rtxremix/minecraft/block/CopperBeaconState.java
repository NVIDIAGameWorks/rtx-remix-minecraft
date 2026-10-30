/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

/** Validation and serialization rules shared by Copper Beacon placement and persistence. */
final class CopperBeaconState {

    private static final String AZIMUTH_KEY = "azimuth";
    private static final String ZENITH_KEY = "zenith";

    private CopperBeaconState() {}

    static float normalizeAzimuth(float degrees) {
        if (!Float.isFinite(degrees)) {
            throw new IllegalArgumentException("Azimuth must be finite");
        }
        float normalized = degrees % 360.0f;
        if (normalized >= 180.0f) {
            normalized -= 360.0f;
        } else if (normalized < -180.0f) {
            normalized += 360.0f;
        }
        return normalized == -0.0f ? 0.0f : normalized;
    }

    static float clampZenith(float degrees) {
        if (!Float.isFinite(degrees)) {
            throw new IllegalArgumentException("Zenith must be finite");
        }
        return Math.max(-90.0f, Math.min(90.0f, degrees));
    }

    static void writeAngles(CompoundTag nbt, float azimuth, float zenith) {
        nbt.putFloat(AZIMUTH_KEY, azimuth);
        nbt.putFloat(ZENITH_KEY, zenith);
    }

    static Angles readAngles(CompoundTag nbt) {
        float azimuth = nbt.getFloat(AZIMUTH_KEY);
        float zenith = nbt.getFloat(ZENITH_KEY);
        return new Angles(
                Float.isFinite(azimuth) ? normalizeAzimuth(azimuth) : 0.0f,
                Float.isFinite(zenith) ? clampZenith(zenith) : 0.0f);
    }

    static Direction orthogonalForward(
            Direction preferred, Direction horizontalFallback, Direction mount) {
        if (preferred.getAxis() != mount.getAxis()) {
            return preferred;
        }
        if (horizontalFallback.getAxis() != mount.getAxis()) {
            return horizontalFallback;
        }
        return mount.getAxis() == Direction.Axis.X ? Direction.NORTH : Direction.EAST;
    }

    static boolean isActive(boolean redstoneControlled, boolean powered) {
        return !redstoneControlled || powered;
    }

    static int applySaturation(int argb, float saturation) {
        float amount = Math.clamp(saturation, 0.0f, 2.0f);
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        int gray = Math.round(red * 0.2126f + green * 0.7152f + blue * 0.0722f);
        int saturatedRed = Math.clamp(Math.round(gray + (red - gray) * amount), 0, 255);
        int saturatedGreen = Math.clamp(Math.round(gray + (green - gray) * amount), 0, 255);
        int saturatedBlue = Math.clamp(Math.round(gray + (blue - gray) * amount), 0, 255);
        return (argb & 0xFF000000)
                | (saturatedRed << 16)
                | (saturatedGreen << 8)
                | saturatedBlue;
    }

    static double animationTick(
            boolean redstoneControlled,
            boolean powered,
            long currentTick,
            float tickDelta,
            long frozenTick) {
        return redstoneControlled && !powered
                ? frozenTick
                : currentTick + tickDelta;
    }

    record Angles(float azimuth, float zenith) {}
}
