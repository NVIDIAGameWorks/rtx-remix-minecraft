/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import com.nvidia.rtxremix.forge.texture.SpriteOpacity;
import java.util.List;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;

/** The single baked-model emitter; lighting is supplied by Remix, vertex and biome colors by the model. */
final class BakedBlockCapture {
    private static final EnumFacing[] FACES = EnumFacing.values();
    private final Minecraft mc;
    private final float[] positions = new float[12], uvs = new float[8];
    private final int[] colors = new int[4];
    private final SpriteOpacity opacity = new SpriteOpacity(256);
    private final BlockOverlays overlays = new BlockOverlays();
    private boolean classifyOverlays;

    BakedBlockCapture(Minecraft mc) { this.mc = mc; }

    void beginFrame() { opacity.beginFrame(); }
    void classifyOverlays(boolean enabled) { classifyOverlays = enabled; }
    void emit(IBlockState raw, IBlockAccess world, BlockPos pos,
              int originX, int originY, int originZ, BlockQuadSink target) {
        IBlockState actual = raw.getActualState(world, pos);
        IBlockState extended = actual.getBlock().getExtendedState(actual, world, pos);
        Vec3d offset = actual.getOffset(world, pos);
        long seed = MathHelper.getPositionRandom(pos);
        IBakedModel model = mc.getBlockRendererDispatcher().getModelForState(actual);
        if (model.isBuiltInRenderer()) return;
        float x = pos.getX() - originX + (float) offset.x;
        float y = pos.getY() - originY + (float) offset.y;
        float z = pos.getZ() - originZ + (float) offset.z;
        BlockQuadSink sink = classifyOverlays ? overlays : target;
        if (classifyOverlays) overlays.begin();
        for (EnumFacing side : FACES) if (actual.shouldSideBeRendered(world, pos, side))
            emitQuads(sink, model.getQuads(extended, side, seed), actual, world, pos, x, y, z);
        emitQuads(sink, model.getQuads(extended, null, seed), actual, world, pos, x, y, z);
        if (classifyOverlays) overlays.append(target);
    }
    void emitQuads(BlockQuadSink target, List<BakedQuad> quads, IBlockState state, IBlockAccess world,
                           BlockPos pos, float x, float y, float z) {
        for (int q = 0; q < quads.size(); q++) {
            BakedQuad quad = quads.get(q);
            VertexFormat format = quad.getFormat();
            if (!supportedFormat(format)) throw new IllegalArgumentException("Unsupported baked vertex format");
            int[] data = quad.getVertexData();
            int stride = format.getIntegerSize();
            int uv = format.getUvOffsetById(0) / 4;
            if (data.length != stride * 4) throw new IllegalArgumentException("Baked face must contain four vertices");
            int tint = quad.hasTintIndex() ? mc.getBlockColors().colorMultiplier(state, world, pos, quad.getTintIndex()) : 0xFFFFFF;
            boolean finite = true;
            for (int v = 0; v < 4; v++) {
                int base = v * stride;
                positions[v * 3] = Float.intBitsToFloat(data[base]) + x;
                positions[v * 3 + 1] = Float.intBitsToFloat(data[base + 1]) + y;
                positions[v * 3 + 2] = Float.intBitsToFloat(data[base + 2]) + z;
                uvs[v * 2] = Float.intBitsToFloat(data[base + uv]);
                uvs[v * 2 + 1] = Float.intBitsToFloat(data[base + uv + 1]);
                finite &= Float.isFinite(positions[v * 3]) && Float.isFinite(positions[v * 3 + 1])
                        && Float.isFinite(positions[v * 3 + 2]) && Float.isFinite(uvs[v * 2]) && Float.isFinite(uvs[v * 2 + 1]);
                if (format.hasColor()) {
                    int colorByte = base * 4 + format.getColorOffset();
                    colors[v] = MeshPacking.tintRgba(byteAt(data, colorByte), byteAt(data, colorByte + 1),
                            byteAt(data, colorByte + 2), byteAt(data, colorByte + 3), tint);
                } else {
                    colors[v] = 0xFF000000 | (tint & 0xFFFFFF);
                }
            }
            if (!finite) throw new IllegalArgumentException("Non-finite baked geometry");
            float ax = positions[3] - positions[0], ay = positions[4] - positions[1], az = positions[5] - positions[2];
            float bx = positions[6] - positions[0], by = positions[7] - positions[1], bz = positions[8] - positions[2];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!Float.isFinite(length)) throw new IllegalArgumentException("Non-finite baked normal");
            if (length > 0.00001f) {
                nx /= length; ny /= length; nz /= length;
            } else if (quad.getFace() != null) {
                nx = quad.getFace().getXOffset(); ny = quad.getFace().getYOffset(); nz = quad.getFace().getZOffset();
            } else throw new IllegalArgumentException("Baked face has no normal");
            TextureAtlasSprite sprite = quad.getSprite();
            if (sprite == null) throw new IllegalArgumentException("Baked face has no atlas sprite");
            if (!Float.isFinite(sprite.getMinU()) || !Float.isFinite(sprite.getMinV())
                    || !Float.isFinite(sprite.getMaxU()) || !Float.isFinite(sprite.getMaxV())
                    || sprite.getMinU() >= sprite.getMaxU() || sprite.getMinV() >= sprite.getMaxV()) {
                throw new IllegalArgumentException("Invalid baked sprite bounds");
            }
            if (target == overlays) overlays.coverage(opacity.sprite(sprite));
            target.quad(positions, uvs, colors, nx, ny, nz,
                    sprite.getMinU(), sprite.getMinV(),
                    sprite.getMaxU(), sprite.getMaxV());
        }
    }

    static boolean supportedFormat(VertexFormat format) {
        if (format == null || format.getSize() < 20 || format.getSize() > 64 || format.getSize() % 4 != 0
                || !format.hasUvOffset(0) || format.getUvOffsetById(0) % 4 != 0) return false;
        boolean position = false, uv = false;
        for (int i = 0; i < format.getElementCount(); i++) {
            VertexFormatElement element = format.getElement(i);
            if (element.getUsage() == VertexFormatElement.EnumUsage.POSITION)
                position = format.getOffset(i) == 0 && element.getType() == VertexFormatElement.EnumType.FLOAT && element.getElementCount() == 3;
            else if (element.getUsage() == VertexFormatElement.EnumUsage.UV && element.getIndex() == 0)
                uv = element.getType() == VertexFormatElement.EnumType.FLOAT && element.getElementCount() == 2;
            else if (element.getUsage() == VertexFormatElement.EnumUsage.COLOR
                    && (element.getType() != VertexFormatElement.EnumType.UBYTE || element.getElementCount() != 4)) return false;
        }
        return position && uv;
    }

    private static int byteAt(int[] data, int offset) {
        return (data[offset / 4] >>> ((offset & 3) * 8)) & 255;
    }

}
