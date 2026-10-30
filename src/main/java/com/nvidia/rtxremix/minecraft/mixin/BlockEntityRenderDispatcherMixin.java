/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Captures block entities where vanilla renders them, instead of rendering them twice.
 *
 * <p>Vanilla evaluates the block-entity renderer here and writes it to OpenGL. Each
 * dispatch is offered to {@code RemixRenderer.renderBlockEntityInPlace}, which runs the
 * same renderer against the Remix capture stack and provider and submits the result;
 * the OpenGL write never happens. Vanilla's own matrix stack and provider are
 * deliberately not forwarded: the stack is camera-relative, and the capture supplies an
 * identity one so the geometry stays block-local. When Remix declines, vanilla renders
 * as usual.
 *
 * <p>{@code renderEntity}, which held items reach through {@code BuiltinModelItemRenderer},
 * is not wrapped: a block entity rendered inside an entity capture arrives through that
 * entity's provider and becomes that entity's parts.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    @WrapMethod(
        method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"
    )
    private <E extends BlockEntity> void rtxremix$captureBlockEntityInPlace(
            E be, float tickDelta, PoseStack matrices,
            MultiBufferSource provider, Operation<Void> original) {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        boolean captured = renderer != null
                && be.hasLevel()
                && renderer.renderBlockEntityInPlace(be,
                        (captureMatrices, captureProvider) ->
                                original.call(be, tickDelta, captureMatrices, captureProvider));
        if (!captured) {
            original.call(be, tickDelta, matrices, provider);
        }
    }
}
