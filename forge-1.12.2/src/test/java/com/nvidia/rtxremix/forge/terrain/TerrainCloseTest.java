/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TerrainCloseTest {
    @Test public void failedPagesKeepTexturesAliveUntilTheRetrySucceeds() {
        TerrainClose lifecycle = new TerrainClose();
        List<String> calls = new ArrayList<>();
        Runnable detach = () -> calls.add("detach");
        Runnable textures = () -> calls.add("textures");
        try {
            lifecycle.close(detach, () -> { calls.add("pages-fail"); throw new IllegalStateException("busy"); }, textures);
            fail("Expected failed page release");
        } catch (IllegalStateException expected) { }
        assertEquals(Arrays.asList("detach", "pages-fail"), calls);
        lifecycle.close(detach, () -> calls.add("pages-ok"), textures);
        assertEquals(Arrays.asList("detach", "pages-fail", "pages-ok", "textures"), calls);
        lifecycle.close(() -> fail(), () -> fail(), () -> fail());
    }

    @Test public void failedTextureReleaseRetriesWithoutDetachingAgain() {
        TerrainClose lifecycle = new TerrainClose();
        int[] calls = new int[3];
        Runnable detach = () -> calls[0]++;
        Runnable pages = () -> calls[1]++;
        Runnable textures = () -> { if (calls[2]++ == 0) throw new UnsatisfiedLinkError("texture release"); };
        try { lifecycle.close(detach, pages, textures); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getCause() instanceof UnsatisfiedLinkError); }
        lifecycle.close(detach, pages, textures);
        assertArrayEquals(new int[] {1, 2, 2}, calls);
    }

    @Test public void failedListenerDetachDoesNotPreventNativeCleanup() {
        TerrainClose lifecycle = new TerrainClose();
        int[] calls = new int[3];
        Runnable detach = () -> { if (calls[0]++ == 0) throw new IllegalStateException("listener"); };
        Runnable pages = () -> calls[1]++;
        Runnable textures = () -> calls[2]++;
        try { lifecycle.close(detach, pages, textures); fail(); }
        catch (IllegalStateException expected) { }
        assertArrayEquals(new int[] {1, 1, 1}, calls);
        lifecycle.close(detach, pages, textures);
        assertArrayEquals(new int[] {2, 2, 2}, calls);
    }
}
