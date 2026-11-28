/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

/**
 * Chunk unloads the integrated server owes the local client, held back so they
 * leave a few per tick instead of a whole trailing row at once.
 *
 * <p>Keys are chunk positions ({@code ChunkPos.toLong}) in the order they were
 * queued, each at most once. The invariants that keep the queue safe:
 * <ul>
 *   <li>It is touched only on the integrated-server thread, like the sender that
 *       owns it.</li>
 *   <li>A key is never both pending for delivery in the sender and queued here: the
 *       sender drops a pending column instead of queueing its unload, and re-adding a
 *       column {@link #cancelForResend cancels} its queued unload before the column is
 *       pending again. So an unload never follows the chunk data that re-sent the
 *       column.</li>
 *   <li>A column whose unload was cancelled is one the client still holds while the
 *       sender treats it as pending. It stays remembered as held until its data is
 *       {@link #markSent written} or the sender {@link #dropPending drops} it without
 *       a packet, in which case the unload is owed again and the sender re-queues it.
 *       Vanilla never needs this: it writes the unload at once, so a pending column is
 *       one the client never received.</li>
 *   <li>A key whose column has reached the last band of the client's chunk ring is
 *       sent in the tick it gets there, whatever the budget: one more crossing and the
 *       client would ignore the unload and keep the stale column until its slot is
 *       reused.</li>
 * </ul>
 */
public final class PacedUnloadQueue {

    private final LongLinkedOpenHashSet keys = new LongLinkedOpenHashSet();
    private final LongOpenHashSet heldWhilePending = new LongOpenHashSet();

    /** Queues an unload for {@code key}; a key already queued keeps its place. */
    public void enqueue(long key) {
        keys.add(key);
    }

    /**
     * The sender is re-adding {@code key} to its pending set. A queued unload for it is
     * cancelled, because the data about to follow reloads the client's copy in place, and
     * that copy is remembered as held until the data goes out or the column is dropped.
     *
     * @return whether a queued unload was cancelled
     */
    public boolean cancelForResend(long key) {
        if (!keys.remove(key)) {
            return false;
        }
        heldWhilePending.add(key);
        return true;
    }

    /** The data for {@code key} was written: the client's copy is current again. */
    public void markSent(long key) {
        heldWhilePending.remove(key);
    }

    /**
     * The sender is about to unload {@code key}, or to drop it from its pending set
     * without a packet. Forgets any held copy; when there was one and {@code unloadOwed}
     * (the column is pending and the player alive, vanilla's own condition for writing
     * an unload), the unload is queued again.
     *
     * @return whether an unload was queued again
     */
    public boolean dropPending(long key, boolean unloadOwed) {
        if (!heldWhilePending.remove(key)) {
            return false;
        }
        if (unloadOwed) {
            enqueue(key);
        }
        return unloadOwed;
    }

    public int size() {
        return keys.size();
    }

    /** Forgets every queued unload and held copy. */
    public void clear() {
        keys.clear();
        heldWhilePending.clear();
    }

    /** Sends every queued key, oldest first, and returns how many. */
    public int drainAll(LongConsumer send) {
        int sent = 0;
        while (!keys.isEmpty()) {
            send.accept(keys.removeFirstLong());
            sent++;
        }
        return sent;
    }

    /**
     * Sends every key {@code lastChance} accepts, oldest first, then the oldest
     * remaining keys until {@code budget} keys have been sent in total. Last-chance
     * keys count against the budget but are never held back by it.
     *
     * @return the number of keys sent
     */
    public int drain(int budget, LongPredicate lastChance, LongConsumer send) {
        int sent = 0;
        LongIterator it = keys.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            if (lastChance.test(key)) {
                it.remove();
                send.accept(key);
                sent++;
            }
        }
        while (sent < budget && !keys.isEmpty()) {
            send.accept(keys.removeFirstLong());
            sent++;
        }
        return sent;
    }
}
