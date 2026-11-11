/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.minecraft.asset.RemixInstance;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/**
 * Render-thread queue for paced terrain instance and mesh retirement.
 *
 * <p>An instance is removed before its mesh, and the mesh survives at least
 * one scene-frame boundary after that removal. This keeps native scene work
 * bounded without releasing geometry that may still be referenced by the
 * preceding submitted frame.
 */
public final class TerrainAssetRetirementQueue {

    private static final long MIB = 1024L * 1024L;
    private static final long RETIRE_TIME_BUDGET_NS = Math.max(250_000L,
            Long.getLong("rtxremix.chunk.meshRetireBudgetNs", 750_000L));
    // Count thresholds are sized for flight-scale unload bursts; the byte
    // thresholds are the real memory bound.
    private static final int RETIRE_SOFT_COUNT = Math.max(4,
            Integer.getInteger("rtxremix.chunk.meshRetireSoftCount", 256));
    private static final int RETIRE_HARD_COUNT = Math.max(RETIRE_SOFT_COUNT,
            Integer.getInteger("rtxremix.chunk.meshRetireHardCount", 512));
    private static final long RETIRE_SOFT_BYTES = Math.max(64L * MIB,
            Long.getLong("rtxremix.chunk.meshRetireSoftBytes", 256L * MIB));
    private static final long RETIRE_HARD_BYTES = Math.max(RETIRE_SOFT_BYTES,
            Long.getLong("rtxremix.chunk.meshRetireHardBytes", 512L * MIB));

    private static final class RetiredInstance {
        private final RemixInstance instance;
        private final RemixMesh mesh;
        private final long estimatedBytes;

        RetiredInstance(RemixInstance instance, RemixMesh mesh, long estimatedBytes) {
            this.instance = instance;
            this.mesh = mesh;
            this.estimatedBytes = estimatedBytes;
        }

        RemixInstance instance() { return instance; }
        RemixMesh mesh() { return mesh; }
        long estimatedBytes() { return estimatedBytes; }
    }

    private static final class RetiredMesh {
        private final RemixMesh mesh;
        private final long eligibleFrame;
        private final long estimatedBytes;

        RetiredMesh(RemixMesh mesh, long eligibleFrame, long estimatedBytes) {
            this.mesh = mesh;
            this.eligibleFrame = eligibleFrame;
            this.estimatedBytes = estimatedBytes;
        }

        RemixMesh mesh() { return mesh; }
        long eligibleFrame() { return eligibleFrame; }
        long estimatedBytes() { return estimatedBytes; }
    }

    private final ArrayDeque<RetiredInstance> retiredInstances = new ArrayDeque<>();
    private final ArrayDeque<RetiredMesh> retiredMeshes = new ArrayDeque<>();
    private final LongSupplier nanoTime;
    private final TerrainNativeCallStats nativeCalls;
    private long retirementFrame;
    private long retiredMeshBytes;
    private boolean retirementBackpressured;
    private boolean lastDrainBudgetHit;

    public TerrainAssetRetirementQueue(TerrainNativeCallStats nativeCalls) {
        this(System::nanoTime, nativeCalls);
    }

    TerrainAssetRetirementQueue(LongSupplier nanoTime, TerrainNativeCallStats nativeCalls) {
        this.nanoTime = nanoTime;
        this.nativeCalls = nativeCalls;
    }

    /** Queues an unreferenced instance and its mesh for paced native retirement. */
    public void retire(RemixInstance instance) {
        if (instance == null) return;

        RemixMesh mesh = instance.getMesh();
        if (instance.isDeleted()) {
            retire(mesh);
            return;
        }

        long estimatedBytes = mesh != null && !mesh.isDeleted()
                ? mesh.estimatedNativeBytes()
                : 0;
        retiredInstances.addLast(new RetiredInstance(instance, mesh, estimatedBytes));
        retiredMeshBytes += estimatedBytes;
    }

    /** Queues an unreferenced mesh for paced native retirement. */
    public void retire(RemixMesh mesh) {
        if (mesh == null || mesh.isDeleted()) return;

        long estimatedBytes = mesh.estimatedNativeBytes();
        retiredMeshes.addLast(new RetiredMesh(
                mesh, retirementFrame + 1, estimatedBytes));
        retiredMeshBytes += estimatedBytes;
    }

    /** Drains this frame's allowed native work and advances the scene-frame clock. */
    public void drainFrame() {
        drain(false, Long.MAX_VALUE);
        retirementFrame++;
    }

