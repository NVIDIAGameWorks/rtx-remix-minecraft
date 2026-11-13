/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

/**
 * Implemented by Forge's vanilla model composites so their children, rather
 * than Forge-injected methods on the composite itself, decide whether the
 * extended model path is needed.
 */
public interface ForgeModelComposition {
    boolean rtxremix$hasCustomChildren();
}
