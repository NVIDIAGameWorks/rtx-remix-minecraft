/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.Identifier;

/**
 * Collects vanilla rain and snow without submitting their layers to OpenGL.
 * The weather renderer owns the collector and its retained native allocations.
 */
public final class RemixWeatherVertexConsumerProvider implements VertexConsumerProvider, AutoCloseable {
    private final LayerBuffer[] buffers = new LayerBuffer[2];
    private boolean finished = true;

    RemixWeatherVertexConsumerProvider() {
    }

    /** Called only after the renderer has closed every previous frame's BuiltBuffer. */
    void beginFrame() {
        for (LayerBuffer buffer : buffers) {
            if (buffer != null) {
                buffer.allocator.clear();
                buffer.builder = null;
                buffer.transferred = false;
            }
        }
        finished = false;
    }

    @Override
    public VertexConsumer getBuffer(RenderLayer layer) {
        if (finished) throw new IllegalStateException("Precipitation capture is already finished");
        for (LayerBuffer buffer : buffers) {
            if (buffer != null && buffer.layer == layer) {
                return buffer.builder(layer);
            }
        }
        Identifier texture = texture(layer);
        for (int i = 0; i < buffers.length; i++) {
            LayerBuffer buffer = buffers[i];
            if (buffer == null) {
                buffer = new LayerBuffer(layer, texture);
                buffers[i] = buffer;
                return buffer.builder(layer);
            }
            if (buffer.texture.equals(texture)) {
                return buffer.builder(layer);
            }
        }
        throw new IllegalArgumentException("Precipitation produced more than two texture layers");
    }

    /** Transfers completed buffers to the deferred pass, retaining their backing allocations. */
    public void captureInto(RemixWeatherRenderer renderer) {
        if (finished) throw new IllegalStateException("Precipitation capture is already finished");
        finished = true;
        for (LayerBuffer layer : buffers) {
            if (layer == null || layer.builder == null) continue;
            BuiltBuffer buffer = layer.builder.endNullable();
            if (buffer != null) {
                renderer.capture(buffer, layer.texture);
                layer.transferred = true;
            }
        }
    }

    /** Ends this capture; transferred buffers remain valid until the deferred pass consumes them. */
    @Override
    public void close() {
        finished = true;
        for (LayerBuffer layer : buffers) {
            if (layer != null && !layer.transferred) {
                layer.allocator.clear();
                layer.builder = null;
            }
        }
    }

    /** Releases retained native capacity after all pending draw buffers have been closed. */
    void destroy() {
        finished = true;
        for (int i = 0; i < buffers.length; i++) {
            if (buffers[i] != null) {
                buffers[i].allocator.close();
                buffers[i] = null;
            }
        }
    }

    private static Identifier texture(RenderLayer layer) {
        if (!(layer instanceof RenderLayer.MultiPhase multiPhase)
                || !(multiPhase.getPhases().texture instanceof RenderPhase.Texture texturePhase)) {
            throw new IllegalArgumentException("Precipitation layer has no texture: " + layer);
        }
        return texturePhase.getId().orElseThrow(() ->
                new IllegalArgumentException("Precipitation layer has no texture identifier: " + layer));
    }

    private static final class LayerBuffer {
        private RenderLayer layer;
        private final Identifier texture;
        private final BufferAllocator allocator;
        private BufferBuilder builder;
        private boolean transferred;

        private LayerBuffer(RenderLayer layer, Identifier texture) {
            this.layer = layer;
            this.texture = texture;
            allocator = new BufferAllocator(layer.getExpectedBufferSize());
        }

        private BufferBuilder builder(RenderLayer requestedLayer) {
            if (builder == null) {
                layer = requestedLayer;
                builder = new BufferBuilder(allocator, layer.getDrawMode(), layer.getVertexFormat());
            } else if (layer != requestedLayer) {
                throw new IllegalStateException("Precipitation changed a texture's layer during capture");
            }
            return builder;
        }
    }
}
