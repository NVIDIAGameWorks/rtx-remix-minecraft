/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SectionLeftRenderSetQueueTest {

    @BeforeEach
    void clearBeforeTest() {
        SectionLeftRenderSetQueue.clear();
    }

    @AfterEach
    void clearAfterTest() {
        SectionLeftRenderSetQueue.clear();
    }

    @Test
    void duplicateCoordinatesAreCoalescedWithoutChangingOrder() {
        for (int i = 0; i < 1_000; i++) {
            SectionLeftRenderSetQueue.enqueueEmptyCandidate(4, -2, 9);
        }
        SectionLeftRenderSetQueue.enqueueEmptyCandidate(-7, 3, 1);

        assertEquals(new SectionLeftRenderSetQueue.SectionCoord(4, -2, 9),
                SectionLeftRenderSetQueue.pollEmptyCandidate());
        assertEquals(new SectionLeftRenderSetQueue.SectionCoord(-7, 3, 1),
                SectionLeftRenderSetQueue.pollEmptyCandidate());
        assertNull(SectionLeftRenderSetQueue.pollEmptyCandidate());
    }

    @Test
    void coordinateCanBeQueuedAgainAfterItIsPolled() {
        var expected = new SectionLeftRenderSetQueue.SectionCoord(2, 5, -8);
        SectionLeftRenderSetQueue.enqueueEmptyCandidate(
                expected.x(), expected.y(), expected.z());
        assertEquals(expected, SectionLeftRenderSetQueue.pollEmptyCandidate());

        SectionLeftRenderSetQueue.enqueueEmptyCandidate(expected);
        assertEquals(expected, SectionLeftRenderSetQueue.pollEmptyCandidate());
        assertNull(SectionLeftRenderSetQueue.pollEmptyCandidate());
    }

    @Test
    void concurrentDuplicateProducersQueueEachCoordinateOnce() throws Exception {
        int producerCount = 8;
        Set<SectionLeftRenderSetQueue.SectionCoord> expected = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            expected.add(new SectionLeftRenderSetQueue.SectionCoord(i, i % 24 - 8, -i));
        }

        ExecutorService executor = Executors.newFixedThreadPool(producerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int producer = 0; producer < producerCount; producer++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int repetition = 0; repetition < 100; repetition++) {
                        for (SectionLeftRenderSetQueue.SectionCoord section : expected) {
                            SectionLeftRenderSetQueue.enqueueEmptyCandidate(section);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);

            Set<SectionLeftRenderSetQueue.SectionCoord> actual = new HashSet<>();
            int count = 0;
            SectionLeftRenderSetQueue.SectionCoord section;
            while ((section = SectionLeftRenderSetQueue.pollEmptyCandidate()) != null) {
                actual.add(section);
                count++;
            }
            assertEquals(expected, actual);
            assertEquals(expected.size(), count);
            assertNull(SectionLeftRenderSetQueue.pollEmptyCandidate());
        } finally {
            executor.shutdownNow();
        }
    }
}
