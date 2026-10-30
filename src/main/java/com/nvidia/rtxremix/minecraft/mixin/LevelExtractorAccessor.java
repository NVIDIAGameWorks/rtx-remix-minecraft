/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The extractor's section dirty state, null while no level is loaded. */
@Mixin(LevelExtractor.class)
public interface LevelExtractorAccessor {
    @Accessor("sectionUpdateTracker")
    @Nullable SectionUpdateTracker rtxremix$sectionUpdateTracker();
}
