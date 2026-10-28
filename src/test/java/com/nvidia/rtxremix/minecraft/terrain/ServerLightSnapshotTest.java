/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.light.SkyLightStorage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerLightSnapshotTest {

    /** Columns and section heights addressed by the simulated engine; small so removals recur. */
    private static final int COLUMNS = 10;
    private static final int HEIGHT = 6;
    private static final int MIN_SECTION = -2;

    @Test
    void viewsReadExactlyWhatAFullCloneWouldAcrossManyPasses() {
        Random random = new Random(0x11);
        ServerLightSnapshot snapshot = new ServerLightSnapshot();
        Long2ObjectOpenHashMap<ChunkNibbleArray> live = new Long2ObjectOpenHashMap<>();
        Long2IntOpenHashMap liveColumns = new Long2IntOpenHashMap();
        liveColumns.defaultReturnValue(MIN_SECTION);
        LongOpenHashSet dirty = new LongOpenHashSet();
        List<ServerLightSnapshot.Published> views = new ArrayList<>();
        List<Long2ObjectOpenHashMap<ChunkNibbleArray>> clones = new ArrayList<>();
        List<Long2IntOpenHashMap> columnClones = new ArrayList<>();

        for (int pass = 0; pass < 400; pass++) {
            liveColumns.defaultReturnValue(MIN_SECTION - pass / 7);
            // Enough passes of every size that layers merge many times and the base rebuilds.
            int operations = pass < 20 ? 60 : 1 + random.nextInt(12);
            for (int i = 0; i < operations; i++) {
                int x = random.nextInt(COLUMNS);
                int z = random.nextInt(COLUMNS);
                int y = random.nextInt(HEIGHT);
                long section = ChunkSectionPos.asLong(x, y, z);
                if (random.nextInt(10) < 2 && live.containsKey(section)) {
                    live.remove(section);
                } else {
                    // The engine copies before its first write after a publish, so a
                    // changed section is a new array instance to the snapshot.
                    live.put(section, new ChunkNibbleArray(new byte[2048]));
                }
                dirty.add(section);
                updateColumn(live, liveColumns, x, z);
            }
            if (dirty.isEmpty()) {
                continue;
            }
            views.add(snapshot.publish(live, liveColumns, dirty));
            dirty.clear();
            clones.add(live.clone());
            columnClones.add(liveColumns.clone());
        }

        for (int i = 0; i < views.size(); i++) {
            Long2ObjectOpenHashMap<ChunkNibbleArray> view = views.get(i).sections();
            Long2ObjectOpenHashMap<ChunkNibbleArray> expected = clones.get(i);
            Long2IntOpenHashMap columns = views.get(i).columns();
            Long2IntOpenHashMap expectedColumns = columnClones.get(i);
            assertEquals(expected.size(), view.size(), "size of publish " + i);
            assertEquals(expectedColumns.size(), columns.size(), "column count of publish " + i);
            assertEquals(expectedColumns.defaultReturnValue(), columns.defaultReturnValue(), "minimum section of publish " + i);
            for (int x = 0; x < COLUMNS; x++) {
                for (int z = 0; z < COLUMNS; z++) {
                    long column = ChunkSectionPos.asLong(x, 0, z);
                    assertEquals(expectedColumns.get(column), columns.get(column), "column " + x + "," + z + " of publish " + i);
                    assertEquals(expectedColumns.containsKey(column), columns.containsKey(column));
                    for (int y = 0; y < HEIGHT; y++) {
                        long section = ChunkSectionPos.asLong(x, y, z);
                        assertSame(expected.get(section), view.get(section), "section " + section + " of publish " + i);
                        assertEquals(expected.containsKey(section), view.containsKey(section));
                    }
                }
            }
        }
    }

    @Test
    void lowerSectionCanChangeTheMinimumWithoutMutatingPublishedData() {
        ServerLightSnapshot snapshot = new ServerLightSnapshot();
        Long2ObjectOpenHashMap<ChunkNibbleArray> live = new Long2ObjectOpenHashMap<>();
        Long2IntOpenHashMap columns = new Long2IntOpenHashMap();
        SkyLightStorage.Data engine = new SkyLightStorage.Data(live, columns, 6);
        long highSection = ChunkSectionPos.asLong(0, 6, 0);
        long column = ChunkSectionPos.withZeroY(highSection);
        long missing = ChunkSectionPos.asLong(1, 0, 1);
        live.put(highSection, new ChunkNibbleArray(new byte[2048]));
        columns.put(column, 7);
        ServerLightSnapshot.Published high = snapshot.publish(live, columns, LongOpenHashSet.of(highSection));
        SkyLightStorage.Data earlier = new SkyLightStorage.Data(high.sections(), high.columns(), engine.minSectionY);

        long lowerSection = ChunkSectionPos.asLong(0, -5, 0);
        live.put(lowerSection, new ChunkNibbleArray(new byte[2048]));
        // SkyLightStorage.onLoadSection updates both before notifyChanges publishes.
        engine.minSectionY = -5;
        columns.defaultReturnValue(engine.minSectionY);
        ServerLightSnapshot.Published lower = snapshot.publish(live, columns, LongOpenHashSet.of(lowerSection));
        SkyLightStorage.Data published = new SkyLightStorage.Data(lower.sections(), lower.columns(), engine.minSectionY);
        SkyLightStorage.Data vanilla = engine.copy();

        assertEquals(vanilla.minSectionY, published.minSectionY);
        assertEquals(vanilla.columnToTopSection.get(column), published.columnToTopSection.get(column));
        assertEquals(vanilla.columnToTopSection.get(missing), published.columnToTopSection.get(missing));
        assertEquals(6, earlier.columnToTopSection.get(missing));
        assertEquals(6, earlier.columnToTopSection.defaultReturnValue());
    }

    @Test
    void changedMinimumAppliesToMissingAndRemovedColumnsButNotStoredHeights() {
        ServerLightSnapshot snapshot = new ServerLightSnapshot();
        Long2ObjectOpenHashMap<ChunkNibbleArray> live = new Long2ObjectOpenHashMap<>();
        Long2IntOpenHashMap columns = new Long2IntOpenHashMap();
        columns.defaultReturnValue(6);
        long keptSection = ChunkSectionPos.asLong(0, 5, 0);
        long removedSection = ChunkSectionPos.asLong(1, 6, 0);
        long keptColumn = ChunkSectionPos.withZeroY(keptSection);
        long removedColumn = ChunkSectionPos.withZeroY(removedSection);
        long missing = ChunkSectionPos.asLong(2, 0, 0);
        live.put(keptSection, new ChunkNibbleArray(new byte[2048]));
        live.put(removedSection, new ChunkNibbleArray(new byte[2048]));
        columns.put(keptColumn, 6);
        columns.put(removedColumn, 7);
        ServerLightSnapshot.Published earlier = snapshot.publish(live, columns,
                LongOpenHashSet.of(keptSection, removedSection));

        live.remove(removedSection);
        columns.remove(removedColumn);
        columns.defaultReturnValue(-5);
        ServerLightSnapshot.Published later = snapshot.publish(live, columns, LongOpenHashSet.of(removedSection));

        assertEquals(6, later.columns().get(keptColumn));
        assertTrue(later.columns().containsKey(keptColumn));
        assertEquals(-5, later.columns().get(removedColumn));
        assertFalse(later.columns().containsKey(removedColumn));
        assertEquals(-5, later.columns().get(missing));
        later.columns().defaultReturnValue(-5);
        assertThrows(UnsupportedOperationException.class, () -> later.columns().defaultReturnValue(6));
        assertEquals(6, earlier.columns().get(missing));
        assertEquals(7, earlier.columns().get(removedColumn));
    }

    @Test
    void blockLightPublishesNoColumnView() {
        ServerLightSnapshot snapshot = new ServerLightSnapshot();
        Long2ObjectOpenHashMap<ChunkNibbleArray> live = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet dirty = new LongOpenHashSet();
        live.put(7L, new ChunkNibbleArray(new byte[2048]));
        dirty.add(7L);
        ServerLightSnapshot.Published published = snapshot.publish(live, null, dirty);
        assertSame(live.get(7L), published.sections().get(7L));
        assertEquals(null, published.columns());
    }

    @Test
    void publishedViewsRejectMutationAndEnumeration() {
        ServerLightSnapshot snapshot = new ServerLightSnapshot();
        Long2ObjectOpenHashMap<ChunkNibbleArray> live = new Long2ObjectOpenHashMap<>();
        Long2IntOpenHashMap liveColumns = new Long2IntOpenHashMap();
        liveColumns.defaultReturnValue(MIN_SECTION);
        LongOpenHashSet dirty = new LongOpenHashSet();
        live.put(7L, new ChunkNibbleArray(new byte[2048]));
        liveColumns.put(ChunkSectionPos.withZeroY(7L), 1);
        dirty.add(7L);
        ServerLightSnapshot.Published published = snapshot.publish(live, liveColumns, dirty);
        Long2ObjectOpenHashMap<ChunkNibbleArray> view = published.sections();
        Long2IntOpenHashMap columns = published.columns();

        assertThrows(UnsupportedOperationException.class, () -> view.put(8L, new ChunkNibbleArray(new byte[2048])));
        assertThrows(UnsupportedOperationException.class, () -> view.remove(7L));
        assertThrows(UnsupportedOperationException.class, view::clear);
        assertThrows(UnsupportedOperationException.class, view::clone);
        assertThrows(UnsupportedOperationException.class, view::long2ObjectEntrySet);
        assertThrows(UnsupportedOperationException.class, view::keySet);
        assertThrows(UnsupportedOperationException.class, view::values);

        // SkyLightStorage.Data sets the same default the engine's map carries; anything else
        // would change what absent columns answer for every reader of the shared view.
        columns.defaultReturnValue(MIN_SECTION);
        assertEquals(MIN_SECTION, columns.get(ChunkSectionPos.asLong(5, 0, 5)));
        assertThrows(UnsupportedOperationException.class, () -> columns.defaultReturnValue(MIN_SECTION + 1));
        assertThrows(UnsupportedOperationException.class, () -> columns.put(1L, 2));
        assertThrows(UnsupportedOperationException.class, () -> columns.remove(1L));
        assertThrows(UnsupportedOperationException.class, columns::clear);
        assertThrows(UnsupportedOperationException.class, columns::clone);
        assertThrows(UnsupportedOperationException.class, columns::long2IntEntrySet);
        assertThrows(UnsupportedOperationException.class, columns::keySet);
    }

    /** SkyLightStorage's rule: a column maps to one above its highest present section, else absent. */
    private static void updateColumn(Long2ObjectOpenHashMap<ChunkNibbleArray> live, Long2IntOpenHashMap columns, int x, int z) {
        for (int y = HEIGHT - 1; y >= 0; y--) {
            if (live.containsKey(ChunkSectionPos.asLong(x, y, z))) {
                columns.put(ChunkSectionPos.asLong(x, 0, z), y + 1);
                return;
            }
        }
        columns.remove(ChunkSectionPos.asLong(x, 0, z));
    }
}
