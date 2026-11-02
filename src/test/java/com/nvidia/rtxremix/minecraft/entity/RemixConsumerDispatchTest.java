/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.google.common.collect.ImmutableList;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.OverlayVertexConsumer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.SpriteTexturedVertexConsumer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumers;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.metadata.ResourceMetadata;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fabric")
class RemixConsumerDispatchTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @BeforeEach
    @AfterEach
    void reset() {
        EntityRenderContext.get().reset();
    }

    @Test
    void discardedUnionLeafDoesNotDemoteTheCapturedCuboid() {
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer capture = new RemixCapturingConsumer(null, true);
        VertexConsumer discarded = new RemixVertexConsumerProvider().getBuffer(RenderLayer.getWaterMask());
        assertTrue(RemixVertexConsumerProvider.isDiscarding(discarded));
        ModelPart.Cuboid cuboid = cuboid();
        cuboid.renderCuboid(new MatrixStack().peek(), VertexConsumers.union(discarded, capture), 0,
                OverlayTexture.DEFAULT_UV, -1);
        assertEquals(0, capture.getVertexCount());
        assertSame(cuboid.sides, capture.getPartSides(0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownOverlayBranchKeepsItsRenderingWhileTheOtherBranchRetainsTheCuboid(boolean outerSprite) {
        EntityRenderContext.get().begin(true);
        ModelPart.Cuboid cuboid = cuboid();
        MatrixStack matrices = new MatrixStack();
        matrices.translate(2, 3, 4);
        matrices.scale(0.7f, 0.7f, 0.7f);
        RemixCapturingConsumer capture = new RemixCapturingConsumer(null, true);
        var actual = new ProjectedGlintCaptureTest.VertexProbe();
        var expected = new ProjectedGlintCaptureTest.VertexProbe();
        try (SpriteContents contents = new SpriteContents(Identifier.of("minecraft", "test"),
                new SpriteDimensions(8, 8), new NativeImage(8, 8, true), ResourceMetadata.NONE)) {
            Sprite sprite = new Sprite(BlockTextureManager.BLOCK_ATLAS_ID, contents, 32, 32, 8, 16) {};
            VertexConsumer overlay = new OverlayVertexConsumer(actual, matrices.peek(), 1f / 128);
            VertexConsumer reference = new OverlayVertexConsumer(expected, matrices.peek(), 1f / 128);
            VertexConsumer union = VertexConsumers.union(capture, overlay);
            if (outerSprite) {
                union = new SpriteTexturedVertexConsumer(union, sprite);
                reference = new SpriteTexturedVertexConsumer(reference, sprite);
            }
            cuboid.renderCuboid(matrices.peek(), reference, 0, OverlayTexture.DEFAULT_UV, -1);
            cuboid.renderCuboid(matrices.peek(), union, 0, OverlayTexture.DEFAULT_UV, -1);
            assertEquals(0, capture.getVertexCount());
            assertSame(cuboid.sides, capture.getPartSides(0));
            assertEquals(24, actual.count);
            for (int v = 0; v < actual.count; v++) {
                assertEquals(expected.x[v], actual.x[v], 1e-6);
                assertEquals(expected.y[v], actual.y[v], 1e-6);
                assertEquals(expected.z[v], actual.z[v], 1e-6);
                assertEquals(expected.u[v], actual.u[v], 1e-6);
                assertEquals(expected.v[v], actual.v[v], 1e-6);
            }
        }
    }

    @Test
    void glintedSingleCuboidUsesThePooledIterator() {
        EntityRenderContext.get().begin(true);
        CountingCapture capture = new CountingCapture();
        ModelPart.Cuboid cuboid = cuboid();
        ModelPart part = new ModelPart(ImmutableList.of(cuboid), Map.of());
        part.render(new MatrixStack(), VertexConsumers.union(capture, new RemixCapturingConsumer(null, true)),
                0, OverlayTexture.DEFAULT_UV, -1);
        assertEquals(1, capture.iteratorBorrows);
        assertSame(cuboid.sides, capture.getPartSides(0));
    }

    @Test
    void everyGlintPhaseFollowsItsBaseInShellOrder() {
        EntityRenderContext.get().begin(true);
        RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
        provider.beginEntity();
        List<RenderLayer> glints = List.of(RenderLayer.getGlint(), RenderLayer.getEntityGlint(),
                RenderLayer.getArmorEntityGlint(), RenderLayer.getDirectEntityGlint(), RenderLayer.getGlintTranslucent());
        ModelPart.Cuboid cuboid = cuboid();
        MatrixStack matrices = new MatrixStack();
        for (RenderLayer glint : glints) {
            assertEquals(RenderLayerUtils.SurfaceMode.GLINT, RenderLayerUtils.surfaceMode(glint),
                    "an enchantment overlay must not become an opaque cutout shell");
            cuboid.renderCuboid(matrices.peek(), provider.getBuffer(glint), 0, OverlayTexture.DEFAULT_UV, -1);
        }
        cuboid.renderCuboid(matrices.peek(), provider.getBuffer(RenderLayer.getEntityCutout(BlockTextureManager.BLOCK_ATLAS_ID)),
                0, OverlayTexture.DEFAULT_UV, -1);
        var layers = provider.endEntity();
        int base = layers.stream().filter(layer -> !RenderLayerUtils.isGlint(layer.renderLayer())).findFirst().orElseThrow().layerIndex();
        assertEquals(0, base);
        for (var layer : layers) if (RenderLayerUtils.isGlint(layer.renderLayer())) assertTrue(layer.layerIndex() > base);
    }

    private static ModelPart.Cuboid cuboid() {
        return new ModelPart.Cuboid(0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 0,
                false, 16, 16, EnumSet.allOf(Direction.class));
    }

    private static final class CountingCapture extends RemixCapturingConsumer {
        int iteratorBorrows;
        CountingCapture() { super(null, true); }
        @Override public Iterator<ModelPart.Cuboid> acquireSingletonCuboidIterator(ModelPart.Cuboid cuboid) {
            iteratorBorrows++;
            return super.acquireSingletonCuboidIterator(cuboid);
        }
    }
}
