/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.core.terrain.TerrainProfile;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;

/** Connects loader-independent terrain scopes to the Minecraft JFR/Tracy profiler. */
final class TerrainProfileAdapter {
    private TerrainProfileAdapter() {
    }

    static void install() {
        TerrainProfile.setFactory(Scope::new);
    }

    private static final class Scope implements TerrainProfile.Scope {
        private final ProfileScope delegate;

        Scope(String phase) {
            delegate = ProfileScope.begin(phase);
        }

        @Override public Scope captureCount(int value) {
            delegate.captureCount(value);
            return this;
        }

        @Override public Scope surfaceCount(int value) {
            delegate.surfaceCount(value);
            return this;
        }

        @Override public Scope vertexCount(int value) {
            delegate.vertexCount(value);
            return this;
        }

        @Override public Scope meshCategory(int value) {
            delegate.meshCategory(value);
            return this;
        }

        @Override public Scope installCount(int value) {
            delegate.installCount(value);
            return this;
        }

        @Override public Scope bytes(long value) {
            delegate.bytes(value);
            return this;
        }

        @Override public Scope drawCalls(int value) {
            delegate.drawCalls(value);
            return this;
        }

        @Override public void close() {
            delegate.close();
        }
    }
}
