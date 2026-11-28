/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.HeldItemFlame;
import com.nvidia.rtxremix.minecraft.entity.ItemStackRenderStateCapture;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries an item render state's stack and holder, and tells the capture which
 * block layer and which holder an item submit belongs to.
 *
 * <p>Every item render, hands, item frames and dropped items alike, submits
 * through {@code ItemStackRenderState.submit}, so the block layer set here makes
 * a held block shade as its placed counterpart. The holder is recorded only for a
 * hand render by a living entity, the one case where a held flame can be put out.
 */
@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateMixin implements ItemStackRenderStateCapture {

    @Shadow
    ItemDisplayContext displayContext;

    private ItemStack rtxremix$stack = ItemStack.EMPTY;
    private @Nullable ItemOwner rtxremix$owner;

    @Override
    public ItemStack rtxremix$stack() {
        return rtxremix$stack;
    }

    @Override
    public @Nullable ItemOwner rtxremix$owner() {
        return rtxremix$owner;
    }

    @Override
    public void rtxremix$setSource(ItemStack stack, @Nullable ItemOwner owner) {
        rtxremix$stack = stack;
        rtxremix$owner = owner;
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private void rtxremix$beginItem(PoseStack poseStack, SubmitNodeCollector collector,
                                    int lightCoords, int overlayCoords, int outlineColor, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (!context.isRemixPass()) {
            return;
        }
        context.setBlockItemLayer(rtxremix$blockLayer(rtxremix$stack));
        if (HeldItemFlame.isHandMode(displayContext) && rtxremix$owner instanceof LivingEntity holder) {
            context.setHeldItemHolder(holder);
        }
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void rtxremix$endItem(PoseStack poseStack, SubmitNodeCollector collector,
                                  int lightCoords, int overlayCoords, int outlineColor, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) {
            context.setBlockItemLayer(EntityRenderContext.NO_BLOCK_ITEM);
            context.clearHeldItemHolder();
        }
    }

    /**
     * Asks the question terrain asks of a placed block, through the same
     * classifier, so a held block is classified exactly as its placed
     * counterpart is.
     */
    private static int rtxremix$blockLayer(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                ? BlockTextureManager.layerType(
                        BlockQuadEmitter.classifyBlockLayer(blockItem.getBlock().defaultBlockState()))
                : EntityRenderContext.NO_BLOCK_ITEM;
    }
}
