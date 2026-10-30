/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemixCapturingConsumerTest {

    @AfterEach
    void clearRenderContext() {
        EntityRenderContext.get().reset();
    }

    @Test
    void matrixVertexRetainsLocalPositionAndCapturesPoseDuringRemixPass() {
        EntityRenderContext context = EntityRenderContext.get();
        context.begin(true);

        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);
        Matrix4f pose = new Matrix4f()
                .translate(3.0f, 4.0f, 5.0f)
                .rotateY(0.25f);

        consumer.addVertex(pose, -64.0f, 64.0f, -0.01f);
        consumer.finishOpenVertex();

        assertEquals(1, consumer.getVertexCount());
        assertEquals(-64.0f, consumer.getPx()[0]);
        assertEquals(64.0f, consumer.getPy()[0]);
        assertEquals(-0.01f, consumer.getPz()[0]);
        assertEquals(0, consumer.getBoneIndices()[0]);
        assertEquals(1, context.getBoneCount());
        assertEquals(pose, context.getBoneTransforms().getFirst());
    }

    private static int[] quadVertexData(float[][] positions) {
        int[] vd = new int[32];
        for (int v = 0; v < 4; v++) {
            vd[v * 8] = Float.floatToRawIntBits(positions[v][0]);
            vd[v * 8 + 1] = Float.floatToRawIntBits(positions[v][1]);
            vd[v * 8 + 2] = Float.floatToRawIntBits(positions[v][2]);
        }
        return vd;
    }

    @Test
    void onlyAFullLayerFaceOfAnItemCardIsACardFace() {
        float front = 7.5f / 16, back = 8.5f / 16;
        int[] frontFace = quadVertexData(new float[][] {{0, 0, front}, {1, 0, front}, {1, 1, front}, {0, 1, front}});
        int[] backFace = quadVertexData(new float[][] {{0, 0, back}, {0, 1, back}, {1, 1, back}, {1, 0, back}});
        assertTrue(RemixCapturingConsumer.isFullCardFace(frontFace, Direction.NORTH));
        assertTrue(RemixCapturingConsumer.isFullCardFace(backFace, Direction.SOUTH));
        // A side sliver spans one texel run across the card's thickness and faces along the card.
        int[] sliver = quadVertexData(new float[][] {
                {0.4375f, 0.625f, front}, {0.5625f, 0.625f, front}, {0.5625f, 0.625f, back}, {0.4375f, 0.625f, back}});
        assertFalse(RemixCapturingConsumer.isFullCardFace(sliver, Direction.UP));
        assertFalse(RemixCapturingConsumer.isFullCardFace(sliver, Direction.NORTH));
        // A block model's full face lies on the block's own planes, not the card's.
        int[] blockFace = quadVertexData(new float[][] {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}});
        assertFalse(RemixCapturingConsumer.isFullCardFace(blockFace, Direction.SOUTH));
        // A generated face that does not span the whole card is not a layer face either.
        int[] partial = quadVertexData(new float[][] {{0, 0, front}, {0.5f, 0, front}, {0.5f, 1, front}, {0, 1, front}});
        assertFalse(RemixCapturingConsumer.isFullCardFace(partial, Direction.NORTH));
    }

    private static boolean[] mask(String[] rows) {
        int w = rows[0].length();
        boolean[] opaque = new boolean[w * rows.length];
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < w; x++) opaque[y * w + x] = rows[y].charAt(x) == '#';
        }
        return opaque;
    }

    @Test
    void opaqueRectsMergeRowsWithTheSameRunsAndStopAtTheCap() {
        // A torch: a four-wide head over two rows, a two-wide stick below, nothing above.
        int[] torch = RemixCapturingConsumer.opaqueRects(
                mask(new String[] {"......", ".####.", ".####.", "..##..", "..##..", "..##.."}), 6, 6, 16);
        assertArrayEquals(new int[] {1, 1, 5, 3, 2, 3, 4, 6}, torch);
        // Two runs in a row merge with a row of the same runs and not with one that differs.
        int[] split = RemixCapturingConsumer.opaqueRects(
                mask(new String[] {"##.##", "##.##", "##..#"}), 5, 3, 16);
        assertArrayEquals(new int[] {0, 0, 2, 2, 3, 0, 5, 2, 0, 2, 2, 3, 4, 2, 5, 3}, split);
        // A checkerboard wants a rectangle per texel; past the cap the face keeps vanilla's quad.
        assertNull(RemixCapturingConsumer.opaqueRects(
                mask(new String[] {"#.#.", ".#.#", "#.#.", ".#.#"}), 4, 4, 7));
    }

    @Test
    void cardTexelEdgesUseTheItemModelGeneratorsArithmetic() {
        // Slivers are baked from texel * (16 / size), then / 16; the rebuilt faces must land on
        // the same floats or the two no longer share vertices.
        for (int size : new int[] {16, 64, 48}) {
            float scale = 16.0f / size;
            for (int edge = 0; edge <= size; edge++) {
                assertEquals((edge * scale) / 16.0f, RemixCapturingConsumer.cardX(edge, size), "x edge " + edge + " of " + size);
                assertEquals((16.0f - edge * scale) / 16.0f, RemixCapturingConsumer.cardY(edge, size), "y edge " + edge + " of " + size);
            }
        }
        assertEquals(1f, RemixCapturingConsumer.cardY(0, 64), "row 0 is the top of the card");
        assertEquals(0f, RemixCapturingConsumer.cardY(64, 64));
    }

    /** A full item-card face on the plane {@code z}; u runs with x, or against it when mirrored. */
    private static BakedQuad cardFace(TextureAtlasSprite sprite, float z, Direction face, boolean mirroredU) {
        int[] vd = new int[32];
        float[][] corners = {{0, 1}, {0, 0}, {1, 0}, {1, 1}};
        for (int v = 0; v < 4; v++) {
            float x = corners[v][0], y = corners[v][1];
            float span = sprite.getU1() - sprite.getU0();
            vd[v * 8] = Float.floatToRawIntBits(x);
            vd[v * 8 + 1] = Float.floatToRawIntBits(y);
            vd[v * 8 + 2] = Float.floatToRawIntBits(z);
            vd[v * 8 + 3] = 0xFFFFFFFF;
            vd[v * 8 + 4] = Float.floatToRawIntBits(mirroredU ? sprite.getU1() - span * x : sprite.getU0() + span * x);
            vd[v * 8 + 5] = Float.floatToRawIntBits(sprite.getV0() + (sprite.getV1() - sprite.getV0()) * (1 - y));
            vd[v * 8 + 7] = face == Direction.NORTH ? 0x00810000 : 0x007F0000;
        }
        return new BakedQuad(vd, -1, face, sprite, true);
    }

    private static void assertRect(float[] px, float[] py, int start, float x0, float x1, float y0, float y1) {
        boolean sawX0 = false, sawX1 = false, sawY0 = false, sawY1 = false;
        for (int i = start; i < start + 4; i++) {
            assertTrue(px[i] == x0 || px[i] == x1, "x of vertex " + i + " = " + px[i]);
            assertTrue(py[i] == y0 || py[i] == y1, "y of vertex " + i + " = " + py[i]);
            sawX0 |= px[i] == x0; sawX1 |= px[i] == x1; sawY0 |= py[i] == y0; sawY1 |= py[i] == y1;
        }
        assertTrue(sawX0 && sawX1 && sawY0 && sawY1, "rectangle at " + start + " spans both edges");
    }

    /** An 8x8 block-atlas sprite whose {@code '#'} texels are opaque and the rest alpha 0. */
    private static SpriteContents spriteContents(String id, String[] rows) {
        NativeImage image = new NativeImage(8, 8, false);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) image.setPixelRGBA(x, y, rows[y].charAt(x) == '#' ? 0xFF808080 : 0);
        }
        return new SpriteContents(ResourceLocation.fromNamespaceAndPath("minecraft", id), new FrameSize(8, 8), image,
                ResourceMetadata.EMPTY);
    }

    private static TextureAtlasSprite atlasSprite(SpriteContents contents) {
        return new TextureAtlasSprite(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"), contents, 64, 64, 16, 32) {};
    }

    /** Renders both full faces of an item card, as ItemRenderer does for a flat item. */
    private static void cardFaces(RemixCapturingConsumer consumer, TextureAtlasSprite sprite) {
        PoseStack.Pose pose = new PoseStack().last();
        float[] ones = {1, 1, 1, 1};
        consumer.putBulkData(pose, cardFace(sprite, 7.5f / 16, Direction.NORTH, false), ones, 1, 1, 1, 1, new int[4], OverlayTexture.NO_OVERLAY, false);
        consumer.putBulkData(pose, cardFace(sprite, 8.5f / 16, Direction.SOUTH, true), ones, 1, 1, 1, 1, new int[4], OverlayTexture.NO_OVERLAY, false);
        consumer.finishOpenVertex();
    }

    @Test
    void aFullCardFaceIsRebuiltAsItsOpaqueTexelRectangles() {
        EntityRenderContext.get().begin(true);
        SpriteContents contents = spriteContents("item/torch",
                new String[] {"........", "..####..", "..####..", "...##...", "...##...", "...##...", "...##...", "...##..."});
        TextureAtlasSprite sprite = atlasSprite(contents);
        try {
            RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);
            cardFaces(consumer, sprite);

            // Head rows 1-2 and stick rows 3-7 are two rectangles per face; the empty row and
            // the margins get no geometry, so nothing is left for a ray to pass through.
            assertEquals(16, consumer.getVertexCount());
            assertEquals(1, consumer.getPartCount());
            assertEquals(RemixCapturingConsumer.NO_VERTICES, consumer.cardFaceOfPartStartingAt(0),
                    "a rebuilt card has no full face left to flag");
            float[] px = consumer.getPx(), py = consumer.getPy(), pz = consumer.getPz();
            float[] tu = consumer.getTu(), tv = consumer.getTv();
            for (int i = 0; i < 8; i++) assertEquals(7.5f / 16, pz[i], "front vertex " + i);
            for (int i = 8; i < 16; i++) assertEquals(8.5f / 16, pz[i], "back vertex " + i);
            for (int face = 0; face < 16; face += 8) {
                assertRect(px, py, face, 0.25f, 0.75f, 0.625f, 0.875f);
                assertRect(px, py, face + 4, 0.375f, 0.625f, 0f, 0.625f);
            }
            float span = sprite.getU1() - sprite.getU0();
            for (int i = 0; i < 16; i++) {
                float u = i < 8 ? sprite.getU0() + span * px[i] : sprite.getU1() - span * px[i];
                assertEquals(u, tu[i], 1e-6, "u of vertex " + i);
                assertEquals(sprite.getV0() + (sprite.getV1() - sprite.getV0()) * (1 - py[i]), tv[i], 1e-6, "v of vertex " + i);
            }
        } finally {
            contents.close();
        }
    }

    @Test
    void aClearGlassCardKeepsItsWholeFaces() {
        // A glass pane item is a card painted with the glass texture, alpha 0 between
        // the frame texels. Those texels are the glass its material refracts through,
        // as a placed pane's are, so rebuilding the card from its opaque texels would
        // leave a bare frame.
        EntityRenderContext context = EntityRenderContext.get();
        context.begin(true);
        context.setBlockItemLayer(BlockTextureManager.L_GLASS);
        SpriteContents contents = spriteContents("block/glass",
                new String[] {"########", "#......#", "#.#....#", "#..#...#", "#...#..#", "#......#", "#......#", "########"});
        try {
            RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);
            cardFaces(consumer, atlasSprite(contents));

            assertEquals(8, consumer.getVertexCount(), "one whole quad per face");
            assertEquals(1, consumer.getPartCount());
            assertEquals(0, consumer.cardFaceOfPartStartingAt(0),
                    "a whole card face is flagged so the shell offset keeps it in its plane");
            assertEquals(BlockTextureManager.L_GLASS, consumer.getPartBlockItemLayer(0));
            float[] px = consumer.getPx(), py = consumer.getPy();
            assertRect(px, py, 0, 0f, 1f, 0f, 1f);
            assertRect(px, py, 4, 0f, 1f, 0f, 1f);
        } finally {
            contents.close();
        }
    }

    /** Emits one quad of four vertices sharing {@code bone}, at distinct positions. */
    private static void emitQuad(RemixCapturingConsumer consumer, int bone, float offset) {
        for (int v = 0; v < 4; v++) {
            consumer.acceptModelSpaceVertex(
                    offset + v, offset, offset, 0xFFFFFFFF, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, bone);
        }
    }

    @Test
    void consecutiveQuadsSharingAPivotBecomeOneRigidPart() {
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);

        emitQuad(consumer, 0, 1.0f);
        emitQuad(consumer, 0, 2.0f);
        consumer.finishOpenVertex();

        assertEquals(1, consumer.getPartCount());
        assertEquals(0, consumer.getPartStart(0));
        assertEquals(8, consumer.getPartVertexCount(0));
        assertEquals(0, consumer.getPartBone(0));
    }

    @Test
    void aPivotChangeStartsANewRigidPart() {
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);

        emitQuad(consumer, 0, 1.0f);
        emitQuad(consumer, 1, 2.0f);
        consumer.finishOpenVertex();

        assertEquals(2, consumer.getPartCount());
        assertEquals(0, consumer.getPartStart(0));
        assertEquals(4, consumer.getPartVertexCount(0));
        assertEquals(0, consumer.getPartBone(0));
        assertEquals(4, consumer.getPartStart(1));
        assertEquals(4, consumer.getPartVertexCount(1));
        assertEquals(1, consumer.getPartBone(1));
    }

    @Test
    void identicalGeometryUnderDifferentPivotsHashesTheSame() {
        // The pool is keyed by geometry, not by pivot: the same cuboid on two
        // limbs must share one mesh and differ only by instance transform.
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);

        emitQuad(consumer, 0, 1.0f);
        emitQuad(consumer, 1, 1.0f);
        consumer.finishOpenVertex();

        assertEquals(2, consumer.getPartCount());
        assertEquals(consumer.getPartHash(0), consumer.getPartHash(1));
    }

    @Test
    void differingGeometryHashesDifferently() {
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);

        emitQuad(consumer, 0, 1.0f);
        emitQuad(consumer, 1, 9.0f);
        consumer.finishOpenVertex();

        assertNotEquals(consumer.getPartHash(0), consumer.getPartHash(1));
    }

    @Test
    void aQuadWhoseVerticesDisagreeOnAPivotIsDroppedAndEndsTheRun() {
        // A quad whose vertices name different pivots belongs to no rigid part,
        // so no one transform can draw it.
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);

        emitQuad(consumer, 0, 1.0f);
        consumer.acceptModelSpaceVertex(0, 0, 0, 0xFFFFFFFF, 0, 0, 0, 1, 0, 0);
        consumer.acceptModelSpaceVertex(1, 0, 0, 0xFFFFFFFF, 0, 0, 0, 1, 0, 1);
        consumer.acceptModelSpaceVertex(2, 0, 0, 0xFFFFFFFF, 0, 0, 0, 1, 0, 0);
        consumer.acceptModelSpaceVertex(3, 0, 0, 0xFFFFFFFF, 0, 0, 0, 1, 0, 0);
        emitQuad(consumer, 0, 5.0f);
        consumer.finishOpenVertex();

        assertEquals(2, consumer.getPartCount());
        assertEquals(4, consumer.getPartVertexCount(0));
        assertEquals(8, consumer.getPartStart(1), "the mixed quad's vertices must not be in any part");
        assertEquals(4, consumer.getPartVertexCount(1));
    }

    @Test
    void aScratchConsumerRecordsNoParts() {
        // The submitter's rebuild scratch expands cuboids into vertices and reads
        // nothing else back, so it never pays for part records.
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, false);

        emitQuad(consumer, 0, 1.0f);
        consumer.finishOpenVertex();

        assertEquals(4, consumer.getVertexCount());
        assertEquals(0, consumer.getPartCount());
    }

    @Test
    void clearResetsPartRecords() {
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);

        emitQuad(consumer, 0, 1.0f);
        consumer.finishOpenVertex();
        assertEquals(1, consumer.getPartCount());

        consumer.clear();
        assertEquals(0, consumer.getPartCount());
    }

    /** Builds a cuboid-shaped quad array with {@code quads} faces of four vertices. */
    private static ModelPart.Polygon[] quads(int count) {
        ModelPart.Polygon[] sides = new ModelPart.Polygon[count];
        for (int q = 0; q < count; q++) {
            ModelPart.Vertex[] vs = new ModelPart.Vertex[4];
            for (int v = 0; v < 4; v++) {
                vs[v] = new ModelPart.Vertex(new Vector3f(q + v, q, 0.0f), v * 0.25f, q * 0.25f);
            }
            sides[q] = new ModelPart.Polygon(vs, 0, 0, 0, 0, 1, 1, false, Direction.NORTH);
        }
        return sides;
    }

    @Test
    void aCuboidRecordsAPartWithoutEmittingAnyVertices() {
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);

        consumer.acceptCuboid(quads(6), 0xFFFFFFFF, new Matrix4f(), null);
        consumer.finishOpenVertex();

        assertEquals(0, consumer.getVertexCount(), "cuboid geometry stays in the ModelPart");
        assertEquals(1, consumer.getPartCount());
        assertEquals(24, consumer.getPartVertexCount(0), "6 quads x 4 vertices");
        assertEquals(RemixCapturingConsumer.NO_VERTICES, consumer.getPartStart(0));
        assertEquals(0xFFFFFFFF, consumer.getPartTint(0));
        assertNotNull(consumer.getPartSides(0), "the part must carry its cuboid for the key");
    }

    @Test
    void rebuildingACuboidEmitsExactlyTheVerticesThePartPromised() {
        // The submitter's scratch consumer takes this path on a pool miss; the
        // count it produces must match what the part record claimed, or
        // buildSurface would read past the geometry.
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer recorder = new RemixCapturingConsumer(null, true);
        ModelPart.Polygon[] sides = quads(6);
        recorder.acceptCuboid(sides, 0xFFFFFFFF, new Matrix4f(), null);
        recorder.finishOpenVertex();

        RemixCapturingConsumer scratch = new RemixCapturingConsumer(null, false);
        scratch.emitCuboidGeometry(sides, 0xFFFFFFFF, 0, null);
        scratch.finishOpenVertex();

        assertEquals(recorder.getPartVertexCount(0), scratch.getVertexCount());
    }

    @Test
    void aCuboidAndAFollowingBakedQuadStayInSeparateParts() {
        // A cuboid part has no vertices, so it must never be extended by geometry
        // that does. Mixing them would make partStart meaningless.
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);

        consumer.acceptCuboid(quads(1), 0xFFFFFFFF, new Matrix4f(), null);
        emitQuad(consumer, 0, 3.0f);
        consumer.finishOpenVertex();

        assertEquals(2, consumer.getPartCount());
        assertEquals(RemixCapturingConsumer.NO_VERTICES, consumer.getPartStart(0));
        assertEquals(0, consumer.getPartStart(1));
        assertNull(consumer.getPartSides(1), "captured geometry must not claim a cuboid");
    }

    // ---- Sprite-wrapped cuboids ----

    /**
     * Cuboid-shaped quads with a real UV rectangle: pixels (4,4)-(12,12) of a 16x16
     * texture, which the Quad constructor turns into vertex UVs of 0.25 and 0.75.
     */
    private static ModelPart.Polygon[] texturedQuads(int count) {
        ModelPart.Polygon[] sides = new ModelPart.Polygon[count];
        for (int q = 0; q < count; q++) {
            ModelPart.Vertex[] vs = new ModelPart.Vertex[4];
            for (int v = 0; v < 4; v++) {
                vs[v] = new ModelPart.Vertex(new Vector3f(q + v, q, v), 0.0f, 0.0f);
            }
            sides[q] = new ModelPart.Polygon(vs, 4, 4, 12, 12, 16, 16, false, Direction.NORTH);
        }
        return sides;
    }

    @Test
    void aCuboidRecordsItsSpriteAndClearDropsIt() {
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);
        TextureAtlasSprite sprite = SpriteFixtures.sprite(4, 8, 4, 16);

        consumer.acceptCuboid(quads(6), 0xFFFFFFFF, new Matrix4f(), sprite);
        consumer.replayCuboidPart(quads(6), 0xFFFFFFFF, 0, null);
        consumer.finishOpenVertex();

        assertSame(sprite, consumer.getPartSprite(0));
        assertNull(consumer.getPartSprite(1), "a cuboid drawn without the wrapper has no sprite");

        consumer.clear();
        assertNull(consumer.getPartSprite(0), "a pooled consumer must not keep the last entity's sprite alive");
    }

    @Test
    void rebuildingASpritedCuboidRemapsUvsThroughTheSpriteRectangle() {
        // SpriteTexturedVertexConsumer applies exactly this remap to every vertex it
        // forwards. The mixin cancels it for an intercepted cuboid, so the rebuild
        // must reproduce it or the pooled mesh samples the wrong place in the atlas.
        TextureAtlasSprite sprite = SpriteFixtures.sprite(4, 8, 4, 16);
        ModelPart.Polygon[] sides = texturedQuads(6);

        RemixCapturingConsumer scratch = new RemixCapturingConsumer(null, false);
        scratch.emitCuboidGeometry(sides, 0xFFFFFFFF, 0, sprite);
        scratch.finishOpenVertex();

        assertEquals(24, scratch.getVertexCount());
        int i = 0;
        for (ModelPart.Polygon quad : sides) {
            float minU = Float.POSITIVE_INFINITY, minV = Float.POSITIVE_INFINITY;
            float maxU = Float.NEGATIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
            for (ModelPart.Vertex vertex : quad.vertices) {
                float u = sprite.getU0() + (sprite.getU1() - sprite.getU0()) * vertex.u;
                float v = sprite.getV0() + (sprite.getV1() - sprite.getV0()) * vertex.v;
                assertEquals(u, scratch.getTu()[i], "u of vertex " + i);
                assertEquals(v, scratch.getTv()[i], "v of vertex " + i);
                minU = Math.min(minU, u); maxU = Math.max(maxU, u);
                minV = Math.min(minV, v); maxV = Math.max(maxV, v);
                i++;
            }
            // The POM wrapping rectangle derives from the remapped UVs, so it lies
            // inside the sprite instead of spanning the whole atlas.
            assertTrue(minU < maxU && minV < maxV, "the quad must have a UV rectangle");
            assertTrue(minU >= sprite.getU0() && maxU <= sprite.getU1());
            assertTrue(minV >= sprite.getV0() && maxV <= sprite.getV1());
            int expectedMin = HardcodedVertex.packHalf2x16(minU, minV);
            int expectedMax = HardcodedVertex.packHalf2x16(maxU, maxV);
            for (int k = i - 4; k < i; k++) {
                assertEquals(expectedMin, scratch.getTexcoordBoundsMin()[k], "bounds min of vertex " + k);
                assertEquals(expectedMax, scratch.getTexcoordBoundsMax()[k], "bounds max of vertex " + k);
            }
        }
    }

    @Test
    void rebuildingASpritelessCuboidKeepsModelUvs() {
        // Skulls and mob models draw without the wrapper; their UVs already address
        // the whole texture and must pass through untouched.
        ModelPart.Polygon[] sides = texturedQuads(1);

        RemixCapturingConsumer scratch = new RemixCapturingConsumer(null, false);
        scratch.emitCuboidGeometry(sides, 0xFFFFFFFF, 0, null);
        scratch.finishOpenVertex();

        for (int v = 0; v < 4; v++) {
            assertEquals(sides[0].vertices[v].u, scratch.getTu()[v], "u of vertex " + v);
            assertEquals(sides[0].vertices[v].v, scratch.getTv()[v], "v of vertex " + v);
        }
    }

    // ---- Vertex colour channel order ----
    //
    // Remix reads HardcodedVertex.color as VK_FORMAT_B8G8R8A8_UNORM out of a
    // little-endian buffer, so the captured int must be ARGB (0xAARRGGBB).

    @Test
    void channelColorPacksArgb() {
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);

        consumer.addVertex(0.0f, 0.0f, 0.0f);
        consumer.setColor(0x11, 0x22, 0x33, 0xFF);
        consumer.setNormal(0.0f, 0.0f, 1.0f);

        assertEquals(0xFF112233, consumer.getColors()[0]);
    }

    @Test
    void channelColorAgreesWithPackedArgbOverload() {
        RemixCapturingConsumer channels = new RemixCapturingConsumer(null, true);
        channels.addVertex(0.0f, 0.0f, 0.0f);
        channels.setColor(0x11, 0x22, 0x33, 0xFF);
        channels.setNormal(0.0f, 0.0f, 1.0f);

        RemixCapturingConsumer packed = new RemixCapturingConsumer(null, true);
        packed.addVertex(0.0f, 0.0f, 0.0f);
        packed.setColor(0xFF112233);
        packed.setNormal(0.0f, 0.0f, 1.0f);

        assertEquals(packed.getColors()[0], channels.getColors()[0]);
    }

    /**
     * Builds one quad's worth of BakedQuad vertex data. Minecraft packs the
     * colour element as 0xAABBGGRR, with red in the low byte.
     */
    private static BakedQuad bakedQuad(int packedAbgr) {
        int[] vertexData = new int[4 * 8];
        for (int v = 0; v < 4; v++) {
            vertexData[v * 8 + 3] = packedAbgr;
            vertexData[v * 8 + 7] = 0x007F0000; // +Z face normal
        }
        return new BakedQuad(vertexData, -1, Direction.UP, null, true);
    }

    @Test
    void bakedQuadTintWithoutColorizePacksArgb() {
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);

        consumer.putBulkData(new PoseStack().last(), bakedQuad(0xFFFFFFFF),
                new float[]{1.0f, 1.0f, 1.0f, 1.0f},
                1.0f, 0.0f, 0.0f, 1.0f,
                new int[]{0, 0, 0, 0}, OverlayTexture.NO_OVERLAY, false);

        // A pure-red tint must arrive as pure red, not pure blue.
        assertEquals(0xFFFF0000, consumer.getColors()[0]);
    }

    @Test
    void bakedQuadColorizeMultipliesMatchingChannels() {
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);

        // Base vertex colour r=0x80 g=0x40 b=0x20, tinted with blue at half strength.
        consumer.putBulkData(new PoseStack().last(), bakedQuad(0xFF204080),
                new float[]{1.0f, 1.0f, 1.0f, 1.0f},
                1.0f, 1.0f, 0.5f, 1.0f,
                new int[]{0, 0, 0, 0}, OverlayTexture.NO_OVERLAY, true);

        assertEquals(0xFF804010, consumer.getColors()[0]);
    }

    // ---- Block item layer ----

    /** Renders one baked quad under {@code pose}, as ItemRenderer does for an item model. */
    private static void itemQuad(RemixCapturingConsumer consumer, PoseStack.Pose pose) {
        consumer.putBulkData(pose, bakedQuad(0xFFFFFFFF),
                new float[]{1.0f, 1.0f, 1.0f, 1.0f},
                1.0f, 1.0f, 1.0f, 1.0f,
                new int[]{0, 0, 0, 0}, OverlayTexture.NO_OVERLAY, false);
    }

    /** A distinct pose per item, as each item render pushes its own transform. */
    private static PoseStack.Pose poseAt(float x) {
        PoseStack stack = new PoseStack();
        stack.translate(x, 0.0f, 0.0f);
        return stack.last();
    }

    @Test
    void theBlockItemLayerIsSampledAsEachPartOpens() {
        // Glass in one hand, ice in the other and a sword between them can arrive
        // through one layer, so the answer is per part: whatever the context said
        // when the part opened.
        EntityRenderContext context = EntityRenderContext.get();
        context.begin(true);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);

        context.setBlockItemLayer(BlockTextureManager.L_GLASS);
        itemQuad(consumer, poseAt(0.0f));
        context.setBlockItemLayer(BlockTextureManager.L_TRANS);
        itemQuad(consumer, poseAt(1.0f));
        context.setBlockItemLayer(EntityRenderContext.NO_BLOCK_ITEM);
        itemQuad(consumer, poseAt(2.0f));
        consumer.finishOpenVertex();

        assertEquals(3, consumer.getPartCount());
        assertEquals(BlockTextureManager.L_GLASS, consumer.getPartBlockItemLayer(0));
        assertEquals(BlockTextureManager.L_TRANS, consumer.getPartBlockItemLayer(1));
        assertEquals(EntityRenderContext.NO_BLOCK_ITEM, consumer.getPartBlockItemLayer(2));
    }

    /**
     * A pooled consumer as the next entity receives it: cleared, with the answers
     * an earlier item entity wrote into its first two slots still there, and the
     * context still naming a translucent block.
     */
    private static RemixCapturingConsumer clearedWithStaleBlockItemAnswers() {
        EntityRenderContext context = EntityRenderContext.get();
        context.begin(true);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);
        context.setBlockItemLayer(BlockTextureManager.L_TRANS);
        itemQuad(consumer, poseAt(0.0f));
        itemQuad(consumer, poseAt(1.0f));
        consumer.finishOpenVertex();
        assertEquals(BlockTextureManager.L_TRANS, consumer.getPartBlockItemLayer(0));
        assertEquals(BlockTextureManager.L_TRANS, consumer.getPartBlockItemLayer(1));
        consumer.clear();
        return consumer;
    }

    @Test
    void cuboidPartsOverwriteAStaleBlockItemAnswer() {
        // Every part-open site must write its own answer, and cuboids write
        // NO_BLOCK_ITEM even while the context still says otherwise.
        RemixCapturingConsumer consumer = clearedWithStaleBlockItemAnswers();

        consumer.acceptCuboid(quads(1), 0xFFFFFFFF, new Matrix4f(), null);
        consumer.replayCuboidPart(quads(1), 0xFFFFFFFF, 0, null);

        assertEquals(2, consumer.getPartCount());
        assertEquals(EntityRenderContext.NO_BLOCK_ITEM, consumer.getPartBlockItemLayer(0));
        assertEquals(EntityRenderContext.NO_BLOCK_ITEM, consumer.getPartBlockItemLayer(1));
    }

    @Test
    void stripPartsOverwriteAStaleBlockItemAnswer() {
        // The pool rebinds a cleared consumer to any layer, strips included, and a
        // streamed strip vertex never comes from a baked item quad.
        RemixCapturingConsumer consumer = clearedWithStaleBlockItemAnswers();
        consumer.reinit(null, true, RenderLayerUtils.Topology.TRIANGLE_STRIP, true);

        Matrix4f first = new Matrix4f();
        Matrix4f second = new Matrix4f().translate(1.0f, 0.0f, 0.0f);
        for (int i = 0; i < 3; i++) {
            consumer.addVertex(first, i, 0.0f, 0.0f).setColor(255, 255, 255, 255);
        }
        for (int i = 0; i < 3; i++) {
            consumer.addVertex(second, i, 0.0f, 0.0f).setColor(255, 255, 255, 255);
        }
        consumer.finishOpenVertex();

        assertEquals(2, consumer.getPartCount());
        assertEquals(EntityRenderContext.NO_BLOCK_ITEM, consumer.getPartBlockItemLayer(0));
        assertEquals(EntityRenderContext.NO_BLOCK_ITEM, consumer.getPartBlockItemLayer(1));
    }

    // ---- Damage / priming overlay ----

    @Test
    void defaultOverlayLeavesTheVertexColourAlone() {
        assertEquals(0xFF3366CC,
                RemixCapturingConsumer.applyEntityOverlay(0xFF3366CC, OverlayTexture.NO_OVERLAY));
    }

    @Test
    void damageOverlayTintsTowardsRedAndKeepsAlpha() {
        int hurt = OverlayTexture.pack(0.0f, true);

        // mix(red, white, 178/255) leaves red at full and pulls the other two down.
        assertEquals(0x80FFB2B2, RemixCapturingConsumer.applyEntityOverlay(0x80FFFFFF, hurt));
    }

    @Test
    void whiteFlashBrightensByItsRampAndIsIdentityAtTheStartOfIt() {
        int fullFlash = OverlayTexture.pack(1.0f, false);
        int noFlash = OverlayTexture.pack(0.0f, false);

        assertEquals(0xFFC0C0C0, RemixCapturingConsumer.applyEntityOverlay(0xFF000000, fullFlash));
        assertEquals(0xFF000000, RemixCapturingConsumer.applyEntityOverlay(0xFF000000, noFlash));
    }

    @Test
    void streamedVertexCarriesTheDamageTintIntoItsColour() {
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);

        consumer.addVertex(0.0f, 0.0f, 0.0f);
        consumer.setColor(0xFFFFFFFF);
        consumer.setOverlay(OverlayTexture.pack(0.0f, true));
        consumer.setNormal(0.0f, 1.0f, 0.0f);

        assertEquals(0xFFFFB2B2, consumer.getColors()[0]);
    }

    /**
     * Emits a flat zig-zag strip in the XY plane under {@code pose}: {@code count}
     * vertices, all coloured {@code argb}, none with a normal, which is how the
     * lead arrives.
     */
    private static void emitFlatStrip(RemixCapturingConsumer consumer, Matrix4f pose,
                                      int count, int argb) {
        for (int v = 0; v < count; v++) {
            consumer.addVertex(pose, v / 2, v % 2, 0.0f).setColor(argb);
        }
    }

    private static void assertFacesNegativeZ(RemixCapturingConsumer consumer, int from, int to) {
        for (int i = from; i < to; i++) {
            assertEquals(0.0f, consumer.getNx()[i], 1.0e-5f, "nx of vertex " + i);
            assertEquals(0.0f, consumer.getNy()[i], 1.0e-5f, "ny of vertex " + i);
            assertEquals(-1.0f, consumer.getNz()[i], 1.0e-5f, "nz of vertex " + i);
        }
    }

    @Test
    void stripNormalsFollowTheWindingOfTheirOwnPart() {
        // buildIndices restarts the alternating winding at each part's first vertex,
        // so a part that begins at an odd absolute index must generate its normals
        // from that origin too, or every face of the second part flips.
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(
                null, true, RenderLayerUtils.Topology.TRIANGLE_STRIP, true);

        emitFlatStrip(consumer, new Matrix4f(), 3, 0xFFFFFFFF);
        emitFlatStrip(consumer, new Matrix4f().translate(0.0f, 2.0f, 0.0f), 4, 0xFFFFFFFF);
        consumer.finishOpenVertex();

        assertEquals(2, consumer.getPartCount());
        assertEquals(3, consumer.getPartStart(1));
        assertFacesNegativeZ(consumer, 0, 7);
    }

    @Test
    void aStripUnderOnePivotIsOnePart() {
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(
                null, true, RenderLayerUtils.Topology.TRIANGLE_STRIP, true);

        emitFlatStrip(consumer, new Matrix4f(), 5, 0xFFFFFFFF);
        consumer.finishOpenVertex();

        assertEquals(1, consumer.getPartCount());
        assertEquals(0, consumer.getPartStart(0));
        assertEquals(5, consumer.getPartVertexCount(0));
        assertEquals(0, consumer.getPartBone(0));
        assertNull(consumer.getPartSides(0), "captured strip geometry must not claim a cuboid");
    }

    @Test
    void aStripPivotChangeStartsANewPart() {
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(
                null, true, RenderLayerUtils.Topology.TRIANGLE_STRIP, true);

        emitFlatStrip(consumer, new Matrix4f(), 3, 0xFFFFFFFF);
        emitFlatStrip(consumer, new Matrix4f().translate(0.0f, 2.0f, 0.0f), 4, 0xFFFFFFFF);
        consumer.finishOpenVertex();

        assertEquals(2, consumer.getPartCount());
        assertEquals(0, consumer.getPartStart(0));
        assertEquals(3, consumer.getPartVertexCount(0));
        assertEquals(0, consumer.getPartBone(0));
        assertEquals(3, consumer.getPartStart(1));
        assertEquals(4, consumer.getPartVertexCount(1));
        assertEquals(1, consumer.getPartBone(1));
    }

    @Test
    void identicalStripsHashTheSame() {
        // The hash is folded when the run closes, after the seeded normals have
        // landed, so two captures of one strip must agree exactly.
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer first = new RemixCapturingConsumer(
                null, true, RenderLayerUtils.Topology.TRIANGLE_STRIP, true);
        RemixCapturingConsumer second = new RemixCapturingConsumer(
                null, true, RenderLayerUtils.Topology.TRIANGLE_STRIP, true);

        emitFlatStrip(first, new Matrix4f(), 4, 0xFF3366CC);
        first.finishOpenVertex();
        emitFlatStrip(second, new Matrix4f(), 4, 0xFF3366CC);
        second.finishOpenVertex();

        assertEquals(first.getPartHash(0), second.getPartHash(0));
    }

    @Test
    void aStripColourIsFoldedIntoItsHash() {
        // For an untextured strip the colour is the whole surface, so it is part
        // of the geometry's identity.
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer white = new RemixCapturingConsumer(
                null, true, RenderLayerUtils.Topology.TRIANGLE_STRIP, true);
        RemixCapturingConsumer oneRedVertex = new RemixCapturingConsumer(
                null, true, RenderLayerUtils.Topology.TRIANGLE_STRIP, true);

        emitFlatStrip(white, new Matrix4f(), 4, 0xFFFFFFFF);
        white.finishOpenVertex();
        Matrix4f pose = new Matrix4f();
        for (int v = 0; v < 4; v++) {
            oneRedVertex.addVertex(pose, v / 2, v % 2, 0.0f).setColor(v == 2 ? 0xFFFF0000 : 0xFFFFFFFF);
        }
        oneRedVertex.finishOpenVertex();

        assertNotEquals(white.getPartHash(0), oneRedVertex.getPartHash(0));
    }

    // ---- Singleton cuboid iterator ----
    //
    // ModelPartMixin borrows this iterator for a single-cuboid part and falls back
    // to vanilla's on null, so these hand-out rules are what keep a reentrant
    // renderCuboids from ever traversing another part's cuboid.

    private static ModelPart.Cube cuboid() {
        return new ModelPart.Cube(0, 0, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f,
                0.0f, 0.0f, 0.0f, false, 16.0f, 16.0f, EnumSet.allOf(Direction.class));
    }

    @Test
    void theSingletonIteratorYieldsItsCuboidOnceAndIsFreeAgainAfterwards() {
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);
        ModelPart.Cube cuboid = cuboid();

        Iterator<ModelPart.Cube> iterator = consumer.acquireSingletonCuboidIterator(cuboid);

        assertNotNull(iterator);
        assertTrue(iterator.hasNext());
        assertSame(cuboid, iterator.next());
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
        assertSame(iterator, consumer.acquireSingletonCuboidIterator(cuboid),
                "next() releases the slot and the consumer hands out the same instance");
    }

    @Test
    void aPendingSingletonIteratorIsNotHandedOutAgain() {
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);
        ModelPart.Cube first = cuboid();
        Iterator<ModelPart.Cube> pending = consumer.acquireSingletonCuboidIterator(first);
        assertNotNull(pending);

        assertNull(consumer.acquireSingletonCuboidIterator(cuboid()));

        assertSame(first, pending.next(), "a refused acquire must not overwrite the pending cuboid");
    }

    @Test
    void clearFreesAPendingSingletonIterator() {
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);
        assertNotNull(consumer.acquireSingletonCuboidIterator(cuboid()));

        consumer.clear();

        assertNotNull(consumer.acquireSingletonCuboidIterator(cuboid()));
    }
}
