/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ForgeQuickPlayCompatibilityTest {
    private static final class OneStaticManager {
        private static Object manager;
        private Object instanceManager;
    }

    private static final class AmbiguousManagers {
        private static Object first;
        private static Object second;
    }

    @Test
    void findsOneStaticFieldByTypeWithoutDependingOnItsName() {
        assertNotNull(ForgeQuickPlayCompatibility.findStaticField(
                OneStaticManager.class.getName(), Object.class));
    }

    @Test
    void rejectsAnAmbiguousInternalStructure() {
        assertNull(ForgeQuickPlayCompatibility.findStaticField(
                AmbiguousManagers.class.getName(), Object.class));
    }

    @Test
    void existingManagerDoesNoWork() {
        Object existing = new Object();
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();

        ForgeQuickPlayCompatibility.initializeIfMissing(
                existing,
                () -> {
                    loads.incrementAndGet();
                    return new Object();
                },
                ignored -> publications.incrementAndGet());

        assertEquals(0, loads.get());
        assertEquals(0, publications.get());
    }

    @Test
    void missingManagerIsLoadedBeforePublication() {
        Object loaded = new Object();
        AtomicReference<Object> published = new AtomicReference<>();

        ForgeQuickPlayCompatibility.initializeIfMissing(
                null, () -> loaded, published::set);

        assertSame(loaded, published.get());
    }

    @Test
    void failedReloadIsNeverPublished() {
        AtomicInteger publications = new AtomicInteger();

        assertThrows(
                IllegalStateException.class,
                () -> ForgeQuickPlayCompatibility.initializeIfMissing(
                        null,
                        () -> {
                            throw new IllegalStateException("reload failed");
                        },
                        ignored -> publications.incrementAndGet()));
        assertEquals(0, publications.get());
    }
}
