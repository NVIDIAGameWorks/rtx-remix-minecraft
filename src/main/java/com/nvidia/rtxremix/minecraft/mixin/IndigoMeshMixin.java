/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.capture.MeshQuadOutput;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.MeshImpl;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.MutableQuadViewImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps Indigo's bulk copy for its own emitters and uses public accessors for other emitters. */
@Mixin(value = MeshImpl.class, remap = false)
public abstract class IndigoMeshMixin {
    @Inject(method = "outputTo", at = @At("HEAD"), cancellable = true)
    private void rtxremix$outputToForeignEmitter(QuadEmitter emitter, CallbackInfo ci) {
        if (!(emitter instanceof MutableQuadViewImpl)) {
            MeshQuadOutput.output((Mesh) (Object) this, emitter);
            ci.cancel();
        }
    }
}
