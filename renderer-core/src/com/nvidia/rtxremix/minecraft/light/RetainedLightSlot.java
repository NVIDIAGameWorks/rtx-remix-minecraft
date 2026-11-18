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

/** Canonical native lifetime for one persistent analytic light. */
public final class RetainedLightSlot {
    private RemixLight light;

    public void update(LightInfo info) throws RemixException {
        if (light != null && light.isAlive()) {
            RemixLightStore.get().update(light, info);
            return;
        }
        clear();
        light = new RemixLight(info, null);
        RemixLightStore.get().create(light);
    }

    public void draw(RemixAPI api) throws RemixException {
        if (light != null) {
            light.draw(api);
        }
    }

    public boolean isPresent() {
        return light != null;
    }

    public void clear() {
        if (light != null && !light.isDeleted()) {
            light.destroy();
        }
        light = null;
    }
}
