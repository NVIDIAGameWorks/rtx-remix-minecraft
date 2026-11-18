/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import java.util.LinkedHashSet;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * A frustum index under construction while vanilla's full visibility walk runs off the render
 * thread.
 *
 * <p>The build records the slot-move sequence it started at. Sections indexed before a
 * mid-walk re-origin carry bounds that are stale by publication, so the published index must
 * replay every move since that sequence before its first use.
 */
public final class BuiltChunkFrustumIndexBuild {
    private final long moveSequence;
    private final HierarchicalFrustumCuller<SectionRenderDispatcher.RenderSection> culler;
    private LinkedHashSet<?> chunks;

    /**
     * @param recycled a culler taken back from a retired index, cleared here before reuse, or
     *                 null to start from an empty one
     */
    public BuiltChunkFrustumIndexBuild(long moveSequence, HierarchicalFrustumCuller<SectionRenderDispatcher.RenderSection> recycled) {
        this.moveSequence = moveSequence;
        if (recycled != null) {
            recycled.clear();
            this.culler = recycled;
        } else {
            this.culler = new HierarchicalFrustumCuller<>();
        }
    }

    /**
     * Records a section the walk reached.
     *
     * @param chunks the reached-section set the walk is filling; one build serves one set
     * @throws IllegalStateException if the walk switches to another set mid-build
     */
    public void add(LinkedHashSet<?> chunks, SectionRenderDispatcher.RenderSection chunk) {
        if (this.chunks == null) {
            this.chunks = chunks;
        } else if (this.chunks != chunks) {
            throw new IllegalStateException("Full visibility build changed storage");
        }
        BuiltChunkFrustumIndex.add(culler, chunk);
    }

    /**
     * The index for {@code state}, or null when the walk reached nothing and there is nothing
     * to index.
     */
    public BuiltChunkFrustumIndex publish(Object state) {
        if (chunks == null) {
            return null;
        }
        return new BuiltChunkFrustumIndex(state, chunks, culler, moveSequence);
    }
}
