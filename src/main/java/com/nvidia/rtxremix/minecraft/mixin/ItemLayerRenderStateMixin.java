/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.HeldItemEffectsRenderer;
import com.nvidia.rtxremix.minecraft.entity.ItemStackRenderStateCapture;
import com.nvidia.rtxremix.minecraft.entity.RemixSubmitCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a held item's lit element right after its quads, while the pose stack
 * still holds the item's display-transformed pose. Running here covers every
 * hand-held render, first person, other players and mobs, with one hook.
 */
@Mixin(targets = "net.minecraft.client.renderer.item.ItemStackRenderState$LayerRenderState")
public abstract class ItemLayerRenderStateMixin {

    @Shadow
    @Final
    ItemStackRenderState this$0;

    @Shadow
    private ItemTransform itemTransform;

    @Inject(
            method = "submit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitItem(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;III[ILnet/minecraft/client/resources/model/geometry/ItemQuads;Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;)V",
                    shift = At.Shift.AFTER
            ))
    private void rtxremix$renderHeldFlame(PoseStack poseStack, SubmitNodeCollector collector,
                                          int lightCoords, int overlayCoords, int outlineColor,
                                          CallbackInfo ci) {
        if (!(collector instanceof RemixSubmitCollector capture)) {
            return;
        }
        ItemStackRenderStateCapture item = (ItemStackRenderStateCapture) this$0;
        // A hand draws its item scaled down (0.68 in first person, 0.55 in third); the
        // flame is world-sized, so it divides that display scale back out.
        HeldItemEffectsRenderer.render(item.rtxremix$stack(), ((ItemStackRenderStateAccessor) this$0).rtxremix$displayContext(),
                poseStack, capture.provider(), itemTransform.scale().x());
    }
}
