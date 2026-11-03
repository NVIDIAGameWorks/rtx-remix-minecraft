/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter.SHELL_OFFSET;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins how a canvas's pattern stack is baked into one mesh. Remix composites the
 * coplanar decals a ray crosses by primitive index, not by depth, so the layers of
 * one banner must be one mesh in paint order: separate meshes of identical topology
 * tie and land in traversal order, which is what put lower patterns on top.
 */
class RemixEntitySubmitterCanvasMeshTest {

    private static final int LIGHT_BLUE = 0xFF3AB3DA;
    private static final int LIGHT_GRAY = 0xFF9D9D97;
    private static final int GREEN = 0xFF5E7C16;
    private static final int CYAN = 0xFF169C9C;
    private static final int VERTICES_PER_CUBOID = 24;
    /** The translucent no-outline layer is the second layer a banner requests. */
    private static final int LAYER = 1;
    private static final int BASE_PART = 0;
    private static final int FIRST_PATTERN = 1;
    private static final int PATTERN_COUNT = 3;
    private static final float EPSILON = 1e-7f;

    private final ModelPart.Quad[] flag = flag();
    private final Sprite baseSprite = SpriteFixtures.sprite(0, 0, 64, 512);
    private final Sprite rhombus = SpriteFixtures.sprite(64, 0, 64, 512);
    private final Sprite flower = SpriteFixtures.sprite(128, 0, 64, 512);
    private final Sprite halfBottom = SpriteFixtures.sprite(192, 0, 64, 512);
    private final Sprite[] patternSprites = {rhombus, flower, halfBottom};
    private final int[] patternTints = {LIGHT_GRAY, GREEN, CYAN};

    @Test
    void theStackIsOneRunOfCuboidsInPaintOrderEachWithItsOwnTint() {
        RemixCapturingConsumer scratch = emitBeachCanvas();

        assertEquals(PATTERN_COUNT * VERTICES_PER_CUBOID, scratch.getVertexCount());
        int[] colors = scratch.getColors();
        for (int layer = 0; layer < PATTERN_COUNT; layer++) {
            for (int v = 0; v < VERTICES_PER_CUBOID; v++) {
                assertEquals(patternTints[layer], colors[layer * VERTICES_PER_CUBOID + v],
                        "tint of layer " + layer + " vertex " + v);
            }
        }
    }

    @Test
    void eachLayerIsPaintedWithItsOwnSprite() {
        RemixCapturingConsumer scratch = emitBeachCanvas();

        float[] tu = scratch.getTu();
        float[] tv = scratch.getTv();
        int i = 0;
        for (int layer = 0; layer < PATTERN_COUNT; layer++) {
            Sprite sprite = patternSprites[layer];
            for (ModelPart.Quad quad : flag) {
                for (ModelPart.Vertex vertex : quad.vertices) {
                    assertEquals(sprite.getFrameU(vertex.u), tu[i], EPSILON, "u of vertex " + i);
                    assertEquals(sprite.getFrameV(vertex.v), tv[i], EPSILON, "v of vertex " + i);
                    i++;
                }
            }
        }
    }

    @Test
    void layersStepOutwardFromTheBaseInPaintOrder() {
        // The base sits at the layer's own shell; pattern k at k + 1 shells further out,
        // the same depths the patterns had as separate parts, so the stack stays in
        // front of its opaque base and a held canvas still orders by depth.
        RemixCapturingConsumer scratch = emitBeachCanvas();

        float[] px = scratch.getPx(), py = scratch.getPy(), pz = scratch.getPz();
        int i = 0;
        for (int layer = 0; layer < PATTERN_COUNT; layer++) {
            float shell = (LAYER + layer + 1) * SHELL_OFFSET;
            for (ModelPart.Quad quad : flag) {
                for (ModelPart.Vertex vertex : quad.vertices) {
                    assertEquals(vertex.pos.x / 16f + quad.direction.x() * shell, px[i], EPSILON, "x of vertex " + i);
                    assertEquals(vertex.pos.y / 16f + quad.direction.y() * shell, py[i], EPSILON, "y of vertex " + i);
                    assertEquals(vertex.pos.z / 16f + quad.direction.z() * shell, pz[i], EPSILON, "z of vertex " + i);
                    i++;
                }
            }
        }
    }

    /** Bakes the Beach banner's first three patterns the way a pool miss does. */
    private RemixCapturingConsumer emitBeachCanvas() {
        RemixCapturingConsumer source = new RemixCapturingConsumer(null, true);
        source.replayCuboidPart(flag, LIGHT_BLUE, 1, baseSprite);
        for (int layer = 0; layer < PATTERN_COUNT; layer++) {
            source.replayCuboidPart(flag, patternTints[layer], 1, patternSprites[layer]);
        }
        source.finishOpenVertex();
        assertEquals(BASE_PART + 1 + PATTERN_COUNT, source.getPartCount());

        RemixCapturingConsumer scratch = new RemixCapturingConsumer(null, false);
        RemixEntitySubmitter.emitCanvasPatterns(scratch, source, FIRST_PATTERN, PATTERN_COUNT, LAYER);
        scratch.finishOpenVertex();
        return scratch;
    }

    /** A flag-shaped cuboid: six faces on distinct normals, UVs on the banner's 20x40 flag rectangle. */
    private static ModelPart.Quad[] flag() {
        ModelPart.Quad[] sides = new ModelPart.Quad[6];
        Direction[] directions = Direction.values();
        for (int q = 0; q < sides.length; q++) {
            ModelPart.Vertex[] vertices = new ModelPart.Vertex[4];
            for (int v = 0; v < 4; v++) {
                vertices[v] = new ModelPart.Vertex(new Vector3f(q + v, q * 2, v), 0f, 0f);
            }
            sides[q] = new ModelPart.Quad(vertices, 1, 1, 21, 41, 64, 64, false, directions[q]);
        }
        return sides;
    }
}
