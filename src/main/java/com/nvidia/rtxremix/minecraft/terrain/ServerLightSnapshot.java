/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import org.jetbrains.annotations.Nullable;

/**
 * The integrated server's published light snapshot, as an immutable view over the engine's
 * live map instead of a clone of it.
 *
 * <p>Vanilla's {@code LightStorage.notifyChanges} clones the whole section map into
 * {@code uncachedStorage} whenever any section changed, so the server thread and chunk
 * serialisation can read light while the engine keeps mutating its own map. At render
 * distance 32 that map holds about a hundred thousand sections, so every pass that touched
 * one section allocated several megabytes, and light passes run many times a second while
 * chunks load.
 *
 * <p>The published view is a stack of small delta layers over an immutable base. The base is
 * a full clone taken only when the deltas have grown past a fraction of it. Each publish adds
 * one layer holding just that pass's changed sections and removed set; a lookup walks the
 * layers newest first and falls through to the base. Once the stack is {@link #MAX_LAYERS}
 * deep the layers are merged into one, so a pass allocates only its own deltas and the merge
 * cost is paid once every few passes. Nothing already published is ever mutated: layers are
 * complete before the view holding them exists, earlier views keep their own top layer, and
 * the engine copies a nibble array before its first write after a publish, exactly as it does
 * for vanilla's clones. Readers therefore see the same values, through the same {@code get}
 * calls, as they would from a full clone.
 *
 * <p>Sky light's column-to-top-section map gets the same treatment, driven by the same dirty
 * sections: a column's top can only change when a section in it is added or removed, and
 * both mark the section dirty.
 *
 * <p>One instance per storage; {@link #publish} runs on the engine thread that owns it.
 */
public final class ServerLightSnapshot {

    /** Rebuild the base once the deltas exceed this fraction of it. */
    private static final int FLATTEN_DIVISOR = 16;
    /** Below this many entries a base rebuild is cheap enough to skip the bookkeeping. */
    private static final int FLATTEN_FLOOR = 256;
    /** Layers stacked before they are merged into one; bounds the lookups a miss costs. */
    static final int MAX_LAYERS = 4;

    private Long2ObjectOpenHashMap<DataLayer> base;
    @Nullable
    private Layer top;
    private int deltaEntries;
    private int size;

    @Nullable
    private Long2IntOpenHashMap columnBase;
    @Nullable
    private ColumnLayer columnTop;
    private int columnSize;

    /** The maps a pass published: the section view, and for sky light the column view. */
    public record Published(Long2ObjectOpenHashMap<DataLayer> sections, @Nullable Long2IntOpenHashMap columns) {
    }

    /**
     * Builds the views for this pass from the engine's live maps and the sections it changed
     * since the previous publish. The caller clears {@code dirtySections} afterwards, as
     * vanilla does after its clone.
     *
     * @param liveColumns sky light's live column-to-top-section map, or null for block light
     */
    public Published publish(
            Long2ObjectOpenHashMap<DataLayer> live,
            @Nullable Long2IntOpenHashMap liveColumns,
            LongSet dirtySections) {
        int pendingDeltas = deltaEntries + dirtySections.size();
        if (base == null || pendingDeltas > Math.max(FLATTEN_FLOOR, base.size() / FLATTEN_DIVISOR)) {
            base = live.clone();
            top = null;
            deltaEntries = 0;
            size = base.size();
            columnBase = liveColumns != null ? liveColumns.clone() : null;
            columnTop = null;
            columnSize = columnBase != null ? columnBase.size() : 0;
            return published();
        }

        View previous = new View(base, top, size);
        Layer layer = new Layer(top, dirtySections.size());
        ColumnView previousColumns = columnBase != null ? new ColumnView(columnBase, columnTop, columnSize) : null;
        ColumnLayer columnLayer = columnBase != null ? new ColumnLayer(columnTop) : null;
        int newSize = size;
        int newColumnSize = columnSize;
        for (LongIterator dirty = dirtySections.iterator(); dirty.hasNext(); ) {
            long section = dirty.nextLong();
            DataLayer current = live.get(section);
            if (current != null) {
                layer.changed.put(section, current);
                if (!previous.containsKey(section)) {
                    newSize++;
                }
            } else if (previous.containsKey(section)) {
                layer.removed.add(section);
                newSize--;
            }
            if (columnLayer != null) {
                long column = SectionPos.getZeroNode(section);
                if (!columnLayer.changed.containsKey(column) && !columnLayer.removed.contains(column)) {
                    boolean had = previousColumns.containsKey(column);
                    if (liveColumns.containsKey(column)) {
                        int now = liveColumns.get(column);
                        if (!had || previousColumns.get(column) != now) {
                            columnLayer.changed.put(column, now);
                            if (!had) {
                                newColumnSize++;
                            }
                        }
                    } else if (had) {
                        columnLayer.removed.add(column);
                        newColumnSize--;
                    }
                }
            }
        }
        deltaEntries += layer.changed.size() + layer.removed.size();
        size = newSize;
        columnSize = newColumnSize;
        top = layer.depth > MAX_LAYERS ? layer.merged() : layer;
        if (columnLayer != null) {
            columnTop = columnLayer.depth > MAX_LAYERS ? columnLayer.merged() : columnLayer;
        }
        return published();
    }

