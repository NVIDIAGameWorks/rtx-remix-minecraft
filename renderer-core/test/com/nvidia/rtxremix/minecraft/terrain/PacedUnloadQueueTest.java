/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import org.junit.jupiter.api.Test;

import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacedUnloadQueueTest {

    private static final long A = 1;
    private static final long B = 2;
    private static final long C = 3;
    private static final long D = 4;
    private static final LongPredicate NONE = key -> false;

    private final PacedUnloadQueue queue = new PacedUnloadQueue();
    private final LongList sent = new LongArrayList();

    private void enqueue(long... keys) {
        for (long key : keys) {
            queue.enqueue(key);
        }
    }

    @Test
    void drainsTheOldestKeysUpToTheBudget() {
        enqueue(A, B, C);
        assertEquals(2, queue.drain(2, NONE, sent::add));
        assertEquals(LongArrayList.of(A, B), sent);
        assertEquals(1, queue.size());
    }

    @Test
    void aKeyEnqueuedTwiceIsSentOnce() {
        enqueue(A, A);
        assertEquals(1, queue.size());
        assertEquals(1, queue.drainAll(sent::add));
        assertEquals(LongArrayList.of(A), sent);
    }

    @Test
    void cancelRemovesAQueuedKey() {
        enqueue(A);
        assertTrue(queue.cancelForResend(A));
        assertEquals(0, queue.drain(10, NONE, sent::add));
        assertTrue(sent.isEmpty());
        assertFalse(queue.cancelForResend(A));
    }

    @Test
    void cancellingOneKeyKeepsTheOrderOfTheRest() {
        enqueue(A, B);
        queue.cancelForResend(A);
        enqueue(C);
        queue.drain(10, NONE, sent::add);
        assertEquals(LongArrayList.of(B, C), sent);
    }

    @Test
    void drainAllEmptiesTheQueueAndReturnsTheCount() {
        enqueue(A, B, C);
        assertEquals(3, queue.drainAll(sent::add));
        assertEquals(LongArrayList.of(A, B, C), sent);
        assertEquals(0, queue.size());
    }

    @Test
    void zeroBudgetSendsNothingWithoutLastChanceKeys() {
        enqueue(A);
        assertEquals(0, queue.drain(0, NONE, sent::add));
        assertEquals(1, queue.size());
    }

    @Test
    void lastChanceKeysAreSentRegardlessOfTheBudget() {
        enqueue(A, B, C);
        assertEquals(1, queue.drain(0, key -> key == C, sent::add));
        assertEquals(LongArrayList.of(C), sent);
        sent.clear();
        queue.drainAll(sent::add);
        assertEquals(LongArrayList.of(A, B), sent);
    }

    @Test
    void lastChanceKeysGoFirstAndCountAgainstTheBudget() {
        enqueue(A, B, C, D);
        assertEquals(2, queue.drain(2, key -> key == C, sent::add));
        assertEquals(LongArrayList.of(C, A), sent);
        sent.clear();
        queue.drainAll(sent::add);
        assertEquals(LongArrayList.of(B, D), sent);
    }

    @Test
    void aLastChanceKeyWithinTheBudgetIsSentOnce() {
        enqueue(A, B);
        assertEquals(2, queue.drain(5, key -> key == A, sent::add));
        assertEquals(LongArrayList.of(A, B), sent);
        assertEquals(0, queue.size());
    }

    @Test
    void aResendCancelsTheQueuedUnloadAndRemembersTheClientCopy() {
        enqueue(A);
        assertTrue(queue.cancelForResend(A));
        assertEquals(0, queue.size());
        // The column leaves again before its data went out: the copy the cancel spared
        // owes an unload, which is queued again and sent exactly once.
        assertTrue(queue.dropPending(A, true));
        assertEquals(1, queue.size());
        assertEquals(1, queue.drainAll(sent::add));
        assertEquals(LongArrayList.of(A), sent);
        assertFalse(queue.dropPending(A, true));
    }

    @Test
    void aDroppedCopyOwedToNobodyIsForgotten() {
        enqueue(A);
        queue.cancelForResend(A);
        // Vanilla sends a dead player nothing; the held copy is forgotten, not queued.
        assertFalse(queue.dropPending(A, false));
        assertEquals(0, queue.size());
        assertFalse(queue.dropPending(A, true));
    }

    @Test
    void aResendWhoseDataWentOutOwesNothing() {
        enqueue(A);
        queue.cancelForResend(A);
        queue.markSent(A);
        assertFalse(queue.dropPending(A, true));
    }

    @Test
    void aResendOfAnUnqueuedColumnRemembersNothing() {
        assertFalse(queue.cancelForResend(B));
        assertFalse(queue.dropPending(B, true));
    }

    @Test
    void clearForgetsQueuedUnloadsAndHeldCopies() {
        enqueue(A, B);
        queue.cancelForResend(A);
        queue.clear();
        assertEquals(0, queue.size());
        assertFalse(queue.dropPending(A, true));
        assertEquals(0, queue.drainAll(sent::add));
    }
}
