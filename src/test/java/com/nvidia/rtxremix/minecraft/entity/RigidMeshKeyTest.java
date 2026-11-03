/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the identity semantics the rigid mesh keys depend on. Every case here is
 * a way the pool could hand back another part's geometry, or a mesh baked with
 * another part's material, if the key were wrong.
 */
class RigidMeshKeyTest {

    private static final Identifier TEX_A = Identifier.of("minecraft", "a");
    private static final Identifier TEX_B = Identifier.of("minecraft", "b");

    private static RemixEntitySubmitter.CuboidMeshKey key(
            Identifier tex, int shellLayer, ModelPart.Quad[] sides) {
        return key(tex, shellLayer, sides, null);
    }

    private static RemixEntitySubmitter.CuboidMeshKey key(
            Identifier tex, int shellLayer, ModelPart.Quad[] sides, Sprite sprite) {
        return new RemixEntitySubmitter.CuboidMeshKey(
                tex, RenderLayerUtils.SurfaceMode.OPAQUE, shellLayer, sides, sprite);
    }

    @Test
    void twoDistinctCuboidsNeverShareAMeshEvenIfIdenticallyShaped() {
        // Identity, not contents. Two separately baked model trees can hold
        // geometrically identical cuboids; they must not alias.
        assertNotEquals(key(TEX_A, 0, new ModelPart.Quad[6]),
                        key(TEX_A, 0, new ModelPart.Quad[6]));
    }

    @Test
    void textureSeparatesEntitiesSharingOneModel() {
        // One ArmorFeatureRenderer model serves every player. Iron and diamond
        // armour reach the same cuboids and differ only by texture.
        ModelPart.Quad[] sides = new ModelPart.Quad[6];
        assertNotEquals(key(TEX_A, 0, sides), key(TEX_B, 0, sides));
    }

    @Test
    void shellLayerSeparatesTheShellOffsetBakedIntoTheVertices() {
        // createRigidMesh expands vertices by shellLayer * SHELL_OFFSET. Sharing a
        // mesh across shell layers would collapse a dyed-leather overlay onto the
        // body, or every banner pattern onto the dyed base.
        ModelPart.Quad[] sides = new ModelPart.Quad[6];
        assertNotEquals(key(TEX_A, 0, sides), key(TEX_A, 1, sides));
    }

    @Test
    void theSameCuboidUnderTwoSpritesKeysTwoMeshes() {
        // One bed cuboid is painted with sixteen colour sprites, and the mesh bakes
        // the sprite's UVs. Two sprites with the same atlas rectangle are still two
        // identities, as they would be after a re-stitch.
        ModelPart.Quad[] sides = new ModelPart.Quad[6];
        Sprite red = SpriteFixtures.sprite(0, 0, 4, 16);
        Sprite blue = SpriteFixtures.sprite(0, 0, 4, 16);

        assertEquals(key(TEX_A, 0, sides, red), key(TEX_A, 0, sides, red));
        assertNotEquals(key(TEX_A, 0, sides, red), key(TEX_A, 0, sides, blue));
    }

    @Test
    void aSpritelessCuboidNeverMatchesASpritedOne() {
        ModelPart.Quad[] sides = new ModelPart.Quad[6];
        Sprite sprite = SpriteFixtures.sprite(0, 0, 4, 16);

        assertNotEquals(key(TEX_A, 0, sides, null), key(TEX_A, 0, sides, sprite));
        assertNotEquals(key(TEX_A, 0, sides, sprite), key(TEX_A, 0, sides, null));
    }

