/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.SpriteTexturedVertexConsumer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps poses attached to geometry as it crosses vanilla's glint unions.
 * Union defaults otherwise pose the vertices before either delegate can capture
 * the local mesh, its sprite bounds, or the hand owning its transform.
 */
public final class RemixConsumerDispatch {
    private RemixConsumerDispatch() {}

    public static boolean quad(RemixUnionConsumer consumer, MatrixStack.Entry entry, BakedQuad quad,
                               float red, float green, float blue, float alpha, int light, int overlay) {
        RemixCapturingConsumer capture = consumer.remixCapture();
        if (capture == null || !capture.isRemixCaptureActive()) return false;
        VertexConsumer[] delegates = consumer.remixDelegates();
        if (delegates == null) {
            consumer.remixFirst().quad(entry, quad, red, green, blue, alpha, light, overlay);
            consumer.remixSecond().quad(entry, quad, red, green, blue, alpha, light, overlay);
        } else {
            for (VertexConsumer delegate : delegates) delegate.quad(entry, quad, red, green, blue, alpha, light, overlay);
        }
        return true;
    }

    public static boolean quad(RemixUnionConsumer consumer, MatrixStack.Entry entry, BakedQuad quad,
                               float[] brightness, float red, float green, float blue, float alpha,
                               int[] lights, int overlay, boolean colorize) {
        RemixCapturingConsumer capture = consumer.remixCapture();
        if (capture == null || !capture.isRemixCaptureActive()) return false;
        VertexConsumer[] delegates = consumer.remixDelegates();
        if (delegates == null) {
            consumer.remixFirst().quad(entry, quad, brightness, red, green, blue, alpha, lights, overlay, colorize);
            consumer.remixSecond().quad(entry, quad, brightness, red, green, blue, alpha, lights, overlay, colorize);
        } else {
            for (VertexConsumer delegate : delegates) delegate.quad(entry, quad, brightness, red, green, blue, alpha, lights, overlay, colorize);
        }
        return true;
    }

    public static boolean vertex(RemixUnionConsumer consumer, MatrixStack.Entry entry, float x, float y, float z) {
        RemixCapturingConsumer capture = consumer.remixCapture();
        if (capture == null || !capture.isRemixCaptureActive()) return false;
        VertexConsumer[] delegates = consumer.remixDelegates();
        if (delegates == null) {
            consumer.remixFirst().vertex(entry, x, y, z);
            consumer.remixSecond().vertex(entry, x, y, z);
        } else {
            for (VertexConsumer delegate : delegates) delegate.vertex(entry, x, y, z);
        }
        return true;
    }

    public static boolean vertex(RemixUnionConsumer consumer, Matrix4f matrix, float x, float y, float z) {
        RemixCapturingConsumer capture = consumer.remixCapture();
        if (capture == null || !capture.isRemixCaptureActive()) return false;
        VertexConsumer[] delegates = consumer.remixDelegates();
        if (delegates == null) {
            consumer.remixFirst().vertex(matrix, x, y, z);
            consumer.remixSecond().vertex(matrix, x, y, z);
        } else {
            for (VertexConsumer delegate : delegates) delegate.vertex(matrix, x, y, z);
        }
        return true;
    }

    public static boolean normal(RemixUnionConsumer consumer, MatrixStack.Entry entry, float x, float y, float z) {
        RemixCapturingConsumer capture = consumer.remixCapture();
        if (capture == null || !capture.isRemixCaptureActive()) return false;
        VertexConsumer[] delegates = consumer.remixDelegates();
        if (delegates == null) {
            consumer.remixFirst().normal(entry, x, y, z);
            consumer.remixSecond().normal(entry, x, y, z);
        } else {
            for (VertexConsumer delegate : delegates) delegate.normal(entry, x, y, z);
        }
        return true;
    }

    /** Reads the leaf bound by a known wrapper at construction, without walking its chain. */
    public static RemixCapturingConsumer findCapture(VertexConsumer consumer) {
        if (consumer instanceof RemixCapturingConsumer capture) return capture;
        return consumer instanceof RemixCaptureSource source ? source.remixCapture() : null;
    }

