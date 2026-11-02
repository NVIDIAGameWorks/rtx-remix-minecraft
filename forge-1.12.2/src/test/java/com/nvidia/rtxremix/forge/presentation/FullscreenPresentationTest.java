/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.presentation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.DisplayMode;
import static org.junit.Assert.*;

public class FullscreenPresentationTest {
    @Test public void preRuntimeFullscreenCallsRetainVanillaSemantics() throws Exception {
        Window window = new Window(); FullscreenPresentation.Controller policy = new FullscreenPresentation.Controller(window);
        DisplayMode selected = new DisplayMode(1366, 768);
        policy.mode(selected, true, 800, 600); policy.fullscreen(true); policy.resizable(true);
        assertSame(selected, window.mode); assertTrue(window.exclusive);
        assertNull(window.decoration); assertFalse(policy.borderless); assertFalse(policy.owned);
        assertEquals(Arrays.asList("mode", "exclusive:true", "resize:true"), window.trace);
    }

    @Test public void runtimeWindowedStartupDoesNotRecreateTheWindow() throws Exception {
        Window window = new Window(); FullscreenPresentation.Controller policy = new FullscreenPresentation.Controller(window);
        policy.prepare(false, 800, 600); policy.prepare(true, 800, 600);
        assertTrue(policy.owned); assertFalse(policy.borderless); assertTrue(window.trace.isEmpty());
    }

    @Test public void existingExclusiveStartupBecomesDesktopWindowedAndPreservesTheWindowRestoreSize() throws Exception {
        Window window = new Window(); window.exclusive = true; window.width = 2560; window.height = 1440;
        FullscreenPresentation.Controller policy = new FullscreenPresentation.Controller(window);
        policy.prepare(true, 1280, 720);
        assertTrue(policy.borderless); assertFalse(window.exclusive); assertFalse(window.mode.isFullscreenCapable());
        assertEquals(2560, window.width); assertEquals(1440, window.height); assertEquals("true", window.decoration);
        assertEquals(0, window.x); assertEquals(0, window.y); assertFalse(window.resize);
        assertEquals(Arrays.asList("decoration:true", "resize:false", "mode", "exclusive:false", "location:0,0"), window.trace);
        policy.mode(new DisplayMode(1280, 720), false, 1280, 720);
        assertEquals(1280, window.width); assertEquals(720, window.height);
        assertEquals(111, window.x); assertEquals(222, window.y); assertNull(window.decoration); assertTrue(window.resize);
        assertFalse(policy.borderless); assertTrue(policy.owned);
    }

    @Test public void repeatedF11RestoresExactDecorationPositionAndLatestResizedWindowWithoutReenteringExclusive() throws Exception {
        Window window = new Window(); window.decoration = "false"; window.resize = false;
        FullscreenPresentation.Controller policy = new FullscreenPresentation.Controller(window); policy.prepare(false, 800, 600);
        for (int cycle = 0; cycle < 2; cycle++) {
            int width = window.width, height = window.height, x = window.x, y = window.y;
            policy.mode(window.desktop(), true, 800, 600); policy.fullscreen(true);
            assertFalse(window.exclusive); assertTrue(policy.borderless);
            // Renderer enable/disable changes no presentation ownership; a repeated fullscreen
            // request must continue to avoid the competing exclusive GL display mode.
            policy.fullscreen(true); assertFalse(window.exclusive);
            policy.mode(new DisplayMode(width, height), false, width, height); policy.fullscreen(false);
            policy.resizable(false); policy.resizable(true);
            assertEquals(width, window.width); assertEquals(height, window.height); assertEquals(x, window.x); assertEquals(y, window.y);
            assertEquals("false", window.decoration); assertFalse(window.resize); assertFalse(window.exclusive);
            window.width = 1450; window.height = 900; window.x = 245; window.y = 130;
        }
    }

