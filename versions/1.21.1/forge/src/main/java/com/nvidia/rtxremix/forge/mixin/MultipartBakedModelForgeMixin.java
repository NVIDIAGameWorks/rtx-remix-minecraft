/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import com.nvidia.rtxremix.forge.ForgeModelComposition;
import com.nvidia.rtxremix.forge.ForgeTerrainHooks;
import com.nvidia.rtxremix.minecraft.terrain.capture.MultipartModelSelector;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.MultiPartBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraftforge.client.model.data.ModelData;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Forge signature adapter for canonical multipart selection. */
@Mixin(MultiPartBakedModel.class)
abstract class MultipartBakedModelForgeMixin implements ForgeModelComposition {
    @Shadow @Final private List<Pair<Predicate<BlockState>, BakedModel>> selectors;
    @Shadow @Final private Map<BlockState, BitSet> selectorCache;

    @Override
    public boolean rtxremix$hasCustomChildren() {
        for (Pair<Predicate<BlockState>, BakedModel> selector : selectors) {
            if (ForgeTerrainHooks.requiresExtendedModel(selector.getRight())) {
                return true;
            }
        }
        return false;
    }

    @Inject(method = "getQuads", at = @At("HEAD"), cancellable = true, remap = false)
    private void rtxremix$getQuads(
            BlockState state,
            Direction face,
            RandomSource random,
            ModelData modelData,
            RenderType renderType,
            CallbackInfoReturnable<List<BakedQuad>> cir) {
        if (state == null) {
            cir.setReturnValue(List.of());
            return;
        }
        BitSet selected =
                MultipartModelSelector.selected(selectors, selectorCache, state);
        long seed = random.nextLong();
        List<BakedQuad> result = null;
        for (int i = selected.nextSetBit(0); i >= 0; i = selected.nextSetBit(i + 1)) {
            LegacyRandomSource partRandom = MultipartModelSelector.partRandom(seed);
            List<BakedQuad> quads = selectors.get(i).getRight()
                    .getQuads(state, face, partRandom, modelData, renderType);
            result = MultipartModelSelector.append(result, quads);
        }
        cir.setReturnValue(MultipartModelSelector.finish(result));
    }
}
