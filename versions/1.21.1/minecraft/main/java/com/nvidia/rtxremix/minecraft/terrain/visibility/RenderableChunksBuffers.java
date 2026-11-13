/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

/** The reusable buffers a vanilla visibility state was built on; see {@code RenderableChunksMixin}. */
public interface RenderableChunksBuffers {
    ChunkInfoPool rtxremix$infoPool();
}
