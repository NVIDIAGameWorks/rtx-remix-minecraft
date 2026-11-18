/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Preserves insertion order while using coarse cells to avoid redundant
 * per-entry frustum tests.
 *
 * <p>Entries are bucketed by the cell holding their minimum corner, and each cell caches the
 * union of its members' bounds. An entry whose geometry moves after indexing is re-bucketed by
 * its slot through {@link #rebucket}, so keeping the index exact after a move costs one bounds
 * read per moved entry rather than one per indexed entry.
 */
public final class HierarchicalFrustumCuller<T> {
    /**
     * Classification values a {@link BoxClassifier} returns.
     *
     * <p>They are this class's own values, not JOML's: {@code
     * FrustumIntersection.intersectAab} reports an outside box as the
     * non-negative index of the plane that culled it, which collides with an
     * "inside" sentinel here. A classifier built on JOML maps the three
     * outcomes explicitly.
     */
    public static final int OUTSIDE = -1;
    public static final int INTERSECT = 0;
    public static final int INSIDE = 1;

    private static final int CELL_SIZE = 64;
    private static final int CELL_COORDINATE_BITS = 21;
    private static final int CELL_COORDINATE_MASK = (1 << CELL_COORDINATE_BITS) - 1;
    private static final int MIN_CELL_COORDINATE = -(1 << (CELL_COORDINATE_BITS - 1));
    private static final int MAX_CELL_COORDINATE = (1 << (CELL_COORDINATE_BITS - 1)) - 1;

    private final List<T> entries = new ArrayList<>();
    private final List<CellBounds> cells = new ArrayList<>();
    /** Cells from earlier fills, reused before any new one is made. */
    private final List<CellBounds> spareCells = new ArrayList<>();
    private final Long2IntOpenHashMap cellIndices = new Long2IntOpenHashMap();
    private int[] cellByEntry = new int[256];
    private int[] entryBySlot = new int[0];
    private int highestSlotUsed = -1;
    private byte[] classifications = new byte[64];
    private final double[] boundsScratch = new double[6];

    public HierarchicalFrustumCuller() {
        cellIndices.defaultReturnValue(-1);
    }

    /**
     * Forgets every entry and cell while keeping the arrays and cell objects, so a culler
     * filled once a walk can be refilled without growing from empty again. A build that
     * indexes ten thousand sections otherwise leaves the growth copies of three arrays and
     * a few hundred cells behind every time.
     */
    public void clear() {
        entries.clear();
        spareCells.addAll(cells);
        cells.clear();
        cellIndices.clear();
        if (highestSlotUsed >= 0) {
            Arrays.fill(entryBySlot, 0, highestSlotUsed + 1, -1);
            highestSlotUsed = -1;
        }
    }

    /**
     * Indexes an entry under the cell of its minimum corner.
     *
     * <p>Adding an entry whose slot is already indexed re-buckets the existing entry to the
     * given bounds instead of duplicating it: a section that was re-originned is reached again
     * by the graph through the same entry, at its new position.
     *
     * @param slot a non-negative identifier unique to the entry within this index; a later move
     *             of the entry is reported against it through {@link #rebucket}
     * @throws IllegalStateException if the slot is indexed for a different entry
     */
    public void add(T entry, int slot,
                    double minX, double minY, double minZ,
                    double maxX, double maxY, double maxZ) {
        ensureSlotCapacity(slot + 1);
        int existing = entryBySlot[slot];
        if (existing >= 0) {
            if (entries.get(existing) != entry) {
                throw new IllegalStateException("Slot " + slot + " is indexed for a different entry");
            }
            moveEntry(existing, minX, minY, minZ, maxX, maxY, maxZ);
            return;
        }

        int cellIndex = cellFor(cellKeyOf(minX, minY, minZ), minX, minY, minZ, maxX, maxY, maxZ);
        cells.get(cellIndex).include(minX, minY, minZ, maxX, maxY, maxZ);

        int entryIndex = entries.size();
        ensureEntryCapacity(entryIndex + 1);
        cellByEntry[entryIndex] = cellIndex;
        entryBySlot[slot] = entryIndex;
        if (slot > highestSlotUsed) {
            highestSlotUsed = slot;
        }
        entries.add(entry);
    }

    /** Number of indexed entries. */
    public int size() {
        return entries.size();
    }

    /**
     * Re-reads the live bounds of the entry indexed under {@code slot} and moves it to the cell
     * they now fall in.
     *
     * <p>The cell it leaves keeps its cached bounds, which stay a superset of the remaining
     * members' bounds, so classification remains conservative. A slot that is not indexed is
     * ignored: an unindexed entry is never emitted, so it has nothing to correct.
     */
    public void rebucket(int slot, EntryBounds<? super T> currentBounds) {
        if (slot < 0 || slot >= entryBySlot.length) {
            return;
        }
        int entryIndex = entryBySlot[slot];
        if (entryIndex >= 0) {
            rebucketEntry(entryIndex, currentBounds);
        }
    }

    /** Re-buckets every entry, for callers that cannot enumerate which slots moved. */
    public void rebucketAll(EntryBounds<? super T> currentBounds) {
        for (int i = 0; i < entries.size(); i++) {
            rebucketEntry(i, currentBounds);
        }
    }

    /**
     * Emits every visible entry to {@code consumer}, in insertion order.
     *
     * <p>Each coarse cell is classified once by {@code groupClassifier}. Entries
     * of an {@link #INSIDE} cell are emitted without any further test, entries of
     * an {@link #OUTSIDE} cell are skipped without any test, and only the entries
     * of an {@link #INTERSECT} cell reach the exact {@code entryTest}.
     *
     * <p>The index must be reconciled against any moves before this is called;
     * a cell's cached bounds are a superset of its members', so classification is
     * conservative but not exact.
     */
    public void forEachVisible(BoxClassifier groupClassifier,
                               Predicate<? super T> entryTest,
                               Consumer<? super T> consumer) {
        ensureClassificationCapacity(cells.size());
        for (int i = 0; i < cells.size(); i++) {
            CellBounds cell = cells.get(i);
            classifications[i] = (byte) groupClassifier.classify(
                    cell.minX, cell.minY, cell.minZ,
                    cell.maxX, cell.maxY, cell.maxZ);
        }

        for (int i = 0; i < entries.size(); i++) {
            T entry = entries.get(i);
            int classification = classifications[cellByEntry[i]];
            if (classification == INSIDE
                    || (classification == INTERSECT && entryTest.test(entry))) {
                consumer.accept(entry);
            }
        }
    }

    private void rebucketEntry(int entryIndex, EntryBounds<? super T> currentBounds) {
        double[] box = boundsScratch;
        currentBounds.read(entries.get(entryIndex), box);
        moveEntry(entryIndex, box[0], box[1], box[2], box[3], box[4], box[5]);
    }

    private void moveEntry(int entryIndex,
                           double minX, double minY, double minZ,
                           double maxX, double maxY, double maxZ) {
        long key = cellKeyOf(minX, minY, minZ);
        int cellIndex = cellByEntry[entryIndex];
        if (cells.get(cellIndex).key != key) {
            cellIndex = cellFor(key, minX, minY, minZ, maxX, maxY, maxZ);
            cellByEntry[entryIndex] = cellIndex;
        }
        cells.get(cellIndex).include(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private int cellFor(long key,
                        double minX, double minY, double minZ,
                        double maxX, double maxY, double maxZ) {
        int cellIndex = cellIndices.get(key);
        if (cellIndex < 0) {
            CellBounds cell;
            int spare = spareCells.size();
            if (spare > 0) {
                cell = spareCells.remove(spare - 1);
                cell.reset(key, minX, minY, minZ, maxX, maxY, maxZ);
            } else {
                cell = new CellBounds(key, minX, minY, minZ, maxX, maxY, maxZ);
            }
            cells.add(cell);
            cellIndex = cells.size() - 1;
            cellIndices.put(key, cellIndex);
        }
        return cellIndex;
    }

    private void ensureEntryCapacity(int required) {
        if (required <= cellByEntry.length) {
            return;
        }
        int[] replacement = new int[Math.max(required, cellByEntry.length * 2)];
        System.arraycopy(cellByEntry, 0, replacement, 0, entries.size());
        cellByEntry = replacement;
    }

    private void ensureSlotCapacity(int required) {
        if (required <= entryBySlot.length) {
            return;
        }
        int previousLength = entryBySlot.length;
        entryBySlot = Arrays.copyOf(entryBySlot, Math.max(required, Math.max(1024, previousLength * 2)));
        Arrays.fill(entryBySlot, previousLength, entryBySlot.length, -1);
    }

    private void ensureClassificationCapacity(int required) {
        if (required <= classifications.length) {
            return;
        }
        classifications = new byte[Math.max(required, classifications.length * 2)];
    }

    private static long cellKeyOf(double minX, double minY, double minZ) {
        return cellKey(cellCoordinate(minX), cellCoordinate(minY), cellCoordinate(minZ));
    }

    private static int cellCoordinate(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), CELL_SIZE);
    }

    private static long cellKey(int x, int y, int z) {
        if (x < MIN_CELL_COORDINATE || x > MAX_CELL_COORDINATE
                || y < MIN_CELL_COORDINATE || y > MAX_CELL_COORDINATE
                || z < MIN_CELL_COORDINATE || z > MAX_CELL_COORDINATE) {
            throw new IllegalArgumentException("Frustum cell coordinate outside supported world bounds");
        }
        return ((long) (x & CELL_COORDINATE_MASK) << (CELL_COORDINATE_BITS * 2))
                | ((long) (y & CELL_COORDINATE_MASK) << CELL_COORDINATE_BITS)
                | (z & CELL_COORDINATE_MASK);
    }

    @FunctionalInterface
    public interface BoxClassifier {
        int classify(double minX, double minY, double minZ,
                     double maxX, double maxY, double maxZ);
    }

    /** Reads an entry's current world bounds, avoiding an allocation per entry. */
    @FunctionalInterface
    public interface EntryBounds<T> {
        /** Writes minX, minY, minZ, maxX, maxY, maxZ into {@code out}. */
        void read(T entry, double[] out);
    }

    private static final class CellBounds {
        private long key;
        private double minX;
        private double minY;
        private double minZ;
        private double maxX;
        private double maxY;
        private double maxZ;

        private CellBounds(long key,
                           double minX, double minY, double minZ,
                           double maxX, double maxY, double maxZ) {
            reset(key, minX, minY, minZ, maxX, maxY, maxZ);
        }

        private void reset(long key,
                           double minX, double minY, double minZ,
                           double maxX, double maxY, double maxZ) {
            this.key = key;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        private void include(double minX, double minY, double minZ,
                             double maxX, double maxY, double maxZ) {
            this.minX = Math.min(this.minX, minX);
            this.minY = Math.min(this.minY, minY);
            this.minZ = Math.min(this.minZ, minZ);
            this.maxX = Math.max(this.maxX, maxX);
            this.maxY = Math.max(this.maxY, maxY);
            this.maxZ = Math.max(this.maxZ, maxZ);
        }
    }
}
