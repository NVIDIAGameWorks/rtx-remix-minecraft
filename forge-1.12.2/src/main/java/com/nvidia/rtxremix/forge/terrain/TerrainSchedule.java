/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

/** Render-thread window and revision queue, independent of Minecraft and native handles. */
final class TerrainSchedule {
    private static final int MAX_SECTIONS = 65 * 65 * 16;
    interface RemovedColumn { void remove(Column column); }

    static final class Column {
        final int x, z;
        final Section[] sections = new Section[16];
        Object source;
        boolean resident = true;
        Column(int x, int z) {
            this.x = x;
            this.z = z;
            for (int y = 0; y < sections.length; y++) sections[y] = new Section(this, y);
        }
    }

    static final class Section {
        final Column column;
        final int y;
        long revision;
        long publishedRevision;
        long publication;
        boolean queued;
        boolean building;
        boolean failed;
        boolean refresh;
        boolean retained;
        boolean deferred;
        long deferredCapacityRevision;
        long requiredBytes = -1;
        Object asset;
        Section(Column column, int y) { this.column = column; this.y = y; }
        boolean ready() { return publication != 0 && publishedRevision == revision && column.resident; }
    }

    private int x, y, z, radius = -1;
    private Column[] columns = new Column[0];
    private Column[] discovery = new Column[0];
    private int cursor;
    private long publication;
    private int failures;
    private int deferred;
    private long capacityRevision;
    private final Comparator<Section> spatial = new Comparator<Section>() {
        @Override public int compare(Section a, Section b) {
            long da = distance(a.column.x, a.y, a.column.z);
            long db = distance(b.column.x, b.y, b.column.z);
            int result = Long.compare(da, db);
            if (result == 0) result = Integer.compare(a.column.x, b.column.x);
            if (result == 0) result = Integer.compare(a.column.z, b.column.z);
            return result == 0 ? Integer.compare(a.y, b.y) : result;
        }
    };
    private final Comparator<Section> priority = new Comparator<Section>() {
        @Override public int compare(Section a, Section b) {
            return a.refresh != b.refresh ? (a.refresh ? -1 : 1) : spatial.compare(a, b);
        }
    };
    // Admission and pressure eviction never grow heap storage on the render thread.
    private final PriorityQueue<Section> dirty = new PriorityQueue<Section>(MAX_SECTIONS, priority);
    private final PriorityQueue<Section> retained = new PriorityQueue<Section>(MAX_SECTIONS,
            new Comparator<Section>() {
                @Override public int compare(Section a, Section b) { return spatial.compare(b, a); }
            });

