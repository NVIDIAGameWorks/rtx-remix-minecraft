/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

/**
 * Reason an asset's native resource was destroyed. Every deletion must state
 * its reason so crash messages and logs can attribute the source.
 */
public enum DeletionReason {
    NOT_DELETED,
    DIRECT_REQUEST
}
