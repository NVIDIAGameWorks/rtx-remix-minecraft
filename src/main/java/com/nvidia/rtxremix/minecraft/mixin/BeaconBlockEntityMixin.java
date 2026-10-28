/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.light.LoadedVanillaBeacons;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps {@link LoadedVanillaBeacons} in step with the beacons a client world holds. */
@Mixin(BeaconBlockEntity.class)
abstract class BeaconBlockEntityMixin {

    @Inject(method = "setWorld", at = @At("TAIL"))
    private void rtxremix$trackLoadedBeacon(World world, CallbackInfo ci) {
        LoadedVanillaBeacons.track((BeaconBlockEntity) (Object) this);
    }

    @Inject(method = "markRemoved", at = @At("HEAD"))
    private void rtxremix$untrackRemovedBeacon(CallbackInfo ci) {
        LoadedVanillaBeacons.untrack((BeaconBlockEntity) (Object) this);
    }
}
