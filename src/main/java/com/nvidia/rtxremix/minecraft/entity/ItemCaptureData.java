/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ModelTransformationMode;

/**
 * The item facts vanilla's baked geometry no longer carries after state extraction.
 * Owned by one item render state and updated on the render thread with that state.
 */
public final class ItemCaptureData {
    private ItemStack stack;
    private ModelTransformationMode mode = ModelTransformationMode.NONE;
    private LivingEntity holder;
    private int blockLayer = EntityRenderContext.NO_BLOCK_ITEM;

    public void update(ItemStack stack, ModelTransformationMode mode, LivingEntity holder) {
        this.stack = stack;
        this.mode = mode;
        this.holder = holder;
        this.blockLayer = stack.getItem() instanceof BlockItem blockItem
                ? BlockTextureManager.layerType(
                        BlockQuadEmitter.classifyBlockLayer(blockItem.getBlock().getDefaultState()))
                : EntityRenderContext.NO_BLOCK_ITEM;
    }

    public void clear() {
        stack = null;
        mode = ModelTransformationMode.NONE;
        holder = null;
        blockLayer = EntityRenderContext.NO_BLOCK_ITEM;
    }

    public ItemStack stack() {
        return stack == null ? ItemStack.EMPTY : stack;
    }

    public ModelTransformationMode mode() {
        return mode;
    }

    public LivingEntity holder() {
        return holder;
    }

    public int blockLayer() {
        return blockLayer;
    }
}
