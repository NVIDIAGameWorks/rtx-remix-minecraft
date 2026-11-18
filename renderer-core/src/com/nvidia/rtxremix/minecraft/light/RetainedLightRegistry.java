/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.minecraft.asset.RemixLight;
import com.nvidia.rtxremix.minecraft.asset.RemixLightStore;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Canonical create/update/draw/retire lifecycle for frame-driven analytic lights.
 *
 * <p>Capture adapters only provide stable keys and {@link LightInfo}; native
 * ownership never leaks into a loader integration.
 */
public final class RetainedLightRegistry<K> {
    private final Map<K, RemixLight> lights;
    private final Set<K> seen;

    public static <K> RetainedLightRegistry<K> equalityKeys() {
        return new RetainedLightRegistry<K>(new HashMap<K, RemixLight>(), new HashSet<K>());
    }

    public static <K> RetainedLightRegistry<K> identityKeys() {
        return new RetainedLightRegistry<K>(
                new IdentityHashMap<K, RemixLight>(),
                Collections.newSetFromMap(new IdentityHashMap<K, Boolean>()));
    }

    private RetainedLightRegistry(Map<K, RemixLight> lights, Set<K> seen) {
        this.lights = lights;
        this.seen = seen;
    }

    public void beginFrame() {
        seen.clear();
    }

    public void touch(K key, LightInfo info) throws RemixException {
        seen.add(key);
        RemixLight existing = lights.get(key);
        if (existing != null && existing.isAlive()) {
            RemixLightStore.get().update(existing, info);
            return;
        }
        if (existing != null && !existing.isDeleted()) {
            existing.destroy();
        }
        RemixLight light = new RemixLight(info, null);
        RemixLightStore.get().create(light);
        lights.put(key, light);
    }

    public void endFrame() {
        Iterator<Map.Entry<K, RemixLight>> iterator = lights.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, RemixLight> entry = iterator.next();
            if (seen.contains(entry.getKey())) {
                continue;
            }
            destroy(entry.getValue());
            iterator.remove();
        }
    }

    public void draw(RemixAPI api) throws RemixException {
        for (RemixLight light : lights.values()) {
            light.draw(api);
        }
    }

    public int size() {
        return lights.size();
    }

    public void clear() {
        for (RemixLight light : lights.values()) {
            destroy(light);
        }
        lights.clear();
        seen.clear();
    }

    private static void destroy(RemixLight light) {
        if (light != null && !light.isDeleted()) {
            light.destroy();
        }
    }
}
