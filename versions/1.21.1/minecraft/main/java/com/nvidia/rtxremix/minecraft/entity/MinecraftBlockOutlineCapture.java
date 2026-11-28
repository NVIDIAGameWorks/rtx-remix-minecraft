/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.List;

/** Evaluates Minecraft's targeted-block shape and forwards neutral edges to core. */
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
            BlockPos blockPos,
            BlockState blockState,
            Entity entity,
            Camera camera,
            float tickDelta) {
        if (world == null
                || blockState.isAir()
                || !world.getWorldBorder().isWithinBounds(blockPos)) {
            pass.discardCapture();
            return;
        }

        VoxelShape shape = blockState.getShape(
                world, blockPos, CollisionContext.of(entity));
        if (shape.isEmpty()) {
            pass.discardCapture();
            return;
        }

        double cameraX = camera.getPosition().x;
        double cameraY = camera.getPosition().y;
        double cameraZ = camera.getPosition().z;
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

        double fovDegrees = client.gameRenderer.getFov(camera, tickDelta, true);
        fovDegrees = Math.max(MIN_FOV_DEGREES, Math.min(MAX_FOV_DEGREES, fovDegrees));
        double lineWidthPixels = OUTLINE_WIDTH_PIXELS * Math.max(
                1.0, framebufferWidth / REFERENCE_FRAMEBUFFER_WIDTH_PIXELS);
        pass.capture(
                world,
                cameraX,
                cameraY,
                cameraZ,
                Math.tan(Math.toRadians(fovDegrees) * 0.5),
                lineWidthPixels,
                framebufferHeight,
                edges);
    }
}