    boolean move(int nextX, int nextY, int nextZ, int nextRadius, RemovedColumn removed) {
        if (nextRadius < 0 || nextRadius > 32) {
            throw new IllegalArgumentException("Minecraft render distance must be between 0 and 32 chunks");
        }
        if (x == nextX && y == nextY && z == nextZ && radius == nextRadius) return false;
        int oldX = x, oldZ = z, oldRadius = radius;
        Column[] previous = columns;
        x = nextX; y = nextY; z = nextZ; radius = nextRadius;
        if (x != oldX || z != oldZ || radius != oldRadius) {
            int side = radius * 2 + 1;
            columns = new Column[side * side];
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int cx = x + dx, cz = z + dz;
                    Column old = lookup(previous, oldX, oldZ, oldRadius, cx, cz);
                    columns[(dz + radius) * side + dx + radius] = old == null ? new Column(cx, cz) : old;
                }
            }
            for (Column old : previous) {
                if (!contains(old.x, old.z)) {
                    old.resident = false;
                    for (Section section : old.sections) forget(section);
                    removed.remove(old);
                }
            }
            discovery = columns.clone();
            Arrays.sort(discovery, new Comparator<Column>() {
                @Override public int compare(Column a, Column b) {
                    return Long.compare(distance(a.x, y, a.z), distance(b.x, y, b.z));
                }
            });
            cursor = 0;
        }
        // Mutable camera coordinates affect the heap ordering; rebuild only when they change.
        dirty.clear();
        retained.clear();
        for (Column column : columns) {
            for (Section section : column.sections) {
                if (section.deferred) {
                    clearDeferred(section);
                    section.queued = true;
                }
                if (section.queued) dirty.add(section);
                if (section.retained) retained.add(section);
            }
        }
        return true;
    }

    private static Column lookup(Column[] cells, int x, int z, int radius, int cx, int cz) {
        if (radius < 0 || Math.abs((long) cx - x) > radius || Math.abs((long) cz - z) > radius) return null;
        return cells[(cz - z + radius) * (radius * 2 + 1) + cx - x + radius];
    }

    Column column(int cx, int cz) { return lookup(columns, x, z, radius, cx, cz); }
    Column nextDiscovery() {
        if (discovery.length == 0) return null;
        Column column = discovery[cursor++];
        if (cursor == discovery.length) cursor = 0;
        return column;
    }
    int windowSize() { return columns.length; }
    int radius() { return radius; }
    int centerX() { return x; }
    int centerZ() { return z; }
    int pending() { return dirty.size(); }
    int failures() { return failures; }
    int deferred() { return deferred; }
    boolean hasRefresh() { return !dirty.isEmpty() && dirty.peek().refresh; }
    boolean contains(int cx, int cz) {
        return radius >= 0 && Math.abs((long) cx - x) <= radius && Math.abs((long) cz - z) <= radius;
    }

    void invalidate(Column column) {
        if (column == null || column.source == null || !column.resident) return;
        for (Section section : column.sections) invalidate(section);
    }
    void invalidate(Section section) {
        if (section.column.source == null || !section.column.resident) return;
        clearFailure(section);
        clearDeferred(section);
        section.requiredBytes = -1;
        if (!section.refresh && section.asset != null) {
            if (section.queued) dirty.remove(section);
            section.queued = false;
            section.refresh = true;
        }
        section.revision++;
        if (!section.queued && !section.building) {
            section.queued = true;
            dirty.add(section);
        }
    }

    Section next() {
        Section next;
        while ((next = dirty.poll()) != null) {
            next.queued = false;
            if (next.column.resident && next.column.source != null) {
                next.building = true;
                return next;
            }
        }
        return null;
    }
    boolean valid(Section section, Object source, long revision) {
        return section.column.resident && section.column.source == source && section.revision == revision;
    }
    void discard(Section section) {
        section.building = false;
        if (section.column.resident && section.column.source != null && !section.queued) {
            section.queued = true;
            dirty.add(section);
        }
    }
    void publish(Section section, long revision) {
        clearFailure(section);
        clearDeferred(section);
        section.building = false;
        section.refresh = false;
        section.publishedRevision = revision;
        section.publication = ++publication;
        if (section.asset != null && !section.retained) {
            section.retained = true;
            retained.add(section);
        } else if (section.asset == null) removeAsset(section);
    }
    /** The strict spatial order prevents equal-priority sections exchanging places at steady capacity. */
    Section evictionCandidate(Section incoming) {
        Section candidate = retained.peek();
        return candidate != null && spatial.compare(candidate, incoming) > 0 ? candidate : null;
    }
    void removeAsset(Section section) {
        if (section.retained) {
            retained.remove(section);
            section.retained = false;
        }
    }
    void evict(Section section, long previousBytes, boolean defer) {
        removeAsset(section);
        section.asset = null;
        section.publication = 0;
        // An edited victim still needs a fresh measurement: its old mesh size
        // cannot decide whether the replacement fits or whether it is now empty.
        if (defer && !section.refresh) defer(section, previousBytes);
    }
    void defer(Section section, long requiredBytes) {
        if (requiredBytes < 0) throw new IllegalArgumentException("Deferred terrain requires a measured size");
        section.building = false;
        section.refresh = false;
        if (section.queued) dirty.remove(section);
        section.queued = false;
        section.requiredBytes = requiredBytes;
        section.deferredCapacityRevision = capacityRevision;
        section.publication = 0;
        if (section.column.resident && section.column.source != null && !section.deferred) {
            section.deferred = true;
            deferred++;
        }
    }
    /** Pressure's own evictions do not reopen deferred work and cannot create a residency cycle. */
    void capacityChanged() { capacityRevision++; }
    /** Reconsider only columns visited by bounded discovery after actual capacity changes. */
    void retryDeferred(Column column, long availableBytes) {
        if (column.source == null) return;
        for (Section section : column.sections) {
            if (section.deferred && (section.requiredBytes <= availableBytes
                    || section.deferredCapacityRevision != capacityRevision)) {
                clearDeferred(section);
                section.queued = true;
                dirty.add(section);
            }
        }
    }
    private void clearDeferred(Section section) {
        if (section.deferred) { section.deferred = false; deferred--; }
    }
    void forget(Section section) {
        clearFailure(section);
        clearDeferred(section);
        removeAsset(section);
        if (section.queued) dirty.remove(section);
        section.queued = false;
        section.refresh = false;
        section.requiredBytes = -1;
        section.publication = 0;
    }
    void fail(Section section) {
        section.building = false;
        clearDeferred(section);
        if (section.queued) dirty.remove(section);
        section.queued = false;
        if (!section.failed) { section.failed = true; failures++; }
    }
    void clearFailure(Section section) {
        if (section.failed) { section.failed = false; failures--; }
    }
    void clear(RemovedColumn removed) {
        for (Column column : columns) {
            column.resident = false;
            removed.remove(column);
        }
        columns = discovery = new Column[0];
        dirty.clear();
        retained.clear();
        radius = -1;
        cursor = 0;
        failures = 0;
        deferred = 0;
    }
    private long distance(int cx, int cy, int cz) {
        long dx = (long) cx - x, dy = (long) cy - y, dz = (long) cz - z;
        return dx * dx + dz * dz + dy * dy;
    }
}
