/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

import java.util.Arrays;

/**
 * Minimal open-addressing hash map from {@code int} keys to object values.
 * Used for per-column section lookup on reachability hot paths.
 */
public final class IntObjectMap<V> {

    private static final int EMPTY = Integer.MIN_VALUE;
    private static final float LOAD_FACTOR = 0.75f;

    private int[] keys;
    private Object[] values;
    private int mask;
    private int size;
    private int threshold;
    private boolean hasEmptyKey;
    private V emptyKeyValue;

    public IntObjectMap() {
        this(16);
    }

    public IntObjectMap(int initialCapacity) {
        int cap = Integer.highestOneBit(Math.max(initialCapacity, 4) - 1) << 1;
        keys = new int[cap];
        values = new Object[cap];
        mask = cap - 1;
        threshold = (int) (cap * LOAD_FACTOR);
        Arrays.fill(keys, EMPTY);
    }

    @SuppressWarnings("unchecked")
    public V get(int key) {
        if (key == EMPTY) return hasEmptyKey ? emptyKeyValue : null;
        int idx = mix(key) & mask;
        while (true) {
            int existing = keys[idx];
            if (existing == key) return (V) values[idx];
            if (existing == EMPTY) return null;
            idx = (idx + 1) & mask;
        }
    }

    public boolean containsKey(int key) {
        if (key == EMPTY) return hasEmptyKey;
        int idx = mix(key) & mask;
        while (true) {
            int existing = keys[idx];
            if (existing == key) return true;
            if (existing == EMPTY) return false;
            idx = (idx + 1) & mask;
        }
    }

    public V put(int key, V value) {
        if (key == EMPTY) {
            V old = hasEmptyKey ? emptyKeyValue : null;
            if (!hasEmptyKey) {
                hasEmptyKey = true;
                size++;
            }
            emptyKeyValue = value;
            return old;
        }
        if (size >= threshold) grow();
        return putInternal(keys, values, mask, key, value);
    }

    @SuppressWarnings("unchecked")
    public V remove(int key) {
        if (key == EMPTY) {
            if (!hasEmptyKey) return null;
            V old = emptyKeyValue;
            hasEmptyKey = false;
            emptyKeyValue = null;
            size--;
            return old;
        }

        int idx = mix(key) & mask;
        while (true) {
            int existing = keys[idx];
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

    @SuppressWarnings("unchecked")
    private V putInternal(int[] targetKeys, Object[] targetValues, int targetMask,
                          int key, Object value) {
        int idx = mix(key) & targetMask;
        while (true) {
            int existing = targetKeys[idx];
            if (existing == EMPTY) {
                targetKeys[idx] = key;
                targetValues[idx] = value;
                size++;
                return null;
            }
            if (existing == key) {
                V old = (V) targetValues[idx];
                targetValues[idx] = value;
                return old;
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
            int rehashKey = keys[next];
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
        int[] oldKeys = keys;
        Object[] oldValues = values;

        keys = new int[newCap];
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

    private static int mix(int x) {
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }
}