    @Test
    void lookupProbeAgreesWithTheStoredRecordsOnHashAndEquality() {
        // The pool stores records; hits are probed with the mutable key. HashMap
        // calls probe.equals(stored) and requires equal hashCodes, so the two
        // must never drift. This is the invariant the explicit hash helpers exist for.
        ModelPart.Quad[] sides = new ModelPart.Quad[6];
        Sprite sprite = SpriteFixtures.sprite(0, 0, 4, 16);
        RemixEntitySubmitter.RigidMeshLookupKey probe =
                new RemixEntitySubmitter.RigidMeshLookupKey();

        probe.setLayer(TEX_A, RenderLayerUtils.SurfaceMode.OPAQUE, 2);
        probe.setCuboid(sides, sprite);
        RemixEntitySubmitter.RigidMeshKey cuboid = probe.immutableCopy();
        assertEquals(cuboid.hashCode(), probe.hashCode());
        assertTrue(probe.equals(cuboid));

        probe.setCuboid(sides, SpriteFixtures.sprite(0, 0, 4, 16));
        assertFalse(probe.equals(cuboid), "another sprite object must miss");
        probe.setCuboid(sides, null);
        assertFalse(probe.equals(cuboid), "a spriteless probe must miss a sprited record");

        probe.setHashed(RenderLayerUtils.Topology.QUADS, 24, 0x1234_5678_9abc_def0L);
        RemixEntitySubmitter.RigidMeshKey hashed = probe.immutableCopy();
        assertEquals(hashed.hashCode(), probe.hashCode());
        assertTrue(probe.equals(hashed));
        assertFalse(probe.equals(cuboid));
    }

    @Test
    void topologyForksTheHashedKey() {
        // A strip and a run of quads can agree on every other field and still be
        // different geometry: the same vertices index into different triangles.
        RemixEntitySubmitter.RigidMeshLookupKey probe =
                new RemixEntitySubmitter.RigidMeshLookupKey();
        probe.setLayer(TEX_A, RenderLayerUtils.SurfaceMode.OPAQUE, 0);

        probe.setHashed(RenderLayerUtils.Topology.QUADS, 8, 0x1234L);
        RemixEntitySubmitter.RigidMeshKey quads = probe.immutableCopy();
        probe.setHashed(RenderLayerUtils.Topology.TRIANGLE_STRIP, 8, 0x1234L);

        assertFalse(probe.equals(quads));
        assertNotEquals(quads, probe.immutableCopy());
    }

    @Test
    void surfaceModeForksBothKeyForms() {
        // The material is baked into the pooled mesh, and atlasSurfaceMode narrows
        // item parts one at a time, so two parts of one layer can agree on every
        // other field and still need different materials.
        ModelPart.Quad[] sides = new ModelPart.Quad[6];
        RemixEntitySubmitter.RigidMeshLookupKey probe =
                new RemixEntitySubmitter.RigidMeshLookupKey();

        probe.setLayer(TEX_A, RenderLayerUtils.SurfaceMode.CUTOUT, 0);
        probe.setCuboid(sides, null);
        RemixEntitySubmitter.RigidMeshKey cutoutCuboid = probe.immutableCopy();
        probe.setHashed(RenderLayerUtils.Topology.QUADS, 8, 0x1234L);
        RemixEntitySubmitter.RigidMeshKey cutoutHashed = probe.immutableCopy();

        probe.setLayer(TEX_A, RenderLayerUtils.SurfaceMode.TRANSLUCENT, 0);
        probe.setCuboid(sides, null);
        assertFalse(probe.equals(cutoutCuboid));
        assertNotEquals(cutoutCuboid, probe.immutableCopy());
        probe.setHashed(RenderLayerUtils.Topology.QUADS, 8, 0x1234L);
        assertFalse(probe.equals(cutoutHashed));
        assertNotEquals(cutoutHashed, probe.immutableCopy());
    }

    // ---- Canvas keys ----
    //
    // A canvas's pattern stack is one mesh: the flag cuboid painted once per pattern,
    // in paint order, each layer with its own sprite and tint baked in. The key must
    // therefore see every layer, in order, or two banners with the same cuboid would
    // share a design.

    private static final int RED = 0xFFB02E26;
    private static final int BLUE = 0xFF3C44AA;

