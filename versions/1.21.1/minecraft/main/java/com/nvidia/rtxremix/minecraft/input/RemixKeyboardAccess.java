/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.input;

/** Clears Minecraft's F3 key-combination state when Remix takes input ownership. */
public interface RemixKeyboardAccess {
    void rtxremix$resetDebugKeyState();
}
