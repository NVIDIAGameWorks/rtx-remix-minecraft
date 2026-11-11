/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Minimal open-addressing hash map from {@code long} keys to object values.
 * This avoids {@code Long} boxing on render-thread column lookups while keeping
 * the API intentionally small for terrain state hot paths.
 */
public final class LongObjectMap<V> {

    private static final long EMPTY = Long.MIN_VALUE;
    private static final float LOAD_FACTOR = 0.75f;

    private long[] keys;
    private Object[] values;
    private int mask;
    private int size;
    private int threshold;
    private boolean hasEmptyKey;
    private V emptyKeyValue;

    public LongObjectMap() {
        this(256);
    }

    public LongObjectMap(int initialCapacity) {
        int cap = Integer.highestOneBit(Math.max(initialCapacity, 4) - 1) << 1;
        keys = new long[cap];
        values = new Object[cap];
        mask = cap - 1;
        threshold = (int) (cap * LOAD_FACTOR);
        Arrays.fill(keys, EMPTY);
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    @SuppressWarnings("unchecked")
    public V get(long key) {
        if (key == EMPTY) return hasEmptyKey ? emptyKeyValue : null;
        int idx = (int) (mix(key) & mask);
        while (true) {
            long existing = keys[idx];
            if (existing == key) return (V) values[idx];
            if (existing == EMPTY) return null;
            idx = (idx + 1) & mask;
        }
    }

    public void put(long key, V value) {
        if (key == EMPTY) {
            if (!hasEmptyKey) {
                hasEmptyKey = true;
                size++;
            }
            emptyKeyValue = value;
            return;
        }
        if (size >= threshold) grow();
        putInternal(keys, values, mask, key, value);
    }

    @SuppressWarnings("unchecked")
    public V remove(long key) {
        if (key == EMPTY) {
            if (!hasEmptyKey) return null;
            V old = emptyKeyValue;
            hasEmptyKey = false;
            emptyKeyValue = null;
            size--;
            return old;
        }

        int idx = (int) (mix(key) & mask);
        while (true) {
            long existing = keys[idx];
            if (existing == key) return removeAt(idx);
            if (existing == EMPTY) return null;
            idx = (idx + 1) & mask;
        }
    }

    public void clear() {
        Arrays.fill(keys, EMPTY);
        Arrays.fill(values, null);
        hasEmptyKey = false;
        emptyKeyValue = null;
        size = 0;
    }

    public Iterable<V> values() {
        return () -> new Iterator<V>() {
            private boolean emptyPending = hasEmptyKey;
            private int index;
            private V nextValue;
            private boolean hasNextValue;

            @Override
            public boolean hasNext() {
                if (emptyPending) return true;
                findNext();
                return hasNextValue;
            }

            @Override
            public V next() {
                if (emptyPending) {
                    emptyPending = false;
                    return emptyKeyValue;
                }
                findNext();
                if (!hasNextValue) throw new NoSuchElementException();
                V out = nextValue;
                nextValue = null;
                hasNextValue = false;
                return out;
            }

            @SuppressWarnings("unchecked")
            private void findNext() {
                while (!hasNextValue && index < keys.length) {
                    if (keys[index] != EMPTY) {
                        nextValue = (V) values[index++];
                        hasNextValue = true;
                    } else {
                        index++;
                    }
                }
            }
        };
    }

    private void putInternal(long[] targetKeys, Object[] targetValues, int targetMask,
                             long key, Object value) {
        int idx = (int) (mix(key) & targetMask);
        while (true) {
            long existing = targetKeys[idx];
            if (existing == EMPTY) {
                targetKeys[idx] = key;
                targetValues[idx] = value;
                size++;
                return;
            }
            if (existing == key) {
                targetValues[idx] = value;
                return;
            }
            idx = (idx + 1) & targetMask;
        }
    }

    @SuppressWarnings("unchecked")
    private V removeAt(int idx) {
        V old = (V) values[idx];
        keys[idx] = EMPTY;
        values[idx] = null;
        size--;

        int next = (idx + 1) & mask;
        while (keys[next] != EMPTY) {
            long rehashKey = keys[next];
            Object rehashValue = values[next];
            keys[next] = EMPTY;
            values[next] = null;
            size--;
            putInternal(keys, values, mask, rehashKey, rehashValue);
            next = (next + 1) & mask;
        }

        return old;
    }

    private void grow() {
        int newCap = keys.length << 1;
        long[] oldKeys = keys;
        Object[] oldValues = values;

        keys = new long[newCap];
        values = new Object[newCap];
        mask = newCap - 1;
        threshold = (int) (newCap * LOAD_FACTOR);
        Arrays.fill(keys, EMPTY);

        size = hasEmptyKey ? 1 : 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                putInternal(keys, values, mask, oldKeys[i], oldValues[i]);
            }
        }
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