    /** Retains capture leaves independently while other branches keep their normal vertex rendering. */
    public static boolean cuboid(VertexConsumer consumer, ModelPart.Cuboid cuboid, MatrixStack.Entry entry,
                                 int light, int overlay, int color) {
        RemixCapturingConsumer capture = findCapture(consumer);
        if (capture == null || !capture.isRemixCaptureActive()) return false;
        renderCuboid(consumer, cuboid, entry, light, overlay, color, null);
        return true;
    }

    private static void renderCuboid(VertexConsumer consumer, ModelPart.Cuboid cuboid, MatrixStack.Entry entry,
                                      int light, int overlay, int color, Sprite sprite) {
        if (consumer instanceof RemixCapturingConsumer capture) {
            // The tint is folded before capture because it is an instance property
            // downstream: a damage or priming flash updates the instance, never the mesh.
            capture.acceptCuboid(cuboid.sides, RemixCapturingConsumer.applyEntityOverlay(color, overlay),
                    entry.getPositionMatrix(), sprite);
        } else if (consumer instanceof SpriteTexturedVertexConsumer wrapped && sprite == null) {
            // The capture redoes the sprite's UV remap when it rebuilds the cuboid, so the
            // sprite travels with the part: one cuboid is painted with many sprites.
            renderCuboid(wrapped.delegate, cuboid, entry, light, overlay, color, wrapped.sprite);
        } else if (consumer instanceof RemixUnionConsumer union) {
            VertexConsumer[] delegates = union.remixDelegates();
            if (delegates == null) {
                renderCuboid(union.remixFirst(), cuboid, entry, light, overlay, color, sprite);
                renderCuboid(union.remixSecond(), cuboid, entry, light, overlay, color, sprite);
            } else {
                for (VertexConsumer delegate : delegates) renderCuboid(delegate, cuboid, entry, light, overlay, color, sprite);
            }
        } else if (!RemixVertexConsumerProvider.isDiscarding(consumer)) {
            if (sprite == null) cuboid.renderCuboid(entry, consumer, light, overlay, color);
            else emitSpriteCuboid(consumer, cuboid, entry, light, overlay, color, sprite);
        }
    }

    private static final class SpriteRemaps {
        final List<SpriteRemap> remaps = new ArrayList<>();
        int depth;
    }

    private static final ThreadLocal<SpriteRemaps> spriteRemaps = ThreadLocal.withInitial(SpriteRemaps::new);

    /** Reuses only the sprite remap; vanilla and other mods still own cuboid drawing. */
    private static void emitSpriteCuboid(VertexConsumer consumer, ModelPart.Cuboid cuboid, MatrixStack.Entry entry,
                                         int light, int overlay, int color, Sprite sprite) {
        SpriteRemaps pool = spriteRemaps.get();
        int depth = pool.depth++;
        if (depth == pool.remaps.size()) pool.remaps.add(new SpriteRemap());
        SpriteRemap remap = pool.remaps.get(depth);
        remap.target = consumer;
        remap.sprite = sprite;
        try {
            cuboid.renderCuboid(entry, remap, light, overlay, color);
        } finally {
            remap.target = null;
            remap.sprite = null;
            pool.depth--;
        }
    }

    private static final class SpriteRemap implements VertexConsumer {
        private VertexConsumer target;
        private Sprite sprite;

        @Override public VertexConsumer vertex(float x, float y, float z) { target.vertex(x, y, z); return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { target.color(r, g, b, a); return this; }
        @Override public VertexConsumer texture(float u, float v) {
            target.texture(sprite.getFrameU(u), sprite.getFrameV(v));
            return this;
        }
        @Override public VertexConsumer overlay(int u, int v) { target.overlay(u, v); return this; }
        @Override public VertexConsumer light(int u, int v) { target.light(u, v); return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { target.normal(x, y, z); return this; }
    }
}
