/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.minecraft.asset.RemixLight;
import com.nvidia.rtxremix.minecraft.asset.RemixLightStore;
import com.nvidia.rtxremix.minecraft.light.BlockLightInfoFactory;
import com.nvidia.rtxremix.minecraft.light.LightEntry;
import com.nvidia.rtxremix.minecraft.light.LightPowerPolicy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical, budgeted reconciliation of section light descriptions with
 * retained native terrain lights.
 */
public final class TerrainLightReconciler {
    private static final int MAX_LIGHT_OPS_PER_FRAME = Math.max(
            1, Integer.getInteger("rtxremix.chunk.maxLightOpsPerFrame", 32));
    private static final long MAX_PENDING_LIGHT_OPS = Math.max(
            256L, Long.getLong("rtxremix.chunk.maxPendingLightOps", 4096L));

    private final TerrainState terrain;
    private final Resolver resolver;
    private final Reporter reporter;
    private final ArrayDeque<WorkItem> workQueue = new ArrayDeque<WorkItem>();
    private final Map<SectionKey, ReconcileTask> activeTasks =
            new LinkedHashMap<SectionKey, ReconcileTask>();
    private long pendingOps;
    private boolean backpressured;
    private long lastFailureLogNanos;

    public TerrainLightReconciler(
            TerrainState terrain, Resolver resolver, Reporter reporter) {
        this.terrain = terrain;
        this.resolver = resolver;
        this.reporter = reporter;
    }

    public void reconcile(
            int chunkX,
            int sectionY,
            int chunkZ,
            TerrainState.SectionEntry oldEntry,
            TerrainState.SectionEntry newEntry,
            List<LightEntry> incomingEntries) {
        SectionKey section = new SectionKey(chunkX, sectionY, chunkZ);
        cancel(chunkX, sectionY, chunkZ);
        enqueue(new ReconcileTask(section, oldEntry, newEntry, incomingEntries));
    }

    public void retire(List<RemixLight> lights) {
        if (!lights.isEmpty()) {
            enqueue(new RetireTask(lights));
        }
    }

    public void drain(long deadlineNanos) {
        int nativeCalls = 0;
        int inspected = 0;
        while (true) {
            WorkItem task = workQueue.peekFirst();
            if (task == null) {
                return;
            }
            int nextCost = Math.max(1, task.nextNativeCallCost());
            if (inspected > 0
                    && (inspected >= MAX_LIGHT_OPS_PER_FRAME
                    || System.nanoTime() >= deadlineNanos)) {
                return;
            }
            if (nativeCalls > 0
                    && nativeCalls + nextCost > MAX_LIGHT_OPS_PER_FRAME) {
                return;
            }
            inspected++;

            try {
                task.runOne();
            } catch (RuntimeException failure) {
                workQueue.removeFirst();
                workQueue.addLast(task);
                long now = System.nanoTime();
                if (lastFailureLogNanos == 0
                        || now - lastFailureLogNanos >= 1_000_000_000L) {
                    lastFailureLogNanos = now;
                    reporter.nativeOperationFailed(failure);
                }
                return;
            }

            pendingOps = Math.max(0, pendingOps - 1);
            nativeCalls += nextCost;
            if (task.remaining() == 0) {
                workQueue.removeFirst();
                task.onFinished();
            }
        }
    }

    public boolean isBackpressured() {
        if (!backpressured) {
            backpressured = pendingOps >= MAX_PENDING_LIGHT_OPS;
        } else if (pendingOps <= MAX_PENDING_LIGHT_OPS / 2) {
            backpressured = false;
        }
        return backpressured;
    }

    public void cancel(int chunkX, int sectionY, int chunkZ) {
        SectionKey section = new SectionKey(chunkX, sectionY, chunkZ);
        ReconcileTask task = activeTasks.remove(section);
        if (task != null && workQueue.remove(task)) {
            pendingOps = Math.max(0, pendingOps - task.remaining());
        }
    }

    public void clear() {
        for (WorkItem task : workQueue) {
            task.destroyRemainingOnClear();
        }
        workQueue.clear();
        activeTasks.clear();
        pendingOps = 0;
        backpressured = false;
        lastFailureLogNanos = 0;
    }

    public long pendingOps() {
        return pendingOps;
    }

    public int taskCount() {
        return workQueue.size();
    }

    private void enqueue(WorkItem task) {
        int remaining = task.remaining();
        if (remaining == 0) {
            task.onFinished();
            return;
        }
        workQueue.addLast(task);
        pendingOps += remaining;
        if (task instanceof ReconcileTask) {
            ReconcileTask reconcile = (ReconcileTask) task;
            activeTasks.put(reconcile.section, reconcile);
        }
    }

    private final class ReconcileTask implements WorkItem {
        private final SectionKey section;
        private final TerrainState.SectionEntry entry;
        private final List<Mutation> mutations;
        private int cursor;

