/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Process arguments blanked before the native runtime can include them in a crash dump. */
public final class LauncherAccountOptions {
    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            "--username",
            "--accessToken",
            "--uuid",
            "--xuid",
            "--clientId"));

    private LauncherAccountOptions() {
    }
}
