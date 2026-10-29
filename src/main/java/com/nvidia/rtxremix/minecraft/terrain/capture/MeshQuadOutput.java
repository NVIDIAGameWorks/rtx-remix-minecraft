/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;

import java.util.function.Consumer;

/** Copies meshes through the public quad API without allocating a consumer per mesh. */
public final class MeshQuadOutput implements Consumer<QuadView> {
    private static final ThreadLocal<MeshQuadOutput> LOCAL = ThreadLocal.withInitial(MeshQuadOutput::new);

    private QuadEmitter emitter;

    private MeshQuadOutput() {}

    /**
     * Copies and emits each quad without retaining the mesh, its quads, or the emitter.
     *
     * @param mesh mesh to read on the calling thread
     * @param emitter destination whose {@link QuadEmitter#emit()} resets its quad state
     */
    public static void output(Mesh mesh, QuadEmitter emitter) {
        MeshQuadOutput output = LOCAL.get();
        QuadEmitter previous = output.emitter;
        output.emitter = emitter;
        try {
            mesh.forEach(output);
        } finally {
            // A transform can output another mesh into a different context on this thread.
            output.emitter = previous;
        }
    }

    @Override
    public void accept(QuadView quad) {
        emitter.copyFrom(quad).emit();
    }
}
