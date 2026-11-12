/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import com.nvidia.rtxremix.forge.texture.SpriteOpacity;
import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class BlockOverlaysTest {
    private static final float[] FACE = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
    private static final float[] UV = {0.2f, 0.3f, 0.4f, 0.3f, 0.4f, 0.5f, 0.2f, 0.5f};
    private static final int[] COLOR = {0xff258945, 0xff369a56, 0xff47ab67, 0xff58bc78};

    @Test public void exactGrassSidePairRoutesOnlyOverlayAndPreservesEveryPackedAttribute() {
        BlockOverlays stage = new BlockOverlays(); stage.begin();
        add(stage, FACE, UV, COLOR, SpriteOpacity.OPAQUE, 1);
        add(stage, FACE, UV, COLOR, SpriteOpacity.CUTOUT, 1);
        MeshPacking base = new MeshPacking(), decal = new MeshPacking();
        BlockOverlaySink sink = new BlockOverlaySink(decal); sink.target(base); stage.append(sink);
        assertEquals(4, base.vertexCount()); assertEquals(4, decal.vertexCount());
        assertEquals(base.vertices(), decal.vertices());
        assertEquals(0xff258945, decal.vertices().getInt(32));
    }

    @Test public void candidateMayPrecedeBaseAndCyclicVertexOriginsStillMatch() {
        BlockOverlays stage = new BlockOverlays(); stage.begin();
        add(stage, FACE, UV, COLOR, SpriteOpacity.CUTOUT, 1);
        add(stage, rotate(FACE, 1), UV, COLOR, SpriteOpacity.OPAQUE, 1);
        assertCounts(stage, 4, 4);
    }

    @Test public void offsetOppositeWindingOppositeNormalAndPartialOverlapRemainOrdinary() {
        for (int kind = 0; kind < 4; kind++) {
            BlockOverlays stage = new BlockOverlays(); stage.begin();
            add(stage, FACE, UV, COLOR, SpriteOpacity.OPAQUE, 1);
            float[] face = FACE.clone();
            if (kind == 0) for (int v = 0; v < 4; v++) face[v * 3 + 2] = 0.000001f;
            if (kind == 1) for (int v = 0; v < 4; v++) System.arraycopy(FACE, (3 - v) * 3, face, v * 3, 3);
            if (kind == 3) for (int v = 0; v < 4; v++) face[v * 3] *= 0.5f;
            add(stage, face, UV, COLOR, SpriteOpacity.CUTOUT, kind == 2 ? -1 : 1);
            assertCounts(stage, 8, 0);
        }
    }

    @Test public void unknownOrTransparentBasesAndFadedVertexColorsCannotAuthorizeDecals() {
        for (int coverage : new int[] {SpriteOpacity.UNKNOWN, SpriteOpacity.CUTOUT}) {
            BlockOverlays stage = new BlockOverlays(); stage.begin();
            add(stage, FACE, UV, COLOR, coverage, 1);
            add(stage, FACE, UV, COLOR, SpriteOpacity.CUTOUT, 1);
            assertCounts(stage, 8, 0);
        }
        for (boolean fadeBase : new boolean[] {false, true}) {
            BlockOverlays stage = new BlockOverlays(); stage.begin();
            int[] faded = COLOR.clone(); faded[2] &= 0x7fffffff;
            add(stage, FACE, UV, fadeBase ? faded : COLOR, SpriteOpacity.OPAQUE, 1);
            add(stage, FACE, UV, fadeBase ? COLOR : faded, SpriteOpacity.CUTOUT, 1);
            assertCounts(stage, 8, 0);
        }
    }

    @Test public void outOfSpriteUvCannotBorrowAnUnrelatedOpacityProof() {
        for (boolean badBase : new boolean[] {false, true}) {
            BlockOverlays stage = new BlockOverlays(); stage.begin();
            float[] outside = UV.clone(); outside[0] = 0.19f;
            add(stage, FACE, badBase ? outside : UV, COLOR, SpriteOpacity.OPAQUE, 1);
            add(stage, FACE, badBase ? UV : outside, COLOR, SpriteOpacity.CUTOUT, 1);
            assertCounts(stage, 8, 0);
        }
    }

    @Test public void fullBoundedTableKeepsPaintOrderAndNextBlockHasNoStaleBases() {
        BlockOverlays stage = new BlockOverlays(); stage.begin();
        for (int i = 0; i < BlockOverlays.MAX_QUADS; i++) {
            float[] face = FACE.clone(); for (int v = 0; v < 4; v++) face[v * 3 + 2] = i / 2;
            int[] colors = COLOR.clone(); colors[0] = 0xff000000 | i;
            add(stage, face, UV, colors, (i & 1) == 0 ? SpriteOpacity.OPAQUE : SpriteOpacity.CUTOUT, 1);
        }
        MeshPacking base = new MeshPacking(), decal = new MeshPacking();
        BlockOverlaySink sink = new BlockOverlaySink(decal); sink.target(base); stage.append(sink);
        assertEquals(BlockOverlays.MAX_QUADS * 2, base.vertexCount());
        assertEquals(BlockOverlays.MAX_QUADS * 2, decal.vertexCount());
        ByteBuffer vertices = decal.vertices();
        for (int i = 0; i < BlockOverlays.MAX_QUADS / 2; i++)
            assertEquals(0xff000000 | i * 2 + 1, vertices.getInt(i * 4 * MeshPacking.STRIDE + 32));
        try { add(stage, FACE, UV, COLOR, SpriteOpacity.OPAQUE, 1); fail("Expected bounded staging rejection"); }
        catch (IllegalStateException expected) { }
        stage.begin(); add(stage, FACE, UV, COLOR, SpriteOpacity.CUTOUT, 1);
        assertCounts(stage, 4, 0);
    }

    private static void assertCounts(BlockOverlays stage, int ordinary, int overlay) {
        MeshPacking base = new MeshPacking(), decal = new MeshPacking();
        BlockOverlaySink sink = new BlockOverlaySink(decal); sink.target(base); stage.append(sink);
        assertEquals(ordinary, base.vertexCount()); assertEquals(overlay, decal.vertexCount());
        // Classification is scope-local even when a following emitter has no overlay metadata.
        sink.quad(FACE, UV, COLOR, 0, 0, 1, 0.2f, 0.3f, 0.4f, 0.5f);
        assertEquals(ordinary + 4, base.vertexCount()); assertEquals(overlay, decal.vertexCount());
    }

    private static void add(BlockOverlays stage, float[] positions, float[] uv, int[] colors, int coverage, float nz) {
        stage.coverage(coverage); stage.quad(positions, uv, colors, 0, 0, nz, 0.2f, 0.3f, 0.4f, 0.5f);
    }
    private static float[] rotate(float[] input, int count) {
        float[] output = new float[12];
        for (int v = 0; v < 4; v++) System.arraycopy(input, ((v + count) & 3) * 3, output, v * 3, 3);
        return output;
    }
}
