/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.apache.commons.lang3.tuple.Pair;

/** Allocation-free cache of the parts selected by a multipart block state. */
public final class MultipartModelSelector {
    private static final ThreadLocal<LegacyRandomSource> PART_RANDOM =
            ThreadLocal.withInitial(() -> new LegacyRandomSource(0L));

    private MultipartModelSelector() {
    }

    public static BitSet selected(
            List<Pair<Predicate<BlockState>, BakedModel>> selectors,
            Map<BlockState, BitSet> cache,
            BlockState state) {
        BitSet selected = cache.get(state);
        if (selected != null) {
            return selected;
        }
        selected = new BitSet();
        for (int i = 0, n = selectors.size(); i < n; i++) {
            if (selectors.get(i).getLeft().test(state)) {
                selected.set(i);
            }
        }
        cache.put(state, selected);
        return selected;
    }

    /** Reused random whose seed each selected child receives, matching vanilla. */
    public static LegacyRandomSource partRandom(long seed) {
        LegacyRandomSource random = PART_RANDOM.get();
        random.setSeed(seed);
        return random;
    }

    /**
     * Adds one selected part without the intermediate arrays produced by
     * {@code ArrayList(Collection)} and {@code addAll}.
     */
    public static <T> List<T> append(List<T> result, List<T> part) {
        if (result == null) {
            return part;
        }
        CombinedList<T> combined;
        if (result instanceof CombinedList<?>) {
            @SuppressWarnings("unchecked")
            CombinedList<T> owned = (CombinedList<T>) result;
            combined = owned;
        } else {
            combined = new CombinedList<>(result.size() + part.size());
            copy(result, combined);
        }
        copy(part, combined);
        return combined;
    }

    public static <T> List<T> finish(List<T> result) {
        return result == null ? Collections.emptyList() : result;
    }

    private static <T> void copy(List<T> source, List<T> target) {
        for (int i = 0, n = source.size(); i < n; i++) {
            target.add(source.get(i));
        }
    }

    private static final class CombinedList<T> extends ArrayList<T> {
        private CombinedList(int capacity) {
            super(capacity);
        }
    }
}
