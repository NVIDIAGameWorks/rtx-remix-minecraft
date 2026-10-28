/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link VertexConsumerProvider} every Remix capture pass renders into.
 *
 * <p>Stands in for the vanilla {@code VertexConsumerProvider.Immediate} while an
 * entity, the first-person model or a block entity is rendered for Remix. For each
 * {@link RenderLayer} requested via {@link #getBuffer(RenderLayer)}, it returns a
 * part-recording {@link RemixCapturingConsumer} that records vertices alongside the
 * layer's texture {@link Identifier}.
 *
 * <h3>Usage per capture</h3>
 * <ol>
 *   <li>Call {@link #beginEntity()} before rendering</li>
 *   <li>Pass this provider to the vanilla renderer</li>
 *   <li>Call {@link #endEntity()} after rendering; it returns all captured layers</li>
 * </ol>
 */
public class RemixVertexConsumerProvider implements VertexConsumerProvider {

    /**
     * Consumers created during the current entity's render, keyed by RenderLayer, in
     * request order. Multiple layers per entity are common (body + eyes + glint) but the
     * count is small, so parallel arrays with a linear scan replace a map: a map entry per
     * layer per entity was a steady allocation on the render thread.
     */
    private RenderLayer[] activeLayers = new RenderLayer[8];
    private RemixCapturingConsumer[] activeConsumers = new RemixCapturingConsumer[8];
    private int activeCount;

    /** Pool of consumers for reuse. Only safe when consumers are consumed before next beginEntity(). */
    private final List<RemixCapturingConsumer> consumerPool = new ArrayList<>();

    /** Reusable result buffer, only safe when caller consumes before next beginEntity(). */
    private final List<CapturedLayer> resultBuffer = new ArrayList<>();

    /** The block the captured geometry is local to; see {@link #setBlockOrigin}. */
    private int originX, originY, originZ;

    // ---- Per-capture lifecycle ----

    /**
     * Begins capturing for a new entity. Clears previous capture state.
     */
    public void beginEntity() {
        for (int i = 0; i < activeCount; i++) {
            RemixCapturingConsumer consumer = activeConsumers[i];
            consumer.clear();
            consumerPool.add(consumer);
            activeConsumers[i] = null;
            activeLayers[i] = null;
        }
        activeCount = 0;
    }

    /**
     * Sets the block whose local space the next captures are in. Only surfaces whose
     * texture coordinates are projected from position read it, so neighbouring end
     * portal blocks continue one texture; a capture that never sets it projects as if
     * at the origin.
     */
    public void setBlockOrigin(BlockPos pos) {
        originX = pos.getX();
        originY = pos.getY();
        originZ = pos.getZ();
    }

    /**
     * Ends capturing for the current entity and returns all captured layers.
     * The returned list is reused; callers must consume it before the next
     * {@link #beginEntity()} call.
     */
    public List<CapturedLayer> endEntity() {
        resultBuffer.clear();
        for (int i = 0; i < activeCount; i++) {
            RemixCapturingConsumer consumer = activeConsumers[i];
            consumer.finishOpenVertex();
            // A cuboid part carries no vertices, so a layer can be non-empty with zero
            // captured vertices. layerIndex is assigned at first request, so dropping
            // an empty layer here cannot shift the others.
            if (consumer.getVertexCount() > 0 || consumer.getPartCount() > 0) {
                CapturedLayer layer = consumer.capturedLayer();
                layer.bind(activeLayers[i]);
                resultBuffer.add(layer);
            }
        }
        // The active set is kept: beginEntity() returns it to the pool.
        return resultBuffer;
    }

    // ---- VertexConsumerProvider ----

    @Override
    public VertexConsumer getBuffer(RenderLayer layer) {
        // Reuse consumer for same layer within one entity (e.g. left arm + right arm)
        for (int i = 0; i < activeCount; i++) {
            if (activeLayers[i].equals(layer)) {
                return activeConsumers[i];
            }
        }

        Optional<Identifier> texOpt = RenderLayerUtils.capturableTexture(layer);
        Identifier textureId;
        if (texOpt.isPresent()) {
            textureId = texOpt.get();
        } else if (RenderLayerUtils.isCapturableUntexturedLayer(layer)) {
            // Shaded entirely by vertex colour, so it needs an identity to hang a
            // material on but no texture of its own. In 1.21.1 the lead is the only
            // layer that reaches this.
            textureId = RenderLayerUtils.UNTEXTURED_LAYER_ID;
        } else {
            // Nothing Remix can draw: shadow, debug overlays, lines, depth-only masks,
            // or text drawn over the whole scene regardless of depth.
            return NoOpVertexConsumer.INSTANCE;
        }

        RenderLayerUtils.Topology topology = RenderLayerUtils.topology(layer)
                .orElse(RenderLayerUtils.Topology.QUADS);
        boolean transientGeometry = RenderLayerUtils.isStreamedPerFrame(layer);
        RemixCapturingConsumer consumer;
        if (!consumerPool.isEmpty()) {
            consumer = consumerPool.remove(consumerPool.size() - 1);
            consumer.reinit(textureId, true, topology, transientGeometry);
        } else {
            consumer = new RemixCapturingConsumer(textureId, true, topology, transientGeometry);
        }
        if (EndPortalSurface.isSurfaceTexture(textureId)) {
            consumer.projectTexcoords(EndPortalSurface.TILE_BLOCKS, originX, originY, originZ);
        }
        consumer.setLayerIndex(activeCount);
        if (activeCount == activeLayers.length) {
            activeLayers = Arrays.copyOf(activeLayers, activeCount * 2);
            activeConsumers = Arrays.copyOf(activeConsumers, activeCount * 2);
        }
        activeLayers[activeCount] = layer;
        activeConsumers[activeCount] = consumer;
        activeCount++;
        return consumer;
    }

    // ---- Result container ----

    /**
     * Captured vertex data for a single RenderLayer within an entity render: a view of the
     * consumer that captured it, holding its flat arrays directly (no copy). One instance
     * lives with each pooled consumer and is re-pointed per entity, so a frame of entity
     * capture allocates no layer objects. Valid until the provider's next
     * {@link #beginEntity()}, like the list that holds it.
     */
    public static final class CapturedLayer {
        private final RemixCapturingConsumer consumer;
        private RenderLayer renderLayer;

        CapturedLayer(RemixCapturingConsumer consumer) {
            this.consumer = consumer;
        }

        void bind(RenderLayer renderLayer) {
            this.renderLayer = renderLayer;
        }

        public Identifier textureId() { return consumer.getTextureId(); }
        public RenderLayer renderLayer() { return renderLayer; }
        public RemixCapturingConsumer consumer() { return consumer; }
        public int vertexCount() { return consumer.getVertexCount(); }
        /** Order in which the entity first requested this layer; scales the shell offset. */
        public int layerIndex() { return consumer.getLayerIndex(); }
        /** How to read the vertices as triangles. */
        public RenderLayerUtils.Topology topology() { return consumer.getTopology(); }
        /** Whether the layer's vertex content differs every frame, so a mesh built from it must not be pooled. */
        public boolean transientGeometry() { return consumer.isTransientGeometry(); }
    }

    // ---- No-op consumer for layers Remix cannot draw ----

    private static class NoOpVertexConsumer implements VertexConsumer {
        static final NoOpVertexConsumer INSTANCE = new NoOpVertexConsumer();
        @Override public VertexConsumer vertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer texture(float u, float v) { return this; }
        @Override public VertexConsumer overlay(int u, int v) { return this; }
        @Override public VertexConsumer light(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
    }
}
