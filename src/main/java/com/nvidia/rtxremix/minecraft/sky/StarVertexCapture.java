/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import net.minecraft.client.render.VertexConsumer;

import java.util.Arrays;

/** Mirrors the canonical star builder's positions without changing its vanilla output. */
public final class StarVertexCapture implements VertexConsumer {
    private final VertexConsumer delegate;
    private float[] positions = new float[4096];
    private int size;

    public StarVertexCapture(VertexConsumer delegate) {
        this.delegate = delegate;
    }

    public float[] copyVertices() {
        return Arrays.copyOf(positions, size);
    }

    @Override
    public VertexConsumer vertex(float x, float y, float z) {
        if (size + 3 > positions.length) {
            positions = Arrays.copyOf(positions, positions.length * 2);
        }
        positions[size++] = x;
        positions[size++] = y;
        positions[size++] = z;
        delegate.vertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        delegate.color(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer texture(float u, float v) {
        delegate.texture(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlay(int u, int v) {
        delegate.overlay(u, v);
        return this;
    }

    @Override
    public VertexConsumer light(int u, int v) {
        delegate.light(u, v);
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        delegate.normal(x, y, z);
        return this;
    }
}
