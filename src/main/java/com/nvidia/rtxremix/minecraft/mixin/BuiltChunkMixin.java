/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.SectionLeftRenderSetQueue;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Offers every section whose new mesh has no vanilla layers to the Remix
 * section-removal validator. While capture is on that is every captured
 * section, and the validator decides from the section's blocks whether the
 * Remix terrain it held must go.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class BuiltChunkMixin {

    /** The node a slot holds before the view area first places it. */
    private static final long rtxremix$UNPLACED = SectionPos.asLong(-1, -1, -1);

    @Shadow
    public abstract long getSectionNode();

    @Inject(method = "setSectionMesh", at = @At("HEAD"))
    private void rtxremix$onSectionMeshSet(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> cir) {
        if (mesh.hasRenderableLayers()) {
            return;
        }
        long node = getSectionNode();
        if (node == rtxremix$UNPLACED) {
            return;
        }
        SectionLeftRenderSetQueue.enqueueEmptyCandidate(SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
    }
}
