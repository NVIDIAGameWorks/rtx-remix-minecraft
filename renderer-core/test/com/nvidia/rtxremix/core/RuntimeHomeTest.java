/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeHomeTest {
    private static final UnaryOperator<String> NOTHING_SET = name -> null;

    @Test
    void aWorkingDirectoryThatIsTheHomeFolderOverridesNothing(@TempDir Path tmp) throws IOException {
        Path home = Files.createDirectories(tmp.resolve("game"));

        RuntimeHome runtimeHome = RuntimeHome.resolve(home, home, NOTHING_SET);

        assertTrue(runtimeHome.environment().isEmpty());
        assertTrue(runtimeHome.warnings().isEmpty());
    }

    @Test
    void theSameFolderUnderAnotherSpellingOverridesNothing(@TempDir Path tmp) throws IOException {
        Path home = Files.createDirectories(tmp.resolve("game"));
        Path respelled = tmp.resolve("other").resolve("..").resolve("game");

        RuntimeHome runtimeHome = RuntimeHome.resolve(home, respelled, NOTHING_SET);

        assertTrue(runtimeHome.environment().isEmpty());
    }

    @Test
    void anotherWorkingDirectoryMovesEveryLocationIntoTheHomeFolder(@TempDir Path tmp)
            throws IOException {
        Path home = Files.createDirectories(tmp.resolve("game"));
        Path launcher = Files.createDirectories(tmp.resolve("launcher"));

        RuntimeHome runtimeHome = RuntimeHome.resolve(home, launcher, NOTHING_SET);

        Map<String, String> expected = Map.of(
                "DXVK_CONFIG_FILE", home.resolve("dxvk.conf").toString(),
                "DXVK_RTX_CONFIG_FILE", home.resolve("rtx.conf").toString(),
                "DEFAULT_MODS_DIR", home.resolve("rtx-remix").resolve("mods").toString(),
                "DXVK_CAPTURE_PATH", home.resolve("rtx-remix").resolve("captures").toString(),
                "DXVK_LOG_PATH", home.resolve("rtx-remix").resolve("logs").toString(),
                "DXVK_STATE_CACHE_PATH", home.toString(),
                "DXVK_METRICS_PATH", home.toString(),
                "DXVK_AFTERMATH_DUMP_PATH", home.toString(),
                "DXVK_SCREENSHOT_PATH", home.resolve("Screenshots").toString());
        assertEquals(expected, runtimeHome.environment());
        assertTrue(runtimeHome.warnings().isEmpty());
    }

    @Test
    void aVariableTheProcessStartedWithIsLeftAlone(@TempDir Path tmp) throws IOException {
        Path home = Files.createDirectories(tmp.resolve("game"));
        Path launcher = Files.createDirectories(tmp.resolve("launcher"));
        UnaryOperator<String> benchmark = name ->
                name.equals("DXVK_RTX_CONFIG_FILE") ? "rtx.conf,benchmark.conf" : null;

        RuntimeHome runtimeHome = RuntimeHome.resolve(home, launcher, benchmark);

        assertFalse(runtimeHome.environment().containsKey("DXVK_RTX_CONFIG_FILE"));
        assertEquals(home.resolve("dxvk.conf").toString(),
                runtimeHome.environment().get("DXVK_CONFIG_FILE"));
    }

    @Test
    void aPathTheRuntimeWouldDropIsLeftUnsetAndReported(@TempDir Path tmp) throws IOException {
        Path launcher = Files.createDirectories(tmp.resolve("launcher"));
        // Long enough that rtx-remix/captures no longer fits, while rtx.conf still does.
        Path home = padTo(tmp, RuntimeHome.MAX_VALUE_LENGTH - "/rtx-remix/logs".length());

        RuntimeHome runtimeHome = RuntimeHome.resolve(home, launcher, NOTHING_SET);

        assertEquals(home.resolve("rtx.conf").toString(),
                runtimeHome.environment().get("DXVK_RTX_CONFIG_FILE"));
        assertEquals(RuntimeHome.MAX_VALUE_LENGTH,
                runtimeHome.environment().get("DXVK_LOG_PATH").length());
        assertFalse(runtimeHome.environment().containsKey("DXVK_CAPTURE_PATH"));
        assertEquals(1, runtimeHome.warnings().size());
        assertTrue(runtimeHome.warnings().get(0).startsWith("DXVK_CAPTURE_PATH is left unset"),
                runtimeHome.warnings().get(0));
    }

    @Test
    void aCommaInTheHomeFolderLeavesTheConfListsUnset(@TempDir Path tmp) throws IOException {
        Path home = Files.createDirectories(tmp.resolve("game, modded"));
        Path launcher = Files.createDirectories(tmp.resolve("launcher"));

        RuntimeHome runtimeHome = RuntimeHome.resolve(home, launcher, NOTHING_SET);

        assertFalse(runtimeHome.environment().containsKey("DXVK_RTX_CONFIG_FILE"));
        assertFalse(runtimeHome.environment().containsKey("DXVK_CONFIG_FILE"));
        assertEquals(home.resolve("rtx-remix").resolve("logs").toString(),
                runtimeHome.environment().get("DXVK_LOG_PATH"));
        assertEquals(2, runtimeHome.warnings().size());
    }

    @Test
    void aUserConfTheRuntimeWillNotReadIsReported(@TempDir Path tmp) throws IOException {
        Path home = Files.createDirectories(tmp.resolve("game"));
        Path launcher = Files.createDirectories(tmp.resolve("launcher"));
        Files.writeString(home.resolve("user.conf"), "rtx.graphicsPreset = 3");

        List<String> warnings = RuntimeHome.resolve(home, launcher, NOTHING_SET).warnings();

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains(home.resolve("user.conf").toString()), warnings.get(0));
        assertTrue(warnings.get(0).contains(launcher.toString()), warnings.get(0));
    }

    @Test
    void crashFilesBelongInTheHomeFolderWhateverTheWorkingDirectory(@TempDir Path tmp)
            throws IOException {
        Path home = Files.createDirectories(tmp.resolve("game"));
        Path launcher = Files.createDirectories(tmp.resolve("launcher"));

        assertEquals(home.resolve("crash"), RuntimeHome.resolve(home, home, NOTHING_SET).crashDirectory());
        assertEquals(home.resolve("crash"),
                RuntimeHome.resolve(home, launcher, NOTHING_SET).crashDirectory());
    }

    /** @return a folder under {@code parent} whose absolute path is {@code length} characters */
    private static Path padTo(Path parent, int length) {
        Path padded = parent.toAbsolutePath().normalize();
        int remaining = length - padded.toString().length();
        // Each segment costs its name and one separator; the last takes what is left.
        while (remaining > 42) {
            padded = padded.resolve("d".repeat(40));
            remaining -= 41;
        }
        return padded.resolve("d".repeat(remaining - 1));
    }
}
