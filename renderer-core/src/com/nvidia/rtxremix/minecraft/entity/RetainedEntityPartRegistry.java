/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Tracks retained values by owner identity and stable part slot across frame boundaries.
 * Owners use identity semantics so a recycled numeric game ID cannot inherit native state.
 */
final class RetainedEntityPartRegistry<O, V> {

    /**
     * Overrides hashCode so the stored record and the {@link SlotProbe} share one
     * explicit formula, rather than the probe pinning a generated hash the JLS
     * leaves unspecified.
     */
    private static final class Slot {
        private final int categoryFlags;
        private final int layerIndex;
        private final int partIndex;

        Slot(int categoryFlags, int layerIndex, int partIndex) {
            this.categoryFlags = categoryFlags;
            this.layerIndex = layerIndex;
            this.partIndex = partIndex;
        }

        int categoryFlags() { return categoryFlags; }
        int layerIndex() { return layerIndex; }
        int partIndex() { return partIndex; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Slot)) return false;
            Slot slot = (Slot) other;
            return categoryFlags == slot.categoryFlags
                    && layerIndex == slot.layerIndex
                    && partIndex == slot.partIndex;
        }

        @Override
        public int hashCode() {
            return hash(categoryFlags, layerIndex, partIndex);
        }

        static int hash(int categoryFlags, int layerIndex, int partIndex) {
            return 31 * (31 * categoryFlags + layerIndex) + partIndex;
        }
    }

    /** Mutable probe for allocation-free slot lookups; equals compares against stored Slots. */
    private static final class SlotProbe {
        int categoryFlags, layerIndex, partIndex;

        SlotProbe set(int c, int l, int p) {
            categoryFlags = c; layerIndex = l; partIndex = p; return this;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Slot)) return false;
            Slot slot = (Slot) o;
            return categoryFlags == slot.categoryFlags()
                    && layerIndex == slot.layerIndex()
                    && partIndex == slot.partIndex();
        }

        @Override
        public int hashCode() {
            return Slot.hash(categoryFlags, layerIndex, partIndex);
        }
    }

    private static final class Entry<V> {
        final V value;
        long lastSeenFrame;

        Entry(V value, long lastSeenFrame) {
            this.value = value;
            this.lastSeenFrame = lastSeenFrame;
        }
    }

    private final IdentityHashMap<O, Map<Slot, Entry<V>>> owners = new IdentityHashMap<>();
    private final SlotProbe slotProbe = new SlotProbe();
    private long frameIndex;
    private boolean frameOpen;
    private int size;

    void beginFrame() {
        if (frameOpen) {
            throw new IllegalStateException("Retained entity frame is already open");
        }
        frameOpen = true;
        frameIndex++;
    }

    long frameIndex() {
        return frameIndex;
    }

    int size() {
        return size;
    }

    /**
     * Returns the retained value for this owner and slot, creating one if absent and retiring
     * one the caller rejects. Looks up through a reusable probe, so the steady state allocates
     * no {@link Slot}; a real one is minted only when a part first appears.
     */
    V retain(O owner,
             int categoryFlags, int layerIndex, int partIndex,
             Predicate<? super V> reusable,
             Supplier<? extends V> factory,
             Consumer<? super V> retire) {
        requireOpenFrame();

        Map<Slot, Entry<V>> parts = owners.computeIfAbsent(owner, ignored -> new HashMap<>());
        Entry<V> entry = parts.get(slotProbe.set(categoryFlags, layerIndex, partIndex));
        if (entry != null && !reusable.test(entry.value)) {
            parts.remove(slotProbe);
            size--;
            retire.accept(entry.value);
            entry = null;
        }

        if (entry == null) {
            V value = factory.get();
            entry = new Entry<>(value, frameIndex);
            parts.put(new Slot(categoryFlags, layerIndex, partIndex), entry);
            size++;
        } else {
            entry.lastSeenFrame = frameIndex;
        }
        return entry.value;
    }

    /** Retires every part this owner holds, so a partly submitted owner leaves nothing in the scene. */
    void discardOwner(O owner, Consumer<? super V> retire) {
        Map<Slot, Entry<V>> parts = owners.remove(owner);
        if (parts == null) return;

        size -= parts.size();
        for (Entry<V> entry : parts.values()) {
            retire.accept(entry.value);
        }
    }

    void discardIf(Predicate<? super V> predicate, Consumer<? super V> retire) {
        Iterator<Map.Entry<O, Map<Slot, Entry<V>>>> ownerIt = owners.entrySet().iterator();
        while (ownerIt.hasNext()) {
            Map<Slot, Entry<V>> parts = ownerIt.next().getValue();
            Iterator<Entry<V>> partIt = parts.values().iterator();
            while (partIt.hasNext()) {
                Entry<V> entry = partIt.next();
                if (!predicate.test(entry.value)) continue;

                partIt.remove();
                size--;
                retire.accept(entry.value);
            }
            if (parts.isEmpty()) ownerIt.remove();
        }
    }

    void endFrame(Consumer<? super V> retire) {
        requireOpenFrame();
        try {
            Iterator<Map.Entry<O, Map<Slot, Entry<V>>>> ownerIt = owners.entrySet().iterator();
            while (ownerIt.hasNext()) {
                Map<Slot, Entry<V>> parts = ownerIt.next().getValue();
                Iterator<Entry<V>> partIt = parts.values().iterator();
                while (partIt.hasNext()) {
                    Entry<V> entry = partIt.next();
                    if (entry.lastSeenFrame == frameIndex) continue;

                    partIt.remove();
                    size--;
                    retire.accept(entry.value);
                }
                if (parts.isEmpty()) ownerIt.remove();
            }
        } finally {
            frameOpen = false;
        }
    }

    void clear(Consumer<? super V> retire) {
        frameOpen = false;
        Iterator<Map<Slot, Entry<V>>> ownerIt = owners.values().iterator();
        while (ownerIt.hasNext()) {
            Map<Slot, Entry<V>> parts = ownerIt.next();
            Iterator<Entry<V>> partIt = parts.values().iterator();
            while (partIt.hasNext()) {
                Entry<V> entry = partIt.next();
                partIt.remove();
                size--;
                retire.accept(entry.value);
            }
            ownerIt.remove();
        }
    }

    private void requireOpenFrame() {
        if (!frameOpen) {
            throw new IllegalStateException("Retained entity frame is not open");
        }
    }
}
