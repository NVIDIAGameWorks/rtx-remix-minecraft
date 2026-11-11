/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter.Decision;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BiConsumer;

import static com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter.COMPLETE;
import static com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter.DX;
import static com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter.DZ;
import static com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter.NEIGHBOUR_COUNT;
import static com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter.bit;
import static com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter.decideNeighbour;
import static com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter.mirroredIndex;
import static com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter.neighbourIndex;
import static com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter.sectionInRange;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeighbourReflagFilterTest {
    private static final int SECTIONS_PER_COLUMN = 24;
    private static final int FLAG_RUNNABLES_PER_FRAME = 10;
    private static final int ROW_HALF_WIDTH = 32;

    @Test
    void offsetTableHasEightDistinctNeighboursAndMirrorIsAnInvolution() {
        Set<Integer> offsets = new HashSet<>();
        for (int k = 0; k < NEIGHBOUR_COUNT; k++) {
            int dx = DX[k];
            int dz = DZ[k];
            assertTrue(Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && (dx != 0 || dz != 0), "k=" + k);
            assertTrue(offsets.add(dx * 3 + dz), "duplicate offset at k=" + k);
            assertEquals(k, neighbourIndex(dx, dz));
            int mirrored = mirroredIndex(k);
            assertEquals(-dx, DX[mirrored]);
            assertEquals(-dz, DZ[mirrored]);
            assertEquals(k, mirroredIndex(mirrored));
        }
        assertEquals(NEIGHBOUR_COUNT, offsets.size());
        assertThrows(IllegalArgumentException.class, () -> neighbourIndex(0, 0));
        assertThrows(IllegalArgumentException.class, () -> neighbourIndex(2, 0));
    }

    @Test
    void decideNeighbourTruthTable() {
        int loader = bit(3);
        assertEquals(Decision.SKIP_NOT_RESIDENT, decideNeighbour(false, false, 0, loader));
        assertEquals(Decision.SKIP_NOT_RESIDENT, decideNeighbour(false, true, COMPLETE, loader));
        assertEquals(Decision.SKIP_AWAITING_BUILD, decideNeighbour(true, true, 0, loader));
        assertEquals(Decision.SKIP_AWAITING_BUILD, decideNeighbour(true, true, COMPLETE, loader));
        assertEquals(Decision.DROP_IN_SNAPSHOT, decideNeighbour(true, false, loader, loader));
        assertEquals(Decision.FLAG, decideNeighbour(true, false, COMPLETE & ~loader, loader));
        assertEquals(Decision.FLAG, decideNeighbour(true, false, 0, loader));
        for (int k = 0; k < NEIGHBOUR_COUNT; k++) {
            assertEquals(Decision.DROP_IN_SNAPSHOT, decideNeighbour(true, false, COMPLETE, bit(k)));
        }
    }

    @Test
    void sectionInRangeIsHalfOpenOnTheStorageRows() {
        int bottom = -4;
        int sizeY = 24;
        assertFalse(sectionInRange(bottom - 1, bottom, sizeY));
        assertTrue(sectionInRange(bottom, bottom, sizeY));
        assertTrue(sectionInRange(bottom + sizeY - 1, bottom, sizeY));
        assertFalse(sectionInRange(bottom + sizeY, bottom, sizeY));
    }

    @Test
    void incompleteFirstBuildNeedsAFirstBuildBeyondNearRangeWithAMissingNeighbour() {
        assertFalse(NeighbourReflagFilter.isIncompleteFirstBuild(false, 1.0e6, 0));
        assertFalse(NeighbourReflagFilter.isIncompleteFirstBuild(true, 576.0, 0));
        assertFalse(NeighbourReflagFilter.isIncompleteFirstBuild(true, 1.0e6, COMPLETE));
        assertTrue(NeighbourReflagFilter.isIncompleteFirstBuild(true, 576.5, COMPLETE & ~bit(0)));
    }

    @Test
    void enabledDefaultsToTrueAndHonoursAnExplicitFalse() {
        assertTrue(NeighbourReflagFilter.enabledFrom(null));
        assertTrue(NeighbourReflagFilter.enabledFrom("true"));
        assertFalse(NeighbourReflagFilter.enabledFrom("false"));
    }

    @Test
    void countersDrainAndReset() {
        NeighbourReflagFilter.drainKept();
        NeighbourReflagFilter.drainIncompleteFirstBuilds();
        SkippedSectionRebuilds.drainNeighbourInSnapshot();

        NeighbourReflagFilter.recordKept();
        NeighbourReflagFilter.recordKept();
        NeighbourReflagFilter.recordIncompleteFirstBuild();
        SkippedSectionRebuilds.recordNeighbourInSnapshot();

        assertEquals(2, NeighbourReflagFilter.drainKept());
        assertEquals(0, NeighbourReflagFilter.drainKept());
        assertEquals(1, NeighbourReflagFilter.drainIncompleteFirstBuilds());
        assertEquals(0, NeighbourReflagFilter.drainIncompleteFirstBuilds());
        assertEquals(1, SkippedSectionRebuilds.drainNeighbourInSnapshot());
        assertEquals(0, SkippedSectionRebuilds.drainNeighbourInSnapshot());
    }

    /**
     * The benchmark route's chunk-boundary crossing in single-player: the integrated server
     * sends the whole new row in one tick, so all 65 columns are in the client chunk map
     * before the deferred flag runnables drain at ten per frame in distance order. The
     * frontier row one chunk nearer already sits in the storage awaiting its first build,
     * which the visibility walk admits within the first frames once the new row, its
     * cardinal, is present. Every frontier section then snapshots the complete row, and each
     * trailing runnable re-flags it with data its capture already read.
     */
    @Test
    void singlePlayerRowCrossingBuildsEachFrontierSectionOnceWhereVanillaBuildsItTwoToThreeTimes() {
        StorageModel filtered = simulateRowCrossing(true, 6039L, 2, 2);
        StorageModel vanilla = simulateRowCrossing(false, 6039L, 2, 2);

        int filteredTotal = 0;
        int vanillaTotal = 0;
        for (int x = -ROW_HALF_WIDTH; x <= ROW_HALF_WIDTH; x++) {
            for (int sy = 0; sy < SECTIONS_PER_COLUMN; sy++) {
                Slot f = filtered.residentSlot(x, sy, StorageModel.FRONTIER_ROW);
                Slot v = vanilla.residentSlot(x, sy, StorageModel.FRONTIER_ROW);
                assertEquals(1, f.builds, "filtered builds of section " + x + "," + sy);
                assertTrue(v.builds >= 2 && v.builds <= 3, "vanilla builds of section " + x + "," + sy + ": " + v.builds);
                assertFalse(f.needsRebuild);
                assertFalse(v.needsRebuild);
                assertEquals(v.snapshotMask, f.snapshotMask, "the filter changes when a section builds, not what it sees");
                filteredTotal += f.builds;
                vanillaTotal += v.builds;
            }
        }
        int rowSections = (2 * ROW_HALF_WIDTH + 1) * SECTIONS_PER_COLUMN;
        assertEquals(rowSections, filteredTotal);
        assertTrue(vanillaTotal >= 2 * filteredTotal, "vanilla total " + vanillaTotal);
        assertEquals(0, filtered.kept);
        // Every new column re-flags its three frontier neighbours per section; the row ends have two.
        assertEquals(3 * rowSections - 2 * SECTIONS_PER_COLUMN, filtered.dropped);
    }

    /** The exactly-once property does not depend on how admission and the runnables interleave. */
    @Test
    void rowCrossingBuildsEachFrontierSectionOnceUnderAnyRunnableInterleaving() {
        for (long seed = 1; seed <= 5; seed++) {
            StorageModel filtered = simulateRowCrossing(true, seed, 7, 0);
            StorageModel vanilla = simulateRowCrossing(false, seed, 7, 0);
            int filteredTotal = 0;
            int vanillaTotal = 0;
            for (int x = -ROW_HALF_WIDTH; x <= ROW_HALF_WIDTH; x++) {
                for (int sy = 0; sy < SECTIONS_PER_COLUMN; sy++) {
                    Slot f = filtered.residentSlot(x, sy, StorageModel.FRONTIER_ROW);
                    Slot v = vanilla.residentSlot(x, sy, StorageModel.FRONTIER_ROW);
                    assertEquals(1, f.builds, "seed " + seed + " section " + x + "," + sy);
                    assertTrue(v.builds >= 1);
                    assertEquals(v.snapshotMask, f.snapshotMask);
                    filteredTotal += f.builds;
                    vanillaTotal += v.builds;
                }
            }
            assertTrue(vanillaTotal > filteredTotal, "seed " + seed);
        }
    }

    /**
     * A dedicated server sends three to nine columns per tick, so a frontier section's first
     * build often precedes some of its neighbours. Those later loads must still re-flag it,
     * while a load whose flag runnable merely trails a snapshot that already saw it must not.
     * Each neighbour decision is checked against the columns the snapshot really held.
     */
    @Test
    void partialBatchesFlagExactlyTheLoadsAbsentFromASectionSnapshot() {
        int size = 9;
        int framesPerTick = 3;
        StorageModel model = new StorageModel(true);
        Random random = new Random(31L);
        List<Column> arrivals = new ArrayList<>();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                model.addColumn(x, z, 0);
                arrivals.add(new Column(x, z));
            }
        }
        Collections.shuffle(arrivals, random);

        Map<Integer, List<Column>> runnablesByFrame = new HashMap<>();
        int next = 0;
        while (next < arrivals.size() || !runnablesByFrame.isEmpty()) {
            if (model.frame % framesPerTick == 0 && next < arrivals.size()) {
                int batch = 3 + random.nextInt(7);
                for (int i = 0; i < batch && next < arrivals.size(); i++, next++) {
                    Column column = arrivals.get(next);
                    model.load(column.cx(), column.cz());
                    runnablesByFrame.computeIfAbsent(model.frame + 1 + random.nextInt(2), f -> new ArrayList<>())
                            .add(column);
                }
            }
            List<Column> due = runnablesByFrame.remove(model.frame);
            if (due != null) {
                for (Column column : due) {
                    model.runFlagRunnable(column.cx(), column.cz());
                }
            }
            model.updateChunks();
        }
        model.updateChunks();

        assertTrue(model.kept > 0, "some loads arrived after a neighbour's first build");
        assertTrue(model.dropped > 0, "some flag runnables trailed a snapshot that already held the column");
        for (int x = 1; x < size - 1; x++) {
            for (int z = 1; z < size - 1; z++) {
                for (int sy = 0; sy < SECTIONS_PER_COLUMN; sy++) {
                    Slot slot = model.residentSlot(x, sy, z);
                    assertTrue(slot.builds >= 1, "section " + x + "," + sy + "," + z + " never built");
                    assertFalse(slot.needsRebuild, "section " + x + "," + sy + "," + z + " left pending");
                    assertEquals(model.currentNeighbourMask(x, z), slot.snapshotMask,
                            "section " + x + "," + sy + "," + z + " holds a stale neighbourhood");
                }
            }
        }
    }

    @Test
    void unloadAndInPlaceReloadClearExactlyTheNeighbourBit() {
        // A 7x7 region so that every neighbour of the centre's neighbours has its cardinals
        // loaded and is built; the outermost ring never passes shouldBuild and has no snapshot.
        StorageModel model = new StorageModel(true);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                model.addColumn(x, z, 0);
                model.load(x, z);
                model.lit.add(new Column(x, z));
            }
        }
        model.updateChunks();
        Slot centre = model.residentSlot(0, 3, 0);
        assertEquals(COMPLETE, centre.snapshotMask);
        int neighbourSections = NEIGHBOUR_COUNT * SECTIONS_PER_COLUMN;

        for (int k = 0; k < NEIGHBOUR_COUNT; k++) {
            int cx = DX[k];
            int cz = DZ[k];
            model.unload(cx, cz);
            assertEquals(COMPLETE & ~bit(k), centre.snapshotMask, "unloading neighbour " + k);
            model.forEachSlot((column, slot) -> {
                if (slot.builds > 0) {
                    assertEquals(model.currentNeighbourMask(column.cx(), column.cz()), slot.snapshotMask,
                            "unloading " + cx + "," + cz + " changed more than its own bit in " + column);
                }
            });

            int keptBefore = model.kept;
            int droppedBefore = model.dropped;
            model.load(cx, cz);
            model.runFlagRunnable(cx, cz);
            assertTrue(centre.needsRebuild);
            assertEquals(keptBefore + neighbourSections, model.kept, "the reload re-flags all eight neighbours");
            assertEquals(droppedBefore, model.dropped);
            model.updateChunks();
            assertEquals(COMPLETE, centre.snapshotMask);

            model.runFlagRunnable(cx, cz);
            assertEquals(keptBefore + neighbourSections, model.kept, "a trailing re-flag of a present column is dropped");
            assertEquals(droppedBefore + neighbourSections, model.dropped);
            model.updateChunks();
        }

        int keptBefore = model.kept;
        model.clearNeighbourSnapshotBits(1, 1);
        assertEquals(COMPLETE & ~bit(neighbourIndex(1, 1)), centre.snapshotMask, "in-place reload forgets the column");
        model.runFlagRunnable(1, 1);
        assertEquals(keptBefore + neighbourSections, model.kept);
        assertTrue(centre.needsRebuild);
    }

    private static StorageModel simulateRowCrossing(boolean filtered, long seed, int admissionFrames, int firstRunnableFrame) {
        StorageModel model = new StorageModel(filtered);
        Random random = new Random(seed);
        int nearerRow = StorageModel.FRONTIER_ROW - 1;
        int newRow = StorageModel.FRONTIER_ROW + 1;
        for (int x = -ROW_HALF_WIDTH - 1; x <= ROW_HALF_WIDTH + 1; x++) {
            model.load(x, nearerRow);
            model.load(x, StorageModel.FRONTIER_ROW);
            model.lit.add(new Column(x, StorageModel.FRONTIER_ROW));
        }
        for (int x = -ROW_HALF_WIDTH; x <= ROW_HALF_WIDTH; x++) {
            model.addColumn(x, StorageModel.FRONTIER_ROW, random.nextInt(admissionFrames));
        }

        List<Integer> distanceOrder = new ArrayList<>();
        for (int x = -ROW_HALF_WIDTH; x <= ROW_HALF_WIDTH; x++) {
            distanceOrder.add(x);
            model.load(x, newRow);
        }
        distanceOrder.sort(Comparator.comparingInt((Integer x) -> Math.abs(x)).thenComparingInt(x -> x));
        ArrayDeque<Integer> runnables = new ArrayDeque<>(distanceOrder);

        while (!runnables.isEmpty()) {
            if (model.frame >= firstRunnableFrame) {
                for (int i = 0; i < FLAG_RUNNABLES_PER_FRAME && !runnables.isEmpty(); i++) {
                    model.runFlagRunnable(runnables.poll(), newRow);
                }
            }
            model.updateChunks();
        }
        for (int i = 0; i < admissionFrames + 2; i++) {
            model.updateChunks();
        }
        return model;
    }

    private record Column(int cx, int cz) {
    }

    /** A built-chunk slot as the rule sees it, plus the ground truth the assertions compare against. */
    private static final class Slot {
        boolean needsRebuild = true;
        int snapshotMask;
        final Set<Column> snapshotColumns = new HashSet<>();
        int admittedFrame;
        int builds;
    }

    /**
     * Render-thread model of BuiltChunkStorage under either rule. Every column in the storage
     * has a full stack of resident slots. A slot builds once vanilla's visibility walk has
     * admitted it, its column's light data has arrived, and its four cardinal columns are
     * loaded (shouldBuild); the build snapshots the loaded neighbourhood exactly as
     * ChunkRendererRegionBuilder does.
     */
    private static final class StorageModel {
        static final int FRONTIER_ROW = 1;

        final boolean filtered;
        final Set<Column> loaded = new HashSet<>();
        final Set<Column> lit = new HashSet<>();
        final Map<Column, Slot[]> storage = new HashMap<>();
        int frame;
        int kept;
        int dropped;

        StorageModel(boolean filtered) {
            this.filtered = filtered;
        }

        void addColumn(int cx, int cz, int admittedFrame) {
            Slot[] slots = new Slot[SECTIONS_PER_COLUMN];
            for (int sy = 0; sy < slots.length; sy++) {
                slots[sy] = new Slot();
                slots[sy].admittedFrame = admittedFrame;
            }
            storage.put(new Column(cx, cz), slots);
        }

        void load(int cx, int cz) {
            loaded.add(new Column(cx, cz));
        }

        void unload(int cx, int cz) {
            loaded.remove(new Column(cx, cz));
            lit.remove(new Column(cx, cz));
            clearNeighbourSnapshotBits(cx, cz);
        }

        /** BuiltChunkStorageMixin.rtxremix$clearNeighbourSnapshotBits. */
        void clearNeighbourSnapshotBits(int cx, int cz) {
            for (int k = 0; k < NEIGHBOUR_COUNT; k++) {
                Slot[] slots = storage.get(new Column(cx + DX[k], cz + DZ[k]));
                if (slots == null) {
                    continue;
                }
                int bit = bit(mirroredIndex(k));
                for (Slot slot : slots) {
                    slot.snapshotMask &= ~bit;
                    slot.snapshotColumns.remove(new Column(cx, cz));
                }
            }
        }

        Slot residentSlot(int cx, int sy, int cz) {
            Slot[] slots = storage.get(new Column(cx, cz));
            return slots == null || !sectionInRange(sy, 0, SECTIONS_PER_COLUMN) ? null : slots[sy];
        }

        void forEachSlot(BiConsumer<Column, Slot> visitor) {
            for (Map.Entry<Column, Slot[]> column : storage.entrySet()) {
                for (Slot slot : column.getValue()) {
                    visitor.accept(column.getKey(), slot);
                }
            }
        }

        /**
         * The deferred chunk runnable: light data enables the column, then scheduleBlockRenders(III)
         * per section. The client issues that request once per light type and once more with the
         * chunk data, so its dropped count runs two to three times the per-section count modelled here.
         */
        void runFlagRunnable(int cx, int cz) {
            lit.add(new Column(cx, cz));
            for (int sy = 0; sy < SECTIONS_PER_COLUMN; sy++) {
                if (filtered) {
                    flagFiltered(cx, sy, cz);
                } else {
                    flagVanilla(cx, sy, cz);
                }
            }
        }

        private void flagVanilla(int cx, int sy, int cz) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        Slot slot = residentSlot(cx + dx, sy + dy, cz + dz);
                        if (slot != null) {
                            slot.needsRebuild = true;
                        }
                    }
                }
            }
        }

        /** BuiltChunkStorageMixin.rtxremix$flagLoadedSection, each neighbour decision checked against the snapshot's columns. */
        private void flagFiltered(int cx, int sy, int cz) {
            if (!sectionInRange(sy, 0, SECTIONS_PER_COLUMN)) {
                return;
            }
            Slot own = residentSlot(cx, sy, cz);
            if (own != null) {
                own.needsRebuild = true;
            }
            Column loader = new Column(cx, cz);
            for (int k = 0; k < NEIGHBOUR_COUNT; k++) {
                Slot slot = residentSlot(cx + DX[k], sy, cz + DZ[k]);
                boolean resident = slot != null;
                Decision decision = decideNeighbour(
                        resident,
                        resident && slot.needsRebuild,
                        resident ? slot.snapshotMask : 0,
                        bit(mirroredIndex(k)));
                if (resident && !slot.needsRebuild) {
                    Decision expected = slot.snapshotColumns.contains(loader) ? Decision.DROP_IN_SNAPSHOT : Decision.FLAG;
                    assertEquals(expected, decision, "load of " + loader + " seen from neighbour " + k);
                }
                switch (decision) {
                    case FLAG -> {
                        slot.needsRebuild = true;
                        kept++;
                    }
                    case DROP_IN_SNAPSHOT -> dropped++;
                    default -> {
                    }
                }
            }
        }

        /** WorldRenderer.updateChunks: createRebuildTask for every admitted slot that needs one, then the frame ends. */
        void updateChunks() {
            for (Map.Entry<Column, Slot[]> entry : storage.entrySet()) {
                Column column = entry.getKey();
                if (!lit.contains(column) || !cardinalsLoaded(column.cx(), column.cz())) {
                    continue;
                }
                for (Slot slot : entry.getValue()) {
                    if (slot.needsRebuild && slot.admittedFrame <= frame) {
                        build(slot, column.cx(), column.cz());
                    }
                }
            }
            frame++;
        }

        private boolean cardinalsLoaded(int cx, int cz) {
            return loaded.contains(new Column(cx - 1, cz))
                    && loaded.contains(new Column(cx + 1, cz))
                    && loaded.contains(new Column(cx, cz - 1))
                    && loaded.contains(new Column(cx, cz + 1));
        }

        private void build(Slot slot, int cx, int cz) {
            slot.snapshotMask = currentNeighbourMask(cx, cz);
            slot.snapshotColumns.clear();
            for (int k = 0; k < NEIGHBOUR_COUNT; k++) {
                Column neighbour = new Column(cx + DX[k], cz + DZ[k]);
                if (loaded.contains(neighbour)) {
                    slot.snapshotColumns.add(neighbour);
                }
            }
            slot.needsRebuild = false;
            slot.builds++;
        }

        /** BuiltChunkMixin.rtxremix$recordSnapshotNeighbourhood's probe over the loaded set. */
        int currentNeighbourMask(int cx, int cz) {
            int mask = 0;
            for (int k = 0; k < NEIGHBOUR_COUNT; k++) {
                if (loaded.contains(new Column(cx + DX[k], cz + DZ[k]))) {
                    mask |= bit(k);
                }
            }
            return mask;
        }
    }
}
