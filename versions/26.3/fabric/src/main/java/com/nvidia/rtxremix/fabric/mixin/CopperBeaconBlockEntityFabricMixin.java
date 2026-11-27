/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.fabric.mixin;

import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

/** Adds Fabric's menu-opening payload contract to the shared block entity. */
@Mixin(CopperBeaconBlockEntity.class)
@SuppressWarnings("rawtypes")
abstract class CopperBeaconBlockEntityFabricMixin
        implements ExtendedMenuProvider {
    /**
     * Fabric invokes the generic interface's erased {@code Object} descriptor,
     * while the shared target owns the covariant descriptor. Keep both in the
     * merged class and delegate the erased ABI entry to the canonical method.
     */
    @Override
    public Object getScreenOpeningData(ServerPlayer player) {
        return ((CopperBeaconBlockEntity) (Object) this)
                .getScreenOpeningData(player);
    }
}
