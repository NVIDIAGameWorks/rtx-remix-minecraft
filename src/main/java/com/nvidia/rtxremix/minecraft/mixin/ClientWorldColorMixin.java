/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla's {@code calculateColor} averages the biome colour over the blend radius and
 * allocates a {@code BlockPos.Mutable} to walk it. It runs once per block position per colour
 * type on every section build, on the shared workers, so the position is kept per thread
 * instead. It never escapes the method: the colour resolver receives its coordinates as ints.
 */
@Mixin(ClientLevel.class)
public abstract class ClientWorldColorMixin {

    private static final ThreadLocal<BlockPos.MutableBlockPos> rtxremix$blendPos =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @WrapOperation(
            method = "calculateBlockTint",
            at = @At(value = "NEW", target = "()Lnet/minecraft/core/BlockPos$MutableBlockPos;"))
    private BlockPos.MutableBlockPos rtxremix$reuseBlendPos(Operation<BlockPos.MutableBlockPos> original) {
        return rtxremix$blendPos.get();
    }
}
