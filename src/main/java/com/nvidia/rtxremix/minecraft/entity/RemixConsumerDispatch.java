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
        return consumer instanceof VertexConsumers.Dual ? 2 : ((VertexConsumers.Union) consumer).delegates.length;
    }

    private static VertexConsumer delegate(VertexConsumer consumer, int index) {
        if (consumer instanceof VertexConsumers.Dual dual) {
            return index == 0 ? dual.first : dual.second;
        }
        return ((VertexConsumers.Union) consumer).delegates[index];
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

    /** Captures a complete cuboid only when every branch can retain its local geometry. */
    public static boolean cuboid(VertexConsumer consumer, ModelPart.Quad[] sides, int color, Matrix4f pose) {
        if (!EntityRenderContext.get().isRemixPass() || !canCaptureCuboid(consumer, false)) return false;
        captureCuboid(consumer, sides, color, pose, null);
        return true;
    }

    private static boolean canCaptureCuboid(VertexConsumer consumer, boolean hasSprite) {
        if (consumer instanceof RemixCapturingConsumer) return true;
        if (consumer instanceof SpriteTexturedVertexConsumer wrapped) {
            return !hasSprite && canCaptureCuboid(wrapped.delegate, true);
        }
        if (!isUnion(consumer)) return false;
        for (int i = 0, count = size(consumer); i < count; i++) {
            if (!canCaptureCuboid(delegate(consumer, i), hasSprite)) return false;
        }
        return true;
    }

    private static void captureCuboid(VertexConsumer consumer, ModelPart.Quad[] sides,
                                      int color, Matrix4f pose, Sprite sprite) {
        if (consumer instanceof RemixCapturingConsumer capture) {
            capture.acceptCuboid(sides, color, pose, sprite);
        } else if (consumer instanceof SpriteTexturedVertexConsumer wrapped) {
            captureCuboid(wrapped.delegate, sides, color, pose, wrapped.sprite);
        } else {
            for (int i = 0, count = size(consumer); i < count; i++) {
                captureCuboid(delegate(consumer, i), sides, color, pose, sprite);
            }
        }
    }
}
