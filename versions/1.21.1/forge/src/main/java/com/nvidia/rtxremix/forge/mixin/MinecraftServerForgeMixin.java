/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import com.nvidia.rtxremix.forge.ForgeQuickPlayCompatibility;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies Forge's missing quick-play data reload before the first server tick. */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerForgeMixin {
    @Inject(
            method = "runServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraftforge/server/ServerLifecycleHooks;"
                            + "handleServerStarted(Lnet/minecraft/server/MinecraftServer;)V",
                    remap = false),
            require = 0)
    private void rtxremix$initializeQuickPlayLootModifiers(CallbackInfo ci) {
        ForgeQuickPlayCompatibility.ensureLootModifiersReady(
                (MinecraftServer) (Object) this);
    }
}
