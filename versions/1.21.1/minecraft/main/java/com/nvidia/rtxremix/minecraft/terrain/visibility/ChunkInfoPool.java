/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import net.minecraft.client.renderer.SectionOcclusionGraph.Node;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.Direction;

/**
 * One {@code ChunkInfo} per section slot, reused from walk to walk instead of allocated per
 * reached section. The {@code BuiltChunk} at a slot is stable for the life of the storage, so
 * an info can keep it and only its per-walk state is reset: the direction mask, the culling
 * state and the propagation level, exactly what vanilla's constructor sets.
 *
 * <p>Each visibility state owns one pool, and the two states that can be alive at once
 * (published and being walked) own different pools, so a walk never rewrites an info the
 * render thread is reading. A slot reached twice in one walk, which happens when its section
 * is re-originned mid-walk and so becomes a new element of the reached set, gets a fresh
 * info the second time, as vanilla would.
 */
public final class ChunkInfoPool {
    private final Node[] infos;
    private final int[] claimed;
    private int walk = 1;

    public ChunkInfoPool(int chunkCount) {
        infos = new Node[Math.max(1, chunkCount)];
        claimed = new int[Math.max(1, chunkCount)];
    }

    /** Starts a walk: every slot may be claimed again. */
    public void beginWalk() {
        if (++walk == 0) {
            Arrays.fill(claimed, 0);
            walk = 1;
        }
    }

    /** The info for {@code chunk} in this walk, initialised as vanilla's constructor would. */
    public Node acquire(SectionRenderDispatcher.RenderSection chunk, @Nullable Direction direction, int propagationLevel) {
        int index = chunk.index;
        if (index < 0 || index >= infos.length || claimed[index] == walk) {
            return new Node(chunk, direction, propagationLevel);
        }
        claimed[index] = walk;
        Node info = infos[index];
        if (info == null || info.section != chunk) {
            info = new Node(chunk, direction, propagationLevel);
            infos[index] = info;
            return info;
        }
        info.sourceDirections = 0;
        if (direction != null) {
            info.addSourceDirection(direction);
        }
        info.directions = 0;
        info.step = propagationLevel;
        return info;
    }
}
