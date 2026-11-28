/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import net.minecraft.nbt.CompoundTag;

/** Persistent, world-time-driven animation state for one Copper Beacon axis. */
public record CopperBeaconAxisState(
        float position,
        CopperBeaconAnimationMode mode,
        float rotationSpeed,
        float minimumOffset,
        float maximumOffset,
        float cycleSeconds,
        float patrolSeconds,
        long anchorTick) {

    public static final float MIN_ANGLE = -360.0f;
    public static final float MAX_ANGLE = 360.0f;
    public static final float MAX_ROTATION_SPEED = 1080.0f;
    public static final float MIN_DURATION_SECONDS = 0.25f;
    public static final float MAX_DURATION_SECONDS = 120.0f;
    public static final float DEFAULT_MINIMUM_OFFSET = -45.0f;
    public static final float DEFAULT_MAXIMUM_OFFSET = 45.0f;
    public static final float DEFAULT_CYCLE_SECONDS = 10.0f;
    public static final float DEFAULT_PATROL_SECONDS = 5.0f;

    private static final String POSITION_KEY = "position";
    private static final String MODE_KEY = "mode";
    private static final String ROTATION_SPEED_KEY = "rotation_speed";
    private static final String MINIMUM_OFFSET_KEY = "minimum_offset";
    private static final String MAXIMUM_OFFSET_KEY = "maximum_offset";
    private static final String CYCLE_SECONDS_KEY = "cycle_seconds";
    private static final String PATROL_SECONDS_KEY = "patrol_seconds";
    private static final String ANCHOR_TICK_KEY = "anchor_tick";

    public static CopperBeaconAxisState defaults() {
        return defaults(0L);
    }

    public static CopperBeaconAxisState defaults(long anchorTick) {
        return new CopperBeaconAxisState(
                0.0f,
                CopperBeaconAnimationMode.STATIC,
                0.0f,
                DEFAULT_MINIMUM_OFFSET,
                DEFAULT_MAXIMUM_OFFSET,
                DEFAULT_CYCLE_SECONDS,
                DEFAULT_PATROL_SECONDS,
                anchorTick);
    }

    public CopperBeaconAxisState {
        position = finiteClamped(position, MIN_ANGLE, MAX_ANGLE, 0.0f);
        mode = mode == null ? CopperBeaconAnimationMode.STATIC : mode;
        rotationSpeed = finiteClamped(
                rotationSpeed, -MAX_ROTATION_SPEED, MAX_ROTATION_SPEED, 0.0f);
        minimumOffset = finiteClamped(
                minimumOffset, MIN_ANGLE, MAX_ANGLE, DEFAULT_MINIMUM_OFFSET);
        maximumOffset = finiteClamped(
                maximumOffset, MIN_ANGLE, MAX_ANGLE, DEFAULT_MAXIMUM_OFFSET);
        if (minimumOffset > maximumOffset) {
            float swap = minimumOffset;
            minimumOffset = maximumOffset;
            maximumOffset = swap;
        }
        cycleSeconds = finiteClamped(
                cycleSeconds,
                MIN_DURATION_SECONDS,
                MAX_DURATION_SECONDS,
                DEFAULT_CYCLE_SECONDS);
        patrolSeconds = finiteClamped(
                patrolSeconds,
                MIN_DURATION_SECONDS,
                MAX_DURATION_SECONDS,
                DEFAULT_PATROL_SECONDS);
    }

    public float angle(double worldTick, long seed) {
        double seconds = (worldTick - anchorTick) / 20.0;
        return switch (mode) {
            case STATIC -> position;
            case ROTATE -> position + (float) Math.IEEEremainder(
                    rotationSpeed * seconds, 360.0);
            case OSCILLATE -> position + oscillatingOffset(seconds);
            case PATROL -> position + patrolOffset(seconds, seed);
        };
    }

    public CopperBeaconAxisState reanchored(long tick) {
        return new CopperBeaconAxisState(
                position,
                mode,
                rotationSpeed,
                minimumOffset,
                maximumOffset,
                cycleSeconds,
                patrolSeconds,
                tick);
    }

    public void writeNbt(CompoundTag nbt) {
        nbt.putFloat(POSITION_KEY, position);
        nbt.putInt(MODE_KEY, mode.ordinal());
        nbt.putFloat(ROTATION_SPEED_KEY, rotationSpeed);
        nbt.putFloat(MINIMUM_OFFSET_KEY, minimumOffset);
        nbt.putFloat(MAXIMUM_OFFSET_KEY, maximumOffset);
        nbt.putFloat(CYCLE_SECONDS_KEY, cycleSeconds);
        nbt.putFloat(PATROL_SECONDS_KEY, patrolSeconds);
        nbt.putLong(ANCHOR_TICK_KEY, anchorTick);
    }

    public static CopperBeaconAxisState readNbt(CompoundTag nbt) {
        CopperBeaconAxisState defaults = defaults(nbt.getLong(ANCHOR_TICK_KEY));
        return new CopperBeaconAxisState(
                nbt.contains(POSITION_KEY) ? nbt.getFloat(POSITION_KEY) : defaults.position,
                CopperBeaconAnimationMode.byId(nbt.getInt(MODE_KEY)),
                nbt.contains(ROTATION_SPEED_KEY)
                        ? nbt.getFloat(ROTATION_SPEED_KEY)
                        : defaults.rotationSpeed,
                nbt.contains(MINIMUM_OFFSET_KEY)
                        ? nbt.getFloat(MINIMUM_OFFSET_KEY)
                        : defaults.minimumOffset,
                nbt.contains(MAXIMUM_OFFSET_KEY)
                        ? nbt.getFloat(MAXIMUM_OFFSET_KEY)
                        : defaults.maximumOffset,
                nbt.contains(CYCLE_SECONDS_KEY)
                        ? nbt.getFloat(CYCLE_SECONDS_KEY)
                        : defaults.cycleSeconds,
                nbt.contains(PATROL_SECONDS_KEY)
                        ? nbt.getFloat(PATROL_SECONDS_KEY)
                        : defaults.patrolSeconds,
                defaults.anchorTick);
    }

    private float oscillatingOffset(double seconds) {
        double phase = seconds / cycleSeconds * Math.PI * 2.0;
        float amount = (float) (0.5 - 0.5 * Math.cos(phase));
        return minimumOffset + (maximumOffset - minimumOffset) * amount;
    }

    private float patrolOffset(double seconds, long seed) {
        double coordinate = seconds / patrolSeconds;
        long lower = (long) Math.floor(coordinate);
        float fraction = (float) (coordinate - lower);
        float smooth = fraction * fraction * fraction * (fraction * (fraction * 6.0f - 15.0f) + 10.0f);
        float a = randomUnit(seed, lower);
        float b = randomUnit(seed, lower + 1L);
        float amount = a + (b - a) * smooth;
        return minimumOffset + (maximumOffset - minimumOffset) * amount;
    }

    private static float randomUnit(long seed, long index) {
        long value = seed ^ (index * 0x9E3779B97F4A7C15L);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 40) * 0x1.0p-24f;
    }

    private static float finiteClamped(float value, float minimum, float maximum, float fallback) {
        return Float.isFinite(value) ? Math.clamp(value, minimum, maximum) : fallback;
    }
}
