/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.Transform;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures vanilla's targeted-block outline and submits it as dynamic Remix
 * geometry.
 *
 * <p>Remix accepts triangle surfaces rather than line primitives, so each
 * voxel-shape edge becomes a thin camera-facing ribbon. Its dynamic mesh is
 * recreated for each captured outline because the Remix scene API exposes
 * immutable mesh geometry. The instance is submitted only in frames where
 * Fabric reports a block outline. {@link InstanceCategoryBit#WORLD_UI} applies
 * Remix's world-UI material treatment while preserving scene depth testing.
 */
public final class RemixBlockOutlinePass implements AutoCloseable {
    // Matches Minecraft's VIEW_OFFSET_Z_LAYERING transform. Pulling the
    // outline slightly toward the camera prevents coplanar z-fighting.
    private static final float VANILLA_VIEW_OFFSET_SCALE = 0.99975586F;
    private static final float OUTLINE_WIDTH_PIXELS = 2.5F;
    private static final float OUTLINE_OPACITY = 0.9F;
    private static final double REFERENCE_FRAMEBUFFER_WIDTH_PIXELS = 1920.0;
    private static final double MIN_FOV_DEGREES = 1.0;
    private static final double MAX_FOV_DEGREES = 179.0;
    private static final double MIN_VECTOR_LENGTH_SQUARED = 1.0E-12;
    private static final double MIN_HALF_WIDTH_WORLD_UNITS = 0.0005;
    private static final int VERTICES_PER_EDGE = 4;
    private static final int INDICES_PER_EDGE = 6;
    private static final int WHITE_VERTEX_COLOR = 0xFFFFFFFF;
    private static final int DOUBLE_SIDED = 1;
    private static final long NO_INSTANCE_EXTENSION = 0L;

    private final RemixAPI api;
    private final Transform instanceTransform = new Transform();

    private BlockOutlineSnapshot pendingOutline;
    private RemixMesh outlineMesh;
    private RemixMaterial outlineMaterial;

    public RemixBlockOutlinePass(RemixAPI api) {
        this.api = api;
    }

    /**
     * Starts a new vanilla world-render frame. The mesh remains allocated, but
     * the captured outline does not carry over if Fabric omits the callback.
     */
    public void beginFrame() {
        pendingOutline = null;
    }

    /**
     * Captures the targeted block's vanilla outline shape. Shape evaluation
     * happens during the Fabric callback so modded block shape behavior is
     * preserved exactly at the point vanilla draws it.
     */
    public void captureBlockOutline(
            WorldRenderContext context,
            WorldRenderContext.BlockOutlineContext outline) {
        ClientLevel world = context.world();
        BlockPos blockPos = outline.blockPos();
        if (world == null
                || outline.blockState().isAir()
                || !world.getWorldBorder().isWithinBounds(blockPos)) {
            pendingOutline = null;
            return;
        }

        VoxelShape shape = outline.blockState().getShape(
                world,
                blockPos,
                CollisionContext.of(outline.entity()));
        if (shape.isEmpty()) {
            pendingOutline = null;
            return;
        }

        double cameraX = outline.cameraX();
        double cameraY = outline.cameraY();
        double cameraZ = outline.cameraZ();
        double offsetX = blockPos.getX() - cameraX;
        double offsetY = blockPos.getY() - cameraY;
        double offsetZ = blockPos.getZ() - cameraZ;

        List<OutlineEdge> edges = new ArrayList<>();
        shape.forAllEdges((minX, minY, minZ, maxX, maxY, maxZ) ->
                edges.add(new OutlineEdge(
                        minX + offsetX,
                        minY + offsetY,
                        minZ + offsetZ,
                        maxX + offsetX,
                        maxY + offsetY,
                        maxZ + offsetZ)));
        if (edges.isEmpty()) {
            pendingOutline = null;
            return;
        }

        Minecraft client = Minecraft.getInstance();
        int framebufferWidth = client.getWindow().getWidth();
        int framebufferHeight = client.getWindow().getHeight();
        if (framebufferHeight <= 0) {
            pendingOutline = null;
            return;
        }

        float tickDelta = context.tickCounter().getGameTimeDeltaPartialTick(true);
        double fovDegrees = context.gameRenderer().getFov(context.camera(), tickDelta, true);
        fovDegrees = Math.max(MIN_FOV_DEGREES, Math.min(MAX_FOV_DEGREES, fovDegrees));
        double tanHalfFov = Math.tan(Math.toRadians(fovDegrees) * 0.5);
        double lineWidthPixels = OUTLINE_WIDTH_PIXELS * Math.max(
                1.0,
                framebufferWidth / REFERENCE_FRAMEBUFFER_WIDTH_PIXELS);

        pendingOutline = new BlockOutlineSnapshot(
                world,
                cameraX,
                cameraY,
                cameraZ,
                tanHalfFov,
                lineWidthPixels,
                framebufferHeight,
                edges);
    }

    /**
     * Updates and submits the captured block outline before
     * {@code remixapi_Submit()}.
     */
    public void submit() {
        BlockOutlineSnapshot outline = pendingOutline;
        pendingOutline = null;
        if (outline == null
                || Minecraft.getInstance().level != outline.world()) {
            return;
        }

        int edgeCount = outline.edges().size();
        HardcodedVertex[] vertices = new HardcodedVertex[edgeCount * VERTICES_PER_EDGE];
        int[] indices = new int[edgeCount * INDICES_PER_EDGE];
        int emittedEdges = 0;

        for (OutlineEdge edge : outline.edges()) {
            if (appendEdgeRibbon(edge, outline, vertices, indices, emittedEdges)) {
                emittedEdges++;
            }
        }
        if (emittedEdges == 0) {
            return;
        }

        RemixMaterial material = getOrCreateOutlineMaterial();
        RemixMeshSurface surface = new RemixMeshSurface(material)
                .setVertices(vertices, emittedEdges * VERTICES_PER_EDGE)
                .setIndices(indices, emittedEdges * INDICES_PER_EDGE);
        destroyOutlineMesh();
        outlineMesh = new RemixMesh(new RemixMeshSurface[]{surface});
        RemixMeshStore.get().create(outlineMesh);

        instanceTransform.setTranslation(
                (float) outline.cameraX(),
                (float) outline.cameraY(),
                (float) outline.cameraZ());
        api.drawInstance(
                outlineMesh.getHandle(),
                instanceTransform.matrix,
                InstanceCategoryBit.WORLD_UI,
                DOUBLE_SIDED,
                NO_INSTANCE_EXTENSION);
    }

    private static boolean appendEdgeRibbon(
            OutlineEdge source,
            BlockOutlineSnapshot outline,
            HardcodedVertex[] vertices,
            int[] indices,
            int emittedEdgeIndex) {
        double startX = source.startX() * VANILLA_VIEW_OFFSET_SCALE;
        double startY = source.startY() * VANILLA_VIEW_OFFSET_SCALE;
        double startZ = source.startZ() * VANILLA_VIEW_OFFSET_SCALE;
        double endX = source.endX() * VANILLA_VIEW_OFFSET_SCALE;
        double endY = source.endY() * VANILLA_VIEW_OFFSET_SCALE;
        double endZ = source.endZ() * VANILLA_VIEW_OFFSET_SCALE;

        double edgeX = endX - startX;
        double edgeY = endY - startY;
        double edgeZ = endZ - startZ;
        double edgeLengthSquared = edgeX * edgeX + edgeY * edgeY + edgeZ * edgeZ;
        if (edgeLengthSquared < MIN_VECTOR_LENGTH_SQUARED) {
            return false;
        }
        double inverseEdgeLength = 1.0 / Math.sqrt(edgeLengthSquared);
        edgeX *= inverseEdgeLength;
        edgeY *= inverseEdgeLength;
        edgeZ *= inverseEdgeLength;

        double midpointX = (startX + endX) * 0.5;
        double midpointY = (startY + endY) * 0.5;
        double midpointZ = (startZ + endZ) * 0.5;
        double distance = Math.sqrt(
                midpointX * midpointX
                        + midpointY * midpointY
                        + midpointZ * midpointZ);
        double halfWidth = Math.max(
                MIN_HALF_WIDTH_WORLD_UNITS,
                distance
                        * outline.tanHalfFov()
                        * outline.lineWidthPixels()
                        / outline.framebufferHeight());

        // Point from the edge toward the camera, which is the origin in this
        // camera-relative coordinate system.
        double viewX = -midpointX;
        double viewY = -midpointY;
        double viewZ = -midpointZ;

        // side = edge x view
        double sideX = edgeY * viewZ - edgeZ * viewY;
        double sideY = edgeZ * viewX - edgeX * viewZ;
        double sideZ = edgeX * viewY - edgeY * viewX;
        double sideLengthSquared = sideX * sideX + sideY * sideY + sideZ * sideZ;
        if (sideLengthSquared < MIN_VECTOR_LENGTH_SQUARED) {
            // The edge points almost directly at the camera. Pick a stable
            // perpendicular axis so it remains visible instead of collapsing.
            if (Math.abs(edgeX) < 0.9) {
                sideX = 0.0;
                sideY = edgeZ;
                sideZ = -edgeY;
            } else {
                sideX = -edgeZ;
                sideY = 0.0;
                sideZ = edgeX;
            }
            sideLengthSquared = sideX * sideX + sideY * sideY + sideZ * sideZ;
        }
        double inverseSideLength = 1.0 / Math.sqrt(sideLengthSquared);
        sideX *= inverseSideLength;
        sideY *= inverseSideLength;
        sideZ *= inverseSideLength;

        // normal = side x edge, oriented toward the camera.
        float normalX = (float) (sideY * edgeZ - sideZ * edgeY);
        float normalY = (float) (sideZ * edgeX - sideX * edgeZ);
        float normalZ = (float) (sideX * edgeY - sideY * edgeX);

        // Slightly extend each end so adjoining ribbons do not expose gaps.
        startX -= edgeX * halfWidth;
        startY -= edgeY * halfWidth;
        startZ -= edgeZ * halfWidth;
        endX += edgeX * halfWidth;
        endY += edgeY * halfWidth;
        endZ += edgeZ * halfWidth;

        double widthX = sideX * halfWidth;
        double widthY = sideY * halfWidth;
        double widthZ = sideZ * halfWidth;
        int vertexBase = emittedEdgeIndex * VERTICES_PER_EDGE;
        vertices[vertexBase] = vertex(
                startX + widthX, startY + widthY, startZ + widthZ,
                normalX, normalY, normalZ);
        vertices[vertexBase + 1] = vertex(
                startX - widthX, startY - widthY, startZ - widthZ,
                normalX, normalY, normalZ);
        vertices[vertexBase + 2] = vertex(
                endX - widthX, endY - widthY, endZ - widthZ,
                normalX, normalY, normalZ);
        vertices[vertexBase + 3] = vertex(
                endX + widthX, endY + widthY, endZ + widthZ,
                normalX, normalY, normalZ);

        int indexBase = emittedEdgeIndex * INDICES_PER_EDGE;
        indices[indexBase] = vertexBase;
        indices[indexBase + 1] = vertexBase + 2;
        indices[indexBase + 2] = vertexBase + 1;
        indices[indexBase + 3] = vertexBase;
        indices[indexBase + 4] = vertexBase + 3;
        indices[indexBase + 5] = vertexBase + 2;
        return true;
    }

    private static HardcodedVertex vertex(
            double x,
            double y,
            double z,
            float normalX,
            float normalY,
            float normalZ) {
        return HardcodedVertex.create(
                (float) x,
                (float) y,
                (float) z,
                normalX,
                normalY,
                normalZ,
                0.0F,
                0.0F,
                WHITE_VERTEX_COLOR);
    }

    private RemixMaterial getOrCreateOutlineMaterial() {
        if (outlineMaterial != null && !outlineMaterial.isDeleted()) {
            return outlineMaterial;
        }

        MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(0.0F, 0.0F, 0.0F)
                .opacityConstant(OUTLINE_OPACITY)
                .roughnessConstant(1.0F)
                .metallicConstant(0.0F)
                .useDrawCallAlphaState(false)
                .blendEnabled(true)
                .blendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA)
                .build();
        RemixMaterial material = new RemixMaterial(
                MaterialInfo.builder()
                        .opaqueExt(opaque)
                        .build());
        RemixMaterialStore.get().create(material);
        outlineMaterial = material;
        return material;
    }

    private void destroyOutlineMesh() {
        if (outlineMesh != null && !outlineMesh.isDeleted()) {
            outlineMesh.destroy();
        }
        outlineMesh = null;
    }

    public void clear() {
        pendingOutline = null;
        destroyOutlineMesh();
        if (outlineMaterial != null && !outlineMaterial.isDeleted()) {
            outlineMaterial.destroy();
        }
        outlineMaterial = null;
    }

    @Override
    public void close() {
        clear();
    }

    private record BlockOutlineSnapshot(
            ClientLevel world,
            double cameraX,
            double cameraY,
            double cameraZ,
            double tanHalfFov,
            double lineWidthPixels,
            int framebufferHeight,
            List<OutlineEdge> edges) {
    }

    private record OutlineEdge(
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ) {
    }
}
