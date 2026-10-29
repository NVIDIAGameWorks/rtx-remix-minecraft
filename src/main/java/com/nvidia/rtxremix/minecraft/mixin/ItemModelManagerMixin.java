/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.ItemRenderStateCapture;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ModelTransformationMode;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records source identity after vanilla clears and populates a deferred item state. */
@Mixin(ItemModelManager.class)
public abstract class ItemModelManagerMixin {
    @Inject(method = "update(Lnet/minecraft/client/render/item/ItemRenderState;"
            + "Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ModelTransformationMode;Z"
            + "Lnet/minecraft/world/World;Lnet/minecraft/entity/LivingEntity;I)V", at = @At("RETURN"))
    private void rtxremix$recordItem(ItemRenderState state, ItemStack stack, ModelTransformationMode mode,
                                     boolean leftHanded, World world, LivingEntity holder, int seed,
                                     CallbackInfo ci) {
        ((ItemRenderStateCapture) state).rtxremix$getItemCaptureData().update(stack, mode, holder);
    }
}
