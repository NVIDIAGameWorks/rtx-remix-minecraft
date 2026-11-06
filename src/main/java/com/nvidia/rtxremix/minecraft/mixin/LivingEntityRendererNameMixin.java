/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.PlayerNameText;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The upside-down name check only reads the string; see {@link PlayerNameText}. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererNameMixin {
    @Redirect(
            method = "isEntityUpsideDown",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getName()Lnet/minecraft/network/chat/Component;"))
    private static Component rtxremix$cachedName(LivingEntity entity) {
        return entity instanceof PlayerNameText cached ? cached.rtxremix$nameText() : entity.getName();
    }
}
