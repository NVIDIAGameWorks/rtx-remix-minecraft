/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core.terrain;

/** Optional profiling bridge supplied by the active loader. */
public final class TerrainProfile {
    public interface Factory {
        Scope begin(String phase);
    }

    public interface Scope extends AutoCloseable {
        Scope captureCount(int value);

        Scope surfaceCount(int value);

        Scope vertexCount(int value);

        Scope meshCategory(int value);

        Scope installCount(int value);

        Scope bytes(long value);

        Scope drawCalls(int value);

        @Override
        void close();
    }

    private static final Scope NOOP_SCOPE = new Scope() {
        @Override public Scope captureCount(int value) { return this; }
        @Override public Scope surfaceCount(int value) { return this; }
        @Override public Scope vertexCount(int value) { return this; }
        @Override public Scope meshCategory(int value) { return this; }
        @Override public Scope installCount(int value) { return this; }
        @Override public Scope bytes(long value) { return this; }
        @Override public Scope drawCalls(int value) { return this; }
        @Override public void close() { }
    };
    private static final Factory NOOP_FACTORY = new Factory() {
        @Override public Scope begin(String phase) { return NOOP_SCOPE; }
    };
    private static volatile Factory factory = NOOP_FACTORY;

    private TerrainProfile() {
    }

    public static void setFactory(Factory value) {
        factory = value == null ? NOOP_FACTORY : value;
    }

    public static Scope begin(String phase) {
        Scope scope = factory.begin(phase);
        return scope == null ? NOOP_SCOPE : scope;
    }
}