    @Test public void equalDesktopSizeStillRecreatesForDecorationAndShutdownDoesNotRecreate() throws Exception {
        Window window = new Window(); window.width = 2560; window.height = 1440;
        FullscreenPresentation.Controller policy = new FullscreenPresentation.Controller(window); policy.prepare(false, 800, 600);
        policy.mode(window.desktop(), true, 800, 600);
        assertEquals(1, window.recreations);
        policy.afterShutdown(); assertEquals(1, window.recreations); assertNull(window.decoration);
        assertFalse(policy.owned); assertFalse(policy.borderless);
    }

    @Test public void failedInitialModeChangeRestoresPolicyWithoutAcquiringOwnershipOrRecreatingAgain() throws Exception {
        Window window = new Window(); window.fail = true;
        FullscreenPresentation.Controller policy = new FullscreenPresentation.Controller(window);
        assertSame(window.modeFailure, assertThrows(LWJGLException.class, () -> policy.prepare(true, 800, 600)));
        assertFalse(policy.owned); assertFalse(policy.borderless); assertNull(window.decoration); assertTrue(window.resize);
        assertEquals(Arrays.asList("decoration:true", "resize:false", "mode", "decoration:null", "resize:true"), window.trace);
        policy.afterShutdown(); assertNull(window.decoration); assertEquals(0, window.recreations);
    }

    @Test public void failedF11EntryRestoresPolicyBeforeVanillaCanCatchTheException() throws Exception {
        Window window = new Window(); window.decoration = "false";
        FullscreenPresentation.Controller policy = new FullscreenPresentation.Controller(window); policy.prepare(false, 800, 600);
        window.fail = true;
        assertSame(window.modeFailure, assertThrows(LWJGLException.class,
                () -> policy.mode(window.desktop(), true, 1280, 720)));
        assertTrue(policy.owned); assertFalse(policy.borderless); assertEquals("false", window.decoration); assertTrue(window.resize);
        assertEquals(Arrays.asList("decoration:true", "resize:false", "mode", "decoration:false", "resize:true"), window.trace);
        // An explicit later windowed request sees the original policy. The failure
        // handler itself must neither retry nor issue fullscreen/location calls.
        window.fail = false; policy.mode(new DisplayMode(1280, 720), false, 1280, 720);
        assertEquals(1, window.recreations); assertEquals("false", window.decoration); assertTrue(window.resize);
    }

    @Test public void failedRepeatedEntryDoesNotLeaveAFalseBorderlessState() throws Exception {
        Window window = new Window(); FullscreenPresentation.Controller policy = new FullscreenPresentation.Controller(window);
        policy.prepare(true, 1280, 720); window.trace.clear(); window.fail = true;
        assertThrows(LWJGLException.class, () -> policy.mode(window.desktop(), true, 1280, 720));
        assertTrue(policy.owned); assertFalse(policy.borderless); assertNull(window.decoration); assertTrue(window.resize);
        assertEquals(Arrays.asList("decoration:true", "resize:false", "mode", "decoration:null", "resize:true"), window.trace);
        assertEquals(1, window.recreations);
    }

    private static final class Window implements FullscreenPresentation.Access {
        boolean exclusive, resize = true, fail; int width = 1280, height = 720, x = 111, y = 222, recreations;
        String decoration; DisplayMode mode; final List<String> trace = new ArrayList<>();
        final LWJGLException modeFailure = new LWJGLException("Expected failed recreation");
        public boolean exclusive() { return exclusive; } public int width() { return width; } public int height() { return height; }
        public int x() { return x; } public int y() { return y; } public boolean resizable() { return resize; }
        public String decoration() { return decoration; } public DisplayMode desktop() { return new DisplayMode(2560, 1440); }
        public void decoration(String value) { decoration = value; trace.add("decoration:" + value); }
        public void resizable(boolean value) { resize = value; trace.add("resize:" + value); }
        public void mode(DisplayMode value) throws LWJGLException {
            trace.add("mode"); if (fail) throw modeFailure;
            mode = value; width = value.getWidth(); height = value.getHeight(); recreations++;
            if (!value.isFullscreenCapable()) exclusive = false;
        }
        public void fullscreen(boolean value) { exclusive = value; trace.add("exclusive:" + value); }
        public void location(int a, int b) { x = a; y = b; trace.add("location:" + a + "," + b); }
    }
}
