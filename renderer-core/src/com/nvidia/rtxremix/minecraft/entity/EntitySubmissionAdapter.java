/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

/**
 * Loader-facing operations needed by retained entity submission.
 *
 * <p>Queries return primitives and existing identity objects so the per-part
 * capture path remains allocation-free.
 */
public interface EntitySubmissionAdapter {
    int BEACON_LENS_NONE = 0;
    int BEACON_LENS_UNLIT = 1;
    int BEACON_LENS_EMISSIVE = 2;

    CapturedEntityGeometry createGeometryScratch();

    int beaconLensKind(Object owner, Object platformSprite);

    float beaconIntensityMultiplier(Object owner);
}