    private Published published() {
        return new Published(
                new View(base, top, size),
                columnBase != null ? new ColumnView(columnBase, columnTop, columnSize) : null);
    }

    /** One pass's deltas over the layer below it. Complete before any view sees it. */
    private static final class Layer {
        @Nullable
        final Layer parent;
        final int depth;
        final Long2ObjectOpenHashMap<DataLayer> changed;
        final LongOpenHashSet removed = new LongOpenHashSet();

        Layer(@Nullable Layer parent, int expectedChanges) {
            this.parent = parent;
            this.depth = parent == null ? 1 : parent.depth + 1;
            this.changed = new Long2ObjectOpenHashMap<>(Math.max(expectedChanges, 16));
        }

        /** This layer and everything below it as one layer, newest deltas winning. */
        Layer merged() {
            int total = 0;
            for (Layer layer = this; layer != null; layer = layer.parent) {
                total += layer.changed.size();
            }
            Layer merged = new Layer(null, total);
            apply(merged);
            return merged;
        }

        private void apply(Layer into) {
            if (parent != null) {
                parent.apply(into);
            }
            // The fast iterator reuses one entry; the plain one allocates an entry per
            // element, and a merge walks every accumulated delta.
            for (var entries = changed.long2ObjectEntrySet().fastIterator(); entries.hasNext(); ) {
                Long2ObjectMap.Entry<DataLayer> entry = entries.next();
                into.changed.put(entry.getLongKey(), entry.getValue());
                into.removed.remove(entry.getLongKey());
            }
            for (LongIterator gone = removed.iterator(); gone.hasNext(); ) {
                long section = gone.nextLong();
                into.changed.remove(section);
                into.removed.add(section);
            }
        }
    }

    /**
     * Read-only section map: layers newest first, then the base. Extends the map type the
     * storage expects so vanilla's readers need no change; every mutator and every iteration
     * path fails loudly, because a caller holding this instead of a clone would otherwise
     * mutate or enumerate a shared structure.
     */
    static final class View extends Long2ObjectOpenHashMap<DataLayer> {
        private final Long2ObjectOpenHashMap<DataLayer> base;
        @Nullable
        private final Layer top;
        private final int size;

        View(Long2ObjectOpenHashMap<DataLayer> base, @Nullable Layer top, int size) {
            super(0);
            this.base = base;
            this.top = top;
            this.size = size;
        }

        @Override
        public DataLayer get(long section) {
            for (Layer layer = top; layer != null; layer = layer.parent) {
                if (!layer.removed.isEmpty() && layer.removed.contains(section)) {
                    return null;
                }
                DataLayer current = layer.changed.get(section);
                if (current != null) {
                    return current;
                }
            }
            return base.get(section);
        }

