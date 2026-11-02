/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.presentation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.lwjgl.opengl.Display;

/**
 * Pinned LWJGL 2.9.4 Display.update(true), with the GL swap replaced by Vulkan presentation.
 * processMessages alone omits Display's cached size/resize updates. Cached primitive reflection
 * and zero-argument method handles retain that tail without allocating per-frame argument arrays.
 */
final class LegacyDisplayUpdate {
    interface Access {
        Object lock();
        boolean created();
        boolean fullscreen();
        boolean parented();
        boolean wasResized();
        int width();
        int height();
        void dimensions(int width, int height);
        void windowResized(boolean value);
        boolean parentResized();
        void reshape();
        void clearParentResized();
        void processMessages();
    }
    private LegacyDisplayUpdate() { }
    static void update(Runnable present) { update(Holder.ACCESS, present); }
    static void update(Access access, Runnable present) {
        synchronized (access.lock()) {
            if (!access.created()) throw new IllegalStateException("Display not created");
            present.run();
            boolean resized = !access.fullscreen() && !access.parented() && access.wasResized();
            access.windowResized(resized);
            if (resized) access.dimensions(access.width(), access.height());
            if (access.parentResized()) {
                access.reshape(); access.clearParentResized(); access.windowResized(true);
            }
            access.processMessages();
        }
    }
    private static final class Holder { static final Access ACCESS = new PinnedAccess(); }
    private static final class PinnedAccess implements Access {
        private final Object lock;
        private final Field parent, width, height, windowResized, parentResized;
        private final MethodHandle wasResized, getWidth, getHeight, reshape;
        PinnedAccess() {
            try {
                lock = field(Class.forName("org.lwjgl.opengl.GlobalLock"), "lock").get(null);
                Object implementation = field(Display.class, "display_impl").get(null);
                Class<?> contract = Class.forName("org.lwjgl.opengl.DisplayImplementation");
                wasResized = method(contract, "wasResized").bindTo(implementation);
                getWidth = method(contract, "getWidth").bindTo(implementation);
                getHeight = method(contract, "getHeight").bindTo(implementation);
                reshape = method(Display.class, "reshape");
                parent = field(Display.class, "parent"); width = field(Display.class, "width"); height = field(Display.class, "height");
                windowResized = field(Display.class, "window_resized"); parentResized = field(Display.class, "parent_resized");
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported LWJGL Display layout", failure); }
        }
        public Object lock() { return lock; }
        public boolean created() { return Display.isCreated(); }
        public boolean fullscreen() { return Display.isFullscreen(); }
        public boolean parented() { try { return parent.get(null) != null; } catch (IllegalAccessException failure) { throw error(failure); } }
        public boolean wasResized() { try { return (boolean) wasResized.invokeExact(); } catch (Throwable failure) { throw error(failure); } }
        public int width() { try { return (int) getWidth.invokeExact(); } catch (Throwable failure) { throw error(failure); } }
        public int height() { try { return (int) getHeight.invokeExact(); } catch (Throwable failure) { throw error(failure); } }
        public void dimensions(int w, int h) { try { width.setInt(null, w); height.setInt(null, h); } catch (IllegalAccessException failure) { throw error(failure); } }
        public void windowResized(boolean value) { try { windowResized.setBoolean(null, value); } catch (IllegalAccessException failure) { throw error(failure); } }
        public boolean parentResized() { try { return parentResized.getBoolean(null); } catch (IllegalAccessException failure) { throw error(failure); } }
        public void reshape() { try { reshape.invokeExact(); } catch (Throwable failure) { throw error(failure); } }
        public void clearParentResized() { try { parentResized.setBoolean(null, false); } catch (IllegalAccessException failure) { throw error(failure); } }
        public void processMessages() { Display.processMessages(); }
        private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
            Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
        }
        private static MethodHandle method(Class<?> owner, String name) throws ReflectiveOperationException {
            Method method = owner.getDeclaredMethod(name); method.setAccessible(true); return MethodHandles.lookup().unreflect(method);
        }
        private static RuntimeException error(Throwable failure) {
            if (failure instanceof Error) throw (Error) failure;
            return failure instanceof RuntimeException ? (RuntimeException) failure : new IllegalStateException("LWJGL Display update failed", failure);
        }
    }
}
