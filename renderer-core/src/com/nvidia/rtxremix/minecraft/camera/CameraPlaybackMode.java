/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

/** Clock policy for canonical camera-path playback. */
public enum CameraPlaybackMode {
    TIME("time"),
    FIXED_60("fixed-60");

    private final String commandName;

    CameraPlaybackMode(String commandName) {
        this.commandName = commandName;
    }

    public String commandName() {
        return commandName;
    }
}
