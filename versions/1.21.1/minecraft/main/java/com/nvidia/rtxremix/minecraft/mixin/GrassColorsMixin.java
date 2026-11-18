/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.BiomeColorGeneration;
import net.minecraft.world.level.GrassColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The grass colormap reloader replaces the static map through this setter. Every biome
 * colour {@link BiomeMixin} cached was derived from the old map, so the generation moves on
 * and the next lookup recomputes.
 */
@Mixin(GrassColor.class)
public abstract class GrassColorsMixin {
    @Inject(method = "init", at = @At("RETURN"))
    private static void rtxremix$onColormapReplaced(int[] map, CallbackInfo ci) {
        BiomeColorGeneration.invalidate();
    }
}
