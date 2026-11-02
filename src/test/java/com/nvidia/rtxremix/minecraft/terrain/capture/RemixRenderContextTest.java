/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemixRenderContextTest {
    @Test
    void emitterTransformsRemainStackedAcrossEmitsAndRunNewestFirst() {
        RemixRenderContext context = new RemixRenderContext();
        context.prepare(face -> false);
        QuadEmitter emitter = context.getEmitter();
        List<Integer> order = new ArrayList<>();
        emitter.pushTransform(quad -> {
            order.add(1);
            return true;
        });
        emitter.pushTransform(quad -> {
            order.add(2);
            return true;
        });
        emitter.square(Direction.SOUTH, 0, 0, 1, 1, 0).emit();
        emitter.popTransform();
        emitter.square(Direction.SOUTH, 0, 0, 1, 1, 0).emit();
        emitter.popTransform();

        assertEquals(List.of(2, 1, 1), order);
        assertEquals(2, context.harvest().size());
    }

    @Test
    void filteredAndCulledQuadsAreNotCapturedAndPrepareResetsTransforms() {
        RemixRenderContext context = new RemixRenderContext();
        context.prepare(face -> false);
        QuadEmitter emitter = context.getEmitter();
        emitter.pushTransform(quad -> false);
        emitter.square(Direction.SOUTH, 0, 0, 1, 1, 0).emit();
        assertTrue(context.harvest().isEmpty());

        context.prepare(face -> face == Direction.NORTH);
        emitter = context.getEmitter();
        emitter.square(Direction.NORTH, 0, 0, 1, 1, 0).cullFace(Direction.NORTH).emit();
        assertTrue(context.harvest().isEmpty());
        emitter.square(Direction.SOUTH, 0, 0, 1, 1, 0).cullFace(Direction.SOUTH).emit();
        assertEquals(1, context.harvest().size());
    }
}
