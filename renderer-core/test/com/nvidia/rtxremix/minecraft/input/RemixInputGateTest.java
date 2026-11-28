/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.input;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.nvidia.rtxremix.minecraft.input.RemixInputGate.KeyDecision.BLOCK;
import static com.nvidia.rtxremix.minecraft.input.RemixInputGate.KeyDecision.PASS;
import static com.nvidia.rtxremix.minecraft.input.RemixInputGate.KeyDecision.TOGGLE_MENU;
import static com.nvidia.rtxremix.minecraft.input.RemixInputGate.VisibilityChange.CLOSED;
import static com.nvidia.rtxremix.minecraft.input.RemixInputGate.VisibilityChange.NONE;
import static com.nvidia.rtxremix.minecraft.input.RemixInputGate.VisibilityChange.OPENED;
import static com.nvidia.rtxremix.minecraft.input.RemixInputGate.VisibilityChange.OWN_WRITE_OVERDUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemixInputGateTest {
    private static final class GLFW {
        static final int GLFW_RELEASE = 0;
        static final int GLFW_PRESS = 1;
        static final int GLFW_REPEAT = 2;
        static final int GLFW_KEY_E = 69;
        static final int GLFW_KEY_W = 87;
        static final int GLFW_KEY_X = 88;
        static final int GLFW_KEY_ESCAPE = 256;
    }

    @Test
    void altXOpensWheneverCaptureIsClosed() {
        RemixInputGate gate = new RemixInputGate();

        assertSame(TOGGLE_MENU, key(gate, GLFW.GLFW_KEY_X, GLFW.GLFW_PRESS, true));
        assertSame(PASS, key(gate, GLFW.GLFW_KEY_X, GLFW.GLFW_REPEAT, true));
        assertSame(PASS, key(gate, GLFW.GLFW_KEY_X, GLFW.GLFW_RELEASE, true));
        assertSame(PASS, key(gate, GLFW.GLFW_KEY_X, GLFW.GLFW_PRESS, false));
        assertSame(PASS, key(gate, GLFW.GLFW_KEY_W, GLFW.GLFW_PRESS, true));
    }

    @Test
    void openSessionBlocksEveryOtherKeyboardEventAndAllCharacters() {
        RemixInputGate gate = new RemixInputGate();
        open(gate);

        assertSame(BLOCK, key(gate, GLFW.GLFW_KEY_W, GLFW.GLFW_PRESS, false));
        assertSame(BLOCK, key(gate, GLFW.GLFW_KEY_W, GLFW.GLFW_PRESS, true));
        assertSame(BLOCK, key(gate, GLFW.GLFW_KEY_W, GLFW.GLFW_REPEAT, false));
        assertSame(BLOCK, key(gate, GLFW.GLFW_KEY_W, GLFW.GLFW_RELEASE, false));
        assertSame(BLOCK, key(gate, GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, false));
        assertTrue(gate.isCaptureSessionOpen());
    }

    @Test
    void altXClosesWheneverCaptureIsOpen() {
        RemixInputGate gate = new RemixInputGate();
        open(gate);

        assertSame(TOGGLE_MENU, key(gate, GLFW.GLFW_KEY_X, GLFW.GLFW_PRESS, true));
        assertSame(BLOCK, key(gate, GLFW.GLFW_KEY_X, GLFW.GLFW_REPEAT, true));
        assertSame(BLOCK, key(gate, GLFW.GLFW_KEY_X, GLFW.GLFW_RELEASE, true));
        assertSame(BLOCK, key(gate, GLFW.GLFW_KEY_X, GLFW.GLFW_PRESS, false));
    }

    @Test
    void controlAltXDoesNotToggle() {
        RemixInputGate gate = new RemixInputGate();

        assertSame(PASS, key(gate, GLFW.GLFW_KEY_X, GLFW.GLFW_PRESS, true, true));
        assertSame(PASS, key(gate, GLFW.GLFW_KEY_X, GLFW.GLFW_PRESS, false, true));
        assertSame(PASS, key(gate, GLFW.GLFW_KEY_X, GLFW.GLFW_REPEAT, true, true));
        assertSame(PASS, key(gate, GLFW.GLFW_KEY_X, GLFW.GLFW_RELEASE, true, true));

        open(gate);

        assertSame(BLOCK, key(gate, GLFW.GLFW_KEY_X, GLFW.GLFW_PRESS, true, true));
        assertSame(BLOCK, key(gate, GLFW.GLFW_KEY_X, GLFW.GLFW_PRESS, false, true));
        assertSame(BLOCK, key(gate, GLFW.GLFW_KEY_X, GLFW.GLFW_REPEAT, true, true));
        assertSame(BLOCK, key(gate, GLFW.GLFW_KEY_X, GLFW.GLFW_RELEASE, true, true));
        assertTrue(gate.isCaptureSessionOpen());
    }

    @Test
    void closingReturnsHeldKeyEventsToMinecraftImmediately() {
        RemixInputGate gate = new RemixInputGate();
        assertSame(PASS, key(gate, GLFW.GLFW_KEY_W, GLFW.GLFW_PRESS, false));
        open(gate);
        assertSame(BLOCK, key(gate, GLFW.GLFW_KEY_W, GLFW.GLFW_REPEAT, false));

        close(gate);

        assertSame(PASS, key(gate, GLFW.GLFW_KEY_W, GLFW.GLFW_REPEAT, false));
        assertSame(PASS, key(gate, GLFW.GLFW_KEY_W, GLFW.GLFW_RELEASE, false));
    }

    @Test
    void keyPressedInsideOverlayPassesAfterClose() {
        RemixInputGate gate = new RemixInputGate();
        open(gate);
        assertSame(BLOCK, key(gate, GLFW.GLFW_KEY_E, GLFW.GLFW_PRESS, false));

        close(gate);

        assertSame(PASS, key(gate, GLFW.GLFW_KEY_E, GLFW.GLFW_REPEAT, false));
        assertSame(PASS, key(gate, GLFW.GLFW_KEY_E, GLFW.GLFW_RELEASE, false));
        assertSame(PASS, key(gate, GLFW.GLFW_KEY_E, GLFW.GLFW_PRESS, false));
    }

    @Test
    void menuClosedFromTheRuntimeReleasesCapture() {
        RemixInputGate gate = new RemixInputGate();
        open(gate);
        assertSame(NONE, follow(gate, "2"));

        assertSame(CLOSED, follow(gate, "0"));

        assertFalse(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "0"));
    }

    @Test
    void menuOpenedByTheRuntimeTakesCapture() {
        RemixInputGate gate = new RemixInputGate();
        assertSame(NONE, follow(gate, "0"));

        assertSame(OPENED, follow(gate, "3"));

        assertTrue(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "3"));
    }

    @Test
    void readFromBeforeTheModOpenedDoesNotClose() {
        RemixInputGate gate = new RemixInputGate();
        open(gate);

        assertSame(NONE, follow(gate, "0"));
        assertSame(NONE, follow(gate, "0"));

        assertTrue(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "2"));
        assertSame(CLOSED, follow(gate, "0"));
    }

    @Test
    void readFromBeforeTheModClosedDoesNotReopen() {
        RemixInputGate gate = new RemixInputGate();
        open(gate);
        assertSame(NONE, follow(gate, "2"));
        close(gate);

        assertSame(NONE, follow(gate, "2"));

        assertFalse(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "0"));
        assertSame(OPENED, follow(gate, "2"));
    }

    @Test
    void overdueOpenReleasesCaptureWhileNoMenuIsShown() {
        RemixInputGate gate = new RemixInputGate();
        open(gate);
        for (int frame = 1; frame < RemixInputGate.OWN_WRITE_OVERDUE_FRAMES; frame++) {
            assertSame(NONE, follow(gate, "0"));
        }

        assertSame(OWN_WRITE_OVERDUE, follow(gate, "0"));
        assertTrue(gate.isCaptureSessionOpen());

        assertSame(CLOSED, follow(gate, "0"));
        assertFalse(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "0"));
    }

    @Test
    void overdueCloseTakesCaptureWhileAMenuIsShown() {
        RemixInputGate gate = new RemixInputGate();
        open(gate);
        assertSame(NONE, follow(gate, "2"));
        close(gate);
        for (int frame = 1; frame < RemixInputGate.OWN_WRITE_OVERDUE_FRAMES; frame++) {
            assertSame(NONE, follow(gate, "2"));
        }

        assertSame(OWN_WRITE_OVERDUE, follow(gate, "2"));
        assertFalse(gate.isCaptureSessionOpen());

        assertSame(OPENED, follow(gate, "2"));
        assertTrue(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "2"));
    }

    @Test
    void eachOwnWriteGetsItsOwnOverdueCount() {
        RemixInputGate gate = new RemixInputGate();
        open(gate);
        for (int frame = 1; frame < RemixInputGate.OWN_WRITE_OVERDUE_FRAMES; frame++) {
            assertSame(NONE, follow(gate, "0"));
        }
        close(gate);

        for (int frame = 1; frame < RemixInputGate.OWN_WRITE_OVERDUE_FRAMES; frame++) {
            assertSame(NONE, follow(gate, "2"));
        }

        assertSame(OWN_WRITE_OVERDUE, follow(gate, "2"));
        assertFalse(gate.isCaptureSessionOpen());
    }

    @Test
    void switchingMenusKeepsCapture() {
        RemixInputGate gate = new RemixInputGate();
        gate.open(RemixInputGate.Menu.GRAPHICS_SETTINGS, (option, value) -> { });
        assertSame(NONE, follow(gate, "1"));

        assertSame(NONE, follow(gate, "2"));

        assertTrue(gate.isCaptureSessionOpen());
    }

    @Test
    void anyShownMenuResolvesTheModsOpen() {
        RemixInputGate gate = new RemixInputGate();
        open(gate);

        assertSame(NONE, follow(gate, "3"));

        assertSame(CLOSED, follow(gate, "0"));
    }

    @Test
    void menuStillShownAfterAFailedCloseTakesCaptureBack() {
        RemixInputGate gate = new RemixInputGate();
        open(gate);
        assertSame(NONE, follow(gate, "2"));
        assertThrows(IllegalStateException.class,
                () -> gate.close((option, value) -> {
                    throw new IllegalStateException("close failed");
                }));

        assertSame(OPENED, follow(gate, "2"));

        assertTrue(gate.isCaptureSessionOpen());
    }

    @Test
    void gateOwnsTheNativeVisibilityValues() {
        RemixInputGate gate = new RemixInputGate();
        Map<String, String> writes = new HashMap<>();

        gate.open(RemixInputGate.Menu.DEVELOPER, writes::put);
        assertEquals("2", writes.get("rtx.showUI"));
        gate.close(writes::put);
        assertEquals("0", writes.get("rtx.showUI"));

        gate.open(RemixInputGate.Menu.GRAPHICS_SETTINGS, writes::put);
        assertEquals("1", writes.get("rtx.showUI"));
        gate.close(writes::put);

        assertEquals("0", writes.get("rtx.showUI"));
        assertFalse(gate.isCaptureSessionOpen());
        gate.followVisibility((option, buffer) -> {
            assertEquals("rtx.showUI", option);
            return resolve("0", buffer);
        });
    }

    @Test
    void aValueTooLongForTheReadBufferIsAShownMenu() {
        RemixInputGate gate = new RemixInputGate();

        assertSame(OPENED, follow(gate, "10"));

        assertSame(CLOSED, follow(gate, "0"));
    }

    @Test
    void everyReadReusesTheSameBuffer() {
        RemixInputGate gate = new RemixInputGate();
        List<byte[]> buffers = new ArrayList<>();

        for (int frame = 0; frame < 3; frame++) {
            gate.followVisibility((option, buffer) -> {
                buffers.add(buffer);
                return resolve("0", buffer);
            });
        }

        assertSame(buffers.get(0), buffers.get(1));
        assertSame(buffers.get(0), buffers.get(2));
    }

    @Test
    void openingWhileOpenKeepsTheMenuAlreadyShown() {
        RemixInputGate gate = new RemixInputGate();
        Map<String, String> writes = new HashMap<>();
        gate.open(RemixInputGate.Menu.DEVELOPER, writes::put);

        gate.open(RemixInputGate.Menu.GRAPHICS_SETTINGS, writes::put);

        assertEquals("2", writes.get("rtx.showUI"));
        assertTrue(gate.isCaptureSessionOpen());
    }

    @Test
    void failedVisibilityWritesDoNotCommitCaptureState() {
        RemixInputGate gate = new RemixInputGate();

        assertThrows(IllegalStateException.class,
                () -> gate.open(RemixInputGate.Menu.DEVELOPER, (option, value) -> {
                    throw new IllegalStateException("open failed");
                }));
        assertFalse(gate.isCaptureSessionOpen());

        open(gate);
        assertThrows(IllegalStateException.class,
                () -> gate.close((option, value) -> {
                    throw new IllegalStateException("close failed");
                }));
        assertFalse(gate.isCaptureSessionOpen());
    }

    private static void open(RemixInputGate gate) {
        gate.open(RemixInputGate.Menu.DEVELOPER, (option, value) -> { });
    }

    private static void close(RemixInputGate gate) {
        gate.close((option, value) -> { });
    }

    private static RemixInputGate.VisibilityChange follow(RemixInputGate gate, String visibility) {
        return gate.followVisibility((option, buffer) -> resolve(visibility, buffer));
    }

    /**
     * Writes {@code value} the way {@code GetConfigVariableResolved} does: UTF-8 and a NUL byte
     * when they fit, and the size they need either way.
     */
    private static int resolve(String value, byte[] buffer) {
        byte[] resolved = (value + '\0').getBytes(StandardCharsets.UTF_8);
        if (resolved.length <= buffer.length) {
            System.arraycopy(resolved, 0, buffer, 0, resolved.length);
        }
        return resolved.length;
    }

    private static RemixInputGate.KeyDecision key(
            RemixInputGate gate,
            int key,
            int action,
            boolean altDown) {
        return key(gate, key, action, altDown, false);
    }

    private static RemixInputGate.KeyDecision key(
            RemixInputGate gate,
            int key,
            int action,
            boolean altDown,
            boolean controlDown) {
        return gate.onKey(
                key == GLFW.GLFW_KEY_X,
                action == GLFW.GLFW_PRESS,
                altDown,
                controlDown);
    }
}
