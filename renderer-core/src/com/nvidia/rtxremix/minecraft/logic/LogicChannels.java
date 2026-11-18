/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.logic;

/**
 * Shared name contract for the minecraft-logic conf layers. Zones key their
 * weights by ZONE_PREFIX + name (ZoneDefinition / ZoneRegistry) and
 * ConfLayerDriver maps those names onto the zone confs; the remaining
 * constants are the driver's internal layer/trigger names for water,
 * automatic view distance, volumetric settling, biomes, weather, and fixed
 * time-of-day confs.
 */
public final class LogicChannels {
    public static final String UNDERWATER = "mc.underwater";
    public static final String VIEW_DISTANCE = "mc.view_distance";
    public static final String VOLUMETRICS_SETTLING = "mc.volumetrics.settling";
    public static final String WEATHER_RAIN = "mc.weather.rain";
    public static final String WEATHER_THUNDER = "mc.weather.thunder";
    public static final String ZONE_PREFIX = "mc.zone.";
    public static final String BIOME_PREFIX = "mc.biome.";
    public static final String TIME_PREFIX = "mc.time.";
    public static final String TIME_DAWN = TIME_PREFIX + "dawn";
    public static final String TIME_DAY = TIME_PREFIX + "day";
    public static final String TIME_DUSK = TIME_PREFIX + "dusk";
    public static final String TIME_NIGHT = TIME_PREFIX + "night";

    private LogicChannels() {
    }
}
