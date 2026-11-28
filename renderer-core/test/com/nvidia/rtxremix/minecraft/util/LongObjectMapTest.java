/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LongObjectMapTest {

    @Test
    void putGetAndRemovePreserveProbeChains() {
        LongObjectMap<String> map = new LongObjectMap<>(4);
        for (int i = 0; i < 128; i++) {
            map.put(key(i), "v" + i);
        }

        for (int i = 0; i < 128; i += 2) {
            assertEquals("v" + i, map.remove(key(i)));
        }

        assertEquals(64, map.size());
        for (int i = 0; i < 128; i++) {
            String expected = (i & 1) == 0 ? null : "v" + i;
            assertEquals(expected, map.get(key(i)));
        }
    }

    @Test
    void theSentinelKeyBehavesLikeANormalKey() {
        LongObjectMap<String> map = new LongObjectMap<>(4);
        map.put(Long.MIN_VALUE, "sentinel");
        map.put(42L, "normal");

        assertEquals("sentinel", map.get(Long.MIN_VALUE));
        assertEquals("normal", map.get(42L));
        assertEquals("sentinel", map.remove(Long.MIN_VALUE));
        assertNull(map.get(Long.MIN_VALUE));
        assertEquals("normal", map.get(42L));
    }

    @Test
    void valuesIteratesTheCurrentValues() {
        LongObjectMap<String> map = new LongObjectMap<>(4);
        map.put(1L, "a");
        map.put(2L, "b");
        map.put(Long.MIN_VALUE, "c");

        Set<String> values = new HashSet<>();
        for (String value : map.values()) {
            values.add(value);
        }

        assertEquals(Set.of("a", "b", "c"), values);
    }

    private static long key(int value) {
        return ((long) value << 32) | (value ^ 0x5a5a5a5aL);
    }
}