        private ReconcileTask(
                SectionKey section,
                TerrainState.SectionEntry oldEntry,
                TerrainState.SectionEntry newEntry,
                List<LightEntry> incomingEntries) {
            this.section = section;
            this.entry = newEntry;

            Map<BlockLightKey, LightEntry> incoming =
                    new LinkedHashMap<BlockLightKey, LightEntry>();
            for (LightEntry light : incomingEntries) {
                incoming.put(light.key(), light);
            }
            mutations = new ArrayList<Mutation>(
                    incoming.size() + (oldEntry != null ? oldEntry.lightCount() : 0));

            if (oldEntry != null) {
                boolean transferOwnership = oldEntry != newEntry;
                for (Map.Entry<BlockLightKey, TerrainLight> old
                        : oldEntry.lights().entrySet()) {
                    BlockLightKey key = old.getKey();
                    TerrainLight record = old.getValue();
                    RemixLight existing = record.light;
                    LightEntry desired = incoming.remove(key);
                    Integer oldFingerprint = oldEntry.lightFingerprints().get(key);

                    // Keep the last committed native light visible while its
                    // update/removal waits for a render-thread budget slot. The
                    // whole record moves: a fingerprint-equal light gets no
                    // UPDATE, so nothing else would carry its position and
                    // power over.
                    if (transferOwnership) {
                        newEntry.putLight(key, record);
                        if (oldFingerprint != null) {
                            newEntry.lightFingerprints().put(key, oldFingerprint);
                        }
                    }

                    if (desired == null) {
                        mutations.add(new Mutation(
                                MutationKind.REMOVE, key, null, existing, 0));
                    } else {
                        int fingerprint = fingerprint(desired);
                        if (oldFingerprint == null
                                || oldFingerprint.intValue() != fingerprint) {
                            mutations.add(new Mutation(
                                    MutationKind.UPDATE,
                                    key,
                                    desired,
                                    existing,
                                    fingerprint));
                        } else {
                            newEntry.lightFingerprints().put(key, fingerprint);
                        }
                    }
                }
                if (transferOwnership) {
                    oldEntry.lights().clear();
                    oldEntry.lightEntries().clear();
                    oldEntry.lightFingerprints().clear();
                }
            }

            for (Map.Entry<BlockLightKey, LightEntry> added : incoming.entrySet()) {
                mutations.add(new Mutation(
                        MutationKind.CREATE,
                        added.getKey(),
                        added.getValue(),
                        null,
                        fingerprint(added.getValue())));
            }
        }

        @Override
        public int remaining() {
            return mutations.size() - cursor;
        }

        @Override
        public int nextNativeCallCost() {
            return mutations.get(cursor).nativeCallCost();
        }

        @Override
        public void runOne() {
            Mutation mutation = mutations.get(cursor);
            if (activeTasks.get(section) != this
                    || !terrain.isCurrentSectionEntry(
                            section.chunkX, section.sectionY, section.chunkZ, entry)) {
                cursor++;
                return;
            }
            try {
                switch (mutation.kind) {
                    case CREATE:
                        create(mutation);
                        break;
                    case UPDATE:
                        update(mutation);
                        break;
                    case REMOVE:
                        remove(mutation);
                        break;
                    default:
                        throw new IllegalStateException("Unknown light mutation");
                }
            } catch (RuntimeException failure) {
                mutations.add(mutations.remove(cursor));
                throw failure;
            }
            cursor++;
        }

        private void create(Mutation mutation) {
            if (entry.lights().containsKey(mutation.key)) {
                return;
            }
            LightEntry incoming = mutation.incoming;
            Radiance radiance = resolver.radiance(incoming);
            LightInfo info = BlockLightInfoFactory.build(
                    incoming, radiance.r(), radiance.g(), radiance.b());
            RemixLight light = new RemixLight(info, null);
            RemixLightStore.get().create(light);
            TerrainLight record = new TerrainLight(light);
            record.place(
                    incoming.x(),
                    incoming.y(),
                    incoming.z(),
                    LightPowerPolicy.of(
                            incoming.shape(),
                            incoming.radius(),
                            incoming.axisLength(),
                            incoming.xSize(),
                            incoming.ySize(),
                            radiance.r(),
                            radiance.g(),
                            radiance.b()));
            entry.putLight(mutation.key, record);
            entry.lightFingerprints().put(mutation.key, mutation.fingerprint);
            terrain.adjustSectionLightCount(
                    section.chunkX, section.sectionY, section.chunkZ, entry, 1);
        }

        private void update(Mutation mutation) {
            TerrainLight record = entry.lights().get(mutation.key);
            if (record == null || record.light != mutation.existing) {
                return;
            }
            LightEntry incoming = mutation.incoming;
            Radiance radiance = resolver.radiance(incoming);
            LightInfo info = BlockLightInfoFactory.build(
                    incoming, radiance.r(), radiance.g(), radiance.b());
            RemixLightStore.get().update(mutation.existing, info);
            record.place(
                    incoming.x(),
                    incoming.y(),
                    incoming.z(),
                    LightPowerPolicy.of(
                            incoming.shape(),
                            incoming.radius(),
                            incoming.axisLength(),
                            incoming.xSize(),
                            incoming.ySize(),
                            radiance.r(),
                            radiance.g(),
                            radiance.b()));
            entry.lightFingerprints().put(mutation.key, mutation.fingerprint);
        }

