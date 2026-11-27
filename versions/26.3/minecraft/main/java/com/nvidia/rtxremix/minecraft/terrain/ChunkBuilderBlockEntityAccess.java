/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

/**
 * Reports whether a built chunk's latest chunk data contained any block
 * entities, recorded by {@code BuiltChunkMixin} as the data is published.
 *
 * <p>The render thread reads it to walk only the visible chunks that can hold a
 * block entity, instead of every visible chunk.
 */
public interface ChunkBuilderBlockEntityAccess {
    boolean rtxremix$hasBlockEntities();
}
