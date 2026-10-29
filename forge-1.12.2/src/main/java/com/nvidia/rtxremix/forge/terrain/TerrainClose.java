/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

/** Retryable teardown: pages retain their textures until every page release succeeds. */
final class TerrainClose {
    private boolean detached;
    private boolean complete;

    void close(Runnable detach, Runnable pages, Runnable textures) {
        if (complete) return;
        Throwable failure = null;
        if (!detached) {
            try { detach.run(); detached = true; }
            catch (RuntimeException | LinkageError error) { failure = error; }
        }
        boolean pagesReleased = false;
        try { pages.run(); pagesReleased = true; }
        catch (RuntimeException | LinkageError error) { failure = collect(failure, error); }
        if (pagesReleased) {
            try { textures.run(); }
            catch (RuntimeException | LinkageError error) { failure = collect(failure, error); }
        }
        if (failure != null) throw new IllegalStateException("Cannot release terrain scene", failure);
        complete = true;
    }

    private static Throwable collect(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }
}