    /**
     * Drains without beginning another native operation after the caller's
     * shared terrain deadline. One atomic operation may still run so retirement
     * cannot starve behind other terrain work.
     */
    public void drainFrame(long sharedDeadline) {
        drain(false, sharedDeadline);
        retirementFrame++;
    }

    /** Synchronously destroys all queued assets and resets queue state. */
    public void clear() {
        drain(true, Long.MAX_VALUE);
        retirementFrame = 0;
        retiredMeshBytes = 0;
        retirementBackpressured = false;
    }

    public int pendingRetirements() {
        return retiredInstances.size() + retiredMeshes.size();
    }

    public long pendingRetirementBytes() {
        return retiredMeshBytes;
    }

    public boolean lastDrainBudgetHit() {
        return lastDrainBudgetHit;
    }

    /**
     * Byte-only gate for new terrain installs. Item counts never pause
     * presentation; only genuine native memory pressure does.
     */
    public boolean retirementBytesBackpressured() {
        return retiredMeshBytes >= RETIRE_HARD_BYTES;
    }

    /** Hysteretic signal for pausing producers while native retirement catches up. */
    public boolean retirementBackpressured() {
        int pendingRetirements = pendingRetirements();
        if (!retirementBackpressured) {
            retirementBackpressured = pendingRetirements >= RETIRE_SOFT_COUNT
                    || retiredMeshBytes >= RETIRE_SOFT_BYTES;
        } else if (pendingRetirements <= RETIRE_SOFT_COUNT / 2
                && retiredMeshBytes <= RETIRE_SOFT_BYTES / 2) {
            retirementBackpressured = false;
        }
        return retirementBackpressured;
    }

    private void drain(boolean destroyAll, long sharedDeadline) {
        lastDrainBudgetHit = false;
        long deadline = Math.min(
                nanoTime.getAsLong() + RETIRE_TIME_BUDGET_NS, sharedDeadline);
        boolean pressure = pendingRetirements() >= RETIRE_HARD_COUNT
                || retiredMeshBytes >= RETIRE_HARD_BYTES;
        long pressureByteQuota = pressure
                ? Math.max(MIB, (retiredMeshBytes - (RETIRE_SOFT_BYTES / 2) + 59) / 60)
                : 0;
        int destroyed = 0;
        long destroyedBytes = 0;
        boolean preferInstance = true;

        while (true) {
            RetiredInstance retiredInstance = retiredInstances.peekFirst();
            RetiredMesh retiredMesh = retiredMeshes.peekFirst();
            boolean meshEligible = retiredMesh != null
                    && (destroyAll || retiredMesh.eligibleFrame() <= retirementFrame);
            if (retiredInstance == null && !meshEligible) return;

            if (!destroyAll && destroyed > 0) {
                if (nanoTime.getAsLong() >= deadline) {
                    lastDrainBudgetHit = true;
                    return;
                }
                // Under pressure the byte quota spreads recovery over ~60
                // frames and the time budget bounds each frame; a count cap
                // here would make the drain slowest exactly when it is fullest.
                if (pressure && destroyedBytes >= pressureByteQuota) return;
            }

            if (retiredInstance != null && (!meshEligible || preferInstance)) {
                RemixInstance instance = retiredInstance.instance();
                if (!instance.isDeleted()) {
                    instance.destroy();
                    nativeCalls.instanceRemoved();
                    destroyed++;
                }
                retiredInstances.removeFirst();

                RemixMesh mesh = retiredInstance.mesh();
                if (mesh != null && !mesh.isDeleted()) {
                    retiredMeshes.addLast(new RetiredMesh(
                            mesh, retirementFrame + 1, retiredInstance.estimatedBytes()));
                } else {
                    retiredMeshBytes = Math.max(0,
                            retiredMeshBytes - retiredInstance.estimatedBytes());
                }
                preferInstance = false;
                continue;
            }

            if (!retiredMesh.mesh().isDeleted()) {
                retiredMesh.mesh().destroy();
                nativeCalls.meshDestroyed();
                destroyed++;
                destroyedBytes += retiredMesh.estimatedBytes();
            }
            retiredMeshes.removeFirst();
            retiredMeshBytes = Math.max(0,
                    retiredMeshBytes - retiredMesh.estimatedBytes());
            preferInstance = true;
        }
    }
}
