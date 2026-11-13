/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import com.nvidia.rtxremix.minecraft.profiling.PerformanceTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

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
    private static final int VERTS_PER_PARTICLE = ParticleQuadWriter.VERTICES_PER_PARTICLE;
    private static final int INDICES_PER_PARTICLE = ParticleQuadWriter.INDICES_PER_PARTICLE;
    private static final int STRIDE = ParticleQuadWriter.STRIDE;
    private static final int VERTEX_BYTES_PER_PARTICLE =
            ParticleQuadWriter.VERTEX_BYTES_PER_PARTICLE;

    private static final float[] IDENTITY_TRANSFORM = {
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f
    };

    private static final int BLEND_PARTICLE = 1;
    private static final int BLEND_TERRAIN = 2;

    private final RemixAPI api;
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

    public RemixParticleRenderer(RemixAPI api) {
        this.api = api;
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

    /** Starts one worker-side particle capture. */
    public long beginPreparation() {
        long frame = ++frameCounter;
        resetFrameStats();
        return frame;
    }

    /** Resolves one shared-material bucket before the host walks its particles. */
    public CaptureBucket captureBucket(
            RemixMaterial material, boolean terrain) {
        if (material == null || !material.isAlive()) return null;
        int categoryFlags = terrain ? 0 : InstanceCategoryBit.PARTICLE;
        int blendKind = terrain ? BLEND_TERRAIN : BLEND_PARTICLE;
        DynamicBucket bucket = getOrCreateBucket(
                new BucketKey(material.getHandle(), categoryFlags, blendKind),
                material, categoryFlags, blendKind);
        return bucket == null ? null : bucket.captureBucket;
    }

    /**
     * Captures one host particle directly into the canonical bucket storage.
     * The host supplies only values read from the active Minecraft version.
     */
    public void capture(
            long frame,
            CaptureBucket captureBucket,
            RemixParticleStateAccess particle,
            double previousX,
            double previousY,
            double previousZ,
            double x,
            double y,
            double z,
            float previousAngle,
            float angle,
            float scale,
            float tickDelta,
            ParticleQuadWriter.Basis billboardBasis,
            float u0,
            float v0,
            float u1,
            float v1,
            int color,
            int boundsMin,
            int boundsMax) {
        if (captureBucket == null) return;
        DynamicBucket bucket = captureBucket.bucket;

        int slot = bucket.assignSlot(particle, frame);
        if (slot < 0) return;
        ParticleQuadWriter.write(
                bucket.vertexBuffer,
                slot,
                previousX,
                previousY,
                previousZ,
                x,
                y,
                z,
                previousAngle,
                angle,
                scale,
                tickDelta,
                billboardBasis,
                u0,
                v0,
                u1,
                v1,
                color,
                boundsMin,
                boundsMax);
        bucket.dirty = true;
        particlesThisFrame++;
    }

    public PreparedFrame finishPreparation(long frame) {
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

        PerformanceTimer.plot("Java.Particles.Count", preparedFrame.particleCount);
        PerformanceTimer.plot("Java.Particles.MeshBuckets", draws);
        PerformanceTimer.plot("Java.Particles.DynamicQuads", activeQuads);
        PerformanceTimer.plot("Java.Particles.NewSlots", preparedFrame.newSlotCount);
        PerformanceTimer.plot("Java.Particles.RemovedSlots", preparedFrame.removedSlotCount);
        PerformanceTimer.plot("Java.Particles.CapacityGrows", preparedFrame.capacityGrowCount);
        PerformanceTimer.plot("Java.Particles.Capacity", preparedFrame.capacity);
        PerformanceTimer.plot("Java.Particles.MeshUpdates", preparedFrame.meshUpdateCount);
    }

    private void resetFrameStats() {
        particlesThisFrame = 0;
        newSlotsThisFrame = 0;
        removedSlotsThisFrame = 0;
        capacityGrowsThisFrame = 0;
        meshUpdatesThisFrame = 0;
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

    private static ByteBuffer allocateVertexBuffer(int capacity) {
        ByteBuffer buffer = DirectBufferPool.acquire(capacity * VERTEX_BYTES_PER_PARTICLE);
        for (int i = 0; i < capacity; i++) {
            ParticleQuadWriter.writeDegenerate(buffer, i);
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
            this.buckets = Collections.unmodifiableList(
                    new ArrayList<PreparedBucket>(buckets));
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

    private static final class PreparedBucket {
        private final DynamicBucket bucket;
        private final PreparedMeshData meshData;

        private PreparedBucket(
                DynamicBucket bucket, PreparedMeshData meshData) {
            this.bucket = bucket;
            this.meshData = meshData;
        }

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

    private static final class BucketKey {
        private final long materialHandle;
        private final int categoryFlags;
        private final int blendKind;

        private BucketKey(
                long materialHandle, int categoryFlags, int blendKind) {
            this.materialHandle = materialHandle;
            this.categoryFlags = categoryFlags;
            this.blendKind = blendKind;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof BucketKey)) return false;
            BucketKey other = (BucketKey) object;
            return materialHandle == other.materialHandle
                    && categoryFlags == other.categoryFlags
                    && blendKind == other.blendKind;
        }

        @Override
        public int hashCode() {
            int result = (int) (materialHandle ^ (materialHandle >>> 32));
            result = 31 * result + categoryFlags;
            result = 31 * result + blendKind;
            return result;
        }
    }

    /** Opaque, allocation-stable handle used while a host captures one sheet. */
    public static final class CaptureBucket {
        private final DynamicBucket bucket;

        private CaptureBucket(DynamicBucket bucket) {
            this.bucket = bucket;
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
        final CaptureBucket captureBucket;
        Slot[] slots;
        RemixParticleStateAccess[] particlesBySlot;
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
            this.captureBucket = new CaptureBucket(this);
            this.mesh = mesh;
            this.vertexBuffer = vertexBuffer;
            this.indexBuffer = indexBuffer;
            this.capacity = capacity;
            this.slots = new Slot[capacity];
            this.particlesBySlot = new RemixParticleStateAccess[capacity];
            for (int i = 0; i < capacity; i++) {
                this.slots[i] = new Slot(i);
            }
        }

        int assignSlot(RemixParticleStateAccess particle, long frame) {
            if (particle.rtxremix$getParticleBucket() == this) {
                int slotIndex = particle.rtxremix$getParticleSlot();
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
            particle.rtxremix$setParticleBucket(this);
            particle.rtxremix$setParticleSlot(slotIndex);
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
            RemixParticleStateAccess removedParticle = particlesBySlot[slotIndex];
            Slot removedSlot = slots[slotIndex];
            clearParticleState(removedParticle, slotIndex);

            int lastIndex = liveCount - 1;
            if (slotIndex != lastIndex) {
                RemixParticleStateAccess movedParticle = particlesBySlot[lastIndex];
                Slot movedSlot = slots[lastIndex];
                ParticleQuadWriter.copy(vertexBuffer, lastIndex, slotIndex);
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

        private void setParticleState(
                RemixParticleStateAccess particle, int slotIndex) {
            if (particle == null) return;
            particle.rtxremix$setParticleBucket(this);
            particle.rtxremix$setParticleSlot(slotIndex);
        }

        private void clearParticleState(
                RemixParticleStateAccess particle, int slotIndex) {
            if (particle == null) return;
            if (particle.rtxremix$getParticleBucket() == this
                    && particle.rtxremix$getParticleSlot() == slotIndex) {
                particle.rtxremix$setParticleBucket(null);
                particle.rtxremix$setParticleSlot(-1);
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
            RemixParticleStateAccess[] newParticlesBySlot;
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
            if (nativeFailure instanceof RuntimeException) {
                throw (RuntimeException) nativeFailure;
            }
            if (nativeFailure instanceof Error) {
                throw (Error) nativeFailure;
            }
        }
    }
}
