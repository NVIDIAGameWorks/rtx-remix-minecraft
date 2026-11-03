/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter.CanvasReader;
import net.minecraft.client.model.ModelPart;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import static com.nvidia.rtxremix.minecraft.entity.RemixBlockEntityPass.STATIC_WORLD_CANVASES;
import static com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter.PatternCanvasRole.BASE;
import static com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter.PatternCanvasRole.FRAME;
import static com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter.PatternCanvasRole.NONE;
import static com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter.PatternCanvasRole.PATTERN;
import static com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter.shellLayerFor;
import static com.nvidia.rtxremix.minecraft.entity.RenderLayerUtils.SurfaceMode.CUTOUT;
import static com.nvidia.rtxremix.minecraft.entity.RenderLayerUtils.SurfaceMode.OPAQUE;
import static com.nvidia.rtxremix.minecraft.entity.RenderLayerUtils.SurfaceMode.TRANSLUCENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how banner and shield canvases are read off captured parts: which cuboid
 * is the dyed base, which are patterns, where one canvas ends and the next
 * begins, which solid-layer cuboid is vanilla's depth backing, how deep each is
 * baked, and what each owner's category policy adds to them.
 */
class RemixEntitySubmitterPatternCanvasTest {

    private static final Identifier BANNER_ATLAS =
            Identifier.of("minecraft", "textures/atlas/banner_patterns.png");
    private static final Identifier SHIELD_ATLAS =
            Identifier.of("minecraft", "textures/atlas/shield_patterns.png");
    private static final Identifier CREEPER =
            Identifier.of("minecraft", "textures/entity/creeper/creeper.png");
    private static final int WHITE = 0xFFFFFFFF;

    // Bones as a capture records them: every cuboid render pushes its own, so the
    // passes of one canvas are distinct bones at one pose. Bones 0 to 2 are one
    // canvas's backing, base and pattern pass; bone 3 is the other hand's pose.
    private static final int BACKING = 0, BASE_PASS = 1, PATTERN_PASS = 2, OTHER_HAND = 3;
    private static final float[] BONES = bones(pose(0.5f), pose(0.5f), pose(0.5f), pose(-0.5f));

    private final ModelPart.Quad[] flag = new ModelPart.Quad[6];
    private final ModelPart.Quad[] pole = new ModelPart.Quad[6];
    private final ModelPart.Quad[] plate = new ModelPart.Quad[6];

    @Test
    void aTextureOffThePatternAtlasesHasNoRole() {
        CanvasReader reader = reader();
        for (RenderLayerUtils.SurfaceMode mode : RenderLayerUtils.SurfaceMode.values()) {
            reader.beginLayer(CREEPER, mode);
            assertEquals(NONE, reader.next(flag, BASE_PASS), mode.name());
            assertEquals(NONE, reader.next(flag, PATTERN_PASS), mode.name());
        }
    }

    @Test
    void solidCanvasCuboidsAreTheFrameWhateverTheirPositionOrPose() {
        // Pole, crossbar, handle and the untinted backing pass all draw on the solid
        // layer; none of them is a canvas depth.
        CanvasReader reader = reader();
        reader.beginLayer(BANNER_ATLAS, OPAQUE);
        assertEquals(FRAME, reader.next(pole, BACKING));
        assertEquals(FRAME, reader.next(flag, BACKING));
        assertEquals(FRAME, reader.next(flag, OTHER_HAND));

        reader.beginLayer(BANNER_ATLAS, CUTOUT);
        assertEquals(FRAME, reader.next(flag, BASE_PASS));
    }

    @Test
    void oneCanvasIsARunOfOneCuboidAtOnePoseWithTheBaseFirst() {
        CanvasReader reader = reader();
        reader.beginLayer(BANNER_ATLAS, TRANSLUCENT);

        assertEquals(BASE, reader.next(flag, BASE_PASS));
        assertEquals(0, reader.depth());
        assertEquals(PATTERN, reader.next(flag, PATTERN_PASS));
        assertEquals(1, reader.depth());
        // The same pattern twice is two decals at two depths.
        assertEquals(PATTERN, reader.next(flag, PATTERN_PASS));
        assertEquals(2, reader.depth());
    }

    @Test
    void theSameCuboidAtAnotherPoseStartsANewCanvas() {
        // A shield in each hand, or the copies of a dropped banner stack, paint one
        // cuboid at several poses through one translucent layer. Each is its own
        // canvas: its base opaque, its depths counted from zero.
        CanvasReader reader = reader();
        reader.beginLayer(SHIELD_ATLAS, TRANSLUCENT);

        assertEquals(BASE, reader.next(plate, BASE_PASS));
        assertEquals(PATTERN, reader.next(plate, PATTERN_PASS));
        assertEquals(BASE, reader.next(plate, OTHER_HAND));
        assertEquals(0, reader.depth());
        assertEquals(PATTERN, reader.next(plate, OTHER_HAND));
        assertEquals(1, reader.depth());
    }

    @Test
    void anotherCuboidAtTheSamePoseStartsANewCanvas() {
        CanvasReader reader = reader();
        reader.beginLayer(BANNER_ATLAS, TRANSLUCENT);

        assertEquals(BASE, reader.next(flag, BASE_PASS));
        assertEquals(BASE, reader.next(plate, PATTERN_PASS));
        assertEquals(0, reader.depth());
    }

    @Test
    void beginningALayerClosesTheRun() {
        CanvasReader reader = reader();
        reader.beginLayer(BANNER_ATLAS, TRANSLUCENT);
        reader.next(flag, BASE_PASS);
        reader.next(flag, PATTERN_PASS);

        reader.beginLayer(BANNER_ATLAS, TRANSLUCENT);
        assertEquals(BASE, reader.next(flag, PATTERN_PASS));
        assertEquals(0, reader.depth());
    }

