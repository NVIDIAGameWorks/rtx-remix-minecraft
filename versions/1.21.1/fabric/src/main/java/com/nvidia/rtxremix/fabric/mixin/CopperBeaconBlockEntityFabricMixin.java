/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.fabric.mixin;

import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenData;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import org.spongepowered.asm.mixin.Mixin;

/** Adds Fabric's menu-opening payload contract to the shared block entity. */
@Mixin(CopperBeaconBlockEntity.class)
abstract class CopperBeaconBlockEntityFabricMixin
        implements ExtendedScreenHandlerFactory<CopperBeaconScreenData> {
}
