/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.LabPBRSidecarFilter;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

@Mixin(SpriteSourceList.class)
public abstract class SpriteSourceListMixin {

    /** Sources run in order, so the filter goes after every source the packs define for the atlas. */
    @ModifyArg(method = "load", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/atlas/SpriteSourceList;<init>(Ljava/util/List;)V"))
    private static List<SpriteSource> rtxremix$keepSidecarsOutOfAtlas(List<SpriteSource> sources) {
        sources.add(LabPBRSidecarFilter.INSTANCE);
        return sources;
    }
}
