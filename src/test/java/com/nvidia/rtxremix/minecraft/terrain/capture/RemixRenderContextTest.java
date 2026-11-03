/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.MeshBuilderImpl;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("deprecation")
@Tag("fabric")
class RemixRenderContextTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @ParameterizedTest
    @EnumSource(MeshOutput.class)
    void consumesIndigoMeshThroughCaptureEmitterWithVertexDataIntact(MeshOutput output) {
        MeshBuilderImpl builder = new MeshBuilderImpl();
        QuadEmitter emitter = builder.getEmitter();
        emitter.square(Direction.UP, 0, 0, 1, 1, 0);
        for (int vertex = 0; vertex < 4; vertex++) {
            emitter.color(vertex, 0xFF123456 + vertex);
            emitter.uv(vertex, vertex * 0.125f, vertex * 0.25f);
            emitter.lightmap(vertex, 0x00F000A0 + vertex);
            emitter.normal(vertex, 0, 1, 0);
        }
        emitter.colorIndex(3).tag(42).emit();
        Mesh mesh = builder.build();
        RemixRenderContext context = context();
        int[] visits = {0};
        context.pushTransform(quad -> {
            visits[0]++;
            assertSame(IndigoRenderer.MATERIAL_STANDARD, quad.material());
            assertEquals(Direction.UP, quad.cullFace());
            assertEquals(Direction.UP, quad.nominalFace());
            assertEquals(3, quad.colorIndex());
            assertEquals(42, quad.tag());
            for (int vertex = 0; vertex < 4; vertex++) {
                assertEquals(0xFF123456 + vertex, quad.color(vertex));
                assertEquals(0x00F000A0 + vertex, quad.lightmap(vertex));
                assertTrue(quad.hasNormal(vertex));
                assertEquals(1.0f, quad.normalY(vertex));
            }
            return true;
        });

        output.accept(mesh, context);

        assertEquals(1, visits[0]);
        assertEquals(1, context.harvest().size());
        SectionBlockData.BlockQuad captured = context.harvest().getFirst();
        assertArrayEquals(new float[]{0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0},
                captured.positions());
        assertArrayEquals(new float[]{0, 0, 0.125f, 0.25f, 0.25f, 0.5f, 0.375f, 0.75f},
                captured.uvs());
        assertEquals(0xFF123456, captured.color());
        assertEquals(1.0f, captured.ny());
    }

    @ParameterizedTest
    @EnumSource(MeshOutput.class)
    void meshQuadsFollowTransformStackFilteringAndFaceCulling(MeshOutput output) {
        MeshBuilderImpl builder = new MeshBuilderImpl();
        QuadEmitter emitter = builder.getEmitter();
        for (int tag = 0; tag < 3; tag++) {
            emitter.square(Direction.UP, 0, 0, 1, 1, 0).tag(tag).emit();
        }
        Mesh mesh = builder.build();
        RemixRenderContext context = context();
        context.prepare(null, null, BlockPos.ORIGIN, face -> face == Direction.NORTH);
        context.pushTransform(quad -> {
            // Transforms run in reverse push order: tag 0 has already been rejected.
            assertTrue(quad.tag() > 0);
            if (quad.tag() == 1) {
                quad.cullFace(Direction.NORTH);
            }
            for (int vertex = 0; vertex < 4; vertex++) {
                quad.pos(vertex, quad.x(vertex) + 2, quad.y(vertex), quad.z(vertex));
            }
            return true;
        });
        context.pushTransform(quad -> quad.tag() != 0);

        output.accept(mesh, context);

        assertEquals(1, context.harvest().size());
        assertEquals(2.0f, context.harvest().getFirst().positions()[0]);
        context.popTransform();
        context.popTransform();
        assertFalse(context.hasTransform());
    }

    @ParameterizedTest
    @EnumSource(MeshOutput.class)
    void repeatedMeshConsumptionClearsStaleEmitterStateAndKeepsEarlierCaptures(MeshOutput output) {
        MeshBuilderImpl builder = new MeshBuilderImpl();
        builder.getEmitter().square(Direction.UP, 0, 0, 1, 1, 0).emit();
        Mesh mesh = builder.build();
        RemixRenderContext context = context();
        context.getEmitter().normal(0, 1, 0, 0).tag(99);
        context.pushTransform(quad -> {
            assertFalse(quad.hasNormal(0));
            assertEquals(0, quad.tag());
            return true;
        });

        output.accept(mesh, context);
        output.accept(mesh, context);

        assertEquals(2, context.harvest().size());
        context.prepare(null, null, BlockPos.ORIGIN, null);
        output.accept(mesh, context);
        assertEquals(1, context.harvest().size());
        assertFalse(context.hasTransform());
    }

    @Test
    void meshOutputReplacesStaleNormalsOnTheSameEmitter() {
        MeshBuilderImpl builder = new MeshBuilderImpl();
        builder.getEmitter().square(Direction.UP, 0, 0, 1, 1, 0).normal(1, 0, 1, 0).emit();
        Mesh mesh = builder.build();
        RemixRenderContext context = context();
        QuadEmitter emitter = context.getEmitter();
        for (int vertex = 0; vertex < 4; vertex++) {
            emitter.normal(vertex, 1, 0, 0);
        }
        int[] vanilla = new int[32];
        int[] visits = {0};
        context.pushTransform(quad -> {
            visits[0]++;
            quad.toVanilla(vanilla, 0);
            for (int vertex = 0; vertex < 4; vertex++) {
                if (vertex == 1) {
                    assertTrue(quad.hasNormal(vertex));
                    assertEquals(0.0f, quad.normalX(vertex));
                    assertEquals(1.0f, quad.normalY(vertex));
                    assertEquals(0.0f, quad.normalZ(vertex));
                    assertEquals(0x00007F00, vanilla[vertex * 8 + 7]);
                } else {
                    assertFalse(quad.hasNormal(vertex));
                    assertTrue(Float.isNaN(quad.normalX(vertex)));
                    assertTrue(Float.isNaN(quad.normalY(vertex)));
                    assertTrue(Float.isNaN(quad.normalZ(vertex)));
                    assertNull(quad.copyNormal(vertex, null));
                    assertEquals(0, vanilla[vertex * 8 + 7]);
                }
            }
            return true;
        });

        mesh.outputTo(emitter);

        assertEquals(1, visits[0]);
        assertEquals(1, context.harvest().size());
    }

    @ParameterizedTest
    @EnumSource(MeshOutput.class)
    void emptyMeshDoesNotEmitAndClearsStaleEmitterState(MeshOutput output) {
        Mesh empty = new MeshBuilderImpl().build();
        RemixRenderContext context = context();
        QuadEmitter emitter = context.getEmitter().normal(0, 1, 0, 0).tag(99);
        context.pushTransform(quad -> {
            assertFalse(quad.hasNormal(0));
            assertEquals(0, quad.tag());
            return true;
        });

        output.accept(empty, context);

        assertTrue(context.harvest().isEmpty());
        emitter.square(Direction.UP, 0, 0, 1, 1, 0).emit();
        assertEquals(1, context.harvest().size());
    }

    @Test
    void outputToIndigoEmitterPreservesQuadsAndClearsEmitter() {
        MeshBuilderImpl source = new MeshBuilderImpl();
        source.getEmitter().square(Direction.UP, 0, 0, 1, 1, 0).tag(42).emit();
        source.getEmitter().square(Direction.NORTH, 0, 0, 1, 1, 0).tag(43).emit();
        MeshBuilderImpl target = new MeshBuilderImpl();
        QuadEmitter emitter = target.getEmitter().normal(0, 1, 0, 0).tag(99);

        source.build().outputTo(emitter);

        assertEquals(0, emitter.tag());
        assertFalse(emitter.hasNormal(0));
        int[] visits = {0};
        target.build().forEach(quad -> {
            assertEquals(42 + visits[0], quad.tag());
            assertEquals(visits[0] == 0 ? Direction.UP : Direction.NORTH, quad.cullFace());
            assertFalse(quad.hasNormal(0));
            visits[0]++;
        });
        assertEquals(2, visits[0]);
    }

    @Test
    void nestedMeshOutputRestoresTheOuterEmitter() {
        MeshBuilderImpl outerBuilder = new MeshBuilderImpl();
        outerBuilder.getEmitter().square(Direction.UP, 0, 0, 1, 1, 0).tag(1).emit();
        outerBuilder.getEmitter().square(Direction.UP, 0, 0, 1, 1, 0).tag(2).emit();
        MeshBuilderImpl innerBuilder = new MeshBuilderImpl();
        innerBuilder.getEmitter().square(Direction.NORTH, 0, 0, 1, 1, 0).emit();
        Mesh innerMesh = innerBuilder.build();
        RemixRenderContext outer = context();
        RemixRenderContext inner = context();
        outer.pushTransform(quad -> {
            if (quad.tag() == 1) {
                innerMesh.outputTo(inner.getEmitter());
            }
            return true;
        });

        outerBuilder.build().outputTo(outer.getEmitter());

        assertEquals(2, outer.harvest().size());
        assertEquals(1, inner.harvest().size());
        assertEquals(1.0f, outer.harvest().getLast().ny());
        assertEquals(-1.0f, inner.harvest().getFirst().nz());
    }

    private static RemixRenderContext context() {
        RemixRenderContext context = new RemixRenderContext();
        context.prepare(null, null, BlockPos.ORIGIN, null);
        return context;
    }

    enum MeshOutput {
        DIRECT,
        DEPRECATED;

        void accept(Mesh mesh, RemixRenderContext context) {
            if (this == DIRECT) {
                mesh.outputTo(context.getEmitter());
            } else {
                context.meshConsumer().accept(mesh);
            }
        }
    }
}
