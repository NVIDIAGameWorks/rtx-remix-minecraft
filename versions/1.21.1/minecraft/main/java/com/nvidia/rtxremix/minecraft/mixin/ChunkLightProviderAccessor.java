/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Vanilla hands out each layer's ChunkLightProvider through {@code LightingProvider.get}
 * but keeps its LightStorage to itself; the RTX toggle needs the storage to republish a
 * real light snapshot before capture switches off.
 */
@Mixin(LightEngine.class)
public interface ChunkLightProviderAccessor {
    @Accessor("storage")
    LayerLightSectionStorage<?> rtxremix$getLightStorage();
}
