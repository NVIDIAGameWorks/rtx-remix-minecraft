/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;

/** A chunk holder's cached entry for the server's per-tick chunk list; see {@code ChunkHolderMixin}. */
public interface ChunkHolderTickEntry {
    ServerChunkCache.ChunkAndHolder rtxremix$tickEntry(LevelChunk chunk);
}
