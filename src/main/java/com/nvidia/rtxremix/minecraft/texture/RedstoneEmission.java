/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import net.minecraft.block.BlockState;
import net.minecraft.block.RedstoneWireBlock;

/**
 * Which atlas material a terrain surface takes for redstone dust's power state.
 *
 * <p>Dust draws every power level from the same sprites and tints them by power, so the
 * sprites cannot say whether it glows. Powered dust emits its own albedo, which the runtime
 * tints by the vertex colour exactly as it tints the albedo, so the glow dims with vanilla's
 * power colour; unpowered dust emits nothing. Only placed dust knows its power, so nothing
 * else drawing these sprites glows.
 */
public enum RedstoneEmission {
    /** Not dust: the ordinary atlas material. */
    NONE,
    /** Dust at power 0: the atlas material without emission. */
    UNPOWERED,
    /** Dust at power 1 to 15: the atlas material emitting its albedo. */
    POWERED;

    /**
     * Returns the state every quad of this block's model is drawn with. A redstone wire's
     * model is all dust, whatever sprites a pack gives it, so the whole block follows its power.
     */
    public static RedstoneEmission of(BlockState state) {
        if (!(state.getBlock() instanceof RedstoneWireBlock) || !state.contains(RedstoneWireBlock.POWER)) {
            return NONE;
        }
        return forPower(state.get(RedstoneWireBlock.POWER));
    }

    static RedstoneEmission forPower(int power) {
        return power > 0 ? POWERED : UNPOWERED;
    }

    public boolean isDust() {
        return this != NONE;
    }
}
