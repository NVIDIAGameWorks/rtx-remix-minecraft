/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntObjectMapTest {

    @Test
    void putGetAndRemovePreserveProbeChains() {
        IntObjectMap<String> map = new IntObjectMap<>(4);
        for (int i = -64; i < 64; i++) {
            map.put(key(i), "v" + i);
        }

        for (int i = -64; i < 64; i += 2) {
            assertEquals("v" + i, map.remove(key(i)));
        }

        for (int i = -64; i < 64; i++) {
            String expected = (i & 1) == 0 ? null : "v" + i;
            assertEquals(expected, map.get(key(i)));
            assertEquals(expected != null, map.containsKey(key(i)));
        }
    }

    @Test
    void theSentinelKeyBehavesLikeANormalKey() {
        IntObjectMap<String> map = new IntObjectMap<>(4);
        map.put(Integer.MIN_VALUE, "sentinel");
        map.put(7, "normal");

        assertTrue(map.containsKey(Integer.MIN_VALUE));
        assertEquals("sentinel", map.get(Integer.MIN_VALUE));
        assertEquals("normal", map.get(7));
        assertEquals("sentinel", map.remove(Integer.MIN_VALUE));
        assertFalse(map.containsKey(Integer.MIN_VALUE));
        assertEquals("normal", map.get(7));
    }

    @Test
    void updatingAnExistingKeyReplacesItsValue() {
        IntObjectMap<String> map = new IntObjectMap<>(4);
        map.put(3, "old");
        map.put(3, "new");

        assertEquals("new", map.get(3));
    }

    private static int key(int value) {
        return value * 33 + 17;
    }
}
