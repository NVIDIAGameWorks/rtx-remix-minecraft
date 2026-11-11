/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.nvidia.rtxremix.core.HostGraphicsIntegration;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The small loader-owned filesystem and metadata boundary used by the shared
 * Minecraft integration.
 */
public final class MinecraftPlatform {
    public static final String RESOURCE_NAMESPACE = "rtx-remix-renderer";

    public interface Services {
        Path gameDirectory();

        Path configDirectory();

        Path modRoot();

        String modVersion();

        String loaderId();

        /** Loader metadata path inside the production artifact. */
        String metadataResource();

        /** Creates the host graphics bridge selected by this loader/version integration. */
        HostGraphicsIntegration createGraphicsIntegration();
    }

    private static volatile Services services;

    private MinecraftPlatform() {
    }

    public static void install(Services value) {
        Objects.requireNonNull(value, "value");
        synchronized (MinecraftPlatform.class) {
            if (services != null && services != value) {
                throw new IllegalStateException("Minecraft platform services already installed");
            }
            services = value;
        }
    }

    public static Services services() {
        Services value = services;
        if (value == null) {
            throw new IllegalStateException("Minecraft platform services are not installed");
        }
        return value;
    }
}
