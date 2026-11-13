/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import java.util.Objects;

/** Loader-neutral identity of a Minecraft texture resource. */
public final class EntityTextureKey {
    public static final String DEFAULT_NAMESPACE = "minecraft";

    private final String namespace;
    private final String path;
    private final Object platformKey;
    private final int hash;

    public EntityTextureKey(String namespace, String path) {
        this(namespace, path, null);
    }

    public EntityTextureKey(String namespace, String path, Object platformKey) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.path = Objects.requireNonNull(path, "path");
        this.platformKey = platformKey;
        this.hash = 31 * namespace.hashCode() + path.hashCode();
    }

    public static EntityTextureKey of(String namespace, String path) {
        return new EntityTextureKey(namespace, path);
    }

    public static EntityTextureKey of(String value) {
        int separator = value.indexOf(':');
        return separator < 0
                ? new EntityTextureKey(DEFAULT_NAMESPACE, value)
                : new EntityTextureKey(
                        value.substring(0, separator),
                        value.substring(separator + 1));
    }

    public String namespace() {
        return namespace;
    }

    public String path() {
        return path;
    }

    public Object platformKey() {
        return platformKey;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EntityTextureKey)) {
            return false;
        }
        EntityTextureKey key = (EntityTextureKey) other;
        return namespace.equals(key.namespace) && path.equals(key.path);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return namespace + ':' + path;
    }
}
