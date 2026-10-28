/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A block a mod draws through Fabric's renderer API keeps its tint: a captured
 * quad carries the color index its material allows, and staging multiplies the
 * block's tint into the quad's vertex color as Indigo does.
 */
class FrapiBlockTintTest {

    @Test
    void aCapturedQuadKeepsItsColorIndex() {
        assertEquals(2, captureOne(emitter -> emitter.colorIndex(2)).colorIndex());
        assertEquals(-1, captureOne(emitter -> { }).colorIndex());
    }

    @Test
    void aMaterialThatDisablesTheColorIndexLeavesTheQuadUntinted() {
        assertEquals(-1, captureOne(emitter -> emitter.material(material(true)).colorIndex(2)).colorIndex());
        assertEquals(2, captureOne(emitter -> emitter.material(material(false)).colorIndex(2)).colorIndex());
    }

    @Test
    void theTintMultipliesEachChannelAndKeepsTheVertexAlpha() {
        assertEquals(0xFF7FB238, BlockQuadEmitter.tintFrapiColor(0xFFFFFFFF, 0x7FB238));
        // 0x80 * 0x80 / 0xFF rounds down to 0x40, as Indigo's ColorHelper does.
        assertEquals(0x40404040, BlockQuadEmitter.tintFrapiColor(0x40808080, 0x808080));
        assertEquals(0x80336699, BlockQuadEmitter.tintFrapiColor(0x80336699, 0xFFFFFF));
    }

    @Test
    void aBlockWithoutAColorProviderKeepsTheVertexColor() {
        // BlockColors answers -1 for a block with no provider registered.
        assertEquals(0x80336699, BlockQuadEmitter.tintFrapiColor(0x80336699, -1));
    }

    private static SectionBlockData.BlockQuad captureOne(Consumer<QuadEmitter> quad) {
        RemixRenderContext context = new RemixRenderContext();
        context.prepare(null, null, BlockPos.ORIGIN, face -> false);
        QuadEmitter emitter = context.getEmitter();
        emitter.pos(0, 0, 0, 0).pos(1, 1, 0, 0).pos(2, 1, 1, 0).pos(3, 0, 1, 0);
        quad.accept(emitter);
        emitter.emit();
        List<SectionBlockData.BlockQuad> captured = context.harvest();
        assertEquals(1, captured.size());
        return captured.get(0);
    }

    /** A material that answers only whether it disables the color index. */
    private static RenderMaterial material(boolean disableColorIndex) {
        return (RenderMaterial) Proxy.newProxyInstance(RenderMaterial.class.getClassLoader(),
                new Class<?>[] {RenderMaterial.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "disableColorIndex" -> disableColorIndex;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "RenderMaterial(disableColorIndex=" + disableColorIndex + ")";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
