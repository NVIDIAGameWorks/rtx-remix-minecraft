/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.EnumFacing;
import org.junit.Test;
import static org.junit.Assert.*;

public class BakedBlockCaptureTest {
    @Test public void capturesUnlitColorUvAndLocalPositionFromEitherVanillaFormat() {
        for (VertexFormat format : new VertexFormat[] {DefaultVertexFormats.ITEM, DefaultVertexFormats.BLOCK}) {
            MeshPacking mesh = new MeshPacking();
            new BakedBlockCapture(null).emitQuads(mesh, Collections.singletonList(quad(format, 0)), null, null, null, 3, 4, 5);
            assertEquals(4, mesh.vertexCount());
            ByteBuffer data = mesh.vertices();
            assertEquals(3, data.getFloat(0), 0);
            assertEquals(4, data.getFloat(4), 0);
            assertEquals(5, data.getFloat(8), 0);
            assertEquals(1, data.getFloat(20), 0);
            assertEquals(0, data.getFloat(24), 0);
            assertEquals(0xff112233, data.getInt(32));
            assertEquals(1, data.getFloat(MeshPacking.STRIDE + 24), 0);
        }
    }

    @Test public void rejectsMissingSpritesTruncatedBuffersAndNonfiniteGeometry() {
        for (int invalid = 1; invalid <= 3; invalid++) {
            MeshPacking mesh = new MeshPacking();
            try {
                new BakedBlockCapture(null).emitQuads(mesh, Collections.singletonList(quad(DefaultVertexFormats.ITEM, invalid)),
                        null, null, null, 0, 0, 0);
                fail("Expected invalid quad " + invalid);
            } catch (IllegalArgumentException expected) { }
            assertEquals(0, mesh.vertexCount());
        }
        assertFalse(BakedBlockCapture.supportedFormat(DefaultVertexFormats.POSITION));
        assertFalse(BakedBlockCapture.supportedFormat(null));
    }

    private static BakedQuad quad(VertexFormat format, int invalid) {
        int stride = format.getIntegerSize(), uv = format.getUvOffsetById(0) / 4;
        int[] data = new int[stride * 4];
        float[] xy = {0, 0, 1, 0, 1, 1, 0, 1};
        for (int i = 0; i < 4; i++) {
            data[i * stride] = Float.floatToIntBits(xy[i * 2]);
            data[i * stride + 1] = Float.floatToIntBits(xy[i * 2 + 1]);
            data[i * stride + format.getColorOffset() / 4] = 0xff332211;
            data[i * stride + uv] = Float.floatToIntBits(xy[i * 2]);
            data[i * stride + uv + 1] = Float.floatToIntBits(xy[i * 2 + 1]);
        }
        if (invalid == 1) data[0] = Float.floatToIntBits(Float.NaN);
        if (invalid == 2) data = Arrays.copyOf(data, data.length - 1);
        TextureAtlasSprite sprite = new TextureAtlasSprite("test") { };
        sprite.setIconWidth(16); sprite.setIconHeight(16); sprite.initSprite(16, 16, 0, 0, false);
        return new BakedQuad(data, -1, EnumFacing.NORTH, invalid == 3 ? null : sprite, true, format);
    }
}
