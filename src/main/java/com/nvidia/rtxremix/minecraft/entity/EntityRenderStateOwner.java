/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * The entity an {@code EntityRenderState} was extracted from. Since 26.x a render
 * state carries no reference to its entity, but capture keys parts, lights and
 * retained instances by the entity, so the extraction records it here.
 */
public interface EntityRenderStateOwner {
    @Nullable Entity rtxremix$entity();

    void rtxremix$setEntity(@Nullable Entity entity);
}
