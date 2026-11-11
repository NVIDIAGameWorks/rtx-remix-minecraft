/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WallBlock.class)
abstract class WallBlockMixin {

    @Inject(method = "connectsTo", at = @At("HEAD"), cancellable = true)
    private void rtxremix$connectToCopperBeacon(
            BlockState state,
            boolean faceFullSquare,
            Direction side,
            CallbackInfoReturnable<Boolean> callback) {
        if (state.is(CopperBeaconBlock.INSTANCE)) {
            callback.setReturnValue(
                    state.getValue(CopperBeaconBlock.MOUNT) == side.getOpposite());
        }
    }

    @Inject(method = "shouldRaisePost", at = @At("HEAD"), cancellable = true)
    private void rtxremix$connectPostToCopperBeacon(
            BlockState state,
            BlockState aboveState,
            VoxelShape aboveShape,
            CallbackInfoReturnable<Boolean> callback) {
        if (aboveState.is(CopperBeaconBlock.INSTANCE)) {
            callback.setReturnValue(
                    aboveState.getValue(CopperBeaconBlock.MOUNT) == Direction.UP);
        }
    }
}