    /** A captured canvas: the base part followed by one part per pattern, all one cuboid. */
    private static RemixCapturingConsumer canvas(ModelPart.Quad[] sides, Sprite[] sprites, int[] tints) {
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);
        consumer.replayCuboidPart(sides, 0xFFFFFFFF, 0, null);
        for (int i = 0; i < sprites.length; i++) {
            consumer.replayCuboidPart(sides, tints[i], 0, sprites[i]);
        }
        consumer.finishOpenVertex();
        return consumer;
    }

    @Test
    void canvasProbeAgreesWithTheStoredRecord() {
        ModelPart.Quad[] sides = new ModelPart.Quad[6];
        Sprite stripe = SpriteFixtures.sprite(0, 0, 4, 16);
        Sprite border = SpriteFixtures.sprite(4, 0, 4, 16);
        RemixCapturingConsumer canvas = canvas(sides, new Sprite[] {stripe, border}, new int[] {RED, BLUE});
        RemixEntitySubmitter.RigidMeshLookupKey probe =
                new RemixEntitySubmitter.RigidMeshLookupKey();

        probe.setLayer(TEX_A, RenderLayerUtils.SurfaceMode.TRANSLUCENT, 1);
        probe.setCanvas(sides, canvas, 1, 2);
        RemixEntitySubmitter.RigidMeshKey record = probe.immutableCopy();

        assertTrue(record instanceof RemixEntitySubmitter.CanvasMeshKey);
        assertEquals(record.hashCode(), probe.hashCode());
        assertTrue(probe.equals(record));
        assertEquals(record, probe.immutableCopy());
    }

    @Test
    void aCanvasIsKeyedByEveryLayersSpriteAndTintInOrder() {
        ModelPart.Quad[] sides = new ModelPart.Quad[6];
        Sprite stripe = SpriteFixtures.sprite(0, 0, 4, 16);
        Sprite border = SpriteFixtures.sprite(4, 0, 4, 16);
        RemixEntitySubmitter.RigidMeshLookupKey probe =
                new RemixEntitySubmitter.RigidMeshLookupKey();
        probe.setLayer(TEX_A, RenderLayerUtils.SurfaceMode.TRANSLUCENT, 1);
        probe.setCanvas(sides, canvas(sides, new Sprite[] {stripe, border}, new int[] {RED, BLUE}), 1, 2);
        RemixEntitySubmitter.RigidMeshKey redStripeBlueBorder = probe.immutableCopy();

        // Same patterns, other dyes.
        probe.setCanvas(sides, canvas(sides, new Sprite[] {stripe, border}, new int[] {BLUE, RED}), 1, 2);
        assertFalse(probe.equals(redStripeBlueBorder), "a recoloured layer must miss");
        // Same dyes, patterns swapped: the border now lies under the stripe.
        probe.setCanvas(sides, canvas(sides, new Sprite[] {border, stripe}, new int[] {RED, BLUE}), 1, 2);
        assertFalse(probe.equals(redStripeBlueBorder), "reordered layers must miss");
        // One layer fewer.
        probe.setCanvas(sides, canvas(sides, new Sprite[] {stripe}, new int[] {RED}), 1, 1);
        assertFalse(probe.equals(redStripeBlueBorder), "a shorter stack must miss");
        // A re-stitched atlas: the same rectangles as new Sprite objects.
        probe.setCanvas(sides, canvas(sides,
                new Sprite[] {SpriteFixtures.sprite(0, 0, 4, 16), border}, new int[] {RED, BLUE}), 1, 2);
        assertFalse(probe.equals(redStripeBlueBorder), "another sprite object must miss");
        // Another cuboid with the same design.
        probe.setCanvas(new ModelPart.Quad[6],
                canvas(sides, new Sprite[] {stripe, border}, new int[] {RED, BLUE}), 1, 2);
        assertFalse(probe.equals(redStripeBlueBorder), "another cuboid must miss");
    }

    @Test
    void aCanvasNeverMatchesACuboidOrHashedKeyOnTheSameLayer() {
        ModelPart.Quad[] sides = new ModelPart.Quad[6];
        Sprite stripe = SpriteFixtures.sprite(0, 0, 4, 16);
        RemixEntitySubmitter.RigidMeshLookupKey probe =
                new RemixEntitySubmitter.RigidMeshLookupKey();
        probe.setLayer(TEX_A, RenderLayerUtils.SurfaceMode.TRANSLUCENT, 1);

        probe.setCuboid(sides, stripe);
        RemixEntitySubmitter.RigidMeshKey cuboid = probe.immutableCopy();
        probe.setHashed(RenderLayerUtils.Topology.QUADS, 24, 0x1234L);
        RemixEntitySubmitter.RigidMeshKey hashed = probe.immutableCopy();

        probe.setCanvas(sides, canvas(sides, new Sprite[] {stripe}, new int[] {RED}), 1, 1);
        assertFalse(probe.equals(cuboid));
        assertFalse(probe.equals(hashed));
        RemixEntitySubmitter.RigidMeshKey canvas = probe.immutableCopy();
        assertNotEquals(cuboid, canvas);
        assertNotEquals(hashed, canvas);
    }
}
