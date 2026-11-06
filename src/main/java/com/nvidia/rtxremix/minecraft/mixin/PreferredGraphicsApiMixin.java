/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Minecraft on its OpenGL backend whatever the graphics API setting says.
 *
 * <p>Remix creates its own Vulkan swapchain on the game window and composites
 * the GUI from OpenGL through the GL interop. Minecraft's Vulkan backend would
 * claim that window's swapchain first.</p>
 */
@Mixin(PreferredGraphicsApi.class)
public abstract class PreferredGraphicsApiMixin {

    @Inject(method = "getBackendsToTry", at = @At("HEAD"), cancellable = true)
    private void rtxremix$openGlOnly(CallbackInfoReturnable<GpuBackend[]> cir) {
        cir.setReturnValue(new GpuBackend[]{new GlBackend()});
    }
}
