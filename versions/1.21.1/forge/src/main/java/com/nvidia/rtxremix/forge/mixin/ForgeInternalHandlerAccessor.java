/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import net.minecraftforge.common.ForgeInternalHandler;
import net.minecraftforge.common.loot.LootModifierManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads Forge's reload-owned loot manager without weakening its visibility. */
@Mixin(value = ForgeInternalHandler.class, remap = false)
public interface ForgeInternalHandlerAccessor {
    @Accessor("INSTANCE")
    static LootModifierManager rtxremix$getLootModifierManager() {
        throw new AssertionError();
    }

    @Accessor("INSTANCE")
    static void rtxremix$setLootModifierManager(LootModifierManager manager) {
        throw new AssertionError();
    }
}
