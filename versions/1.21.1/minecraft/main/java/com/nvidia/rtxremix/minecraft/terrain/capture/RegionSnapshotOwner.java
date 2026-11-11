/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

/**
 * A render region that takes one idempotent ownership reference on every
 * chunk snapshot it contains.
 */
public interface RegionSnapshotOwner {
    void rtxremix$retainSnapshots();
}
