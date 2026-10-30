/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives a compile task's region snapshots back to the storage pool once the task
 * has run, and keeps a captured section's real visibility.
 *
 * <p>A task the dispatcher cancels before it runs never reaches {@code doTask};
 * its pooled arrays are left to the collector rather than released from
 * {@code cancel}, which could race a compile already reading them.</p>
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public abstract class CompileTaskMixin {

    @Shadow
    @Final
    private RenderSectionRegion region;

    @Inject(method = "doTask", at = @At("RETURN"))
    private void rtxremix$releaseRegion(
            SectionBufferBuilderPack buffers,
            CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult> cir) {
        ((RegionSnapshotRelease) region).rtxremix$release();
    }

    /**
     * A section whose blocks all went to Remix has no vanilla layers. Since 26.x
     * vanilla swaps such a section for {@code CompiledSectionMesh.EMPTY}, which
     * claims every face is see-through, unless it holds a block entity. Keeping
     * the compiled mesh instead keeps the visibility the compile resolved, so
     * the occlusion walk behaves as it does for drawn terrain.
     */
    @ModifyExpressionValue(
            method = "doTask",
            at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z"))
    private boolean rtxremix$keepCapturedVisibility(boolean noBlockEntities) {
        return noBlockEntities && !RtxRemixMod.isRtxEnabled();
    }
}
