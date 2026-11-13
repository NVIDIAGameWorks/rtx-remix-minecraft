/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

/**
 * Snapshot control of one client light storage, implemented by the LightStorage mixin.
 */
public interface LightStorageSnapshotAccess {

    /**
     * If readers currently see the engine's live map, publishes a real copy in its place.
     * Render thread only.
     */
    void rtxremix$publishRealSnapshot();
}
