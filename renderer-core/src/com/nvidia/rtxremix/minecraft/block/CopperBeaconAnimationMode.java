/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

/** Animation applied independently to a Copper Beacon's yaw and pitch axes. */
public enum CopperBeaconAnimationMode {
    STATIC,
    ROTATE,
    OSCILLATE,
    PATROL;

    static CopperBeaconAnimationMode byId(int id) {
        CopperBeaconAnimationMode[] values = values();
        return id >= 0 && id < values.length ? values[id] : STATIC;
    }
}
