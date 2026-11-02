/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.WeightedBakedModel;
import net.minecraft.util.collection.Weighted;
import net.minecraft.util.collection.DataPool;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;

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

    @Unique
    private int rtxremix$totalWeight;

    @Shadow
    @Final
    private DataPool<BakedModel> models;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$cacheTotalWeight(DataPool<BakedModel> models, CallbackInfo ci) {
        for (Weighted.Present<BakedModel> variant : models.getEntries()) {
            rtxremix$totalWeight += variant.getWeight().getValue();
        }
    }

    @Inject(method = "getQuads", at = @At("HEAD"), cancellable = true)
    private void rtxremix$getQuads(
            BlockState state,
            Direction face,
            Random random,
            CallbackInfoReturnable<List<BakedQuad>> cir) {
        if (rtxremix$totalWeight == 0) {
            cir.setReturnValue(Collections.emptyList());
            return;
        }
        int remaining = random.nextInt(rtxremix$totalWeight);
        List<Weighted.Present<BakedModel>> variants = models.getEntries();
        for (int i = 0, n = variants.size(); i < n; i++) {
            Weighted.Present<BakedModel> variant = variants.get(i);
            remaining -= variant.getWeight().getValue();
            if (remaining < 0) {
                cir.setReturnValue(variant.data().getQuads(state, face, random));
                return;
            }
        }
        cir.setReturnValue(Collections.emptyList());
    }
}
