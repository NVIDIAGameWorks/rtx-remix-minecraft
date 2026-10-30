/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.EntityRenderStateOwner;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements EntityRenderStateOwner {

    private @Nullable Entity rtxremix$entity;

    @Override
    public @Nullable Entity rtxremix$entity() {
        return rtxremix$entity;
    }

    @Override
    public void rtxremix$setEntity(@Nullable Entity entity) {
        rtxremix$entity = entity;
    }
}
