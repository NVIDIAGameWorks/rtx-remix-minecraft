/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderStateOwner;
import com.nvidia.rtxremix.minecraft.entity.RemixSubmitCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marks every entity submit that feeds Remix capture, nested ones included (a
 * spawner's display entity inside a block entity capture), and records which
 * entity each extracted render state belongs to.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "extractEntity", at = @At("RETURN"))
    private <E extends Entity> void rtxremix$recordOwner(
            E entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
        ((EntityRenderStateOwner) cir.getReturnValue()).rtxremix$setEntity(entity);
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private <S extends EntityRenderState> void rtxremix$onSubmitStart(
            S renderState, CameraRenderState camera, double x, double y, double z,
            PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        EntityRenderContext.get().begin(submitNodeCollector instanceof RemixSubmitCollector);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private <S extends EntityRenderState> void rtxremix$onSubmitEnd(
            S renderState, CameraRenderState camera, double x, double y, double z,
            PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        EntityRenderContext.get().end();
    }
}
