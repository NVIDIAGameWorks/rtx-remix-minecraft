/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import java.util.regex.Pattern;

/**
 * The one rule for a name that becomes a file under the camera directory:
 * camera paths, shot sets, and shots alike. A name is a single file name,
 * never a path, so a value read back from JSON is held to the same rule as
 * one typed in chat before anything is deleted or written under it.
 */
final class CameraNames {

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern WINDOWS_RESERVED_PATTERN =
            Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?");

    static final String RULE =
            "Use 1-128 letters, numbers, '.', '_' or '-', starting with a letter or number.";

    private CameraNames() {}

    /** Whether {@code name} is a safe single file name on every supported platform. */
    static boolean isValid(String name) {
        return name != null
                && NAME_PATTERN.matcher(name).matches()
                && !WINDOWS_RESERVED_PATTERN.matcher(name).matches()
                && !name.equals(".")
                && !name.equals("..");
    }

    /** Whether a Windows device name would shadow this file. */
    static boolean isWindowsReserved(String name) {
        return WINDOWS_RESERVED_PATTERN.matcher(name).matches();
    }
}
