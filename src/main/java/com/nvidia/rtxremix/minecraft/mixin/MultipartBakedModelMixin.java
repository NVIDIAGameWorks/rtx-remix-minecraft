/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.MultipartBakedModel;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Selects a multipart model's parts without the per-call garbage vanilla makes. Fences,
 * walls, panes, redstone and every other multipart blockstate reach this for each face of
 * each block during a section build, and vanilla creates a fresh {@code Random} (a
 * {@code CheckedRandom} with its {@code AtomicLong} seed and Gaussian state) for every
 * matching part plus a result list per call, whether one part matched or five.
 *
 * <p>The part selection and the seed handed to each part are vanilla's: the same
 * {@code random.nextLong()} draw seeds a reused {@code CheckedRandom} per part, which yields
 * the sequence {@code Random.create(seed)} would. A single matching part returns that part's
 * own quad list, as {@link net.minecraft.client.render.model.WeightedBakedModel} already does
 * for its chosen variant; callers treat model quad lists as read-only. Several matching parts
 * still concatenate into a new list.
 */
@Mixin(MultipartBakedModel.class)
public abstract class MultipartBakedModelMixin {

    @Shadow
    @Final
    private List<MultipartBakedModel.Selector> selectors;

    @Shadow
    @Final
    private Map<BlockState, BitSet> stateCache;

    private static final ThreadLocal<CheckedRandom> rtxremix$partRandom =
            ThreadLocal.withInitial(() -> new CheckedRandom(0L));

    @Inject(method = "getQuads", at = @At("HEAD"), cancellable = true)
    private void rtxremix$getQuads(
            BlockState state,
            Direction face,
            Random random,
            CallbackInfoReturnable<List<BakedQuad>> cir) {
        if (state == null) {
            cir.setReturnValue(Collections.emptyList());
            return;
        }
        BitSet selected = stateCache.get(state);
        if (selected == null) {
            selected = new BitSet();
            for (int i = 0, n = selectors.size(); i < n; i++) {
                if (selectors.get(i).condition().test(state)) {
                    selected.set(i);
                }
            }
            stateCache.put(state, selected);
        }
        long seed = random.nextLong();
        CheckedRandom partRandom = rtxremix$partRandom.get();
        List<BakedQuad> result = null;
        boolean owned = false;
        for (int i = selected.nextSetBit(0); i >= 0; i = selected.nextSetBit(i + 1)) {
            partRandom.setSeed(seed);
            List<BakedQuad> quads = selectors.get(i).model().getQuads(state, face, partRandom);
            if (result == null) {
                result = quads;
            } else {
                // Element-wise copies: ArrayList(Collection) and addAll both go through
                // toArray and allocate an intermediate array per part.
                if (!owned) {
                    List<BakedQuad> first = result;
                    result = new ArrayList<>(first.size() + quads.size());
                    for (int j = 0, n = first.size(); j < n; j++) {
                        result.add(first.get(j));
                    }
                    owned = true;
                }
                for (int j = 0, n = quads.size(); j < n; j++) {
                    result.add(quads.get(j));
                }
            }
        }
        cir.setReturnValue(result == null ? Collections.emptyList() : result);
    }
}
