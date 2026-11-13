/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.logic;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe projection of active zones. Written from the integrated-server
 * thread (command-block scanner) and client thread (saved-state hydration and
 * /rtx zone commands); read every frame on the render thread by the
 * conf-layer driver.
 */
public final class ZoneRegistry {
    private final Map<String, ZoneDefinition> zonesByName = new ConcurrentHashMap<>();

    /** Adds or replaces the zone; returns the previous mapping for the name, or null if new. */
    public ZoneDefinition put(ZoneDefinition zone) {
        return zonesByName.put(zone.name(), zone);
    }

    /** Adds or replaces every zone in a snapshot without clearing other producers. */
    public void putAll(Collection<ZoneDefinition> zones) {
        for (ZoneDefinition zone : zones) {
            put(zone);
        }
    }

    public boolean remove(String name) {
        return zonesByName.remove(name) != null;
    }

    public void clear() {
        zonesByName.clear();
    }

    /** Unmodifiable live view of the registered zones. */
    public Collection<ZoneDefinition> zones() {
        return Collections.unmodifiableCollection(zonesByName.values());
    }

    /**
     * Per-channel spatial weight at the camera position; zones sharing a
     * channel combine via max. Channels with no zones are absent; the
     * publisher zeroes channels that disappear.
     */
    public Map<String, Float> channelWeights(double x, double y, double z) {
        Map<String, Float> weights = new HashMap<>();
        for (ZoneDefinition zone : zonesByName.values()) {
            float w = zone.weight(x, y, z);
            weights.merge(zone.channel(), w, Math::max);
        }
        return weights;
    }
}
