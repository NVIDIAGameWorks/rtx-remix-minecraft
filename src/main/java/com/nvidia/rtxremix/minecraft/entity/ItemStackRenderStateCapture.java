/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The stack and holder an {@code ItemStackRenderState} was resolved from. Since
 * 26.x an item render state carries neither, but capture classifies a held block
 * by its block and decides whether a held flame burns by its holder.
 */
public interface ItemStackRenderStateCapture {
    ItemStack rtxremix$stack();

    @Nullable ItemOwner rtxremix$owner();

    void rtxremix$setSource(ItemStack stack, @Nullable ItemOwner owner);
}
