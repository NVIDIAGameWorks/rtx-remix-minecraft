/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import java.util.List;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedEntry;

/** Allocation-free implementation of vanilla's weighted model selection. */
public final class WeightedModelSelector {
    private WeightedModelSelector() {
    }

    public static BakedModel select(
            int totalWeight,
            List<WeightedEntry.Wrapper<BakedModel>> variants,
            RandomSource random) {
        int remaining = Math.abs((int) random.nextLong()) % totalWeight;
        for (int i = 0, n = variants.size(); i < n; i++) {
            WeightedEntry.Wrapper<BakedModel> variant = variants.get(i);
            remaining -= variant.getWeight().asInt();
            if (remaining < 0) {
                return variant.data();
            }
        }
        return null;
    }
}