        @Override
        public boolean containsKey(long section) {
            for (Layer layer = top; layer != null; layer = layer.parent) {
                if (!layer.removed.isEmpty() && layer.removed.contains(section)) {
                    return false;
                }
                if (layer.changed.containsKey(section)) {
                    return true;
                }
            }
            return base.containsKey(section);
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public boolean isEmpty() {
            return size == 0;
        }

        @Override
        public DataLayer put(long section, DataLayer value) {
            throw readOnly();
        }

        @Override
        public DataLayer remove(long section) {
            throw readOnly();
        }

        @Override
        public void clear() {
            throw readOnly();
        }

        @Override
        public Long2ObjectOpenHashMap<DataLayer> clone() {
            throw readOnly();
        }

        @Override
        public Long2ObjectMap.FastEntrySet<DataLayer> long2ObjectEntrySet() {
            throw readOnly();
        }

        @Override
        public LongSet keySet() {
            throw readOnly();
        }

        @Override
        public ObjectCollection<DataLayer> values() {
            throw readOnly();
        }
    }

    /** One pass's column deltas; same shape as {@link Layer} for the top-section map. */
    private static final class ColumnLayer {
        @Nullable
        final ColumnLayer parent;
        final int depth;
        final Long2IntOpenHashMap changed = new Long2IntOpenHashMap();
        final LongOpenHashSet removed = new LongOpenHashSet();

        ColumnLayer(@Nullable ColumnLayer parent) {
            this.parent = parent;
            this.depth = parent == null ? 1 : parent.depth + 1;
        }

        ColumnLayer merged() {
            ColumnLayer merged = new ColumnLayer(null);
            apply(merged);
            return merged;
        }

        private void apply(ColumnLayer into) {
            if (parent != null) {
                parent.apply(into);
            }
            for (var entries = changed.long2IntEntrySet().fastIterator(); entries.hasNext(); ) {
                Long2IntMap.Entry entry = entries.next();
                into.changed.put(entry.getLongKey(), entry.getIntValue());
                into.removed.remove(entry.getLongKey());
            }
            for (LongIterator gone = removed.iterator(); gone.hasNext(); ) {
                long column = gone.nextLong();
                into.changed.remove(column);
                into.removed.add(column);
            }
        }
    }

    /**
     * Read-only column-to-top-section map for sky light. {@code SkyLightStorage.Data} sets the
     * default return value on construction to the same minimum section the engine's map uses,
     * so that call is accepted; absent columns answer with the base's default.
     */
    static final class ColumnView extends Long2IntOpenHashMap {
        private final Long2IntOpenHashMap base;
        @Nullable
        private final ColumnLayer top;
        private final int size;

        ColumnView(Long2IntOpenHashMap base, @Nullable ColumnLayer top, int size) {
            super(0);
            this.base = base;
            this.top = top;
            this.size = size;
            super.defaultReturnValue(base.defaultReturnValue());
        }

        @Override
        public int get(long column) {
            for (ColumnLayer layer = top; layer != null; layer = layer.parent) {
                if (!layer.removed.isEmpty() && layer.removed.contains(column)) {
                    return base.defaultReturnValue();
                }
                if (layer.changed.containsKey(column)) {
                    return layer.changed.get(column);
                }
            }
            return base.get(column);
        }

        @Override
        public boolean containsKey(long column) {
            for (ColumnLayer layer = top; layer != null; layer = layer.parent) {
                if (!layer.removed.isEmpty() && layer.removed.contains(column)) {
                    return false;
                }
                if (layer.changed.containsKey(column)) {
                    return true;
                }
            }
            return base.containsKey(column);
        }

        @Override
        public void defaultReturnValue(int value) {
            if (value != base.defaultReturnValue()) {
                throw readOnly();
            }
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public boolean isEmpty() {
            return size == 0;
        }

        @Override
        public int put(long column, int value) {
            throw readOnly();
        }

        @Override
        public int remove(long column) {
            throw readOnly();
        }

        @Override
        public void clear() {
            throw readOnly();
        }

        @Override
        public Long2IntOpenHashMap clone() {
            throw readOnly();
        }

        @Override
        public Long2IntMap.FastEntrySet long2IntEntrySet() {
            throw readOnly();
        }

        @Override
        public LongSet keySet() {
            throw readOnly();
        }
    }

    private static UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException("published light snapshot is read-only");
    }
}
