/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

import java.util.Arrays;

/**
 * Minimal open-addressing hash set for {@code long} values.  Eliminates the
 * autoboxing overhead of {@code HashSet<Long>}.  Same design as
 * {@link LongIntMap}: linear probing, power-of-two capacity, 0.75 load factor.
 */
public final class LongHashSet {

    private static final long EMPTY = Long.MIN_VALUE;
    private static final float LOAD_FACTOR = 0.75f;

    private long[] keys;
    private int mask;
    private int size;
    private int threshold;
    private boolean hasEmptyKey;

    public LongHashSet() { this(256); }

    public LongHashSet(int initialCapacity) {
        int cap = Integer.highestOneBit(Math.max(initialCapacity, 4) - 1) << 1;
        keys = new long[cap];
        mask = cap - 1;
        threshold = (int) (cap * LOAD_FACTOR);
        Arrays.fill(keys, EMPTY);
    }

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }

    public boolean contains(long key) {
        if (key == EMPTY) return hasEmptyKey;
        int idx = (int) (mix(key) & mask);
        while (true) {
            long k = keys[idx];
            if (k == key) return true;
            if (k == EMPTY) return false;
            idx = (idx + 1) & mask;
        }
    }

    public boolean add(long key) {
        if (key == EMPTY) {
            if (hasEmptyKey) return false;
            hasEmptyKey = true; size++; return true;
        }
        if (size >= threshold) grow();
        return addInternal(keys, mask, key);
    }

    private boolean addInternal(long[] k, int m, long key) {
        int idx = (int) (mix(key) & m);
        while (true) {
            long existing = k[idx];
            if (existing == EMPTY) { k[idx] = key; size++; return true; }
            if (existing == key) return false;
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
        int newMask = newCap - 1;
        Arrays.fill(newKeys, EMPTY);

        int oldSize = size;
        if (hasEmptyKey) oldSize--;
        size = hasEmptyKey ? 1 : 0;

        for (int i = 0; i < keys.length; i++)
            if (keys[i] != EMPTY) addInternal(newKeys, newMask, keys[i]);

        keys = newKeys;
        mask = newMask;
        threshold = (int) (newCap * LOAD_FACTOR);
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
