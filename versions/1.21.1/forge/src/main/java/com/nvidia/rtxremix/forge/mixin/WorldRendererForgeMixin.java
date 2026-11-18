/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.MinecraftRuntime;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.SortedSet;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Adapts Forge-added render-call parameters to canonical capture policy. */
@Mixin(LevelRenderer.class)
abstract class WorldRendererForgeMixin {
    @Shadow @Final
    private Long2ObjectMap<SortedSet<BlockDestructionProgress>>
            destructionProgress;

    @WrapOperation(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderBreakingTexture(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraftforge/client/model/data/ModelData;)V"
            ),
            remap = false)
    private void rtxremix$captureBlockDamageInPlace(
            BlockRenderDispatcher dispatcher,
            BlockState state,
            BlockPos pos,
            BlockAndTintGetter world,
            PoseStack matrices,
            VertexConsumer consumer,
            ModelData modelData,
            Operation<Void> original) {
        boolean captured = MinecraftRuntime.captureBlockDamageInPlace(
                pos,
                destructionProgress.get(pos.asLong()).last().getProgress(),
                (captureMatrices, captureConsumer) -> original.call(
                        dispatcher,
                        state,
                        pos,
                        world,
                        captureMatrices,
                        captureConsumer,
                        modelData));
        if (!captured) {
            original.call(
                    dispatcher, state, pos, world, matrices, consumer, modelData);
        }
    }

    @Redirect(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/ParticleEngine;render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V"
            ),
            remap = false)
    private void rtxremix$skipVanillaParticleDraw(
            ParticleEngine particles,
            LightTexture lightTexture,
            Camera camera,
            float tickDelta,
            Frustum frustum) {
        if (MinecraftRuntime.shouldDrawVanillaParticles()) {
            particles.render(lightTexture, camera, tickDelta, frustum);
        }
    }
}
