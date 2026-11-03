/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlintTextureAnimationTest {
    @org.junit.jupiter.api.BeforeEach
    @org.junit.jupiter.api.AfterEach
    void resetContext() { EntityRenderContext.get().reset(); }

    @Test
    void scrollingMatchesVanillaTextureMatrixTranslationWithoutChangingMeshUVs() {
        byte[] source = new byte[4 * 3 * 4];
        for (int i = 0; i < source.length; i++) source[i] = (byte) (i * 5);
        try (var animation = new GlintTextureAnimation(source, 4, 3)) {
            ByteBuffer retained = animation.pixels();
            for (long time : new long[] {0, 15_000, 30_000, 110_000, 329_999, 330_000}) {
                assertTrue(animation.prepare(time));
                assertSame(retained, animation.pixels(), "texture animation reuses its native staging buffer");
                for (int y = 0; y < 3; y++) for (int x = 0; x < 4; x++) {
                    double u = (x + 0.5) / 4 - (time % 110_000) / 110_000.0;
                    double v = (y + 0.5) / 3 + (time % 30_000) / 30_000.0;
                    for (int c = 0; c < 4; c++) {
                        assertEquals(referenceSample(source, 4, 3, u, v, c),
                                animation.pixels().get((y * 4 + x) * 4 + c) & 255, 1);
                    }
                }
                assertFalse(animation.prepare(time), "a paused glint clock must not upload again");
            }
        }
    }

    @Test
    void itemAndArmorMeshesUseVanillaRotationAndScaleWithoutAtlasClamping() throws Exception {
        RemixCapturingConsumer captured = new RemixCapturingConsumer(null, true);
        for (int i = 0; i < 4; i++) {
            captured.vertex(i & 1, i >> 1, 0).texture(0.4f + (i & 1) * 0.1f, 0.6f).normal(0, 0, 1);
        }
        captured.finishOpenVertex();
        captured.getTexcoordBoundsMin()[0] = HardcodedVertex.packHalf2x16(0.4f, 0.5f);
        captured.getTexcoordBoundsMax()[0] = HardcodedVertex.packHalf2x16(0.5f, 0.7f);
        long originalHash = captured.getPartHash(0);
        RemixMaterial material = new RemixMaterial(new MaterialInfo(), null, null);
        RemixEntitySubmitter submitter = new RemixEntitySubmitter(null, null);
        try {
            for (String kind : new String[] {"item", "entity"}) {
                Identifier texture = Identifier.ofVanilla("textures/misc/enchanted_glint_" + kind + ".png");
                float scale = kind.equals("item") ? 8f : 0.16f;
                Matrix4f vanilla = new Matrix4f().rotateZ((float) Math.toRadians(10)).scale(scale);
                RemixMeshSurface surface = submitter.buildSurface(captured, 0, 4, RenderLayerUtils.Topology.QUADS,
                        0, 0, texture, RenderLayerUtils.SurfaceMode.GLINT, material);
                try {
                    var method = RemixMeshSurface.class.getDeclaredMethod("toNative");
                    method.setAccessible(true);
                    var info = (MeshInfoSurfaceTriangles) method.invoke(surface);
                    for (int vertex = 0; vertex < 4; vertex++) {
                        Vector3f expected = vanilla.transformPosition(new Vector3f(
                                captured.getTu()[vertex], captured.getTv()[vertex], 0));
                        int offset = vertex * HardcodedVertex.STRIDE;
                        assertEquals(expected.x, info.vertexBuffer.getFloat(offset + 24), 1e-6f);
                        assertEquals(expected.y, info.vertexBuffer.getFloat(offset + 28), 1e-6f);
                        assertEquals(0, info.vertexBuffer.getInt(offset + 36));
                        assertEquals(0, info.vertexBuffer.getInt(offset + 40));
                    }
                } finally {
                    surface.disposeData();
                }
            }
            assertEquals(originalHash, captured.getPartHash(0));
            assertEquals(0.4f, captured.getTu()[0], "meshes retain the captured UVs across animation frames");
        } finally {
            submitter.close();
        }
    }

    private static int referenceSample(byte[] pixels, int width, int height, double u, double v, int channel) {
        double x = u * width - 0.5, y = v * height - 0.5;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double total = 0;
        for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
            int index = (Math.floorMod(iy + dy, height) * width + Math.floorMod(ix + dx, width)) * 4 + channel;
            double weight = (dx == 0 ? 1 - (x - ix) : x - ix) * (dy == 0 ? 1 - (y - iy) : y - iy);
            total += (pixels[index] & 255) * weight;
        }
        return (int) Math.round(total);
    }
}
