/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import com.nvidia.rtxremix.forge.ForgeModelComposition;
import com.nvidia.rtxremix.forge.ForgeTerrainHooks;
import com.nvidia.rtxremix.minecraft.terrain.capture.WeightedModelSelector;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Forge signature adapter for the canonical weighted-model selector. */
@Mixin(WeightedBakedModel.class)
abstract class WeightedBakedModelForgeMixin implements ForgeModelComposition {
    @Shadow @Final private int totalWeight;
    @Shadow @Final private List<WeightedEntry.Wrapper<BakedModel>> list;

    @Override
    public boolean rtxremix$hasCustomChildren() {
        for (WeightedEntry.Wrapper<BakedModel> entry : list) {
            if (ForgeTerrainHooks.requiresExtendedModel(entry.data())) {
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
        BakedModel selected = WeightedModelSelector.select(totalWeight, list, random);
        cir.setReturnValue(selected == null
                ? Collections.emptyList()
                : selected.getQuads(state, face, random, modelData, renderType));
    }
}
