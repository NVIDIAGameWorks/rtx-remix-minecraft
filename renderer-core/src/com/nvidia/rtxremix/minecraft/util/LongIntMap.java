/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

import java.util.Arrays;

/**
 * Minimal open-addressing hash map from {@code long} keys to {@code int}
 * values.  Eliminates the autoboxing overhead of {@code HashMap<Long, Integer>}
 * which matters on the BFS hot path (~5 Hz recomputes, thousands of entries).
 *
 * <p>Uses linear probing with a power-of-two capacity and a load factor of
 * 0.75.  The sentinel value {@link #EMPTY} marks unused slots; keys that
 * happen to equal the sentinel are handled by a separate flag.
 */
public final class LongIntMap {

    private static final long EMPTY = Long.MIN_VALUE;
    private static final float LOAD_FACTOR = 0.75f;

    private long[] keys;
    private int[] values;
    private int mask;
    private int size;
    private int threshold;

    // Handle the (extremely unlikely) case where EMPTY is a valid key
    private boolean hasEmptyKey;
    private int emptyKeyValue;

    public LongIntMap() { this(256); }

    public LongIntMap(int initialCapacity) {
        int cap = Integer.highestOneBit(Math.max(initialCapacity, 4) - 1) << 1;
        keys = new long[cap];
        values = new int[cap];
        mask = cap - 1;
        threshold = (int) (cap * LOAD_FACTOR);
        Arrays.fill(keys, EMPTY);
    }

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }

    /** Returns the value for {@code key}, or {@code defaultValue} if absent. */
    public int getOrDefault(long key, int defaultValue) {
        if (key == EMPTY) return hasEmptyKey ? emptyKeyValue : defaultValue;
        int idx = (int) (mix(key) & mask);
        while (true) {
            long k = keys[idx];
            if (k == key) return values[idx];
            if (k == EMPTY) return defaultValue;
            idx = (idx + 1) & mask;
        }
    }

    /** Returns true if the map contains {@code key}. */
    public boolean containsKey(long key) {
        if (key == EMPTY) return hasEmptyKey;
        int idx = (int) (mix(key) & mask);
        while (true) {
            long k = keys[idx];
            if (k == key) return true;
            if (k == EMPTY) return false;
            idx = (idx + 1) & mask;
        }
    }

    /** Associates {@code key} with {@code value}. */
    public void put(long key, int value) {
        if (key == EMPTY) {
            if (!hasEmptyKey) { hasEmptyKey = true; size++; }
            emptyKeyValue = value;
            return;
        }
        if (size >= threshold) grow();
        putInternal(keys, values, mask, key, value);
    }

    private boolean putInternal(long[] k, int[] v, int m, long key, int value) {
        int idx = (int) (mix(key) & m);
        while (true) {
            long existing = k[idx];
            if (existing == EMPTY) { k[idx] = key; v[idx] = value; size++; return true; }
            if (existing == key) { v[idx] = value; return false; }
            idx = (idx + 1) & m;
        }
    }

    public void clear() {
        Arrays.fill(keys, EMPTY);
        size = 0;
        hasEmptyKey = false;
    }

    private void grow() {
        int newCap = keys.length << 1;
        long[] newKeys = new long[newCap];
        int[] newValues = new int[newCap];
        int newMask = newCap - 1;
        Arrays.fill(newKeys, EMPTY);

        int oldSize = size;
        if (hasEmptyKey) oldSize--; // don't count sentinel key in rehash
        size = hasEmptyKey ? 1 : 0; // reset, putInternal increments

        for (int i = 0; i < keys.length; i++)
            if (keys[i] != EMPTY) putInternal(newKeys, newValues, newMask, keys[i], values[i]);

        keys = newKeys;
        values = newValues;
        mask = newMask;
        threshold = (int) (newCap * LOAD_FACTOR);
    }

    /** Stafford variant 13 finalizer from SplitMix64. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
