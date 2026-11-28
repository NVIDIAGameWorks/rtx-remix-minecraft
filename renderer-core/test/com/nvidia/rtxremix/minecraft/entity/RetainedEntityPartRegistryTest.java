/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class RetainedEntityPartRegistryTest {

    @Test
    void retainsSeenPartsAndRetiresMissingPartsAtFrameEnd() {
        RetainedEntityPartRegistry<Object, String> registry = new RetainedEntityPartRegistry<>();
        Object owner = new Object();
        List<String> retired = new ArrayList<>();
        AtomicInteger creates = new AtomicInteger();

        registry.beginFrame();
        String first = registry.retain(owner, 1, 2, 3, value -> true,
                () -> "part-" + creates.incrementAndGet(), retired::add);
        registry.endFrame(retired::add);

        registry.beginFrame();
        String second = registry.retain(owner, 1, 2, 3, value -> true,
                () -> "part-" + creates.incrementAndGet(), retired::add);
        registry.endFrame(retired::add);

        assertSame(first, second);
        assertEquals(1, creates.get());
        assertTrue(retired.isEmpty());
        assertEquals(1, registry.size());

        registry.beginFrame();
        registry.endFrame(retired::add);

        assertEquals(List.of(first), retired);
        assertEquals(0, registry.size());
    }

    @Test
    void ownerIdentityPreventsEqualObjectsFromSharingNativeState() {
        RetainedEntityPartRegistry<EqualOwner, String> registry = new RetainedEntityPartRegistry<>();
        EqualOwner firstOwner = new EqualOwner(7);
        EqualOwner secondOwner = new EqualOwner(7);
        AtomicInteger creates = new AtomicInteger();

        registry.beginFrame();
        String first = registry.retain(firstOwner, 0, 0, 0, value -> true,
                () -> "part-" + creates.incrementAndGet(), value -> fail("unexpected retire"));
        String second = registry.retain(secondOwner, 0, 0, 0, value -> true,
                () -> "part-" + creates.incrementAndGet(), value -> fail("unexpected retire"));
        registry.endFrame(value -> fail("unexpected retire"));

        assertNotSame(first, second);
        assertEquals(2, creates.get());
        assertEquals(2, registry.size());
    }

    @Test
    void replacesInvalidPartsAndSupportsTargetedInvalidation() {
        RetainedEntityPartRegistry<Object, String> registry = new RetainedEntityPartRegistry<>();
        Object owner = new Object();
        List<String> retired = new ArrayList<>();

        registry.beginFrame();
        registry.retain(owner, 0, 1, 0, value -> true, () -> "old", retired::add);
        registry.retain(owner, 0, 1, 1, value -> true, () -> "other", retired::add);
        registry.endFrame(retired::add);

        registry.beginFrame();
        String replacement = registry.retain(owner, 0, 1, 0, value -> false,
                () -> "replacement", retired::add);
        registry.retain(owner, 0, 1, 1, value -> true, () -> "unused", retired::add);
        registry.discardIf("other"::equals, retired::add);
        registry.endFrame(retired::add);

        assertEquals("replacement", replacement);
        assertEquals(List.of("old", "other"), retired);
        assertEquals(1, registry.size());
    }

    @Test
    void discardOwnerRetiresEveryPartOfThatOwnerAndNoOther() {
        RetainedEntityPartRegistry<Object, String> registry = new RetainedEntityPartRegistry<>();
        Object failing = new Object();
        Object bystander = new Object();
        List<String> retired = new ArrayList<>();

        registry.beginFrame();
        registry.retain(failing, 0, 0, 0, value -> true, () -> "head", retired::add);
        registry.retain(failing, 0, 1, 0, value -> true, () -> "body", retired::add);
        registry.retain(bystander, 0, 0, 0, value -> true, () -> "other", retired::add);
        registry.discardOwner(failing, retired::add);

        assertEquals(2, retired.size());
        assertTrue(retired.containsAll(List.of("head", "body")));
        assertEquals(1, registry.size());

        registry.discardOwner(failing, value -> fail("nothing left to retire"));
        registry.endFrame(value -> fail("the bystander was seen this frame"));
        assertEquals(1, registry.size());
    }

    @Test
    void ownersOfUnrelatedClassesNeverShareSlots() {
        RetainedEntityPartRegistry<Object, String> registry = new RetainedEntityPartRegistry<>();
        Object stringOwner = "chest";
        Object integerOwner = 7;
        List<String> retired = new ArrayList<>();
        AtomicInteger creates = new AtomicInteger();

        registry.beginFrame();
        String stringPart = registry.retain(stringOwner, 0, 0, 0, value -> true,
                () -> "part-" + creates.incrementAndGet(), retired::add);
        String integerPart = registry.retain(integerOwner, 0, 0, 0, value -> true,
                () -> "part-" + creates.incrementAndGet(), retired::add);
        registry.endFrame(retired::add);

        assertNotSame(stringPart, integerPart);
        assertEquals(2, creates.get());
        assertTrue(retired.isEmpty());

        registry.beginFrame();
        assertSame(integerPart, registry.retain(integerOwner, 0, 0, 0, value -> true,
                () -> "part-" + creates.incrementAndGet(), retired::add));
        registry.endFrame(retired::add);

        assertEquals(List.of(stringPart), retired);
        assertEquals(1, registry.size());
    }

    @Test
    void enforcesBalancedFrameOwnership() {
        RetainedEntityPartRegistry<Object, Object> registry = new RetainedEntityPartRegistry<>();

        assertThrows(IllegalStateException.class, () -> registry.endFrame(value -> { }));
        registry.beginFrame();
        assertThrows(IllegalStateException.class, registry::beginFrame);
        registry.endFrame(value -> { });
    }

    private record EqualOwner(int id) { }
}