        private void remove(Mutation mutation) {
            TerrainLight record = entry.lights().get(mutation.key);
            if (record == null || record.light != mutation.existing) {
                return;
            }
            if (!mutation.existing.isDeleted()) {
                mutation.existing.destroy();
            }
            if (entry.removeLight(mutation.key) != null) {
                terrain.adjustSectionLightCount(
                        section.chunkX, section.sectionY, section.chunkZ, entry, -1);
            }
        }

        @Override
        public void onFinished() {
            activeTasks.remove(section, this);
        }

        private int fingerprint(LightEntry light) {
            return Objects.hash(
                    light.sourceToken(),
                    light.key().ruleId(),
                    light.key().lightIndex(),
                    light.lightLevel(),
                    light.shape(),
                    bits(light.x()), bits(light.y()), bits(light.z()),
                    bits(light.colorR()), bits(light.colorG()), bits(light.colorB()),
                    light.hasColor(),
                    bits(light.intensity()), light.hasIntensity(),
                    bits(light.radius()),
                    bits(light.axisX()), bits(light.axisY()), bits(light.axisZ()),
                    bits(light.axisLength()),
                    bits(light.xAxisX()), bits(light.xAxisY()), bits(light.xAxisZ()),
                    bits(light.xSize()),
                    bits(light.yAxisX()), bits(light.yAxisY()), bits(light.yAxisZ()),
                    bits(light.ySize()),
                    bits(light.directionX()), bits(light.directionY()),
                    bits(light.directionZ()),
                    light.hasShaping(),
                    bits(light.shapingDirectionX()),
                    bits(light.shapingDirectionY()),
                    bits(light.shapingDirectionZ()),
                    bits(light.coneAngleDegrees()),
                    bits(light.coneSoftness()),
                    bits(light.focusExponent()),
                    bits(light.volumetricRadianceScale()));
        }
    }

    private static int bits(float value) {
        return Float.floatToIntBits(value);
    }

    private static final class RetireTask implements WorkItem {
        private final List<RemixLight> lights;
        private int cursor;

        private RetireTask(List<RemixLight> lights) {
            this.lights = lights;
        }

        @Override
        public int remaining() {
            return lights.size() - cursor;
        }

        @Override
        public void runOne() {
            RemixLight light = lights.get(cursor);
            try {
                if (!light.isDeleted()) {
                    light.destroy();
                }
            } catch (RuntimeException failure) {
                lights.add(lights.remove(cursor));
                throw failure;
            }
            cursor++;
        }

        @Override
        public void destroyRemainingOnClear() {
            while (cursor < lights.size()) {
                runOne();
            }
        }
    }

    private interface WorkItem {
        int remaining();
        default int nextNativeCallCost() { return 1; }
        void runOne();
        default void onFinished() {}
        default void destroyRemainingOnClear() {}
    }

    private static final class SectionKey {
        private final int chunkX;
        private final int sectionY;
        private final int chunkZ;

        private SectionKey(int chunkX, int sectionY, int chunkZ) {
            this.chunkX = chunkX;
            this.sectionY = sectionY;
            this.chunkZ = chunkZ;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof SectionKey)) {
                return false;
            }
            SectionKey other = (SectionKey) object;
            return chunkX == other.chunkX
                    && sectionY == other.sectionY
                    && chunkZ == other.chunkZ;
        }

        @Override
        public int hashCode() {
            int result = chunkX;
            result = 31 * result + sectionY;
            return 31 * result + chunkZ;
        }
    }

    private enum MutationKind {
        CREATE,
        UPDATE,
        REMOVE
    }

    private static final class Mutation {
        private final MutationKind kind;
        private final BlockLightKey key;
        private final LightEntry incoming;
        private final RemixLight existing;
        private final int fingerprint;

        private Mutation(
                MutationKind kind,
                BlockLightKey key,
                LightEntry incoming,
                RemixLight existing,
                int fingerprint) {
            this.kind = kind;
            this.key = key;
            this.incoming = incoming;
            this.existing = existing;
            this.fingerprint = fingerprint;
        }

        private int nativeCallCost() {
            return kind == MutationKind.UPDATE ? 2 : 1;
        }
    }

    public interface Resolver {
        Radiance radiance(LightEntry entry);
    }

    public interface Reporter {
        void nativeOperationFailed(RuntimeException failure);
    }

    public static final class Radiance {
        private final float r;
        private final float g;
        private final float b;

        public Radiance(float r, float g, float b) {
            this.r = r;
            this.g = g;
            this.b = b;
        }

        public float r() { return r; }
        public float g() { return g; }
        public float b() { return b; }
    }
}
