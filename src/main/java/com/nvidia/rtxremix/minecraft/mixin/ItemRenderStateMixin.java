/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.ItemCaptureData;
import com.nvidia.rtxremix.minecraft.entity.ItemRenderStateCapture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps item identity with deferred item state and brackets all of its rendered layers. */
@Mixin(ItemRenderState.class)
public abstract class ItemRenderStateMixin implements ItemRenderStateCapture {
    @Unique
    private final ItemCaptureData rtxremix$itemCaptureData = new ItemCaptureData();

    @Override
    public ItemCaptureData rtxremix$getItemCaptureData() {
        return rtxremix$itemCaptureData;
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void rtxremix$clearCaptureData(CallbackInfo ci) {
        rtxremix$itemCaptureData.clear();
    }

    @WrapMethod(method = "render")
    private void rtxremix$captureItem(MatrixStack matrices, VertexConsumerProvider consumers,
                                      int light, int overlay, Operation<Void> original) {
        EntityRenderContext context = EntityRenderContext.get();
        if (!context.isRemixPass()) {
            original.call(matrices, consumers, light, overlay);
            return;
        }
        boolean emittedBefore = context.hasEmittedItemEffects();
        ItemCaptureData previous = context.enterItemCapture(rtxremix$itemCaptureData);
        try {
            original.call(matrices, consumers, light, overlay);
        } finally {
            context.restoreItemCapture(previous, emittedBefore);
        }
    }
}
