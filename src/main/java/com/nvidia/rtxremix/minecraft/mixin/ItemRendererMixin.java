/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.HeldItemEffectsRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ModelTransformationMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Emits held effects after the first baked layer, while its block-space pose is active. */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    @Inject(method = "renderItem(Lnet/minecraft/item/ModelTransformationMode;"
            + "Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;"
            + "II[ILnet/minecraft/client/render/model/BakedModel;Lnet/minecraft/client/render/RenderLayer;"
            + "Lnet/minecraft/client/render/item/ItemRenderState$Glint;)V", at = @At("RETURN"))
    private static void rtxremix$renderHeldEffects(ModelTransformationMode mode, MatrixStack matrices,
                                                   VertexConsumerProvider consumers, int light, int overlay,
                                                   int[] tints, BakedModel model, RenderLayer layer,
                                                   ItemRenderState.Glint glint, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.claimItemEffects()) {
            HeldItemEffectsRenderer.render(context.getItemCaptureData().stack(), mode, matrices, consumers, model);
        }
    }
}
