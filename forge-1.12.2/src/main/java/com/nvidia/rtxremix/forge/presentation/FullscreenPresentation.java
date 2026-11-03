/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.presentation;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

/** Keeps the GL host window out of exclusive mode while Remix owns its native swapchain. */
public final class FullscreenPresentation {
    private FullscreenPresentation() { }

    /** Call before reading the startup HWND; an existing exclusive window may be recreated. */
    public static void prepare(Minecraft minecraft) {
        Controller controller = Holder.CONTROLLER;
        if (controller.owned) return;
        try {
            controller.prepare(minecraft.isFullScreen(), windowWidth(minecraft), windowHeight(minecraft));
            if (minecraft.isFullScreen()) minecraft.resize(Display.getWidth(), Display.getHeight());
        } catch (LWJGLException failure) {
            throw new IllegalStateException("Cannot prepare the Remix fullscreen window", failure);
        }
    }

    /** Preserves vanilla's fullscreen flag, framebuffer resize, screen resize and vsync sequence. */
    public static void setDisplayMode(DisplayMode mode, Minecraft minecraft) throws LWJGLException {
        Controller controller = Holder.CONTROLLER;
        if (controller.owned && minecraft.isFullScreen() && !controller.borderless && !Display.isFullscreen()) {
            setWindowSize(minecraft, Display.getWidth(), Display.getHeight());
        }
        controller.mode(mode, minecraft.isFullScreen(), windowWidth(minecraft), windowHeight(minecraft));
    }

    public static void setFullscreen(boolean fullscreen) throws LWJGLException {
        Holder.CONTROLLER.fullscreen(fullscreen);
    }

    public static void setResizable(boolean resizable) { Holder.CONTROLLER.resizable(resizable); }
    public static boolean isBorderless() { return Holder.CONTROLLER.owned && Holder.CONTROLLER.borderless; }

    /** Restore the process property only after the display/runtime have both been destroyed. */
    public static void afterShutdown() { Holder.CONTROLLER.afterShutdown(); }

    private static int windowWidth(Minecraft minecraft) { return fieldValue(Fields.WIDTH, minecraft); }
    private static int windowHeight(Minecraft minecraft) { return fieldValue(Fields.HEIGHT, minecraft); }
    private static int fieldValue(Field field, Object owner) {
        try { return field.getInt(owner); }
        catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot read pinned window state", failure); }
    }
    private static void setWindowSize(Minecraft minecraft, int width, int height) {
        try { Fields.WIDTH.setInt(minecraft, width); Fields.HEIGHT.setInt(minecraft, height); }
        catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot save pinned window size", failure); }
    }
    private static final class Fields {
        static final Field WIDTH = ReflectionHelper.findField(Minecraft.class, "tempDisplayWidth", "field_71436_X");
        static final Field HEIGHT = ReflectionHelper.findField(Minecraft.class, "tempDisplayHeight", "field_71435_Y");
    }
    private static final class Holder { static final Controller CONTROLLER = new Controller(new Window()); }

    interface Access {
        boolean exclusive();
        int width();
        int height();
        int x();
        int y();
        boolean resizable();
        String decoration();
        DisplayMode desktop();
        void decoration(String value);
        void resizable(boolean value);
        void mode(DisplayMode value) throws LWJGLException;
        void fullscreen(boolean value) throws LWJGLException;
        void location(int x, int y);
    }

    static final class Controller {
        final Access window;
        boolean owned, borderless;
        private Saved saved;
        Controller(Access window) { this.window = window; }

        void prepare(boolean fullscreen, int fallbackWidth, int fallbackHeight) throws LWJGLException {
            if (owned) return;
            if (fullscreen) enter(fallbackWidth, fallbackHeight);
            owned = true;
        }
        void mode(DisplayMode requested, boolean fullscreen, int fallbackWidth, int fallbackHeight) throws LWJGLException {
            if (!owned) { window.mode(requested); return; }
            if (fullscreen) enter(fallbackWidth, fallbackHeight);
            else if (borderless) {
                window.decoration(saved.decoration); window.resizable(saved.resizable);
                // A two-argument DisplayMode is never exclusive-capable. setDisplayMode
                // recreates even an equal-sized window, so decoration changes take effect.
                window.mode(new DisplayMode(saved.width, saved.height)); window.fullscreen(false);
                window.location(saved.x, saved.y); borderless = false;
            } else window.mode(requested);
        }
        private void enter(int fallbackWidth, int fallbackHeight) throws LWJGLException {
            if (!borderless) saved = new Saved(window, fallbackWidth, fallbackHeight);
            try {
                window.decoration("true"); window.resizable(false);
                DisplayMode desktop = window.desktop();
                window.mode(new DisplayMode(desktop.getWidth(), desktop.getHeight())); window.fullscreen(false);
                window.location(0, 0); borderless = true;
            } catch (LWJGLException | RuntimeException failure) {
                // A failed mode change may already have destroyed the GL context.
                // Restore only window policy; never attempt a second recreation here.
                borderless = false;
                try { window.decoration(saved.decoration); }
                catch (RuntimeException restore) { failure.addSuppressed(restore); }
                try { window.resizable(saved.resizable); }
                catch (RuntimeException restore) { failure.addSuppressed(restore); }
                throw failure;
            }
        }
        void fullscreen(boolean requested) throws LWJGLException { window.fullscreen(owned ? false : requested); }
        void resizable(boolean requested) {
            // Vanilla's Windows decoration workaround toggles false/true after leaving
            // fullscreen. Retain the actual window setting saved on entry instead.
            window.resizable(!owned || saved == null ? requested : borderless ? false : saved.resizable);
        }
        void afterShutdown() {
            if (saved != null) window.decoration(saved.decoration);
            saved = null; owned = borderless = false;
        }
    }

    private static final class Saved {
        final int width, height, x, y;
        final String decoration;
        final boolean resizable;
        Saved(Access window, int fallbackWidth, int fallbackHeight) {
            width = window.exclusive() ? fallbackWidth : window.width();
            height = window.exclusive() ? fallbackHeight : window.height();
            if (width <= 0 || height <= 0) throw new IllegalStateException("Invalid saved window dimensions");
            x = window.x(); y = window.y(); decoration = window.decoration(); resizable = window.resizable();
        }
    }

    private static final class Window implements Access {
        private static final String DECORATION = "org.lwjgl.opengl.Window.undecorated";
        private final Field x = ReflectionHelper.findField(Display.class, "x"), y = ReflectionHelper.findField(Display.class, "y");
        public boolean exclusive() { return Display.isFullscreen(); }
        public int width() { return Display.getWidth(); }
        public int height() { return Display.getHeight(); }
        // LWJGL retains requested window coordinates while the exclusive HWND is at (0,0).
        public int x() { return exclusive() ? fieldValue(x, null) : Display.getX(); }
        public int y() { return exclusive() ? fieldValue(y, null) : Display.getY(); }
        public boolean resizable() { return Display.isResizable(); }
        public String decoration() { return System.getProperty(DECORATION); }
        public DisplayMode desktop() { return Display.getDesktopDisplayMode(); }
        public void decoration(String value) {
            if (value == null) System.clearProperty(DECORATION); else System.setProperty(DECORATION, value);
        }
        public void resizable(boolean value) { Display.setResizable(value); }
        public void mode(DisplayMode value) throws LWJGLException { Display.setDisplayMode(value); }
        public void fullscreen(boolean value) throws LWJGLException { Display.setFullscreen(value); }
        public void location(int x, int y) { Display.setLocation(x, y); }
    }
}
