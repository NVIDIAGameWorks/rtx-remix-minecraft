/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core;

import com.nvidia.rtxremix.InteropInfo;
import com.nvidia.rtxremix.PresentInfo;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.StartupInfo;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loader-neutral owner of the native Remix API and frame state.
 *
 * <p>Window discovery, GL interop, and runtime-path selection remain host
 * responsibilities. Every loader and Minecraft-version adapter enters the same
 * load/start/submit/present/shutdown state machine here.
 */
public final class RemixRendererSession {
    private final RemixAPI api;
    private final RemixFrameState frameState;
    private boolean shutdown;

    private RemixRendererSession(RemixAPI api) {
        this.api = api;
        this.frameState = new RemixFrameState(api);
    }

    public static OpenResult open(
            String runtimePath,
            StartupInfo startupInfo,
            Collection<String> commandLineOptions,
            Map<String, String> configOverrides) throws RemixException {
        int redacted = RemixAPI.redactCommandLineOptions(runtimePath, commandLineOptions);
        RemixAPI api = RemixAPI.load(runtimePath);
        boolean started = false;
        try {
            for (Map.Entry<String, String> entry : configOverrides.entrySet()) {
                api.setConfigVariable(entry.getKey(), entry.getValue());
            }
            api.startup(startupInfo);
            started = true;
            return new OpenResult(new RemixRendererSession(api), redacted);
        } finally {
            if (!started) {
                try {
                    api.shutdown();
                } catch (RuntimeException ignored) {
                    // Preserve the initialization failure.
                }
            }
        }
    }

    public RemixAPI api() {
        requireOpen();
        return api;
    }

    public void submit() {
        requireOpen();
        frameState.submit();
    }

    public InteropInfo submitAndGetInteropInfo() {
        requireOpen();
        return frameState.submitAndGetInteropInfo();
    }

    public void ensureSubmitted() {
        requireOpen();
        frameState.ensureSubmitted();
    }

    public boolean isSubmitted() {
        return !shutdown && frameState.isSubmitted();
    }

    public void present(PresentInfo presentInfo) {
        requireOpen();
        frameState.present(presentInfo);
    }

    public void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        api.shutdown();
    }

    private void requireOpen() {
        if (shutdown) {
            throw new IllegalStateException("RTX Remix renderer session is shut down");
        }
    }

    public static Map<String, String> parseConfigOverrides(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> overrides = new LinkedHashMap<String, String>();
        for (String pair : value.split(";")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = pair.substring(0, separator).trim();
            String optionValue = pair.substring(separator + 1).trim();
            if (!key.isEmpty()) {
                overrides.put(key, optionValue);
            }
        }
        return overrides;
    }

    public static final class OpenResult {
        private final RemixRendererSession session;
        private final int redactedCommandLineValues;

        OpenResult(RemixRendererSession session, int redactedCommandLineValues) {
            this.session = session;
            this.redactedCommandLineValues = redactedCommandLineValues;
        }

        public RemixRendererSession session() {
            return session;
        }

        public int redactedCommandLineValues() {
            return redactedCommandLineValues;
        }
    }
}
