/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.input;

import com.mojang.blaze3d.platform.InputConstants;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.ToIntBiFunction;

/**
 * Owns Minecraft-side input capture for the Remix overlay.
 *
 * <p>Capture follows whether a native Remix menu is shown, which {@code rtx.showUI} records. The
 * mod opens and closes menus by writing it, and the menus' own controls and the runtime's hotkey
 * change it too, so {@link #followVisibility} reads it once per frame and opens or closes capture
 * with whatever is shown. Closing releases capture immediately; a key that remains physically held
 * may resume through vanilla's SDL event handling.
 *
 * <p>Remix resolves a written option a frame or more later, so after its own write the gate
 * ignores reads until one agrees with it. A stale read would otherwise undo the open or close the
 * mod just made. After {@link #OWN_WRITE_OVERDUE_FRAMES} frames without one, the gate gives up on
 * the write and follows reads again.
 */
public final class RemixInputGate {
    private static final String VISIBILITY_OPTION = "rtx.showUI";
    private static final String HIDDEN_MENU = "0";
    /** {@link #HIDDEN_MENU} as {@code GetConfigVariableResolved} writes it, in UTF-8 with a NUL byte. */
    private static final byte[] RESOLVED_HIDDEN_MENU =
            (HIDDEN_MENU + '\0').getBytes(StandardCharsets.UTF_8);
    /**
     * Frames after which an own write that no read agrees with is reported and no longer waited for.
     * This is far longer than Remix takes to resolve a write, so a write unresolved this long is most
     * likely overridden.
     */
    public static final int OWN_WRITE_OVERDUE_FRAMES = 120;

    /** The native Remix menus the mod opens, by their {@code rtx.showUI} value. */
    public enum Menu {
        GRAPHICS_SETTINGS("1"),
        DEVELOPER("2");

        private final String visibilityValue;

        Menu(String visibilityValue) {
            this.visibilityValue = visibilityValue;
        }
    }

    public enum KeyDecision {
        PASS,
        BLOCK,
        TOGGLE_MENU
    }

    /** What {@link #followVisibility} found, and the capture change it made. */
    public enum VisibilityChange {
        NONE,
        /** A menu the mod did not open is shown, and capture is now open. */
        OPENED,
        /** The open menu was closed from the runtime, and capture is now closed. */
        CLOSED,
        /**
         * No read has agreed with the mod's last write for {@link #OWN_WRITE_OVERDUE_FRAMES} frames.
         * Capture is unchanged, and the next read moves it to match what is shown.
         */
        OWN_WRITE_OVERDUE
    }

    private boolean captureSessionOpen;
    /** Whether the mod's last visibility write may not have resolved yet. */
    private boolean awaitingOwnWrite;
    /** Reads since the mod's last write that disagreed with it. */
    private int framesAwaitingOwnWrite;
    /**
     * Receives each visibility read, so the per-frame read allocates nothing. It only needs to fit
     * the hidden value, because any value that does not fit means a menu is shown.
     */
    private final byte[] resolvedVisibility = new byte[RESOLVED_HIDDEN_MENU.length];

    /** Decides whether Minecraft may handle a key event. */
    public KeyDecision onKey(int key, int action, boolean altDown, boolean controlDown) {
        boolean altX = action == InputConstants.PRESS
                && key == InputConstants.KEY_X
                && altDown
                && !controlDown;
        if (captureSessionOpen) {
            return altX ? KeyDecision.TOGGLE_MENU : KeyDecision.BLOCK;
        }

        return altX ? KeyDecision.TOGGLE_MENU : KeyDecision.PASS;
    }

    public boolean isCaptureSessionOpen() {
        return captureSessionOpen;
    }

    /** Acquires capture ownership only after the native menu opens successfully. */
    public void open(Menu menu, BiConsumer<String, String> configWriter) {
        if (captureSessionOpen) {
            return;
        }
        configWriter.accept(VISIBILITY_OPTION, menu.visibilityValue);
        captureSessionOpen = true;
        awaitingOwnWrite = true;
        framesAwaitingOwnWrite = 0;
    }

    /**
     * Releases capture ownership even when hiding the native menu fails, in which case the next
     * {@link #followVisibility} finds the menu still shown and opens capture again.
     */
    public void close(BiConsumer<String, String> configWriter) {
        if (!captureSessionOpen) {
            return;
        }
        captureSessionOpen = false;
        awaitingOwnWrite = false;
        configWriter.accept(VISIBILITY_OPTION, HIDDEN_MENU);
        awaitingOwnWrite = true;
        framesAwaitingOwnWrite = 0;
    }

    /**
     * Reads whether a native menu is shown and moves capture to match, once the mod's own last
     * write has resolved or become overdue. Call once per frame on the render thread.
     *
     * @param configReader Remix's {@code GetConfigVariableResolved} into a caller-owned buffer,
     *                     returning the value's size including its NUL byte
     * @return the capture change made, which the caller applies to Minecraft's input, or
     *         {@link VisibilityChange#OWN_WRITE_OVERDUE} once per write that stays unresolved
     */
    public VisibilityChange followVisibility(ToIntBiFunction<String, byte[]> configReader) {
        int size = configReader.applyAsInt(VISIBILITY_OPTION, resolvedVisibility);
        boolean menuShown = size != resolvedVisibility.length
                || !Arrays.equals(resolvedVisibility, RESOLVED_HIDDEN_MENU);
        if (awaitingOwnWrite) {
            awaitingOwnWrite = menuShown != captureSessionOpen;
            if (!awaitingOwnWrite) {
                return VisibilityChange.NONE;
            }
            framesAwaitingOwnWrite++;
            if (framesAwaitingOwnWrite < OWN_WRITE_OVERDUE_FRAMES) {
                return VisibilityChange.NONE;
            }
            awaitingOwnWrite = false;
            return VisibilityChange.OWN_WRITE_OVERDUE;
        }
        if (menuShown == captureSessionOpen) {
            return VisibilityChange.NONE;
        }
        captureSessionOpen = menuShown;
        return menuShown ? VisibilityChange.OPENED : VisibilityChange.CLOSED;
    }
}
