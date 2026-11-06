/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.ChunkHolderTickEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Ticks the server's loaded chunks without rebuilding the list of them every tick. Vanilla's
 * {@code tickChunks} creates a fresh list and a fresh {@code ChunkWithHolder} record for every
 * loaded chunk twenty times a second; at render distance 32 on the integrated server that is
 * about thirteen thousand records and a hundred-kilobyte array per tick, the server thread's
 * largest source of garbage. The list is kept and cleared, and each holder keeps its record
 * for as long as its chunk stays the same ({@link ChunkHolderMixin}). The order, the shuffle
 * and everything the loop does with the entries are vanilla's.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkManagerMixin {

    private final ArrayList<ServerChunkCache.ChunkAndHolder> rtxremix$tickList = new ArrayList<>();

    @Redirect(
            method = "tickChunks",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/Lists;newArrayListWithCapacity(I)Ljava/util/ArrayList;"
            ))
    private ArrayList<ServerChunkCache.ChunkAndHolder> rtxremix$reuseTickList(int capacity) {
        ArrayList<ServerChunkCache.ChunkAndHolder> list = rtxremix$tickList;
        list.clear();
        list.ensureCapacity(capacity);
        return list;
    }

    @Redirect(
            method = "tickChunks",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/server/level/ChunkHolder;)Lnet/minecraft/server/level/ServerChunkCache$ChunkAndHolder;"
            ))
    private ServerChunkCache.ChunkAndHolder rtxremix$cachedTickEntry(LevelChunk chunk, ChunkHolder holder) {
        return ((ChunkHolderTickEntry) holder).rtxremix$tickEntry(chunk);
    }
}
