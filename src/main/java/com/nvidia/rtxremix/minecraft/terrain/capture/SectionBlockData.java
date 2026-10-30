/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.nvidia.rtxremix.minecraft.light.LightEntry;
import java.util.List;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;

/**
 * Captured block-level mesh data for a single chunk section (16x16x16),
 * produced by {@link BlockQuadEmitter}.
 *
 * <p>Mesh data is pre-categorized and interleaved in HardcodedVertex
 * layout via {@link SectionMeshData}. No intermediate SoA copies.
 */
public record SectionBlockData(
    SectionPos sectionPos,
    SectionMeshData meshData,
    List<LightEntry> lights,
    /**
     * 64-bit openness mask for the top face (y=15).  Each face is divided
     * into an 8x8 grid of 2x2-block sub-regions.  Bit {@code i} is set
     * when sub-region {@code i} contains at least one non-opaque block.
     * A mask of 0 means the face is fully sealed.
     */
    long topFaceMask,
    /** Openness mask for the bottom face (y=0). See {@link #topFaceMask}. */
    long bottomFaceMask,
    /** Openness mask for the east face (x=15). See {@link #topFaceMask}. */
    long eastFaceMask,
    /** Openness mask for the west face (x=0). See {@link #topFaceMask}. */
    long westFaceMask,
    /** Openness mask for the south face (z=15). See {@link #topFaceMask}. */
    long southFaceMask,
    /** Openness mask for the north face (z=0). See {@link #topFaceMask}. */
    long northFaceMask,
    /**
     * Compact 16x16x16 opacity volume.  64 longs, one bit per block.
     * Bit at index {@code (y*256 + z*16 + x)} is {@code 1} when the block
     * is an opaque full cube, {@code 0} otherwise.  Used for intra-section
     * flood fill when seeding the 3D connectivity BFS from the player's
     * position.
     */
    long[] opacityBitmap
) {
    /**
     * A single quad with all rendering context resolved at generation time.
     *
     * @param positions   12 floats: 4 vertices x (x, y, z), section-local coords [0..16]
     * @param uvs         8 floats: 4 vertices x (u, v), atlas coordinates
     * @param nx          face normal X
     * @param ny          face normal Y
     * @param nz          face normal Z
     * @param color       pre-resolved ARGB vertex color (tint already applied)
     * @param spriteId    exact sprite identifier for O(1) material lookup
     * @param spriteU0    sprite atlas UV bounds min U
     * @param spriteV0    sprite atlas UV bounds min V
     * @param spriteU1    sprite atlas UV bounds max U
     * @param spriteV1    sprite atlas UV bounds max V
     * @param renderLayer render layer name ("solid", "cutout", "cutout_mipped", "translucent")
     * @param isOverlay   true if this quad is a decal overlay (transparent sprite on shared face)
     * @param isFluid     true if this quad belongs to a fluid (water, lava)
     */
    public record BlockQuad(
        float[] positions,
        float[] uvs,
        float nx, float ny, float nz,
        int color,
        Identifier spriteId,
        float spriteU0, float spriteV0,
        float spriteU1, float spriteV1,
        String renderLayer,
        boolean isOverlay,
        boolean isFluid
    ) {}
}
