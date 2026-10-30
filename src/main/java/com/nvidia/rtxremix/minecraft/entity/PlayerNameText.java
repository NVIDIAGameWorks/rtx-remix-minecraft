/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.network.chat.Component;

/**
 * A player's name as a text, cached on the player. {@code PlayerEntity.getName()} builds a
 * new {@code MutableText} from the profile name on every call and the renderer calls it for
 * every player draw; the two renderer checks that only read the string take this instead.
 * The cached instance must never be handed to code that could mutate it, which is why
 * {@code getName} itself cannot cache.
 */
public interface PlayerNameText {
    Component rtxremix$nameText();
}