    @Test
    void canvasCuboidsAddTheirDepthToTheShellLayer() {
        // Base and patterns share one render layer, so the layer ordinal alone would
        // bake them all at the same depth.
        assertEquals(1, shellLayerFor(1, 0, BASE));
        assertEquals(3, shellLayerFor(1, 2, PATTERN));
    }

    @Test
    void everyOtherPartKeepsItsLayerOrdinal() {
        assertEquals(1, shellLayerFor(1, 2, FRAME));
        assertEquals(1, shellLayerFor(1, 2, NONE));
    }

    @Test
    void blockEntityCanvasPolicyMarksOnlyPatternsAsStaticDecals() {
        // A placed banner's patterns are decals over its base; the base, the pole
        // and anything off the pattern atlases stay plain geometry.
        assertEquals(InstanceCategoryBit.DECAL_STATIC, STATIC_WORLD_CANVASES.categoryFlags(PATTERN));
        assertEquals(0, STATIC_WORLD_CANVASES.categoryFlags(NONE));
        assertEquals(0, STATIC_WORLD_CANVASES.categoryFlags(FRAME));
        assertEquals(0, STATIC_WORLD_CANVASES.categoryFlags(BASE));
    }

    @Test
    void theBackingClothIsTheBaseCuboidDrawnAgainAtTheBasePoseOnTheSolidLayer() {
        CanvasReader reader = reader();
        reader.collectBases(BANNER_ATLAS, TRANSLUCENT,
                layer(new ModelPart.Quad[][] {flag, flag}, BASE_PASS, PATTERN_PASS));
        reader.beginLayer(BANNER_ATLAS, OPAQUE);

        assertTrue(reader.isBacking(reader.next(flag, BACKING), flag, BACKING));
        assertFalse(reader.isBacking(reader.next(pole, BACKING), pole, BACKING));
        assertFalse(reader.isBacking(reader.next(flag, OTHER_HAND), flag, OTHER_HAND));
    }

    @Test
    void aPatternlessShieldBesideAPatternedOneKeepsItsPlate() {
        // Both shields render through one ShieldEntityModel, so their plates are one
        // cuboid; only the pose tells the plain shield's plate from the backing.
        CanvasReader reader = reader();
        reader.collectBases(SHIELD_ATLAS, TRANSLUCENT,
                layer(new ModelPart.Quad[][] {plate, plate}, BASE_PASS, PATTERN_PASS));
        reader.beginLayer(SHIELD_ATLAS, OPAQUE);

        assertFalse(reader.isBacking(reader.next(plate, OTHER_HAND), plate, OTHER_HAND));
        assertTrue(reader.isBacking(reader.next(plate, BACKING), plate, BACKING));
    }

    @Test
    void aPatternlessShieldAloneKeepsItsPlate() {
        // Without patterns the plate is drawn on the solid layer only, so it is the
        // whole shield and backs nothing.
        CanvasReader reader = reader();
        reader.beginLayer(SHIELD_ATLAS, OPAQUE);
        assertFalse(reader.isBacking(reader.next(plate, BACKING), plate, BACKING));
    }

    @Test
    void onlySolidLayerCuboidsCanBeTheBacking() {
        // The translucent passes are the canvas itself, a hashed part has no cuboid
        // to match, and a texture off the pattern atlases has no canvas.
        CanvasReader reader = reader();
        reader.collectBases(BANNER_ATLAS, TRANSLUCENT,
                layer(new ModelPart.Quad[][] {flag}, BASE_PASS));

        assertFalse(reader.isBacking(BASE, flag, BASE_PASS));
        assertFalse(reader.isBacking(PATTERN, flag, PATTERN_PASS));
        assertFalse(reader.isBacking(FRAME, null, BACKING));
        assertFalse(reader.isBacking(NONE, flag, BACKING));
    }

    @Test
    void solidLayersHoldNoBases() {
        CanvasReader reader = reader();
        reader.collectBases(BANNER_ATLAS, OPAQUE,
                layer(new ModelPart.Quad[][] {flag}, BACKING));
        reader.beginLayer(BANNER_ATLAS, OPAQUE);
        assertFalse(reader.isBacking(reader.next(flag, BACKING), flag, BACKING));
    }

    @Test
    void beginningAnEntityForgetsThePreviousEntitysBases() {
        CanvasReader reader = reader();
        reader.collectBases(BANNER_ATLAS, TRANSLUCENT,
                layer(new ModelPart.Quad[][] {flag}, BASE_PASS));

        reader.beginEntity(BONES);
        reader.beginLayer(BANNER_ATLAS, OPAQUE);
        assertFalse(reader.isBacking(reader.next(flag, BACKING), flag, BACKING));
    }

    private static CanvasReader reader() {
        CanvasReader reader = new CanvasReader();
        reader.beginEntity(BONES);
        return reader;
    }

    /** A captured layer holding the given cuboids, in order, each on its bone. */
    private static RemixCapturingConsumer layer(ModelPart.Quad[][] sides, int... bones) {
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);
        for (int i = 0; i < sides.length; i++) {
            consumer.replayCuboidPart(sides[i], WHITE, bones[i], null);
        }
        consumer.finishOpenVertex();
        return consumer;
    }

    /** One bone in the capture's layout: three rows of four, here a translation. */
    private static float[] pose(float x) {
        return new float[] {
                1f, 0f, 0f, x,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f};
    }

    private static float[] bones(float[]... poses) {
        float[] data = new float[poses.length * 12];
        for (int i = 0; i < poses.length; i++) {
            System.arraycopy(poses[i], 0, data, i * 12, 12);
        }
        return data;
    }
}
