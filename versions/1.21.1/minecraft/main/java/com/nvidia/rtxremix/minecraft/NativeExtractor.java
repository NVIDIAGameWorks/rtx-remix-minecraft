/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.nvidia.rtxremix.core.DirectoryRuntimePayload;
import com.nvidia.rtxremix.core.ShippedPayloadHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapts the active loader's mod archive and game directory to the canonical runtime
 * payload installer.
 */
public final class NativeExtractor {
    private static final Logger LOGGER = LoggerFactory.getLogger(NativeExtractor.class);
    private static final String JAR_NATIVE_ROOT = "native";
    private static final DirectoryRuntimePayload.Reporter REPORTER =
            new DirectoryRuntimePayload.Reporter() {
                @Override
                public void info(String message) {
                    LOGGER.info(message);
                }

                @Override
                public void warn(String message) {
                    LOGGER.warn(message);
                }

                @Override
                public void error(String message) {
                    LOGGER.error(message);
                }
            };

    private NativeExtractor() {}

    /**
     * @return false when extraction failed this launch, so bootstrap can
     *         distinguish that failure from a runtime that was simply absent
     */
    public static boolean extractIfNeeded() {
        try {
            MinecraftPlatform.Services platform = MinecraftPlatform.services();
            DirectoryRuntimePayload.install(
                    platform.modRoot().resolve(JAR_NATIVE_ROOT),
                    platform.gameDirectory(),
                    platform.modVersion(),
                    DirectoryRuntimePayload.detectPlatformSdkDir(System.getProperty("os.arch")),
                    ShippedPayloadHistory.loadBundled(),
                    REPORTER);
            return true;
        } catch (Exception e) {
            LOGGER.error("Native extraction failed; the mod may not function correctly. "
                    + "Relaunch to retry the staged payload update.", e);
            return false;
        }
    }
}
