/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.ChunkHolderTickEntry;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Keeps one {@code ChunkWithHolder} per holder for the server's per-tick chunk list. The
 * holder's world chunk is stable while it is loaded, so the entry is remade only when the
 * chunk changes; see {@link ServerChunkManagerMixin}.
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin implements ChunkHolderTickEntry {

    private ServerChunkCache.ChunkAndHolder rtxremix$tickEntry;

    @Override
    public ServerChunkCache.ChunkAndHolder rtxremix$tickEntry(LevelChunk chunk) {
        ServerChunkCache.ChunkAndHolder entry = rtxremix$tickEntry;
        if (entry == null || entry.chunk != chunk) {
            entry = new ServerChunkCache.ChunkAndHolder(chunk, (ChunkHolder) (Object) this);
            rtxremix$tickEntry = entry;
        }
        return entry;
    }
}
