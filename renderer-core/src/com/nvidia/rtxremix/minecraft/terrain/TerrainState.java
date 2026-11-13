/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.core.terrain.TerrainSectionSnapshot;
import com.nvidia.rtxremix.minecraft.util.LongObjectMap;
import com.nvidia.rtxremix.minecraft.util.LongIntMap;
import com.nvidia.rtxremix.minecraft.util.LongHashSet;
import com.nvidia.rtxremix.minecraft.util.IntObjectMap;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionMeshData;
import com.nvidia.rtxremix.minecraft.asset.RemixLight;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Column-keyed terrain data and block-light connectivity state. Mesh and
 * retained-scene ownership intentionally live in {@code ChunkManager}; camera
 * movement never changes geometry ownership in this class.
 *
 * <p>Section payloads are owned: {@link #addSection} takes over the reference
 * its caller carried, and the state releases it when the entry is replaced,
 * removed, cleared, or rejected as a duplicate. Column builds that read a
 * payload on another thread hold their own reference for that time.
 */
public class TerrainState {

    /** All captured sections at one stable chunk-column coordinate. */
    public static final class ChunkColumn {
        public final int cx, cz;
        private final IntObjectMap<SectionEntry> sections = new IntObjectMap<>();
        private final List<SectionEntry> sectionEntries = new ArrayList<>();
        private int lightCount;

        ChunkColumn(int cx, int cz) { this.cx = cx; this.cz = cz; }

        public SectionEntry section(int sy) {
            return sections.get(sy);
        }

        public List<SectionEntry> sectionEntries() {
            return sectionEntries;
        }

        public boolean isEmpty() {
            return sectionEntries.isEmpty();
        }

        public int sectionCount() {
            return sectionEntries.size();
        }

        SectionEntry putSection(int sy, SectionEntry entry) {
            SectionEntry previous = sections.put(sy, entry);
            if (previous == null) {
                sectionEntries.add(entry);
            } else {
                int index = sectionEntries.indexOf(previous);
                if (index >= 0) {
                    sectionEntries.set(index, entry);
                } else {
                    sectionEntries.add(entry);
                }
                releaseData(previous.data);
            }
            return previous;
        }

        SectionEntry removeSection(int sy) {
            SectionEntry removed = sections.remove(sy);
            if (removed != null) {
                sectionEntries.remove(removed);
                releaseData(removed.data);
            }
            return removed;
        }

        List<RemixLight> detachSectionLights() {
            List<RemixLight> detached = new ArrayList<>(Math.max(0, lightCount));
            for (int i = 0, count = sectionEntries.size(); i < count; i++) {
                for (TerrainLight record : sectionEntries.get(i).lightEntries) {
                    detached.add(record.light);
                }
            }
            clearSections();
            return detached;
        }

        void clearSections() {
            for (int i = 0, count = sectionEntries.size(); i < count; i++) {
                SectionEntry entry = sectionEntries.get(i);
                entry.clearLightOwnership();
                releaseData(entry.data);
            }
            sections.clear();
            sectionEntries.clear();
            lightCount = 0;
        }

        public int lightCount() { return lightCount; }
    }

    /** Stored section data: hash for dedup, world offsets, opaque payload for mesh building. */
    public static final class SectionEntry {
        public final int sy;
        public final int dataHash;
        public final int xOff, yOff, zOff;
        public final Object data;
        /**
         * 64-bit openness masks for each of the 6 section faces.  Each face
         * is divided into an 8x8 grid of 2x2-block sub-regions; bit {@code i}
         * is set when sub-region {@code i} has at least one non-opaque block.
         * A mask of 0 means the face is fully sealed.
         */
        public final long topFaceMask, bottomFaceMask;
        public final long eastFaceMask, westFaceMask;
        public final long southFaceMask, northFaceMask;
        /**
         * Compact 16x16x16 opacity volume (64 longs).  Bit at index
         * {@code (y*256 + z*16 + x)} is set when the block is an opaque
         * full cube.  Used for intra-section flood fill when seeding the
         * 3D connectivity BFS from the player's position.  Test fixtures may
         * pass {@code null}; face masks still define inter-section openness.
         */
        public final long[] opacityBitmap;
        private final Map<BlockLightKey, TerrainLight> lights = new HashMap<>();
        private final List<TerrainLight> lightEntries = new ArrayList<>();
        /**
         * Per-light parameter fingerprint for diffing on rebuild. Two captures
         * with the same key and fingerprint emit identical
         * {@link com.nvidia.rtxremix.LightInfo}, so the existing
         * {@link RemixLight} can move to the replacement section without an
         * update round-trip.
         */
        private final Map<BlockLightKey, Integer> lightFingerprints = new HashMap<>();

        public SectionEntry(int hash, int xOff, int yOff, int zOff, Object data,
                            long topFaceMask, long bottomFaceMask,
                             long eastFaceMask, long westFaceMask,
                             long southFaceMask, long northFaceMask,
                             long[] opacityBitmap) {
            this.sy = yOff >> 4;
            this.dataHash = hash;
            this.xOff = xOff;
            this.yOff = yOff;
            this.zOff = zOff;
            this.data = data;
            this.topFaceMask = topFaceMask;
            this.bottomFaceMask = bottomFaceMask;
            this.eastFaceMask = eastFaceMask;
            this.westFaceMask = westFaceMask;
            this.southFaceMask = southFaceMask;
            this.northFaceMask = northFaceMask;
            this.opacityBitmap = opacityBitmap;
        }

        public Map<BlockLightKey, TerrainLight> lights() { return lights; }
        public List<TerrainLight> lightEntries() { return lightEntries; }
        public int lightCount() { return lightEntries.size(); }
        public Map<BlockLightKey, Integer> lightFingerprints() { return lightFingerprints; }

        public void putLight(BlockLightKey key, TerrainLight light) {
            TerrainLight previous = lights.put(key, light);
            if (previous == null) {
                lightEntries.add(light);
            } else {
                int index = lightEntries.indexOf(previous);
                if (index >= 0) {
                    lightEntries.set(index, light);
                } else {
                    lightEntries.add(light);
                }
            }
        }

        /** Removes one owned light without destroying its native resource. */
        public TerrainLight removeLight(BlockLightKey key) {
            TerrainLight removed = lights.remove(key);
            if (removed != null) lightEntries.remove(removed);
            lightFingerprints.remove(key);
            return removed;
        }

        /** Detaches every owned light so the caller can retire it incrementally. */
        public List<RemixLight> detachLights() {
            List<RemixLight> detached = new ArrayList<>(lightEntries.size());
            for (TerrainLight record : lightEntries) detached.add(record.light);
            clearLightOwnership();
            return detached;
        }

        private void clearLightOwnership() {
            lights.clear();
            lightEntries.clear();
            lightFingerprints.clear();
        }

    }

    /** Drops the owner's reference on a section payload; test fixtures pass plain objects. */
    private static void releaseData(Object data) {
        if (data instanceof SectionMeshData) {
            ((SectionMeshData) data).release();
        }
    }
    private int playerCx = Integer.MIN_VALUE;
    private int playerCz = Integer.MIN_VALUE;
    private int playerSy = Integer.MIN_VALUE;
    private final LongObjectMap<ChunkColumn> columnMap = new LongObjectMap<>();

    /** Smallest light cull radius: one chunk, the granularity of the column test. */
    public static final int MIN_LIGHT_CULL_BLOCKS = 16;

    public TerrainState(int lightCullBlocks) {
        this(lightCullBlocks, DEFAULT_FLOOD_SLICE_NANOS, DEFAULT_RECOMPUTE_COOLDOWN_NANOS);
    }

    /**
     * @param lightCullBlocks       initial radius in blocks; values below {@link #MIN_LIGHT_CULL_BLOCKS} clamp up
     * @param floodSliceNanos       render-thread budget per frame for a rebuild
     * @param recomputeCooldownNanos minimum gap between rebuild starts
     */
    public TerrainState(int lightCullBlocks, long floodSliceNanos, long recomputeCooldownNanos) {
        this.lightCullBlocks = Math.max(MIN_LIGHT_CULL_BLOCKS, lightCullBlocks);
        this.floodSliceNanos = floodSliceNanos;
        this.recomputeCooldownNanos = recomputeCooldownNanos;
        updateFloodRadius();
    }

    // --- accessors ---

    public int columnCount() { return columnMap.size(); }
    public int sectionCount() {
        int count = 0;
        for (ChunkColumn column : columnMap.values()) count += column.sectionCount();
        return count;
    }
    public Iterable<ChunkColumn> columns() { return columnMap.values(); }

    public ChunkColumn findColumn(int cx, int cz) {
        return columnMap.get(packKey(cx, cz));
    }

    /** Primitive snapshot used by bounded maintenance scans. */
    public long[] columnKeysSnapshot() {
        long[] out = new long[columnMap.size()];
        int index = 0;
        for (ChunkColumn col : columnMap.values()) {
            out[index++] = packKey(col.cx, col.cz);
        }
        return index == out.length ? out : Arrays.copyOf(out, index);
    }

    // --- section births ---

    /**
     * Adds or updates a section at (cx, sy, cz), creating its column when needed.
     *
     * @return the newly created {@link SectionEntry} if the section was added
     *         or replaced (caller should diff lights against the old entry
     *         returned via {@link #getReplacedEntry}), or {@code null} if the
     *         section was deduplicated by hash (no change).
     */
    public SectionEntry addSection(int cx, int sy, int cz, int dataHash, Object data) {
        return addSection(cx, sy, cz, dataHash, data,
                -1L, -1L, -1L, -1L, -1L, -1L, null);
    }

    /**
     * Adds or updates a section with per-face openness masks for
     * 3D sub-region connectivity culling (vertical + horizontal).
     */
    public SectionEntry addSection(int cx, int sy, int cz, int dataHash, Object data,
                                    long topFaceMask, long bottomFaceMask,
                                    long eastFaceMask, long westFaceMask,
                                    long southFaceMask, long northFaceMask,
                                    long[] opacityBitmap) {
        ChunkColumn col = getOrCreateColumn(cx, cz);

        SectionEntry existing = col.section(sy);
        if (existing != null && existing.dataHash == dataHash) {
            releaseData(data);
            return null;
        }

        SectionEntry newEntry = new SectionEntry(dataHash, cx * 16, sy * 16, cz * 16, data,
                                                   topFaceMask, bottomFaceMask,
                                                  eastFaceMask, westFaceMask,
                                                  southFaceMask, northFaceMask,
                                                  opacityBitmap);
        lastReplacedEntry = col.putSection(sy, newEntry);

        // Incremental reachability update:
        // If sealing changed in a way that might disconnect sections, mark dirty.
        // Otherwise, try a cheap local flood to expand reachability.
        if (cx == playerCx && cz == playerCz && sy == playerSy) {
            // Player's own section added/updated: always recompute so the
            // constrained flood fill uses the latest opacity bitmap.
            reachabilityDirty = true;
        } else if (reachabilityIncomplete) {
            // Previous BFS was incomplete (player section was missing), so
            // force a full recompute now that more data is available.
            reachabilityDirty = true;
        } else if (existing != null && (
                (existing.topFaceMask & ~topFaceMask) != 0 ||
                (existing.bottomFaceMask & ~bottomFaceMask) != 0 ||
                (existing.eastFaceMask & ~eastFaceMask) != 0 ||
                (existing.westFaceMask & ~westFaceMask) != 0 ||
                (existing.southFaceMask & ~southFaceMask) != 0 ||
                (existing.northFaceMask & ~northFaceMask) != 0)) {
            // A sub-region that was open is now sealed, so it might disconnect.
            reachabilityDirty = true;
        } else if (!reachabilityDirty) {
            expandReachability(cx, cz, sy);
        }

        return newEntry;
    }

    private SectionEntry lastReplacedEntry;

    /**
     * Returns the old entry replaced by the most recent {@link #addSection}
     * call, or {@code null} if it was a fresh add. Only valid immediately
     * after {@code addSection} returns non-null.
     */
    public SectionEntry getReplacedEntry() { return lastReplacedEntry; }

    /** True only while {@code entry} is still the live owner at this section coordinate. */
    public boolean isCurrentSectionEntry(int cx, int sy, int cz, SectionEntry entry) {
        ChunkColumn col = columnMap.get(packKey(cx, cz));
        return col != null && col.section(sy) == entry;
    }

    /** Returns the current section owner at a coordinate, or {@code null}. */
    public SectionEntry findSection(int cx, int sy, int cz) {
        ChunkColumn col = columnMap.get(packKey(cx, cz));
        return col != null ? col.section(sy) : null;
    }

    /** Applies a committed light-count delta only to the current section incarnation. */
    public boolean adjustSectionLightCount(int cx, int sy, int cz,
                                           SectionEntry entry, int delta) {
        if (delta == 0) return true;
        ChunkColumn col = columnMap.get(packKey(cx, cz));
        if (col == null || col.section(sy) != entry) return false;
        col.lightCount = Math.max(0, col.lightCount + delta);
        return true;
    }

    /** Removes a section and transfers its native lights to the caller. */
    public List<RemixLight> removeSectionForRetirement(int cx, int sy, int cz) {
        ChunkColumn col = columnMap.get(packKey(cx, cz));
        if (col == null) return Collections.emptyList();

        SectionEntry removed = col.section(sy);
        List<RemixLight> retired = Collections.emptyList();
        if (removed != null) {
            int removedLights = removed.lightCount();
            retired = removed.detachLights();
            col.removeSection(sy);
            col.lightCount -= removedLights;
            reachabilityDirty = true;  // removal might disconnect sections
        }
        if (col.isEmpty()) removeColumnForRetirement(cx, cz);
        return retired;
    }

    /** Removes a column and transfers its native lights to the caller. */
    public List<RemixLight> removeColumnForRetirement(int cx, int cz) {
        long key = packKey(cx, cz);
        ChunkColumn col = columnMap.get(key);
        if (col == null) return Collections.emptyList();

        List<RemixLight> retired = col.detachSectionLights();
        columnMap.remove(key);
        reachabilityDirty = true;  // removal might disconnect sections
        return retired;
    }

    // --- player position ---

    public void setPlayerPos(int cx, int cz) {
        playerCx = cx;
        playerCz = cz;
    }

    /** Player's exact world-block coordinates, for intra-section flood fill. */
    private int playerBlockX = Integer.MIN_VALUE;
    private int playerBlockY = Integer.MIN_VALUE;
    private int playerBlockZ = Integer.MIN_VALUE;
    private SectionEntry cachedSeedSection;
    private final long[] cachedSeedFaces = new long[6];
    private boolean cachedSeedFacesValid;

    /**
     * Sets the player's exact block position. Derives the section Y
     * coordinate and marks reachability dirty when the player moves to
     * a different 2-block sub-region (matching the face-mask resolution).
     */
    public void setPlayerBlockPos(int bx, int by, int bz) {
        // Only re-evaluate when the player crosses a 2-block sub-region boundary
        if ((bx >> 1) == (playerBlockX >> 1) &&
            (by >> 1) == (playerBlockY >> 1) &&
            (bz >> 1) == (playerBlockZ >> 1)) {
            playerBlockX = bx; playerBlockY = by; playerBlockZ = bz;
            return;
        }

        boolean sectionChanged = playerBlockX == Integer.MIN_VALUE
                || (bx >> 4) != (playerBlockX >> 4)
                || (by >> 4) != (playerBlockY >> 4)
                || (bz >> 4) != (playerBlockZ >> 4);
        playerBlockX = bx;
        playerBlockY = by;
        playerBlockZ = bz;
        playerSy = by >> 4;

        if (sectionChanged) {
            cachedSeedFacesValid = false;
            reachabilityDirty = true;
            return;
        }
        if (reachabilityDirty || hasSameSeedConnectivity()) {
            return;
        }
        reachabilityDirty = true;
    }

    /** True when movement stayed in a local component with identical exits from the player section. */
    private boolean hasSameSeedConnectivity() {
        if (!cachedSeedFacesValid) return false;
        ChunkColumn column = columnMap.get(packKey(playerCx, playerCz));
        SectionEntry section = column != null ? column.section(playerSy) : null;
        if (section == null || section != cachedSeedSection || section.opacityBitmap == null) {
            return false;
        }
        long[] faces = localFloodFill(section.opacityBitmap,
                playerBlockX & 15, playerBlockY & 15, playerBlockZ & 15);
        return Arrays.equals(cachedSeedFaces, faces);
    }

    /** Creates a deterministic section snapshot for one chunk column. */
    public List<TerrainSectionSnapshot> createSnapshot(ChunkColumn column) {
        List<SectionEntry> entries = new ArrayList<>(column.sectionEntries());
        entries.sort(Comparator.comparingInt(entry -> entry.sy));
        List<TerrainSectionSnapshot> out = new ArrayList<>(entries.size());
        for (SectionEntry entry : entries) {
            if (entry.data != null) {
                out.add(new TerrainSectionSnapshot(
                        entry.xOff, entry.yOff, entry.zOff, entry.data));
            }
        }
        return out;
    }

    /** Light cull distance in blocks (XZ Chebyshev, Y via BFS). Never below {@link #MIN_LIGHT_CULL_BLOCKS}. */
    private int lightCullBlocks;

    /**
     * Radius in chunks of the box the reachability flood is allowed to cover
     * = lightCullBlocks >> 4.
     *
     * <p>This is a <i>spatial</i> bound, not a path-length budget: every cull
     * query rejects by Chebyshev chunk distance, so the flood has to reach the
     * corners of that same box. Bounding by BFS step count instead would only
     * cover the inscribed diamond ({@code |dx| + |dz| <= radius}) and cull the
     * rest of the box, worst along the diagonals at 0.707x the cull radius.
     */
    private int floodRadiusChunks;

    // --- flood scheduling ---
    /** Render-thread budget for one slice of a full flood rebuild. */
    private static final long DEFAULT_FLOOD_SLICE_NANOS =
            Long.getLong("rtxremix.light.floodSliceNs", 500_000L);
    /**
     * Minimum gap between two rebuilds. Stale data for a few frames is
     * imperceptible: the Chebyshev XZ test already prevents hard culling
     * artefacts, and Y refinement is approximate.
     */
    private static final long DEFAULT_RECOMPUTE_COOLDOWN_NANOS = 200_000_000L;
    /**
     * Dequeues between deadline checks, so every slice makes progress. Must
     * be a power of two: used as a mask, not a modulus.
     */
    private static final int FLOOD_DEADLINE_CHECK_INTERVAL = 64;

    private final long floodSliceNanos;
    private final long recomputeCooldownNanos;
    /** {@link System#nanoTime()} when the last rebuild started. */
    private long lastRecomputeNanos;
    /** Render-thread time spent in incremental floods since the last {@link #takeIncrementalFloodNanos()}. */
    private long incrementalFloodNanos;

    // --- Zero-alloc section-level BFS queue ---
    /** Flat int buffer: 4 ints per entry (cx, cz, sy, dist). Grows as needed;
     *  never shrinks. Reused across frames to avoid GC churn. */
    private int[] bfsQBuf = new int[4 * 8192];
    private int bfsQHead, bfsQTail;

    private void bfsQReset() { bfsQHead = bfsQTail = 0; }

    private void bfsQEnqueue(int cx, int cz, int sy, int dist) {
        int i = bfsQTail * 4;
        if (i + 4 > bfsQBuf.length)
            bfsQBuf = Arrays.copyOf(bfsQBuf, bfsQBuf.length * 2);
        bfsQBuf[i] = cx; bfsQBuf[i + 1] = cz; bfsQBuf[i + 2] = sy; bfsQBuf[i + 3] = dist;
        bfsQTail++;
    }

    private void updateFloodRadius() {
        int oldRadius = floodRadiusChunks;
        floodRadiusChunks = lightCullBlocks >> 4;
        if (floodRadiusChunks != oldRadius) reachabilityDirty = true;
    }

    /**
     * True when a section sits inside the flood box around the player, i.e.
     * within {@link #floodRadiusChunks} on each of the three axes.
     */
    private boolean withinFloodRadius(int cx, int cz, int sy) {
        if (playerCx == Integer.MIN_VALUE) return true;
        if (Math.abs(cx - playerCx) > floodRadiusChunks) return false;
        if (Math.abs(cz - playerCz) > floodRadiusChunks) return false;
        return playerSy == Integer.MIN_VALUE
                || Math.abs(sy - playerSy) <= floodRadiusChunks;
    }

    /** Sets the light cull distance in blocks at runtime; values below one chunk clamp up. */
    public void setLightCullBlocks(int blocks) {
        this.lightCullBlocks = Math.max(MIN_LIGHT_CULL_BLOCKS, blocks);
        int previousRadius = floodRadiusChunks;
        updateFloodRadius();
        // A build in flight was seeded and sliced against the old radius; publishing it
        // would mix two radii in one set. Drop it so the next ensureReachability reseeds
        // against the new one.
        if (floodRadiusChunks != previousRadius && floodBuildInFlight) {
            floodBuildInFlight = false;
            bfsQReset();
        }
    }

    public int getLightCullBlocks() { return lightCullBlocks; }

    /**
     * Published reachable set: (cx, cz, sy) section keys to BFS distance from
     * the player seed. Queries read only this map. A rebuild fills
     * {@link #buildingReachable} across frames and swaps it in when complete.
     *
     * <p>Primitive long-to-int map: the flood rebuilds this with thousands of
     * entries and boxing would dominate.
     */
    private LongIntMap reachable3D = new LongIntMap();
    /** Column keys with at least one reachable section, for the incomplete-seed fallback. */
    private LongHashSet bfsCoveredColumns = new LongHashSet();
    private LongIntMap buildingReachable = new LongIntMap();
    private LongHashSet buildingColumns = new LongHashSet();
    private boolean floodBuildInFlight;
    /** Whether the build in flight was seeded without the player's own section data. */
    private boolean buildingIncomplete;

    /** Set by removals, boundary closures and player moves; cleared when a rebuild starts. */
    private boolean reachabilityDirty = true;
    /** Cleared each frame by {@link #ensureReachabilityOnce()}. */
    private boolean reachabilityEnsured = false;

    /** True when the last published build was seeded without the player's section (data still loading). */
    private boolean reachabilityIncomplete = false;

    /**
     * Per-frame entry: advances a rebuild in flight by one budget slice, or
     * starts one when the set is dirty and the cooldown has passed. Queries
     * later in the frame skip the dirty check.
     */
    public void ensureReachabilityOnce() {
        reachabilityEnsured = false;
        ensureReachability();
    }

    private void ensureReachability() {
        if (reachabilityEnsured) return;
        reachabilityEnsured = true;
        if (floodBuildInFlight) {
            continueFloodBuild();
            return;
        }
        if (!reachabilityDirty) return;
        long now = System.nanoTime();
        if (!reachable3D.isEmpty() && now - lastRecomputeNanos < recomputeCooldownNanos) {
            return; // dirty stays set, retried next frame
        }
        startFloodBuild(now);
        if (floodBuildInFlight) continueFloodBuild();
    }

    /** True while a rebuild is being filled across frames. */
    public boolean isFloodBuildInFlight() {
        return floodBuildInFlight;
    }

    /** Returns and resets the render-thread time incremental floods used since the last call. */
    public long takeIncrementalFloodNanos() {
        long nanos = incrementalFloodNanos;
        incrementalFloodNanos = 0;
        return nanos;
    }

    /**
     * Seeds a rebuild into the building set from the player's section:
     * constrained through the faces a local flood can reach when the section
     * and its opacity data exist, unconstrained from the nearest section when
     * they do not, and empty (draw everything) when the column is not loaded.
     */
    private void startFloodBuild(long now) {
        reachabilityDirty = false;
        lastRecomputeNanos = now;
        buildingReachable.clear();
        buildingColumns.clear();
        cachedSeedFacesValid = false;
        cachedSeedSection = null;
        bfsQReset();
        floodBuildInFlight = true;
        buildingIncomplete = seedFloodBuild();
        if (bfsQHead == bfsQTail) publishFloodBuild();
    }

    /** @return whether the seed was incomplete (player section or column missing) */
    private boolean seedFloodBuild() {
        if (playerSy == Integer.MIN_VALUE || columnMap.isEmpty()) return true;

        ChunkColumn startCol = columnMap.get(packKey(playerCx, playerCz));
        SectionEntry se = (startCol != null) ? startCol.section(playerSy) : null;

        if (se != null && se.opacityBitmap != null && playerBlockX != Integer.MIN_VALUE) {
            long key = pack3DKey(playerCx, playerCz, playerSy);
            markReachable(buildingReachable, buildingColumns, key, playerCx, playerCz, playerSy, 0);

            int lx = playerBlockX & 15;
            int ly = playerBlockY & 15;
            int lz = playerBlockZ & 15;
            long[] reachableFaces = localFloodFill(se.opacityBitmap, lx, ly, lz);
            System.arraycopy(reachableFaces, 0, cachedSeedFaces, 0, cachedSeedFaces.length);
            cachedSeedSection = se;
            cachedSeedFacesValid = true;
            seedFromConstrainedFaces(startCol, playerSy, se, reachableFaces);
            return false;
        }
        if (startCol != null && !startCol.isEmpty()) {
            int seedSy = clampSy(startCol, playerSy);
            long key = pack3DKey(playerCx, playerCz, seedSy);
            markReachable(buildingReachable, buildingColumns, key, playerCx, playerCz, seedSy, 0);
            bfsQEnqueue(playerCx, playerCz, seedSy, 0);
            return true;
        }
        return true;
    }

    private void continueFloodBuild() {
        bfsFlood(buildingReachable, buildingColumns, System.nanoTime() + floodSliceNanos);
        if (bfsQHead == bfsQTail) publishFloodBuild();
    }

    /** Swaps the completed build in; the old published maps become the next build's scratch. */
    private void publishFloodBuild() {
        LongIntMap finishedMap = buildingReachable;
        buildingReachable = reachable3D;
        reachable3D = finishedMap;
        LongHashSet finishedColumns = buildingColumns;
        buildingColumns = bfsCoveredColumns;
        bfsCoveredColumns = finishedColumns;
        reachabilityIncomplete = buildingIncomplete;
        floodBuildInFlight = false;
        bfsQReset();
    }

    /**
     * Seeds the BFS queue from adjacent sections using only the face
     * openings that the player can reach via local flood fill.
     * Enqueues directly into the instance-level {@link #bfsQBuf}.
     */
    private void seedFromConstrainedFaces(ChunkColumn col, int sy,
                                           SectionEntry se, long[] reachableFaces) {
        int cx = col.cx, cz = col.cz;

        // Top (y+1)
        long effTop = reachableFaces[0] & se.topFaceMask;
        if (effTop != 0) {
            SectionEntry above = col.section(sy + 1);
            if (above != null && (effTop & above.bottomFaceMask) != 0) {
                long k = pack3DKey(cx, cz, sy + 1);
                markReachable(buildingReachable, buildingColumns, k, cx, cz, sy + 1, 1);
                bfsQEnqueue(cx, cz, sy + 1, 1);
            }
        }

        // Bottom (y-1)
        long effBottom = reachableFaces[1] & se.bottomFaceMask;
        if (effBottom != 0) {
            SectionEntry below = col.section(sy - 1);
            if (below != null && (effBottom & below.topFaceMask) != 0) {
                long k = pack3DKey(cx, cz, sy - 1);
                markReachable(buildingReachable, buildingColumns, k, cx, cz, sy - 1, 1);
                bfsQEnqueue(cx, cz, sy - 1, 1);
            }
        }

        // East (cx+1)
        long effEast = reachableFaces[2] & se.eastFaceMask;
        if (effEast != 0) {
            ChunkColumn ecol = columnMap.get(packKey(cx + 1, cz));
            SectionEntry ese = (ecol != null) ? ecol.section(sy) : null;
            if (ese != null && (effEast & ese.westFaceMask) != 0) {
                long k = pack3DKey(cx + 1, cz, sy);
                markReachable(buildingReachable, buildingColumns, k, cx + 1, cz, sy, 1);
                bfsQEnqueue(cx + 1, cz, sy, 1);
            }
        }

        // West (cx-1)
        long effWest = reachableFaces[3] & se.westFaceMask;
        if (effWest != 0) {
            ChunkColumn wcol = columnMap.get(packKey(cx - 1, cz));
            SectionEntry wse = (wcol != null) ? wcol.section(sy) : null;
            if (wse != null && (effWest & wse.eastFaceMask) != 0) {
                long k = pack3DKey(cx - 1, cz, sy);
                markReachable(buildingReachable, buildingColumns, k, cx - 1, cz, sy, 1);
                bfsQEnqueue(cx - 1, cz, sy, 1);
            }
        }

        // South (cz+1)
        long effSouth = reachableFaces[4] & se.southFaceMask;
        if (effSouth != 0) {
            ChunkColumn scol = columnMap.get(packKey(cx, cz + 1));
            SectionEntry sse = (scol != null) ? scol.section(sy) : null;
            if (sse != null && (effSouth & sse.northFaceMask) != 0) {
                long k = pack3DKey(cx, cz + 1, sy);
                markReachable(buildingReachable, buildingColumns, k, cx, cz + 1, sy, 1);
                bfsQEnqueue(cx, cz + 1, sy, 1);
            }
        }

        // North (cz-1)
        long effNorth = reachableFaces[5] & se.northFaceMask;
        if (effNorth != 0) {
            ChunkColumn ncol = columnMap.get(packKey(cx, cz - 1));
            SectionEntry nse = (ncol != null) ? ncol.section(sy) : null;
            if (nse != null && (effNorth & nse.southFaceMask) != 0) {
                long k = pack3DKey(cx, cz - 1, sy);
                markReachable(buildingReachable, buildingColumns, k, cx, cz - 1, sy, 1);
                bfsQEnqueue(cx, cz - 1, sy, 1);
            }
        }
    }

    // --- Cached buffers for localFloodFill (reused across calls) ---
    private final long[] floodFaceMasks = new long[6];
    private final long[] floodVisited = new long[64];
    private final int[] floodQueue = new int[4096]; // max non-opaque blocks in 16 cubed

    /**
     * Block-level flood fill within a single 16x16x16 section from a
     * starting position.  Returns a 6-element array of 64-bit masks
     * indicating which face sub-regions (8x8 grid of 2x2 blocks) the
     * flood can reach.
     *
     * <p>Uses cached instance buffers to avoid ~16 KB of short-lived
     * allocations per recompute.
     *
     * @return {@code long[6]}: [top, bottom, east, west, south, north]
     */
    private long[] localFloodFill(long[] opacity, int startX, int startY, int startZ) {
        long[] faceMasks = floodFaceMasks;
        Arrays.fill(faceMasks, 0L);

        // Check if starting position itself is opaque
        int startBit = (startY << 8) | (startZ << 4) | startX;
        if ((opacity[startBit >> 6] & (1L << (startBit & 63))) != 0) {
            // Player inside opaque block, return all-open to be safe
            Arrays.fill(faceMasks, -1L);
            return faceMasks;
        }

        // BFS through non-opaque blocks within the 16x16x16 section.
        // Encode (x,y,z) as a single short: (y<<8)|(z<<4)|x to avoid int[] allocs.
        long[] visited = floodVisited;
        Arrays.fill(visited, 0L);
        visited[startBit >> 6] |= 1L << (startBit & 63);
        int[] floodQueue = this.floodQueue;
        int head = 0, tail = 0;
        floodQueue[tail++] = (startY << 8) | (startZ << 4) | startX;

        while (head < tail) {
            int encoded = floodQueue[head++];
            int x = encoded & 0xF;
            int z = (encoded >> 4) & 0xF;
            int y = (encoded >> 8) & 0xF;

            // Record face sub-region hits (same mapping as BlockQuadEmitter)
            if (y == 15) faceMasks[0] |= 1L << ((z >> 1) * 8 + (x >> 1));
            if (y == 0)  faceMasks[1] |= 1L << ((z >> 1) * 8 + (x >> 1));
            if (x == 15) faceMasks[2] |= 1L << ((y >> 1) * 8 + (z >> 1));
            if (x == 0)  faceMasks[3] |= 1L << ((y >> 1) * 8 + (z >> 1));
            if (z == 15) faceMasks[4] |= 1L << ((y >> 1) * 8 + (x >> 1));
            if (z == 0)  faceMasks[5] |= 1L << ((y >> 1) * 8 + (x >> 1));

            // 6-connected neighbours
            if (x < 15) { int nb = encoded + 1;      int idx = nb >> 6; long m = 1L << (nb & 63); if ((visited[idx] & m) == 0 && (opacity[idx] & m) == 0) { visited[idx] |= m; floodQueue[tail++] = nb; } }
            if (x > 0)  { int nb = encoded - 1;      int idx = nb >> 6; long m = 1L << (nb & 63); if ((visited[idx] & m) == 0 && (opacity[idx] & m) == 0) { visited[idx] |= m; floodQueue[tail++] = nb; } }
            if (z < 15) { int nb = encoded + 16;     int idx = nb >> 6; long m = 1L << (nb & 63); if ((visited[idx] & m) == 0 && (opacity[idx] & m) == 0) { visited[idx] |= m; floodQueue[tail++] = nb; } }
            if (z > 0)  { int nb = encoded - 16;     int idx = nb >> 6; long m = 1L << (nb & 63); if ((visited[idx] & m) == 0 && (opacity[idx] & m) == 0) { visited[idx] |= m; floodQueue[tail++] = nb; } }
            if (y < 15) { int nb = encoded + 256;    int idx = nb >> 6; long m = 1L << (nb & 63); if ((visited[idx] & m) == 0 && (opacity[idx] & m) == 0) { visited[idx] |= m; floodQueue[tail++] = nb; } }
            if (y > 0)  { int nb = encoded - 256;    int idx = nb >> 6; long m = 1L << (nb & 63); if ((visited[idx] & m) == 0 && (opacity[idx] & m) == 0) { visited[idx] |= m; floodQueue[tail++] = nb; } }
        }

        return faceMasks;
    }

    /**
     * Incremental local flood on the published set when a section is added:
     * if it connects to a reachable neighbour, add it and flood outward.
     *
     * <p>While a rebuild is in flight the queue and the scratch set belong to
     * that build, so the add only marks the set dirty; the follow-up rebuild
     * picks the section up. Expanding the published set instead would be
     * undone at publish and flicker the light back off.
     */
    private void expandReachability(int cx, int cz, int sy) {
        if (floodBuildInFlight) {
            reachabilityDirty = true;
            return;
        }
        long key = pack3DKey(cx, cz, sy);
        int existingDist = reachable3D.getOrDefault(key, -1);
        if (existingDist >= 0) {
            long start = System.nanoTime();
            bfsQReset();
            bfsQEnqueue(cx, cz, sy, existingDist);
            bfsFlood(reachable3D, bfsCoveredColumns, Long.MAX_VALUE);
            incrementalFloodNanos += System.nanoTime() - start;
            return;
        }

        int neighborDist = minNeighborDistance(cx, cz, sy);
        if (neighborDist >= 0 && withinFloodRadius(cx, cz, sy)) {
            long start = System.nanoTime();
            int dist = neighborDist + 1;
            markReachable(reachable3D, bfsCoveredColumns, key, cx, cz, sy, dist);
            bfsQReset();
            bfsQEnqueue(cx, cz, sy, dist);
            bfsFlood(reachable3D, bfsCoveredColumns, Long.MAX_VALUE);
            incrementalFloodNanos += System.nanoTime() - start;
        }
    }

    /**
     * Returns the minimum BFS distance among reachable vertical or horizontal
     * neighbours of (cx, cz, sy), or -1 if none is reachable.
     */
    private int minNeighborDistance(int cx, int cz, int sy) {
        int best = -1;
        ChunkColumn col = columnMap.get(packKey(cx, cz));
        SectionEntry se = null;

        // Vertical neighbours (check boundary openness)
        if (col != null) {
            se = col.section(sy);
            SectionEntry above = col.section(sy + 1);
            if (above != null && yBoundaryOpen(se, above)) {
                int d = reachable3D.getOrDefault(pack3DKey(cx, cz, sy + 1), -1);
                if (d >= 0 && (best < 0 || d < best)) best = d;
            }
            SectionEntry below = col.section(sy - 1);
            if (below != null && yBoundaryOpen(below, se)) {
                int d = reachable3D.getOrDefault(pack3DKey(cx, cz, sy - 1), -1);
                if (d >= 0 && (best < 0 || d < best)) best = d;
            }
        }

        // Horizontal neighbours (check face sealing)
        for (int[] off : HORIZONTAL_OFFSETS) {
            int ncx = cx + off[0], ncz = cz + off[1];
            ChunkColumn ncol = columnMap.get(packKey(ncx, ncz));
            if (ncol != null) {
                SectionEntry nse = ncol.section(sy);
                if (nse == null || !hBoundaryOpen(se, nse, off[0], off[1])) continue;
                int d = reachable3D.getOrDefault(pack3DKey(ncx, ncz, sy), -1);
                if (d >= 0 && (best < 0 || d < best)) best = d;
            }
        }

        return best;
    }

    /** Registers a section in one reachable set and tracks its column coverage. */
    private static void markReachable(LongIntMap into, LongHashSet coveredColumns,
                                      long key3D, int cx, int cz, int sy, int dist) {
        into.put(key3D, dist);
        coveredColumns.add(packKey(cx, cz));
    }

    /**
     * Core BFS loop shared by rebuilds and incremental expansion. Drains the
     * queue into {@code into} until it empties or {@code deadline} passes,
     * checking the clock every {@link #FLOOD_DEADLINE_CHECK_INTERVAL} dequeues
     * so a slice always advances. Stops at the edge of the floodRadiusChunks box.
     *
     * <p>Zero-allocation: the flat {@code int[]} queue persists between frames.
     */
    private void bfsFlood(LongIntMap into, LongHashSet coveredColumns, long deadline) {
        int processed = 0;
        while (bfsQHead < bfsQTail) {
            if ((++processed & (FLOOD_DEADLINE_CHECK_INTERVAL - 1)) == 0
                    && System.nanoTime() >= deadline) {
                return;
            }
            int qi = bfsQHead++ * 4;
            int cx = bfsQBuf[qi], cz = bfsQBuf[qi + 1], sy = bfsQBuf[qi + 2];
            int dist = bfsQBuf[qi + 3];
            int nextDist = dist + 1;

            ChunkColumn col = columnMap.get(packKey(cx, cz));
            if (col == null) continue;
            SectionEntry curSe = col.section(sy);

            SectionEntry upSe = col.section(sy + 1);
            if (upSe != null && yBoundaryOpen(curSe, upSe) && withinFloodRadius(cx, cz, sy + 1)) {
                long upKey = pack3DKey(cx, cz, sy + 1);
                int prev = into.getOrDefault(upKey, -1);
                if (prev < 0 || nextDist < prev) {
                    markReachable(into, coveredColumns, upKey, cx, cz, sy + 1, nextDist);
                    bfsQEnqueue(cx, cz, sy + 1, nextDist);
                }
            }
            SectionEntry dnSe = col.section(sy - 1);
            if (dnSe != null && yBoundaryOpen(dnSe, curSe) && withinFloodRadius(cx, cz, sy - 1)) {
                long dnKey = pack3DKey(cx, cz, sy - 1);
                int prev = into.getOrDefault(dnKey, -1);
                if (prev < 0 || nextDist < prev) {
                    markReachable(into, coveredColumns, dnKey, cx, cz, sy - 1, nextDist);
                    bfsQEnqueue(cx, cz, sy - 1, nextDist);
                }
            }

            for (int[] d : HORIZONTAL_OFFSETS) {
                int ncx = cx + d[0], ncz = cz + d[1];
                if (!withinFloodRadius(ncx, ncz, sy)) continue;
                ChunkColumn ncol = columnMap.get(packKey(ncx, ncz));
                if (ncol == null) continue;
                SectionEntry nse = ncol.section(sy);
                if (nse == null) continue;
                if (!hBoundaryOpen(curSe, nse, d[0], d[1])) continue;
                long nKey = pack3DKey(ncx, ncz, sy);
                int prev = into.getOrDefault(nKey, -1);
                if (prev < 0 || nextDist < prev) {
                    markReachable(into, coveredColumns, nKey, ncx, ncz, sy, nextDist);
                    bfsQEnqueue(ncx, ncz, sy, nextDist);
                }
            }
        }
    }

    private static final int[][] HORIZONTAL_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * Returns true if the horizontal boundary between two adjacent sections
     * has at least one 2x2 sub-region that is open on both sides.
     * {@code dx} and {@code dz} describe the step from source to target
     * (exactly one is +/-1, the other 0).
     *
     * <p>Missing sections (null) default to all-bits-set (fully open).
     */
    private static boolean hBoundaryOpen(SectionEntry src, SectionEntry tgt, int dx, int dz) {
        long srcMask, tgtMask;
        if (dx == 1)       { srcMask = (src != null) ? src.eastFaceMask  : -1L;
                             tgtMask = (tgt != null) ? tgt.westFaceMask  : -1L; }
        else if (dx == -1) { srcMask = (src != null) ? src.westFaceMask  : -1L;
                             tgtMask = (tgt != null) ? tgt.eastFaceMask  : -1L; }
        else if (dz == 1)  { srcMask = (src != null) ? src.southFaceMask : -1L;
                             tgtMask = (tgt != null) ? tgt.northFaceMask : -1L; }
        else               { srcMask = (src != null) ? src.northFaceMask : -1L;
                             tgtMask = (tgt != null) ? tgt.southFaceMask : -1L; }
        return (srcMask & tgtMask) != 0;
    }

    /**
     * Hybrid culling for light queries:
     * <ul>
     *   <li><b>XZ plane</b>: Chebyshev distance &le; {@code cullBlocks >> 4} chunks</li>
     *   <li><b>Y axis</b>: absolute section distance &le; {@code cullBlocks >> 4},
     *       AND BFS reachability (connectivity) to filter out sealed-off sections.
     *       If the BFS never reached any section in the column (e.g. air gaps
     *       above ground), the Y distance check alone is used.</li>
     * </ul>
     *
     * @param cullBlocks distance limit in blocks, at least one chunk.
     */
    private boolean isSectionReachableHybrid(int cx, int cz, int sy, int cullBlocks) {
        if (playerCx == Integer.MIN_VALUE) return true;

        // XZ: Chebyshev distance check (convert blocks to chunks)
        int maxChunks = cullBlocks >> 4;
        int dx = Math.abs(cx - playerCx);
        int dz = Math.abs(cz - playerCz);
        if (Math.max(dx, dz) > maxChunks) return false;

        // Y: distance check, section must be within range of player's Y
        if (playerSy != Integer.MIN_VALUE) {
            int dy = Math.abs(sy - playerSy);
            if (dy > maxChunks) return false;
        }

        // Y: BFS reachability within the column (connectivity filter)
        if (playerSy == Integer.MIN_VALUE) return true;
        ensureReachability();
        if (reachable3D.isEmpty()) return true; // BFS not seeded

        // If this section is directly BFS-reachable, show it
        if (reachable3D.containsKey(pack3DKey(cx, cz, sy))) return true;

        // A column the published flood never reached is drawn while the seed
        // was incomplete or while a rebuild that will cover it is still in
        // flight, so a column loading mid-build stays lit instead of going
        // dark for the build's duration. A covered column whose section was
        // not reached stays culled either way, so sealed caves keep culling
        // during builds.
        if (!bfsCoveredColumns.contains(packKey(cx, cz))) {
            return reachabilityIncomplete || floodBuildInFlight;
        }

        // Column was BFS-covered but this specific section wasn't
        // reached, so cull it (below/above sealed boundary).
        return false;
    }

    /** Returns false when an entire chunk column is outside the light cull XZ range. */
    public boolean isColumnWithinLightCullRange(int cx, int cz) {
        if (playerCx == Integer.MIN_VALUE) return true;
        int maxChunks = lightCullBlocks >> 4;
        return Math.max(Math.abs(cx - playerCx), Math.abs(cz - playerCz)) <= maxChunks;
    }

    /** Whether a section is close and connected enough to keep its lights. */
    public boolean isSectionReachableForLights(int cx, int cz, int sy) {
        return isSectionReachableHybrid(cx, cz, sy, lightCullBlocks);
    }

    /** Returns the number of sections currently in the reachable set. */
    public int reachableCount() {
        ensureReachability();
        return reachable3D.size();
    }

    private static boolean yBoundaryOpen(SectionEntry lowerSe, SectionEntry upperSe) {
        long lowerTopMask = (lowerSe != null) ? lowerSe.topFaceMask : -1L;
        long upperBottomMask = (upperSe != null) ? upperSe.bottomFaceMask : -1L;

        return (lowerTopMask & upperBottomMask) != 0;
    }

    private static int clampSy(ChunkColumn col, int target) {
        int minSy = Integer.MAX_VALUE, maxSy = Integer.MIN_VALUE;
        List<SectionEntry> entries = col.sectionEntries();
        for (int i = 0, count = entries.size(); i < count; i++) {
            int sy = entries.get(i).sy;
            if (sy < minSy) minSy = sy;
            if (sy > maxSy) maxSy = sy;
        }
        return Math.max(minSy, Math.min(maxSy, target));
    }

    /**
     * Packs (cx, cz, sy) into a single long for the reachability set.
     * cx: bits [40..63] (24 bits, +/-8M), cz: bits [16..39] (24 bits),
     * sy: bits [0..15] (16 bits, +/-32K).
     */
    private static long pack3DKey(int cx, int cz, int sy) {
        return ((long)(cx & 0xFFFFFFL) << 40)
             | ((long)(cz & 0xFFFFFFL) << 16)
             | (sy & 0xFFFFL);
    }

    /** Clears logical terrain ownership and returns lights for budgeted retirement. */
    public List<RemixLight> clearForRetirement() {
        List<RemixLight> retired = new ArrayList<>();
        for (ChunkColumn col : columnMap.values()) {
            for (SectionEntry section : col.sectionEntries()) {
                for (TerrainLight record : section.lightEntries) retired.add(record.light);
            }
        }
        for (ChunkColumn col : columnMap.values()) {
            col.clearSections();
        }
        columnMap.clear();
        reachable3D.clear();
        bfsCoveredColumns.clear();
        buildingReachable.clear();
        buildingColumns.clear();
        floodBuildInFlight = false;
        buildingIncomplete = false;
        incrementalFloodNanos = 0;
        bfsQReset();
        reachabilityDirty = true;
        reachabilityEnsured = false;
        reachabilityIncomplete = false;
        lastRecomputeNanos = 0;
        playerCx = Integer.MIN_VALUE;
        playerCz = Integer.MIN_VALUE;
        playerSy = Integer.MIN_VALUE;
        playerBlockX = Integer.MIN_VALUE;
        playerBlockY = Integer.MIN_VALUE;
        playerBlockZ = Integer.MIN_VALUE;
        cachedSeedSection = null;
        cachedSeedFacesValid = false;
        return retired;
    }

    private ChunkColumn getOrCreateColumn(int cx, int cz) {
        long key = packKey(cx, cz);
        ChunkColumn column = columnMap.get(key);
        if (column != null) return column;

        column = new ChunkColumn(cx, cz);
        columnMap.put(key, column);
        return column;
    }

    public static long packKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
