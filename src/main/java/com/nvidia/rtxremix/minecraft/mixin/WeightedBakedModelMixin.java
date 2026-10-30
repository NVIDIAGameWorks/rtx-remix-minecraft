/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.List;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Picks the weighted variant without the iterator and the two {@code Optional}s vanilla
 * allocates per block face. Section builds call this for every face of every block with a
 * random-variant model (stone, dirt, grass, leaves), so on the worker threads it is one of
 * the largest sources of garbage. The random draw and the cumulative-weight scan are
 * vanilla's, so the same variant is chosen.
 *
 * <p>A cancellable inject still allocates its {@code CallbackInfoReturnable} per call unless
 * escape analysis removes it, so this trades three short-lived objects for one, not for
 * none; a JFR allocation profile shows no remaining allocation at this site once the
 * builders are compiled.
 */
@Mixin(WeightedBakedModel.class)
public abstract class WeightedBakedModelMixin {

    @Shadow
    @Final
    private int totalWeight;

    @Shadow
    @Final
    private List<WeightedEntry.Wrapper<BakedModel>> list;

    @Inject(method = "getQuads", at = @At("HEAD"), cancellable = true)
    private void rtxremix$getQuads(
            BlockState state,
            Direction face,
            RandomSource random,
            CallbackInfoReturnable<List<BakedQuad>> cir) {
        int remaining = Math.abs((int) random.nextLong()) % totalWeight;
        List<WeightedEntry.Wrapper<BakedModel>> variants = list;
        for (int i = 0, n = variants.size(); i < n; i++) {
            WeightedEntry.Wrapper<BakedModel> variant = variants.get(i);
            remaining -= variant.getWeight().asInt();
            if (remaining < 0) {
                cir.setReturnValue(variant.data().getQuads(state, face, random));
                return;
            }
        }
        cir.setReturnValue(Collections.emptyList());
    }
}
