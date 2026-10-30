/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerLightSnapshotTest {

    /** Columns and section heights addressed by the simulated engine; small so removals recur. */
    private static final int COLUMNS = 10;
    private static final int HEIGHT = 6;
    private static final int MIN_SECTION = -2;

    @Test
    void viewsReadExactlyWhatAFullCloneWouldAcrossManyPasses() {
        Random random = new Random(0x11);
        ServerLightSnapshot snapshot = new ServerLightSnapshot();
        Long2ObjectOpenHashMap<DataLayer> live = new Long2ObjectOpenHashMap<>();
        Long2IntOpenHashMap liveColumns = new Long2IntOpenHashMap();
        liveColumns.defaultReturnValue(MIN_SECTION);
        LongOpenHashSet dirty = new LongOpenHashSet();
        List<ServerLightSnapshot.Published> views = new ArrayList<>();
        List<Long2ObjectOpenHashMap<DataLayer>> clones = new ArrayList<>();
        List<Long2IntOpenHashMap> columnClones = new ArrayList<>();

        for (int pass = 0; pass < 400; pass++) {
            // Enough passes of every size that layers merge many times and the base rebuilds.
            int operations = pass < 20 ? 60 : 1 + random.nextInt(12);
            for (int i = 0; i < operations; i++) {
                int x = random.nextInt(COLUMNS);
                int z = random.nextInt(COLUMNS);
                int y = random.nextInt(HEIGHT);
                long section = SectionPos.asLong(x, y, z);
                if (random.nextInt(10) < 2 && live.containsKey(section)) {
                    live.remove(section);
                } else {
                    // The engine copies before its first write after a publish, so a
                    // changed section is a new array instance to the snapshot.
                    live.put(section, new DataLayer(new byte[2048]));
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
            Long2ObjectOpenHashMap<DataLayer> view = views.get(i).sections();
            Long2ObjectOpenHashMap<DataLayer> expected = clones.get(i);
            Long2IntOpenHashMap columns = views.get(i).columns();
            Long2IntOpenHashMap expectedColumns = columnClones.get(i);
            assertEquals(expected.size(), view.size(), "size of publish " + i);
            assertEquals(expectedColumns.size(), columns.size(), "column count of publish " + i);
            for (int x = 0; x < COLUMNS; x++) {
                for (int z = 0; z < COLUMNS; z++) {
                    long column = SectionPos.asLong(x, 0, z);
                    assertEquals(expectedColumns.get(column), columns.get(column), "column " + x + "," + z + " of publish " + i);
                    assertEquals(expectedColumns.containsKey(column), columns.containsKey(column));
                    for (int y = 0; y < HEIGHT; y++) {
                        long section = SectionPos.asLong(x, y, z);
                        assertSame(expected.get(section), view.get(section), "section " + section + " of publish " + i);
                        assertEquals(expected.containsKey(section), view.containsKey(section));
                    }
                }
            }
        }
    }

    @Test
    void blockLightPublishesNoColumnView() {
        ServerLightSnapshot snapshot = new ServerLightSnapshot();
        Long2ObjectOpenHashMap<DataLayer> live = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet dirty = new LongOpenHashSet();
        live.put(7L, new DataLayer(new byte[2048]));
        dirty.add(7L);
        ServerLightSnapshot.Published published = snapshot.publish(live, null, dirty);
        assertSame(live.get(7L), published.sections().get(7L));
        assertEquals(null, published.columns());
    }

    @Test
    void publishedViewsRejectMutationAndEnumeration() {
        ServerLightSnapshot snapshot = new ServerLightSnapshot();
        Long2ObjectOpenHashMap<DataLayer> live = new Long2ObjectOpenHashMap<>();
        Long2IntOpenHashMap liveColumns = new Long2IntOpenHashMap();
        liveColumns.defaultReturnValue(MIN_SECTION);
        LongOpenHashSet dirty = new LongOpenHashSet();
        live.put(7L, new DataLayer(new byte[2048]));
        liveColumns.put(SectionPos.getZeroNode(7L), 1);
        dirty.add(7L);
        ServerLightSnapshot.Published published = snapshot.publish(live, liveColumns, dirty);
        Long2ObjectOpenHashMap<DataLayer> view = published.sections();
        Long2IntOpenHashMap columns = published.columns();

        assertThrows(UnsupportedOperationException.class, () -> view.put(8L, new DataLayer(new byte[2048])));
        assertThrows(UnsupportedOperationException.class, () -> view.remove(7L));
        assertThrows(UnsupportedOperationException.class, view::clear);
        assertThrows(UnsupportedOperationException.class, view::clone);
        assertThrows(UnsupportedOperationException.class, view::long2ObjectEntrySet);
        assertThrows(UnsupportedOperationException.class, view::keySet);
        assertThrows(UnsupportedOperationException.class, view::values);

        // SkyLightStorage.Data sets the same default the engine's map carries; anything else
        // would change what absent columns answer for every reader of the shared view.
        columns.defaultReturnValue(MIN_SECTION);
        assertEquals(MIN_SECTION, columns.get(SectionPos.asLong(5, 0, 5)));
        assertThrows(UnsupportedOperationException.class, () -> columns.defaultReturnValue(MIN_SECTION + 1));
        assertThrows(UnsupportedOperationException.class, () -> columns.put(1L, 2));
        assertThrows(UnsupportedOperationException.class, () -> columns.remove(1L));
        assertThrows(UnsupportedOperationException.class, columns::clear);
        assertThrows(UnsupportedOperationException.class, columns::clone);
        assertThrows(UnsupportedOperationException.class, columns::long2IntEntrySet);
        assertThrows(UnsupportedOperationException.class, columns::keySet);
    }

    /** SkyLightStorage's rule: a column maps to one above its highest present section, else absent. */
    private static void updateColumn(Long2ObjectOpenHashMap<DataLayer> live, Long2IntOpenHashMap columns, int x, int z) {
        for (int y = HEIGHT - 1; y >= 0; y--) {
            if (live.containsKey(SectionPos.asLong(x, y, z))) {
                columns.put(SectionPos.asLong(x, 0, z), y + 1);
                return;
            }
        }
        columns.remove(SectionPos.asLong(x, 0, z));
    }
}
