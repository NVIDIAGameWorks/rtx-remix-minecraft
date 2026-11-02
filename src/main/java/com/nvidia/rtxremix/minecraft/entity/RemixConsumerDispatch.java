/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.SpriteTexturedVertexConsumer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumers;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

/**
 * Keeps poses attached to geometry as it crosses vanilla's glint unions.
 * Union defaults otherwise pose the vertices before either delegate can capture
 * the local mesh, its sprite bounds, or the hand owning its transform.
 */
public final class RemixConsumerDispatch {
    private RemixConsumerDispatch() {}

    private static boolean isUnion(VertexConsumer consumer) {
        return consumer instanceof VertexConsumers.Dual || consumer instanceof VertexConsumers.Union;
    }

    private static boolean captureUnion(VertexConsumer consumer) {
        return isUnion(consumer) && EntityRenderContext.get().isRemixPass();
    }

    private static int size(VertexConsumer consumer) {
        return consumer instanceof VertexConsumers.Dual ? 2 : ((VertexConsumers.Union) consumer).delegates().length;
    }

    private static VertexConsumer delegate(VertexConsumer consumer, int index) {
        if (consumer instanceof VertexConsumers.Dual dual) {
            return index == 0 ? dual.first : dual.second;
        }
        return ((VertexConsumers.Union) consumer).delegates()[index];
    }

    public static boolean quad(VertexConsumer consumer, MatrixStack.Entry entry, BakedQuad quad,
                               float red, float green, float blue, float alpha, int light, int overlay) {
        if (!captureUnion(consumer)) return false;
        for (int i = 0, count = size(consumer); i < count; i++) {
            delegate(consumer, i).quad(entry, quad, red, green, blue, alpha, light, overlay);
        }
        return true;
    }

    public static boolean quad(VertexConsumer consumer, MatrixStack.Entry entry, BakedQuad quad,
                               float[] brightness, float red, float green, float blue, float alpha,
                               int[] lights, int overlay, boolean colorize) {
        if (!captureUnion(consumer)) return false;
        for (int i = 0, count = size(consumer); i < count; i++) {
            delegate(consumer, i).quad(entry, quad, brightness, red, green, blue, alpha, lights, overlay, colorize);
        }
        return true;
    }

    public static boolean vertex(VertexConsumer consumer, MatrixStack.Entry entry, float x, float y, float z) {
        if (!captureUnion(consumer)) return false;
        for (int i = 0, count = size(consumer); i < count; i++) {
            delegate(consumer, i).vertex(entry, x, y, z);
        }
        return true;
    }

    public static boolean vertex(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z) {
        if (!captureUnion(consumer)) return false;
        for (int i = 0, count = size(consumer); i < count; i++) {
            delegate(consumer, i).vertex(matrix, x, y, z);
        }
        return true;
    }

    public static boolean normal(VertexConsumer consumer, MatrixStack.Entry entry, float x, float y, float z) {
        if (!captureUnion(consumer)) return false;
        for (int i = 0, count = size(consumer); i < count; i++) {
            delegate(consumer, i).normal(entry, x, y, z);
        }
        return true;
    }

    /** Finds a capture leaf through sprite wrappers and unions, without reading thread-local state. */
    public static RemixCapturingConsumer findCapture(VertexConsumer consumer) {
        if (consumer instanceof RemixCapturingConsumer capture) return capture;
        if (consumer instanceof SpriteTexturedVertexConsumer wrapped) return findCapture(wrapped.delegate);
        if (consumer instanceof VertexConsumers.Dual dual) {
            RemixCapturingConsumer first = findCapture(dual.first);
            return first != null ? first : findCapture(dual.second);
        }
        if (consumer instanceof VertexConsumers.Union union) {
            for (VertexConsumer branch : union.delegates()) {
                RemixCapturingConsumer capture = findCapture(branch);
                if (capture != null) return capture;
            }
        }
        return null;
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
            capture.acceptCuboid(cuboid.sides, RemixCapturingConsumer.applyEntityOverlay(color, overlay),
                    entry.getPositionMatrix(), sprite);
        } else if (consumer instanceof SpriteTexturedVertexConsumer wrapped && sprite == null) {
            renderCuboid(wrapped.delegate, cuboid, entry, light, overlay, color, wrapped.sprite);
        } else if (isUnion(consumer)) {
            for (int i = 0, count = size(consumer); i < count; i++) {
                renderCuboid(delegate(consumer, i), cuboid, entry, light, overlay, color, sprite);
            }
        } else if (!RemixVertexConsumerProvider.isDiscarding(consumer)) {
            if (sprite == null) cuboid.renderCuboid(entry, consumer, light, overlay, color);
            else emitSpriteCuboid(consumer, cuboid, entry, light, overlay, color, sprite);
        }
    }

    private static void emitSpriteCuboid(VertexConsumer consumer, ModelPart.Cuboid cuboid, MatrixStack.Entry entry,
                                         int light, int overlay, int color, Sprite sprite) {
        for (ModelPart.Quad side : cuboid.sides) {
            for (ModelPart.Vertex vertex : side.vertices) {
                consumer.vertex(entry, vertex.pos.x / 16f, vertex.pos.y / 16f, vertex.pos.z / 16f)
                        .color(color).texture(sprite.getFrameU(vertex.u), sprite.getFrameV(vertex.v))
                        .overlay(overlay).light(light).normal(entry, side.direction.x, side.direction.y, side.direction.z);
            }
        }
    }
}
