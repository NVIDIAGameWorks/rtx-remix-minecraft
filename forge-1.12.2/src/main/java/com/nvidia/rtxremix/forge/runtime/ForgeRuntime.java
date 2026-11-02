/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.runtime;

import com.nvidia.rtxremix.forge.RemixForgeMod;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves an explicit development SDK or installs this JAR's bundled runtime. */
public final class ForgeRuntime {
    private ForgeRuntime() {}

    public static File prepare(File gameDirectory) {
        String override = System.getProperty("rtxremix.forge.runtime");
        if (override != null) { return new File(override, "d3d9.dll"); }
        try {
            Path source = RuntimePayload.archivePath(ForgeRuntime.class.getProtectionDomain().getCodeSource().getLocation());
            if (Files.isRegularFile(source) && RuntimePayload.install(source, gameDirectory.toPath())) {
                RemixForgeMod.LOG.info("Installed the bundled Forge Remix runtime and default configuration.");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot install the bundled Remix runtime; close other clients using this game directory and retry.", ex);
        }
        return new File(new File(gameDirectory, "remix-sdk"), "d3d9.dll");
    }
}
