/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.presentation;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;

public class LegacyDisplayUpdateTest {
    @Test public void presentationRetainsWindowResizeAndPollingUnderTheDisplayLock() {
        Fake access = new Fake();
        access.resized = true;
        LegacyDisplayUpdate.update(access, () -> { assertTrue(Thread.holdsLock(access)); access.trace.add("present"); });
        assertEquals(Arrays.asList("present", "resize", "dimensions", "poll"), access.trace);
        assertTrue(access.windowResized);
        assertEquals(913, access.width);
        assertEquals(567, access.height);
        access.trace.clear();
        access.resized = false;
        LegacyDisplayUpdate.update(access, () -> access.trace.add("present"));
        assertFalse(access.windowResized);
        assertEquals(Arrays.asList("present", "resize", "poll"), access.trace);
    }

    @Test public void fullscreenAndEmbeddedDisplayKeepTheirResizeRules() {
        Fake access = new Fake();
        access.fullscreen = true;
        access.resized = true;
        LegacyDisplayUpdate.update(access, () -> access.trace.add("present"));
        assertFalse(access.windowResized);
        assertEquals(Arrays.asList("present", "poll"), access.trace);
        access.trace.clear();
        access.fullscreen = false;
        access.parented = access.parentResized = true;
        LegacyDisplayUpdate.update(access, () -> access.trace.add("present"));
        assertTrue(access.windowResized);
        assertFalse(access.parentResized);
        assertEquals(Arrays.asList("present", "reshape", "poll"), access.trace);
    }

    @Test public void failedPresentationAndMessagePumpReleaseTheLock() {
        Fake access = new Fake();
        access.created = false;
        assertThrows(IllegalStateException.class, () -> LegacyDisplayUpdate.update(access, () -> fail()));
        access.created = true;
        assertThrows(IllegalStateException.class, () -> LegacyDisplayUpdate.update(access,
                () -> { throw new IllegalStateException(); }));
        assertTrue(access.trace.isEmpty());
        access.failPoll = true;
        assertThrows(IllegalStateException.class, () -> LegacyDisplayUpdate.update(access, () -> { }));
        assertFalse(Thread.holdsLock(access));
    }

    @Test public void pinnedLwjglUpdateHasTheExpectedResizeAndPollingTail() throws Exception {
        ClassNode type = new ClassNode(Opcodes.ASM5);
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("org/lwjgl/opengl/Display.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(type, 0);
        }
        List<String> tail = new ArrayList<>();
        for (MethodNode method : type.methods) {
            if (!method.name.equals("update") || !method.desc.equals("(Z)V")) continue;
            boolean afterSwap = false;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.name.equals("swapBuffers")) afterSwap = true;
                else if (afterSwap && (call.owner.equals("org/lwjgl/opengl/Display")
                        || call.owner.equals("org/lwjgl/opengl/DisplayImplementation"))) tail.add(call.name);
            }
        }
        assertEquals(Arrays.asList("isFullscreen", "wasResized", "getWidth", "getHeight", "reshape", "processMessages"), tail);
    }

    private static final class Fake implements LegacyDisplayUpdate.Access {
        final List<String> trace = new ArrayList<>();
        boolean created = true, fullscreen, parented, resized, parentResized, windowResized, failPoll;
        int width, height;
        public Object lock() { return this; }
        public boolean created() { return created; }
        public boolean fullscreen() { return fullscreen; }
        public boolean parented() { return parented; }
        public boolean wasResized() { trace.add("resize"); return resized; }
        public int width() { return 913; }
        public int height() { return 567; }
        public void dimensions(int w, int h) { trace.add("dimensions"); width = w; height = h; }
        public void windowResized(boolean value) { windowResized = value; }
        public boolean parentResized() { return parentResized; }
        public void reshape() { trace.add("reshape"); }
        public void clearParentResized() { parentResized = false; }
        public void processMessages() {
            assertTrue(Thread.holdsLock(this));
            trace.add("poll");
            if (failPoll) throw new IllegalStateException("poll failed");
        }
    }
}