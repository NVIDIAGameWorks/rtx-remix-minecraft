/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

/**
 * Section-addressed access to the built-chunk slots owned by BuiltChunkStorage.
 */
public interface BuiltChunkStorageSectionAccess {

    /**
     * Flags a section for rebuild only if its slot still holds it. Vanilla's
     * scheduleRebuild aliases every section coordinate onto a slot and flags
     * whatever section is resident, so a section that has left the window
     * would trigger a spurious rebuild of an unrelated one.
     */
    void rtxremix$scheduleRebuildIfResident(int cx, int sy, int cz);

    /**
     * Applies one chunk-load or light-data re-flag request for a section, vanilla's
     * WorldRenderer.scheduleBlockRenders(III), under the Remix capture rule. The
     * section's own slot is flagged as vanilla flags it. Each of the eight horizontal
     * neighbour slots at the same section Y is flagged only if it holds that section,
     * has no rebuild pending, and its last region snapshot did not contain the loading
     * column. Vertical neighbours are not visited because every section Y of a loading
     * column receives its own request. A section Y without a slot of its own is ignored
     * rather than aliased onto the far slot. Render thread only.
     */
    void rtxremix$flagLoadedSection(int cx, int sy, int cz);

    /**
     * Clears the bit for column (cx, cz) in every resident slot of its eight neighbour
     * columns after the column unloaded or before it is reloaded in place, so the next
     * load of that column re-flags them. Render thread only.
     */
    void rtxremix$clearNeighbourSnapshotBits(int cx, int cz);
}
