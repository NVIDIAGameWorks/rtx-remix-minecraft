/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.List;

/**
 * Forwards Minecraft's targeted-block outline to core as neutral edges.
 *
 * <p>Since 26.x the shape is evaluated during extraction and handed over in the
 * outline render state, so modded block shape behavior is preserved exactly as
 * vanilla draws it.
 */
public final class MinecraftBlockOutlineCapture {
    private static final float OUTLINE_WIDTH_PIXELS = 2.5F;
    private static final double REFERENCE_FRAMEBUFFER_WIDTH_PIXELS = 1920.0;
    private static final double MIN_FOV_DEGREES = 1.0;
    private static final double MAX_FOV_DEGREES = 179.0;

    private MinecraftBlockOutlineCapture() {
    }

    public static void capture(
            RemixBlockOutlinePass pass,
            ClientLevel world,
            BlockOutlineRenderState outline,
            Vec3 camera,
            double fovDegrees) {
        BlockPos blockPos = outline.pos();
        if (world == null || !world.getWorldBorder().isWithinBounds(blockPos)) {
            pass.discardCapture();
            return;
        }

        VoxelShape shape = outline.shape();
        if (shape.isEmpty()) {
            pass.discardCapture();
            return;
        }

        double cameraX = camera.x;
        double cameraY = camera.y;
        double cameraZ = camera.z;
        double offsetX = blockPos.getX() - cameraX;
        double offsetY = blockPos.getY() - cameraY;
        double offsetZ = blockPos.getZ() - cameraZ;
        List<RemixBlockOutlinePass.OutlineEdge> edges = new ArrayList<>();
        shape.forAllEdges((minX, minY, minZ, maxX, maxY, maxZ) ->
                edges.add(new RemixBlockOutlinePass.OutlineEdge(
                        minX + offsetX,
                        minY + offsetY,
                        minZ + offsetZ,
                        maxX + offsetX,
                        maxY + offsetY,
                        maxZ + offsetZ)));
        if (edges.isEmpty()) {
            pass.discardCapture();
            return;
        }

        Minecraft client = Minecraft.getInstance();
        int framebufferWidth = client.getWindow().getWidth();
        int framebufferHeight = client.getWindow().getHeight();
        if (framebufferHeight <= 0) {
            pass.discardCapture();
            return;
        }

        double clampedFov = Math.max(MIN_FOV_DEGREES, Math.min(MAX_FOV_DEGREES, fovDegrees));
        double lineWidthPixels = OUTLINE_WIDTH_PIXELS * Math.max(
                1.0, framebufferWidth / REFERENCE_FRAMEBUFFER_WIDTH_PIXELS);
        pass.capture(
                world,
                cameraX,
                cameraY,
                cameraZ,
                Math.tan(Math.toRadians(clampedFov) * 0.5),
                lineWidthPixels,
                framebufferHeight,
                edges);
    }
}
