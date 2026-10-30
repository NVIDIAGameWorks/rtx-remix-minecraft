/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.texture.ParticleAtlasManager;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.mixin.BillboardParticleAccessor;
import com.nvidia.rtxremix.minecraft.mixin.ParticleAccessor;
import com.nvidia.rtxremix.minecraft.mixin.ParticleManagerAccessor;
import com.nvidia.rtxremix.minecraft.mixin.SpriteBillboardParticleAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Per-frame particle renderer backed by growable dynamic meshes.
 *
 * <p>Each render bucket owns one mesh for particles that share material and
 * draw-state. Particle slots are kept dense, so mesh recreation and drawing
 * submit only the active particle range instead of the high-water capacity.
 */
public class RemixParticleRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixParticleRenderer.class);
    private static final int INITIAL_CAPACITY = 2048;
    private static final int MAX_CAPACITY = 16384;
    private static final int VERTS_PER_PARTICLE = 4;
    private static final int INDICES_PER_PARTICLE = 6;
    private static final int STRIDE = HardcodedVertex.STRIDE;
    private static final int VERTEX_BYTES_PER_PARTICLE = VERTS_PER_PARTICLE * STRIDE;

    private static final float[] IDENTITY_TRANSFORM = {
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f
    };

    private static final int BLEND_PARTICLE = 1;
    private static final int BLEND_TERRAIN = 2;

    private final RemixRenderer renderer;
    private final ParticleAtlasManager atlasManager;
    private final HashMap<BucketKey, DynamicBucket> buckets = new HashMap<>();

    private InstanceInfoBlendEXT blendExt;
    private long pNext;
    private InstanceInfoBlendEXT terrainBlendExt;
    private long terrainPNext;

    private int lastParticleCount;
    private int lastDrawCount;
    private int lastActiveQuadCount;
    private int lastNewSlotCount;
    private int lastRemovedSlotCount;
    private int lastCapacityGrowCount;
    private int lastCapacityCount;
    private int lastMeshUpdateCount;
    private long frameCounter;

    private int particlesThisFrame;
    private int newSlotsThisFrame;
    private int removedSlotsThisFrame;
    private int capacityGrowsThisFrame;
    private int meshUpdatesThisFrame;

    public RemixParticleRenderer(RemixRenderer renderer, ParticleAtlasManager atlasManager) {
        this.renderer = renderer;
        this.atlasManager = atlasManager;
    }

    private void ensureBlendExts() {
        if (blendExt == null) {
            blendExt = InstanceInfoBlendEXT.textureTimesVertexColorAlphaBlend(10);
            pNext = blendExt.allocNative(0);
        }
        if (terrainBlendExt == null) {
            terrainBlendExt = InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(128);
            terrainPNext = terrainBlendExt.allocNative(0);
        }
    }

    /** Captures particles and prepares immutable mesh payloads without calling Remix. */
    public PreparedFrame prepare(float tickDelta) {
        long frame = ++frameCounter;
        resetFrameStats();

        Minecraft client = Minecraft.getInstance();
        if (client.particleEngine == null) {
            return finishPreparation(frame);
        }

        ParticleManagerAccessor pmAccess = (ParticleManagerAccessor) client.particleEngine;
        Map<ParticleRenderType, Queue<Particle>> particleMap = pmAccess.getParticles();
        if (particleMap == null || particleMap.isEmpty()) {
            return finishPreparation(frame);
        }

        BlockTextureManager blockTexMgr = renderer.getTextureManager();
        var camRot = client.gameRenderer.getMainCamera().rotation();
        float qx = camRot.x, qy = camRot.y, qz = camRot.z, qw = camRot.w;
        ParticleBillboardBasis billboardBasis = ParticleBillboardBasis.fromCamera(qx, qy, qz, qw);

        for (var entry : particleMap.entrySet()) {
            ParticleRenderType sheet = entry.getKey();
            Queue<Particle> queue = entry.getValue();
            if (queue == null || queue.isEmpty()) continue;
            if (sheet == ParticleRenderType.NO_RENDER || sheet == ParticleRenderType.CUSTOM) {
                continue;
            }

            boolean isTerrain = sheet == ParticleRenderType.TERRAIN_SHEET;
            RemixMaterial material = resolveMaterial(sheet, blockTexMgr);
            if (material == null || !material.isAlive()) continue;

            int categoryFlags = isTerrain ? 0 : InstanceCategoryBit.PARTICLE;
            int blendKind = isTerrain ? BLEND_TERRAIN : BLEND_PARTICLE;
            DynamicBucket bucket = getOrCreateBucket(
                    new BucketKey(material.getHandle(), categoryFlags, blendKind),
                    material, categoryFlags, blendKind);
            if (bucket == null) continue;

            for (Particle particle : queue) {
                if (!(particle instanceof SingleQuadParticle)) continue;

                ParticleAccessor pa = (ParticleAccessor) particle;
                if (pa.getDead()) continue;

                BillboardParticleAccessor billboard = (BillboardParticleAccessor) particle;
                int boundsMin = 0;
                int boundsMax = 0;
                if (particle instanceof TextureSheetParticle) {
                    TextureAtlasSprite sprite = ((SpriteBillboardParticleAccessor) particle).getSprite();
                    if (sprite == null) continue;
                    float spriteMinU = sprite.getU0();
                    float spriteMinV = sprite.getV0();
                    float spriteMaxU = sprite.getU1();
                    float spriteMaxV = sprite.getV1();
                    boundsMin = HardcodedVertex.packHalf2x16(
                            Math.min(spriteMinU, spriteMaxU),
                            Math.min(spriteMinV, spriteMaxV));
                    boundsMax = HardcodedVertex.packHalf2x16(
                            Math.max(spriteMinU, spriteMaxU),
                            Math.max(spriteMinV, spriteMaxV));
                }

                float u0 = billboard.invokeGetMinU();
                float v0 = billboard.invokeGetMinV();
                float u1 = billboard.invokeGetMaxU();
                float v1 = billboard.invokeGetMaxV();

                int slot = bucket.assignSlot(particle, frame);
                if (slot < 0) continue;

                // Alpha stays the particle's own; a held puff is drawn at a lower
                // alpha, see HeldItemSmoke.OPACITY for why the two are not one value.
                int color = ParticleTint.isSoot(particle)
                        ? packColor(ParticleTint.SOOT_GREY, ParticleTint.SOOT_GREY, ParticleTint.SOOT_GREY, pa.getAlpha())
                        : packColor(pa.getRed(), pa.getGreen(), pa.getBlue(), pa.getAlpha());
                writeParticleQuad(bucket.vertexBuffer, slot, pa, billboard,
                        tickDelta, billboardBasis, u0, v0, u1, v1, color, boundsMin, boundsMax);
                bucket.dirty = true;
                particlesThisFrame++;
            }
        }

        return finishPreparation(frame);
    }

    private PreparedFrame finishPreparation(long frame) {
        List<PreparedBucket> preparedBuckets = new ArrayList<>(buckets.size());
        int capacity = 0;
        int plannedDraws = 0;
        int plannedActiveQuads = 0;
        try {
            for (DynamicBucket bucket : buckets.values()) {
                bucket.removeUnseen(frame);
                capacity += bucket.capacity;

                PreparedMeshData meshData = null;
                if (bucket.dirty) {
                    meshUpdatesThisFrame++;
                    if (bucket.liveCount > 0) {
                        meshData = prepareMeshData(bucket.vertexBuffer, bucket.indexBuffer, bucket.liveCount);
                    }
                }
                try {
                    preparedBuckets.add(new PreparedBucket(bucket, meshData));
                } catch (RuntimeException | Error e) {
                    if (meshData != null) meshData.close();
                    throw e;
                }

                if (bucket.liveCount > 0) {
                    plannedDraws++;
                    plannedActiveQuads += bucket.liveCount;
                }
            }

            return new PreparedFrame(preparedBuckets,
                    particlesThisFrame, plannedDraws, plannedActiveQuads,
                    newSlotsThisFrame, removedSlotsThisFrame,
                    capacityGrowsThisFrame, capacity, meshUpdatesThisFrame);
        } catch (RuntimeException | Error e) {
            for (PreparedBucket preparedBucket : preparedBuckets) {
                preparedBucket.close();
            }
            throw e;
        }
    }

    /** Creates, retires, and draws the prepared assets on the render thread. */
    public void submit(PreparedFrame preparedFrame) {
        if (preparedFrame == null) return;

        RemixAPI api = renderer.getRemixApi();
        int draws = 0;
        int activeQuads = 0;
        try {
            if (api != null && preparedFrame.plannedDrawCount() > 0) {
                ensureBlendExts();
            }

            for (PreparedBucket preparedBucket : preparedFrame.buckets) {
                DynamicBucket bucket = preparedBucket.bucket;
                if (api != null) {
                    bucket.applyPreparedMesh(preparedBucket.meshData);
                }

                if (api != null && bucket.liveCount > 0
                        && bucket.mesh != null && !bucket.mesh.isDeleted()) {
                    api.drawInstance(bucket.mesh.getHandle(), IDENTITY_TRANSFORM,
                            bucket.categoryFlags, 1, drawPNext(bucket.blendKind));
                    draws++;
                    activeQuads += bucket.liveCount;
                }
            }

            publishFrameStats(preparedFrame, draws, activeQuads);
        } finally {
            preparedFrame.close();
        }
    }

    private long drawPNext(int blendKind) {
        return blendKind == BLEND_TERRAIN ? terrainPNext : pNext;
    }

    private void publishFrameStats(PreparedFrame preparedFrame, int draws, int activeQuads) {
        lastParticleCount = preparedFrame.particleCount;
        lastDrawCount = draws;
        lastActiveQuadCount = activeQuads;
        lastNewSlotCount = preparedFrame.newSlotCount;
        lastRemovedSlotCount = preparedFrame.removedSlotCount;
        lastCapacityGrowCount = preparedFrame.capacityGrowCount;
        lastCapacityCount = preparedFrame.capacity;
        lastMeshUpdateCount = preparedFrame.meshUpdateCount;

        ProfileScope.plot("Java.Particles.Count", preparedFrame.particleCount);
        ProfileScope.plot("Java.Particles.MeshBuckets", draws);
        ProfileScope.plot("Java.Particles.DynamicQuads", activeQuads);
        ProfileScope.plot("Java.Particles.NewSlots", preparedFrame.newSlotCount);
        ProfileScope.plot("Java.Particles.RemovedSlots", preparedFrame.removedSlotCount);
        ProfileScope.plot("Java.Particles.CapacityGrows", preparedFrame.capacityGrowCount);
        ProfileScope.plot("Java.Particles.Capacity", preparedFrame.capacity);
        ProfileScope.plot("Java.Particles.MeshUpdates", preparedFrame.meshUpdateCount);
    }

    private void resetFrameStats() {
        particlesThisFrame = 0;
        newSlotsThisFrame = 0;
        removedSlotsThisFrame = 0;
        capacityGrowsThisFrame = 0;
        meshUpdatesThisFrame = 0;
    }

    private RemixMaterial resolveMaterial(ParticleRenderType sheet, BlockTextureManager blockTexMgr) {
        if (sheet == ParticleRenderType.TERRAIN_SHEET) {
            return blockTexMgr != null ? blockTexMgr.getAtlasMaterial(BlockTextureManager.L_CUTOUT) : null;
        }
        return atlasManager.getMaterial();
    }

    private DynamicBucket getOrCreateBucket(BucketKey key, RemixMaterial material,
                                            int categoryFlags, int blendKind) {
        DynamicBucket bucket = buckets.get(key);
        if (bucket != null) {
            return bucket;
        }

        bucket = createBucket(material, categoryFlags, blendKind, INITIAL_CAPACITY);
        if (bucket == null) return null;
        buckets.put(key, bucket);
        capacityGrowsThisFrame++;
        return bucket;
    }

    private DynamicBucket createBucket(RemixMaterial material, int categoryFlags,
                                       int blendKind, int capacity) {
        ByteBuffer vertices = allocateVertexBuffer(capacity);
        ByteBuffer indices = null;
        try {
            indices = allocateIndexBuffer(capacity);
            return new DynamicBucket(material, categoryFlags, blendKind, null,
                    vertices, indices, capacity);
        } catch (RuntimeException | Error e) {
            DirectBufferPool.release(vertices);
            DirectBufferPool.release(indices);
            throw e;
        }
    }

    private static PreparedMeshData prepareMeshData(ByteBuffer vertices,
                                                    ByteBuffer indices,
                                                    int particleCount) {
        int vertexBytes = particleCount * VERTEX_BYTES_PER_PARTICLE;
        int indexBytes = particleCount * INDICES_PER_PARTICLE * Integer.BYTES;
        ByteBuffer vertexSnapshot = copyPrefix(vertices, vertexBytes);
        try {
            ByteBuffer indexSnapshot = copyPrefix(indices, indexBytes);
            return new PreparedMeshData(vertexSnapshot, indexSnapshot, particleCount);
        } catch (RuntimeException | Error e) {
            DirectBufferPool.release(vertexSnapshot);
            throw e;
        }
    }

    private static RemixMesh createMesh(RemixMaterial material, PreparedMeshData preparedMesh) {
        RemixMesh mesh = preparedMesh.takeMesh(material);
        try {
            RemixMeshStore.get().create(mesh);
            return mesh;
        } catch (RemixException e) {
            LOGGER.error("Failed to recreate particle mesh: {}", e.getMessage());
            mesh.disposeData();
            return null;
        } catch (RuntimeException | Error e) {
            mesh.disposeData();
            throw e;
        }
    }

    private static ByteBuffer copyPrefix(ByteBuffer source, int byteCount) {
        ByteBuffer snapshot = DirectBufferPool.acquire(byteCount);
        ByteBuffer copy = source.duplicate();
        copy.position(0).limit(byteCount);
        snapshot.put(copy);
        snapshot.flip();
        return snapshot;
    }

    private static void writeParticleQuad(ByteBuffer vertices, int slot,
                                          ParticleAccessor pa, BillboardParticleAccessor ba,
                                          float tickDelta, ParticleBillboardBasis basis,
                                          float u0, float v0, float u1, float v1, int color,
                                          int boundsMin, int boundsMax) {
        double px = Mth.lerp(tickDelta, pa.getPrevPosX(), pa.getX());
        double py = Mth.lerp(tickDelta, pa.getPrevPosY(), pa.getY());
        double pz = Mth.lerp(tickDelta, pa.getPrevPosZ(), pa.getZ());

        float angle = Mth.lerp(tickDelta, pa.getPrevAngle(), pa.getAngle());
        float scale = ba.getScale();
        float cosA = Mth.cos(angle);
        float sinA = Mth.sin(angle);

        float s00 = (cosA * basis.r00 + sinA * basis.r10) * scale;
        float s01 = (cosA * basis.r01 + sinA * basis.r11) * scale;
        float s10 = (-sinA * basis.r00 + cosA * basis.r10) * scale;
        float s11 = (-sinA * basis.r01 + cosA * basis.r11) * scale;
        float z00 = basis.r20 * scale;
        float z01 = basis.r21 * scale;

        int base = slot * VERTEX_BYTES_PER_PARTICLE;
        putActiveVertex(vertices, base,      px - s00 - s01, py - s10 - s11, pz - z00 - z01, basis.nx, basis.ny, basis.nz, u0, v1, color);
        putActiveVertex(vertices, base + STRIDE, px + s00 - s01, py + s10 - s11, pz + z00 - z01, basis.nx, basis.ny, basis.nz, u1, v1, color);
        putActiveVertex(vertices, base + STRIDE * 2, px + s00 + s01, py + s10 + s11, pz + z00 + z01, basis.nx, basis.ny, basis.nz, u1, v0, color);
        putActiveVertex(vertices, base + STRIDE * 3, px - s00 + s01, py - s10 + s11, pz - z00 + z01, basis.nx, basis.ny, basis.nz, u0, v0, color);
        writeAtlasBounds(vertices, base, boundsMin, boundsMax);
    }

    private static void writeAtlasBounds(ByteBuffer vertices, int base,
                                         int boundsMin, int boundsMax) {
        for (int vertex = 0; vertex < VERTS_PER_PARTICLE; vertex++) {
            int offset = base + vertex * STRIDE;
            vertices.putInt(offset + 36, boundsMin);
            vertices.putInt(offset + 40, boundsMax);
        }
    }

    private static void writeDegenerateQuad(ByteBuffer vertices, int slot) {
        int base = slot * VERTEX_BYTES_PER_PARTICLE;
        putVertex(vertices, base, 0, 0, 0, 0, 0, -1, 0, 0, 0);
        putVertex(vertices, base + STRIDE, 0, 0, 0, 0, 0, -1, 0, 0, 0);
        putVertex(vertices, base + STRIDE * 2, 0, 0, 0, 0, 0, -1, 0, 0, 0);
        putVertex(vertices, base + STRIDE * 3, 0, 0, 0, 0, 0, -1, 0, 0, 0);
    }

    private static void copyParticleQuad(ByteBuffer vertices, int fromSlot, int toSlot) {
        int from = fromSlot * VERTEX_BYTES_PER_PARTICLE;
        int to = toSlot * VERTEX_BYTES_PER_PARTICLE;
        for (int offset = 0; offset < VERTEX_BYTES_PER_PARTICLE; offset += Long.BYTES) {
            vertices.putLong(to + offset, vertices.getLong(from + offset));
        }
    }

    private static void putVertex(ByteBuffer buffer, int offset,
                                  double px, double py, double pz,
                                  float nx, float ny, float nz,
                                  float u, float v, int color) {
        putActiveVertex(buffer, offset, px, py, pz, nx, ny, nz, u, v, color);
        buffer.putInt(offset + 36, 0);
        buffer.putInt(offset + 40, 0);
        buffer.putInt(offset + 44, 0);
        buffer.putInt(offset + 48, 0);
        buffer.putInt(offset + 52, 0);
        buffer.putInt(offset + 56, 0);
        buffer.putInt(offset + 60, 0);
    }

    private static void putActiveVertex(ByteBuffer buffer, int offset,
                                        double px, double py, double pz,
                                        float nx, float ny, float nz,
                                        float u, float v, int color) {
        buffer.putFloat(offset, (float) px);
        buffer.putFloat(offset + 4, (float) py);
        buffer.putFloat(offset + 8, (float) pz);
        buffer.putFloat(offset + 12, nx);
        buffer.putFloat(offset + 16, ny);
        buffer.putFloat(offset + 20, nz);
        buffer.putFloat(offset + 24, u);
        buffer.putFloat(offset + 28, v);
        buffer.putInt(offset + 32, color);
    }

    private static ByteBuffer allocateVertexBuffer(int capacity) {
        ByteBuffer buffer = DirectBufferPool.acquire(capacity * VERTEX_BYTES_PER_PARTICLE);
        for (int i = 0; i < capacity; i++) {
            writeDegenerateQuad(buffer, i);
        }
        return buffer;
    }

    private static ByteBuffer allocateIndexBuffer(int capacity) {
        ByteBuffer buffer = DirectBufferPool.acquire(capacity * INDICES_PER_PARTICLE * Integer.BYTES);
        for (int slot = 0; slot < capacity; slot++) {
            int vertexBase = slot * VERTS_PER_PARTICLE;
            buffer.putInt((slot * INDICES_PER_PARTICLE) * Integer.BYTES, vertexBase);
            buffer.putInt((slot * INDICES_PER_PARTICLE + 1) * Integer.BYTES, vertexBase + 1);
            buffer.putInt((slot * INDICES_PER_PARTICLE + 2) * Integer.BYTES, vertexBase + 2);
            buffer.putInt((slot * INDICES_PER_PARTICLE + 3) * Integer.BYTES, vertexBase);
            buffer.putInt((slot * INDICES_PER_PARTICLE + 4) * Integer.BYTES, vertexBase + 2);
            buffer.putInt((slot * INDICES_PER_PARTICLE + 5) * Integer.BYTES, vertexBase + 3);
        }
        return buffer;
    }

    /** Packs RGBA floats into D3DCOLOR (ARGB) uint32 matching Remix's native vertex format. */
    private static int packColor(float r, float g, float b, float a) {
        int ri = (int) (Mth.clamp(r, 0f, 1f) * 255f) & 0xFF;
        int gi = (int) (Mth.clamp(g, 0f, 1f) * 255f) & 0xFF;
        int bi = (int) (Mth.clamp(b, 0f, 1f) * 255f) & 0xFF;
        int ai = (int) (Mth.clamp(a, 0f, 1f) * 255f) & 0xFF;
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }

    /** Releases native and CPU particle state on the render thread after the worker is joined. */
    public void cleanup() {
        for (DynamicBucket bucket : buckets.values()) {
            bucket.destroy();
        }
        buckets.clear();

        if (blendExt != null) {
            blendExt.freeNative();
            blendExt = null;
            pNext = 0;
        }
        if (terrainBlendExt != null) {
            terrainBlendExt.freeNative();
            terrainBlendExt = null;
            terrainPNext = 0;
        }

        lastParticleCount = 0;
        lastDrawCount = 0;
        lastActiveQuadCount = 0;
        lastNewSlotCount = 0;
        lastRemovedSlotCount = 0;
        lastCapacityGrowCount = 0;
        lastCapacityCount = 0;
        lastMeshUpdateCount = 0;
    }

    public int getLastParticleCount() { return lastParticleCount; }
    public int getLastDrawCount() { return lastDrawCount; }
    public int getLastActiveQuadCount() { return lastActiveQuadCount; }
    public int getLastNewSlotCount() { return lastNewSlotCount; }
    public int getLastRemovedSlotCount() { return lastRemovedSlotCount; }
    public int getLastCapacityGrowCount() { return lastCapacityGrowCount; }
    public int getLastCapacityCount() { return lastCapacityCount; }
    public int getLastMeshUpdateCount() { return lastMeshUpdateCount; }

    /** CPU result handed from the particle worker to the render thread exactly once. */
    public static final class PreparedFrame implements AutoCloseable {
        private final List<PreparedBucket> buckets;
        private final int particleCount;
        private final int plannedDrawCount;
        private final int plannedActiveQuadCount;
        private final int newSlotCount;
        private final int removedSlotCount;
        private final int capacityGrowCount;
        private final int capacity;
        private final int meshUpdateCount;
        private boolean closed;

        private PreparedFrame(List<PreparedBucket> buckets,
                              int particleCount,
                              int plannedDrawCount,
                              int plannedActiveQuadCount,
                              int newSlotCount,
                              int removedSlotCount,
                              int capacityGrowCount,
                              int capacity,
                              int meshUpdateCount) {
            this.buckets = List.copyOf(buckets);
            this.particleCount = particleCount;
            this.plannedDrawCount = plannedDrawCount;
            this.plannedActiveQuadCount = plannedActiveQuadCount;
            this.newSlotCount = newSlotCount;
            this.removedSlotCount = removedSlotCount;
            this.capacityGrowCount = capacityGrowCount;
            this.capacity = capacity;
            this.meshUpdateCount = meshUpdateCount;
        }

        public int particleCount() {
            return particleCount;
        }

        public int plannedDrawCount() {
            return plannedDrawCount;
        }

        public int plannedActiveQuadCount() {
            return plannedActiveQuadCount;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            for (PreparedBucket bucket : buckets) bucket.close();
        }
    }

    private record PreparedBucket(DynamicBucket bucket, PreparedMeshData meshData) {
        void close() {
            if (meshData != null) meshData.close();
        }
    }

    private static final class PreparedMeshData implements AutoCloseable {
        private ByteBuffer vertices;
        private ByteBuffer indices;
        private final int particleCount;

        private PreparedMeshData(ByteBuffer vertices, ByteBuffer indices, int particleCount) {
            this.vertices = vertices;
            this.indices = indices;
            this.particleCount = particleCount;
        }

        RemixMesh takeMesh(RemixMaterial material) {
            if (vertices == null || indices == null) {
                throw new IllegalStateException("Particle mesh payload already consumed");
            }
            RemixMeshSurface surface = new RemixMeshSurface(material)
                    .setVertexData(vertices, particleCount * VERTS_PER_PARTICLE)
                    .setIndexData(indices, particleCount * INDICES_PER_PARTICLE)
                    .setFlags(MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS);
            RemixMesh mesh = new RemixMesh(new RemixMeshSurface[]{surface});
            vertices = null;
            indices = null;
            return mesh;
        }

        @Override
        public void close() {
            DirectBufferPool.release(vertices);
            DirectBufferPool.release(indices);
            vertices = null;
            indices = null;
        }
    }

    private record BucketKey(long materialHandle, int categoryFlags, int blendKind) {}

    private record ParticleBillboardBasis(
            float r00, float r01,
            float r10, float r11,
            float r20, float r21,
            float nx, float ny, float nz) {

        static ParticleBillboardBasis fromCamera(float qx, float qy, float qz, float qw) {
            float xx = qx * qx, yy = qy * qy, zz = qz * qz;
            float xy = qx * qy, xz = qx * qz, yz = qy * qz;
            float wx = qw * qx, wy = qw * qy, wz = qw * qz;

            float r00 = 1f - 2f * (yy + zz);
            float r01 = 2f * (xy - wz);
            float r02 = 2f * (xz + wy);
            float r10 = 2f * (xy + wz);
            float r11 = 1f - 2f * (xx + zz);
            float r12 = 2f * (yz - wx);
            float r20 = 2f * (xz - wy);
            float r21 = 2f * (yz + wx);
            float r22 = 1f - 2f * (xx + yy);

            return new ParticleBillboardBasis(r00, r01, r10, r11, r20, r21, -r02, -r12, -r22);
        }
    }

    private static final class Slot {
        int index;
        long seenFrame;

        Slot(int index) {
            this.index = index;
        }
    }

    private final class DynamicBucket {
        final RemixMaterial material;
        final int categoryFlags;
        final int blendKind;
        Slot[] slots;
        Particle[] particlesBySlot;
        RemixMesh mesh;
        RemixMesh failedReplacement;
        ByteBuffer vertexBuffer;
        ByteBuffer indexBuffer;
        int capacity;
        int liveCount;
        boolean dirty;

        private DynamicBucket(RemixMaterial material, int categoryFlags, int blendKind,
                              RemixMesh mesh, ByteBuffer vertexBuffer,
                              ByteBuffer indexBuffer, int capacity) {
            this.material = material;
            this.categoryFlags = categoryFlags;
            this.blendKind = blendKind;
            this.mesh = mesh;
            this.vertexBuffer = vertexBuffer;
            this.indexBuffer = indexBuffer;
            this.capacity = capacity;
            this.slots = new Slot[capacity];
            this.particlesBySlot = new Particle[capacity];
            for (int i = 0; i < capacity; i++) {
                this.slots[i] = new Slot(i);
            }
        }

        int assignSlot(Particle particle, long frame) {
            RemixParticleStateAccess state = (RemixParticleStateAccess) particle;
            if (state.rtxremix$getParticleBucket() == this) {
                int slotIndex = state.rtxremix$getParticleSlot();
                if (slotIndex >= 0 && slotIndex < liveCount && particlesBySlot[slotIndex] == particle) {
                    Slot slot = slots[slotIndex];
                    slot.seenFrame = frame;
                    return slotIndex;
                }
            }

            if (liveCount >= capacity && !grow()) {
                return -1;
            }

            int slotIndex = liveCount++;
            Slot slot = slots[slotIndex];
            slot.index = slotIndex;
            slot.seenFrame = frame;
            particlesBySlot[slotIndex] = particle;
            state.rtxremix$setParticleBucket(this);
            state.rtxremix$setParticleSlot(slotIndex);
            newSlotsThisFrame++;
            return slotIndex;
        }

        void removeUnseen(long frame) {
            int slotIndex = 0;
            while (slotIndex < liveCount) {
                Slot slot = slots[slotIndex];
                if (slot.seenFrame == frame) {
                    slotIndex++;
                    continue;
                }

                removeSlot(slotIndex);
                removedSlotsThisFrame++;
                dirty = true;
                continue;
            }
        }

        private void removeSlot(int slotIndex) {
            Particle removedParticle = particlesBySlot[slotIndex];
            Slot removedSlot = slots[slotIndex];
            clearParticleState(removedParticle, slotIndex);

            int lastIndex = liveCount - 1;
            if (slotIndex != lastIndex) {
                Particle movedParticle = particlesBySlot[lastIndex];
                Slot movedSlot = slots[lastIndex];
                copyParticleQuad(vertexBuffer, lastIndex, slotIndex);
                particlesBySlot[slotIndex] = movedParticle;
                slots[slotIndex] = movedSlot;
                movedSlot.index = slotIndex;
                setParticleState(movedParticle, slotIndex);

                slots[lastIndex] = removedSlot;
                removedSlot.index = lastIndex;
            }

            particlesBySlot[lastIndex] = null;
            liveCount--;
        }

        private void setParticleState(Particle particle, int slotIndex) {
            if (particle == null) return;
            RemixParticleStateAccess state = (RemixParticleStateAccess) particle;
            state.rtxremix$setParticleBucket(this);
            state.rtxremix$setParticleSlot(slotIndex);
        }

        private void clearParticleState(Particle particle, int slotIndex) {
            if (particle == null) return;
            RemixParticleStateAccess state = (RemixParticleStateAccess) particle;
            if (state.rtxremix$getParticleBucket() == this
                    && state.rtxremix$getParticleSlot() == slotIndex) {
                state.rtxremix$setParticleBucket(null);
                state.rtxremix$setParticleSlot(-1);
            }
        }

        boolean grow() {
            if (capacity >= MAX_CAPACITY) {
                return false;
            }
            int newCapacity = Math.min(capacity * 2, MAX_CAPACITY);
            ByteBuffer newVertices = null;
            ByteBuffer newIndices = null;
            Slot[] newSlots;
            Particle[] newParticlesBySlot;
            try {
                newVertices = allocateVertexBuffer(newCapacity);
                ByteBuffer oldVertices = vertexBuffer.duplicate();
                oldVertices.position(0).limit(liveCount * VERTEX_BYTES_PER_PARTICLE);
                newVertices.position(0);
                newVertices.put(oldVertices);

                newIndices = allocateIndexBuffer(newCapacity);
                newSlots = Arrays.copyOf(slots, newCapacity);
                newParticlesBySlot = Arrays.copyOf(particlesBySlot, newCapacity);
                for (int i = capacity; i < newCapacity; i++) {
                    newSlots[i] = new Slot(i);
                }
            } catch (RuntimeException | Error e) {
                DirectBufferPool.release(newVertices);
                DirectBufferPool.release(newIndices);
                throw e;
            }

            ByteBuffer oldVertexBuffer = vertexBuffer;
            ByteBuffer oldIndexBuffer = indexBuffer;
            vertexBuffer = newVertices;
            indexBuffer = newIndices;
            slots = newSlots;
            particlesBySlot = newParticlesBySlot;
            capacity = newCapacity;
            capacityGrowsThisFrame++;
            dirty = true;
            DirectBufferPool.release(oldVertexBuffer);
            DirectBufferPool.release(oldIndexBuffer);
            return true;
        }

        void applyPreparedMesh(PreparedMeshData preparedMesh) {
            retireFailedReplacement();
            if (!dirty) {
                return;
            }
            if (liveCount == 0) {
                if (mesh != null && !mesh.isDeleted()) {
                    mesh.destroy();
                }
                mesh = null;
                dirty = false;
                return;
            }

            if (preparedMesh == null) {
                throw new IllegalStateException("Dirty particle bucket has no prepared mesh payload");
            }
            RemixMesh newMesh = createMesh(material, preparedMesh);
            if (newMesh == null) {
                return;
            }
            RemixMesh oldMesh = mesh;
            if (oldMesh != null && !oldMesh.isDeleted()) {
                try {
                    oldMesh.destroy();
                } catch (RuntimeException | Error replacementFailure) {
                    try {
                        newMesh.destroy();
                    } catch (RuntimeException | Error cleanupFailure) {
                        failedReplacement = newMesh;
                        replacementFailure.addSuppressed(cleanupFailure);
                    }
                    throw replacementFailure;
                }
            }
            mesh = newMesh;
            dirty = false;
        }

        private void retireFailedReplacement() {
            RemixMesh replacement = failedReplacement;
            if (replacement == null) return;
            if (!replacement.isDeleted()) replacement.destroy();
            failedReplacement = null;
        }

        void destroy() {
            Throwable nativeFailure = null;
            try {
                retireFailedReplacement();
            } catch (RuntimeException | Error e) {
                nativeFailure = e;
            }
            try {
                if (mesh != null && !mesh.isDeleted()) {
                    mesh.destroy();
                }
            } catch (RuntimeException | Error e) {
                if (nativeFailure == null) nativeFailure = e;
                else nativeFailure.addSuppressed(e);
            }
            for (int i = 0; i < liveCount; i++) {
                clearParticleState(particlesBySlot[i], i);
            }
            DirectBufferPool.release(vertexBuffer);
            DirectBufferPool.release(indexBuffer);
            vertexBuffer = null;
            indexBuffer = null;
            Arrays.fill(particlesBySlot, null);
            liveCount = 0;
            if (nativeFailure instanceof RuntimeException e) throw e;
            if (nativeFailure instanceof Error e) throw e;
        }
    }
}
