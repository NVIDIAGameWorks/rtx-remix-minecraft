/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.Transform;
import com.nvidia.rtxremix.core.terrain.TerrainCategory;
import com.nvidia.rtxremix.core.terrain.TerrainMaterialSet;
import com.nvidia.rtxremix.core.terrain.TerrainProfile;
import com.nvidia.rtxremix.core.terrain.TerrainSectionData;
import com.nvidia.rtxremix.core.terrain.TerrainSectionSnapshot;
import com.nvidia.rtxremix.minecraft.asset.RemixInstance;
import com.nvidia.rtxremix.minecraft.asset.RemixInstanceStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionMeshData;
import com.nvidia.rtxremix.minecraft.util.LongObjectMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Loader-neutral owner of chunk-column mesh building and retained publication.
 *
 * <p>Workers only merge CPU buffers. All native creation, activation, rollback,
 * resurrection, and retirement remains on the calling render thread.
 */
public final class ColumnPublicationOrchestrator {
    private static final int DEFAULT_MESH_BUILD_THREADS = Math.max(1,
            Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
    private static final int MESH_BUILD_THREADS = Math.max(1,
            Integer.getInteger("rtxremix.chunk.meshBuildThreads", DEFAULT_MESH_BUILD_THREADS));
    private static final int MAX_PENDING = Math.max(2, Math.min(16,
            Integer.getInteger("rtxremix.chunk.maxPendingBuilds", MESH_BUILD_THREADS * 2)));
    private static final long DEFAULT_MAX_PENDING_MESH_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_PENDING_MESH_BYTES = Math.max(64L * 1024L * 1024L,
            Long.getLong("rtxremix.chunk.maxPendingMeshBytes", DEFAULT_MAX_PENDING_MESH_BYTES));
    private static final long INSTALL_TIME_BUDGET_NS = Math.max(250_000L,
            Long.getLong("rtxremix.chunk.installBudgetNs", 2_000_000L));
    private static final int MAX_INSTALLED_COLUMNS_PER_FRAME = Math.max(1,
            Integer.getInteger("rtxremix.chunk.maxInstalledColumnsPerFrame", MAX_PENDING));
    private static final int COLUMN_STREAM_SETTLE_FRAMES = Math.max(0,
            Integer.getInteger("rtxremix.chunk.columnStreamSettleFrames", 4));
    private static final int COLUMN_STREAM_MAX_WAIT_FRAMES = Math.max(
            COLUMN_STREAM_SETTLE_FRAMES,
            Integer.getInteger("rtxremix.chunk.columnStreamMaxWaitFrames", 30));
    private static final int DIAGNOSTICS_REFRESH_FRAMES = 8;

    private final TerrainState terrain;
    private final BlockMeshBuilder meshBuilder;
    private final Reporter reporter;
    private final LongObjectMap<ColumnRenderState> renderColumns =
            new LongObjectMap<ColumnRenderState>();
    private final TerrainNativeCallStats nativeCallStats = new TerrainNativeCallStats();
    private final TerrainAssetRetirementQueue retirementQueue =
            new TerrainAssetRetirementQueue(nativeCallStats);
    private final ArrayList<ColumnRenderState> dirtyBuildScratch =
            new ArrayList<ColumnRenderState>();
    private final ConcurrentLinkedQueue<MeshBuildResult> readyQueue =
            new ConcurrentLinkedQueue<MeshBuildResult>();
    private final AtomicInteger pendingResults = new AtomicInteger();
    private final AtomicLong pendingMeshBytes = new AtomicLong();
    private final ExecutorService meshBuildExecutor;

    private MeshInstallCursor activeInstall;
    private int epoch;
    private long frame;
    private int retainedPageCount;
    private boolean retainedInstancesHidden;
    private int diagnosticsRefreshCountdown;
    private Diagnostics diagnostics = Diagnostics.EMPTY;

    public ColumnPublicationOrchestrator(TerrainState terrain) {
        this(terrain, new BlockMeshBuilder(), Reporter.NONE);
    }

    public ColumnPublicationOrchestrator(TerrainState terrain, Reporter reporter) {
        this(terrain, new BlockMeshBuilder(), reporter);
    }

    ColumnPublicationOrchestrator(
            TerrainState terrain, BlockMeshBuilder meshBuilder, Reporter reporter) {
        this.terrain = terrain;
        this.meshBuilder = meshBuilder;
        this.reporter = reporter == null ? Reporter.NONE : reporter;
        final AtomicInteger threadNumber = new AtomicInteger();
        meshBuildExecutor = Executors.newFixedThreadPool(MESH_BUILD_THREADS,
                new java.util.concurrent.ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable task) {
                        Thread thread = new Thread(task,
                                "RTX Remix mesh builder " + threadNumber.incrementAndGet());
                        thread.setDaemon(true);
                        thread.setPriority(Math.max(
                                Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                        return thread;
                    }
                });
    }

    public void beginFrame(long terrainFrame) {
        frame = terrainFrame;
        nativeCallStats.beginFrame();
    }

    public TerrainNativeCallStats nativeCallStats() {
        return nativeCallStats;
    }

    public int pendingBuilds() {
        return pendingResults.get();
    }

    public long pendingMeshBytes() {
        return pendingMeshBytes.get();
    }

    public int readyMeshes() {
        return countCompletedBuilds();
    }

    public int pendingRetirements() {
        return retirementQueue.pendingRetirements();
    }

    public long pendingRetirementBytes() {
        return retirementQueue.pendingRetirementBytes();
    }

    public boolean retirementBackpressured() {
        return retirementQueue.retirementBackpressured();
    }

    public void drainRetirements() {
        retirementQueue.drainFrame();
    }

    public void markColumnDirty(int cx, int cz, int categoryMask, boolean immediate) {
        markDirty(cx, cz, categoryMask, immediate);
    }

    /**
     * Installs one translated section capture into canonical terrain state and
     * schedules only the column categories whose mesh content changed.
     */
    public SectionUpdate applySection(TerrainSectionData capture) {
        int cx = capture.sectionX();
        int sy = capture.sectionY();
        int cz = capture.sectionZ();
        TerrainState.SectionEntry current = terrain.addSection(
                cx, sy, cz, hashCapture(capture), capture.meshData(),
                capture.topFaceMask(), capture.bottomFaceMask(),
                capture.eastFaceMask(), capture.westFaceMask(),
                capture.southFaceMask(), capture.northFaceMask(),
                capture.opacityBitmap());
        TerrainState.SectionEntry previous;
        if (current == null) {
            current = terrain.findSection(cx, sy, cz);
            previous = current;
        } else {
            previous = terrain.getReplacedEntry();
            markDirty(
                    cx,
                    cz,
                    categoryDifferenceMask(
                            previous != null ? previous.data : null,
                            current.data),
                    previous != null);
        }
        return new SectionUpdate(previous, current);
    }

    public void removeColumn(int cx, int cz) {
        ColumnRenderState state = renderColumns.remove(packChunkKey(cx, cz));
        if (state == null) return;
        for (InstalledMeshPage page : state.activePages) {
            retirementQueue.retire(page.instance);
        }
        retainedPageCount -= state.activePages.size();
        state.activePages.clear();
        state.dirtyMask = 0;
        state.generation++;
    }

    public void submitDirtyBuilds(
            TerrainMaterialSet materials, int playerCx, int playerCz) {
        dirtyBuildScratch.clear();
        for (ColumnRenderState state : renderColumns.values()) {
            boolean streamSettled = frame >= state.settleUntilFrame;
            boolean maxWaitElapsed = state.dirtySinceFrame != Long.MIN_VALUE
                    && frame - state.dirtySinceFrame >= COLUMN_STREAM_MAX_WAIT_FRAMES;
            if (state.dirtyMask != 0 && !state.buildInFlight
                    && (state.urgentBuild || streamSettled || maxWaitElapsed)) {
                dirtyBuildScratch.add(state);
            }
        }
        java.util.Collections.sort(dirtyBuildScratch,
                new java.util.Comparator<ColumnRenderState>() {
                    @Override
                    public int compare(ColumnRenderState left, ColumnRenderState right) {
                        if (left.hasPublishedState != right.hasPublishedState) {
                            return left.hasPublishedState ? 1 : -1;
                        }
                        int distanceOrder = Long.compare(
                                columnDistanceSq(left, playerCx, playerCz),
                                columnDistanceSq(right, playerCx, playerCz));
                        return distanceOrder != 0
                                ? distanceOrder : Long.compare(left.key, right.key);
                    }
                });

        final int currentEpoch = epoch;
        for (final ColumnRenderState state : dirtyBuildScratch) {
            if (pendingResults.get() >= MAX_PENDING) break;
            TerrainState.ChunkColumn column = terrain.findColumn(state.cx, state.cz);
            if (column == null || column.isEmpty()) continue;

            final List<TerrainSectionSnapshot> snapshots = terrain.createSnapshot(column);
            int[] fingerprints = columnCategoryFingerprints(snapshots);
            int requestedMask = state.dirtyMask;
            int buildMask = requestedMask;
            if (state.hasPublishedState) {
                for (int category = 0; category < TerrainCategory.COUNT; category++) {
                    int bit = 1 << category;
                    if ((buildMask & bit) != 0
                            && state.categoryFingerprints[category] == fingerprints[category]) {
                        buildMask &= ~bit;
                    }
                }
            }
            if (buildMask == 0) {
                state.dirtyMask &= ~requestedMask;
                clearDirtyTimingIfClean(state);
                state.hasPublishedState = true;
                continue;
            }

            long estimatedBytes = estimateSnapshotMeshBytes(snapshots, buildMask);
            long currentPendingBytes = pendingMeshBytes.get();
            if (currentPendingBytes > 0
                    && estimatedBytes > MAX_PENDING_MESH_BYTES - currentPendingBytes) {
                continue;
            }

            int generation = state.generation;
            final int submittedMask = buildMask;
            final MeshBuildResult result = new MeshBuildResult(
                    state, generation, submittedMask, fingerprints,
                    currentEpoch, estimatedBytes);
            retainSnapshots(snapshots);
            state.dirtyMask &= ~requestedMask;
            clearDirtyTimingIfClean(state);
            state.buildInFlight = true;
            pendingResults.incrementAndGet();
            pendingMeshBytes.addAndGet(estimatedBytes);
            try {
                readyQueue.add(result);
                meshBuildExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            result.pages = meshBuilder.buildColumnMesh(
                                    state.cx, state.cz, snapshots, submittedMask, materials);
                            long actualBytes = estimatePageBytes(result.pages);
                            long reservedBytes = result.accountedBytes;
                            result.accountedBytes = actualBytes;
                            pendingMeshBytes.addAndGet(actualBytes - reservedBytes);
                        } catch (Throwable failure) {
                            disposePages(result.pages);
                            result.pages = null;
                            result.failure = failure;
                        } finally {
                            releaseSnapshots(snapshots);
                            result.complete = true;
                        }
                    }
                });
            } catch (RuntimeException failure) {
                releaseSnapshots(snapshots);
                readyQueue.remove(result);
                state.buildInFlight = false;
                markBuildRetry(state, submittedMask);
                pendingResults.decrementAndGet();
                pendingMeshBytes.addAndGet(-estimatedBytes);
                throw failure;
            } catch (Error failure) {
                releaseSnapshots(snapshots);
                readyQueue.remove(result);
                state.buildInFlight = false;
                markBuildRetry(state, submittedMask);
                pendingResults.decrementAndGet();
                pendingMeshBytes.addAndGet(-estimatedBytes);
                throw failure;
            }
        }
        dirtyBuildScratch.clear();
    }

    public void installReadyMeshes(RemixAPI api) {
        long deadline = System.nanoTime() + INSTALL_TIME_BUDGET_NS;
        int installedColumns = 0;
        while (installedColumns < MAX_INSTALLED_COLUMNS_PER_FRAME) {
            if (activeInstall == null) {
                if (retirementQueue.retirementBytesBackpressured()) return;
                MeshBuildResult result = pollCompletedBuild();
                if (result == null) return;
                pendingResults.decrementAndGet();
                if (result.failure != null) {
                    pendingMeshBytes.addAndGet(-result.accountedBytes);
                    markForRebuildIfLive(result, true);
                    reporter.error("Background mesh build failed", result.failure);
                    continue;
                }
                if (isStale(result)) {
                    disposePages(result.pages);
                    pendingMeshBytes.addAndGet(-result.accountedBytes);
                    markForRebuildIfLive(result, false);
                    continue;
                }
                activeInstall = new MeshInstallCursor(result);
            }

            MeshInstallCursor cursor = activeInstall;
            if (cursor.rollbackFailed) {
                abortInstall(cursor);
                activeInstall = null;
                return;
            }
            if (cursor.abortRequested || isStale(cursor.result)) {
                cursor.abortRequested = true;
                boolean attemptedRollback = rollbackNextActiveInstallPage(cursor, api);
                if (cursor.rollbackFailed) {
                    abortInstall(cursor);
                    activeInstall = null;
                    return;
                }
                if (cursor.nextActivation == 0) {
                    abortInstall(cursor);
                    activeInstall = null;
                }
                if (attemptedRollback && System.nanoTime() >= deadline) return;
                continue;
            }

            boolean attemptedNativePage;
            if (!cursor.staged) {
                attemptedNativePage = installNextPage(cursor, api);
                if (!cursor.failed && cursor.nextPage >= cursor.result.pages.length) {
                    cursor.staged = true;
                }
            } else {
                attemptedNativePage = activateNextPage(cursor, api);
            }
            if (cursor.failed) {
                cursor.abortRequested = true;
                if (cursor.nextActivation == 0) {
                    abortInstall(cursor);
                    activeInstall = null;
                }
                return;
            }
            if (cursor.staged && cursor.nextActivation >= cursor.installedPages.size()) {
                finishInstall(cursor);
                activeInstall = null;
                installedColumns++;
            }
            if (attemptedNativePage && System.nanoTime() >= deadline) return;
        }
    }

    private boolean installNextPage(MeshInstallCursor cursor, RemixAPI api) {
        MeshBuildResult result = cursor.result;
        BlockMeshBuilder.MeshPage[] pages = result.pages;
        while (cursor.nextPage < pages.length) {
            int pageIndex = cursor.nextPage++;
            BlockMeshBuilder.MeshPage pendingPage = pages[pageIndex];
            if (pendingPage == null) continue;
            int category = pendingPage.category();
            RemixMesh pendingMesh = pendingPage.mesh();
            int categoryFlags = category == TerrainCategory.OVERLAY
                    ? InstanceCategoryBit.DECAL_STATIC : 0;
            RemixMesh mesh = null;
            RemixInstance instance = null;
            try {
                try (TerrainProfile.Scope scope = TerrainProfile.begin("createMesh")) {
                    scope.meshCategory(category);
                    mesh = RemixMeshStore.get().create(pendingMesh);
                    nativeCallStats.pageCreated(mesh);
                }
                pages[pageIndex] = null;
                instance = new RemixInstance(mesh)
                        .setTransform(columnTransform(result.owner.cx, result.owner.cz))
                        .setCategoryFlags(categoryFlags | InstanceCategoryBit.HIDDEN)
                        .setBlendExt(blendExtForCategory(category))
                        .setDoubleSided(true);
                try (TerrainProfile.Scope scope = TerrainProfile.begin("createInstance")) {
                    scope.meshCategory(category);
                    RemixInstanceStore.get().create(instance);
                    instance.drawFast(api);
                    nativeCallStats.instanceAdded();
                }
                cursor.installedPages.add(
                        new InstalledMeshPage(instance, category, categoryFlags));
            } catch (RuntimeException failure) {
                failInstallPage(cursor, pages, pageIndex, pendingMesh, mesh, instance, category,
                        failure);
            } catch (Error failure) {
                failInstallPage(cursor, pages, pageIndex, pendingMesh, mesh, instance, category,
                        failure);
            }
            return true;
        }
        return false;
    }

    private void failInstallPage(
            MeshInstallCursor cursor, BlockMeshBuilder.MeshPage[] pages, int pageIndex,
            RemixMesh pendingMesh, RemixMesh mesh, RemixInstance instance, int category,
            Throwable failure) {
        reporter.error("Native terrain install failed for category " + category, failure);
        cursor.failed = true;
        if (instance != null) {
            retirementQueue.retire(instance);
        } else if (mesh != null && !mesh.isDeleted()) {
            retirementQueue.retire(mesh);
        } else {
            pendingMesh.disposeData();
        }
        pages[pageIndex] = null;
    }

    private boolean activateNextPage(MeshInstallCursor cursor, RemixAPI api) {
        if (cursor.nextActivation >= cursor.installedPages.size()) return false;
        InstalledMeshPage page = cursor.installedPages.get(cursor.nextActivation++);
        try (TerrainProfile.Scope scope = TerrainProfile.begin("activateTerrainPage")) {
            scope.meshCategory(page.category);
            page.instance.setCategoryFlags(page.categoryFlags);
            page.instance.drawFast(api);
        } catch (RuntimeException failure) {
            reporter.error(
                    "Native terrain activation failed for category " + page.category, failure);
            cursor.failed = true;
        } catch (Error failure) {
            reporter.error(
                    "Native terrain activation failed for category " + page.category, failure);
            cursor.failed = true;
        }
        return true;
    }

    private boolean rollbackNextActiveInstallPage(MeshInstallCursor cursor, RemixAPI api) {
        if (!cursor.staged || cursor.nextActivation == 0) return false;
        InstalledMeshPage page = cursor.installedPages.get(cursor.nextActivation - 1);
        try (TerrainProfile.Scope scope = TerrainProfile.begin("hideTerrainPage")) {
            scope.meshCategory(page.category);
            page.instance.setCategoryFlags(page.categoryFlags | InstanceCategoryBit.HIDDEN);
            page.instance.drawFast(api);
            cursor.nextActivation--;
        } catch (RuntimeException failure) {
            reporter.error(
                    "Failed to hide staged terrain page for category " + page.category, failure);
            cursor.rollbackFailed = true;
        } catch (Error failure) {
            reporter.error(
                    "Failed to hide staged terrain page for category " + page.category, failure);
            cursor.rollbackFailed = true;
        }
        return true;
    }

    private void finishInstall(MeshInstallCursor cursor) {
        MeshBuildResult result = cursor.result;
        ColumnRenderState owner = result.owner;
        int categoryMask = result.categoryMask;
        Iterator<InstalledMeshPage> oldPages = owner.activePages.iterator();
        while (oldPages.hasNext()) {
            InstalledMeshPage page = oldPages.next();
            if ((categoryMask & (1 << page.category)) == 0) continue;
            oldPages.remove();
            retainedPageCount--;
            retirementQueue.retire(page.instance);
        }
        owner.activePages.addAll(cursor.installedPages);
        retainedPageCount += cursor.installedPages.size();
        for (int category = 0; category < TerrainCategory.COUNT; category++) {
            if ((categoryMask & (1 << category)) != 0) {
                owner.categoryFingerprints[category] = result.categoryFingerprints[category];
            }
        }
        owner.hasPublishedState = true;
        owner.buildInFlight = false;
        pendingMeshBytes.addAndGet(-result.accountedBytes);
    }

    private void abortInstall(MeshInstallCursor cursor) {
        MeshBuildResult result = cursor.result;
        for (InstalledMeshPage page : cursor.installedPages) {
            retirementQueue.retire(page.instance);
        }
        disposePages(result.pages);
        pendingMeshBytes.addAndGet(-result.accountedBytes);
        markForRebuildIfLive(result, cursor.failed || cursor.rollbackFailed);
    }

    public int resubmitHiddenInstances(RemixAPI api) {
        if (!retainedInstancesHidden) return 0;
        int drawCalls = 0;
        for (ColumnRenderState state : renderColumns.values()) {
            for (int i = 0; i < state.activePages.size(); i++) {
                RemixInstance instance = state.activePages.get(i).instance;
                if (instance != null && !instance.isDeletedFast()) {
                    instance.drawFast(api);
                    drawCalls++;
                }
            }
        }
        retainedInstancesHidden = false;
        return drawCalls;
    }

    public void hideRetainedInstances(RemixAPI api) {
        if (api == null) return;
        for (ColumnRenderState state : renderColumns.values()) {
            for (InstalledMeshPage page : state.activePages) {
                RemixInstance instance = page.instance;
                if (instance != null && !instance.isDeletedFast()) {
                    instance.hideFromScene(api);
                }
            }
        }
        rollbackActiveInstallPages(api);
        retainedInstancesHidden = true;
    }

    private void rollbackActiveInstallPages(RemixAPI api) {
        MeshInstallCursor cursor = activeInstall;
        if (cursor == null || !cursor.staged || cursor.nextActivation == 0) return;
        long deadline = System.nanoTime() + INSTALL_TIME_BUDGET_NS;
        while (rollbackNextActiveInstallPage(cursor, api)) {
            if (cursor.rollbackFailed || System.nanoTime() >= deadline) return;
        }
    }

    public Diagnostics diagnostics(int knownCaptureQueue) {
        if (diagnosticsRefreshCountdown > 0) {
            diagnosticsRefreshCountdown--;
            return diagnostics;
        }
        diagnosticsRefreshCountdown = DIAGNOSTICS_REFRESH_FRAMES - 1;
        int published = 0;
        int withGeometry = 0;
        int missing = 0;
        int missingSections = 0;
        for (TerrainState.ChunkColumn column : terrain.columns()) {
            ColumnRenderState state =
                    renderColumns.get(packChunkKey(column.cx, column.cz));
            if (state != null && state.hasPublishedState) {
                published++;
                if (!state.activePages.isEmpty()) withGeometry++;
            } else {
                missing++;
                missingSections += column.sectionCount();
            }
        }
        diagnostics = new Diagnostics(
                published, withGeometry, missing, missingSections, retainedPageCount,
                pendingResults.get(), countCompletedBuilds(), knownCaptureQueue,
                pendingMeshBytes.get());
        return diagnostics;
    }

    public void clear() {
        epoch++;
        if (activeInstall != null) {
            abortInstall(activeInstall);
            activeInstall = null;
        }
        while (pendingResults.get() > 0) {
            MeshBuildResult stale = pollCompletedBuild();
            if (stale != null) {
                disposePages(stale.pages);
                pendingMeshBytes.addAndGet(-stale.accountedBytes);
                pendingResults.decrementAndGet();
            } else {
                LockSupport.parkNanos(100_000L);
            }
        }
        pendingMeshBytes.set(0);
        for (ColumnRenderState state : renderColumns.values()) {
            for (InstalledMeshPage page : state.activePages) {
                retirementQueue.retire(page.instance);
            }
            state.activePages.clear();
        }
        renderColumns.clear();
        retainedPageCount = 0;
        retainedInstancesHidden = false;
        dirtyBuildScratch.clear();
        retirementQueue.clear();
        frame = 0;
        diagnostics = Diagnostics.EMPTY;
        diagnosticsRefreshCountdown = 0;
    }

    public void destroy() {
        clear();
        shutdown();
    }

    /** Stops the build workers after the owner has cleared world state. */
    public void shutdown() {
        meshBuildExecutor.shutdown();
    }

    public interface Reporter {
        Reporter NONE = new Reporter() {
            @Override
            public void error(String message, Throwable failure) {}
        };

        void error(String message, Throwable failure);
    }

    public static final class SectionUpdate {
        private final TerrainState.SectionEntry previous;
        private final TerrainState.SectionEntry current;

        SectionUpdate(
                TerrainState.SectionEntry previous,
                TerrainState.SectionEntry current) {
            this.previous = previous;
            this.current = current;
        }

        public TerrainState.SectionEntry previous() {
            return previous;
        }

        public TerrainState.SectionEntry current() {
            return current;
        }
    }

    private ColumnRenderState markDirty(
            int cx, int cz, int categoryMask, boolean immediate) {
        long key = packChunkKey(cx, cz);
        ColumnRenderState state = renderColumns.get(key);
        if (state == null) {
            state = new ColumnRenderState(key, cx, cz);
            renderColumns.put(key, state);
        }
        if (categoryMask == 0) {
            if (!state.buildInFlight && state.dirtyMask == 0) {
                state.hasPublishedState = true;
            }
            return state;
        }
        state.generation++;
        if (state.dirtyMask == 0) state.dirtySinceFrame = frame;
        state.dirtyMask |= categoryMask;
        if (immediate && state.hasPublishedState) {
            state.urgentBuild = true;
            state.settleUntilFrame = frame;
        } else if (!state.urgentBuild) {
            state.settleUntilFrame = frame + COLUMN_STREAM_SETTLE_FRAMES;
        }
        return state;
    }

    private void markForRebuildIfLive(MeshBuildResult result, boolean urgent) {
        ColumnRenderState owner = result.owner;
        if (result.epoch != epoch || renderColumns.get(owner.key) != owner) return;
        owner.buildInFlight = false;
        if (urgent) {
            markBuildRetry(owner, result.categoryMask);
            return;
        }
        if (owner.dirtyMask == 0) {
            owner.dirtySinceFrame = frame;
            owner.settleUntilFrame = frame + COLUMN_STREAM_SETTLE_FRAMES;
        }
        owner.dirtyMask |= result.categoryMask;
    }

    private void markBuildRetry(ColumnRenderState state, int categoryMask) {
        if (state.dirtyMask == 0) state.dirtySinceFrame = frame;
        state.dirtyMask |= categoryMask;
        state.urgentBuild = true;
        state.settleUntilFrame = frame;
    }

    private static void clearDirtyTimingIfClean(ColumnRenderState state) {
        if (state.dirtyMask != 0) return;
        state.urgentBuild = false;
        state.dirtySinceFrame = Long.MIN_VALUE;
        state.settleUntilFrame = 0;
    }

    private boolean isStale(MeshBuildResult result) {
        ColumnRenderState owner = result.owner;
        return result.epoch != epoch
                || renderColumns.get(owner.key) != owner
                || result.generation != owner.generation
                || terrain.findColumn(owner.cx, owner.cz) == null;
    }

    private MeshBuildResult pollCompletedBuild() {
        for (MeshBuildResult result : readyQueue) {
            if (result.complete && readyQueue.remove(result)) return result;
        }
        return null;
    }

    private int countCompletedBuilds() {
        int count = 0;
        for (MeshBuildResult result : readyQueue) {
            if (result.complete) count++;
        }
        return count;
    }

    private static int hashCapture(TerrainSectionData capture) {
        int hash = capture.meshData().contentHash();
        hash = 31 * hash + Long.hashCode(capture.topFaceMask());
        hash = 31 * hash + Long.hashCode(capture.bottomFaceMask());
        hash = 31 * hash + Long.hashCode(capture.eastFaceMask());
        hash = 31 * hash + Long.hashCode(capture.westFaceMask());
        hash = 31 * hash + Long.hashCode(capture.southFaceMask());
        hash = 31 * hash + Long.hashCode(capture.northFaceMask());
        return 31 * hash + Arrays.hashCode(capture.opacityBitmap());
    }

    public static int categoryDifferenceMask(Object previous, Object replacement) {
        SectionMeshData oldData =
                previous instanceof SectionMeshData ? (SectionMeshData) previous : null;
        SectionMeshData newData =
                replacement instanceof SectionMeshData ? (SectionMeshData) replacement : null;
        int mask = 0;
        for (int category = 0; category < TerrainCategory.COUNT; category++) {
            int oldHash = oldData != null ? oldData.categoryContentHash(category) : 0;
            int newHash = newData != null ? newData.categoryContentHash(category) : 0;
            if (oldHash != newHash) mask |= 1 << category;
        }
        return mask;
    }

    private static int[] columnCategoryFingerprints(
            List<TerrainSectionSnapshot> snapshots) {
        int[] fingerprints = new int[TerrainCategory.COUNT];
        boolean[] present = new boolean[TerrainCategory.COUNT];
        for (TerrainSectionSnapshot snapshot : snapshots) {
            if (!(snapshot.data() instanceof SectionMeshData)) continue;
            SectionMeshData data = (SectionMeshData) snapshot.data();
            for (int category = 0; category < TerrainCategory.COUNT; category++) {
                int sectionHash = data.categoryContentHash(category);
                if (sectionHash == 0) continue;
                if (!present[category]) {
                    fingerprints[category] = 1;
                    present[category] = true;
                }
                fingerprints[category] = 31 * fingerprints[category] + snapshot.yOff();
                fingerprints[category] = 31 * fingerprints[category] + sectionHash;
            }
        }
        for (int category = 0; category < TerrainCategory.COUNT; category++) {
            if (present[category] && fingerprints[category] == 0) {
                fingerprints[category] = 1;
            }
        }
        return fingerprints;
    }

    private static long estimateSnapshotMeshBytes(
            List<TerrainSectionSnapshot> snapshots, int categoryMask) {
        long bytes = 0;
        for (TerrainSectionSnapshot snapshot : snapshots) {
            if (!(snapshot.data() instanceof SectionMeshData)) continue;
            SectionMeshData data = (SectionMeshData) snapshot.data();
            for (int category = 0; category < TerrainCategory.COUNT; category++) {
                if ((categoryMask & (1 << category)) == 0) continue;
                long categoryBytes = data.categoryEstimatedMeshBytes(category);
                if (categoryBytes > Long.MAX_VALUE - bytes) return Long.MAX_VALUE;
                bytes += categoryBytes;
            }
        }
        return bytes;
    }

    private static void retainSnapshots(List<TerrainSectionSnapshot> snapshots) {
        for (TerrainSectionSnapshot snapshot : snapshots) {
            if (snapshot.data() instanceof SectionMeshData) {
                ((SectionMeshData) snapshot.data()).retain();
            }
        }
    }

    private static void releaseSnapshots(List<TerrainSectionSnapshot> snapshots) {
        for (TerrainSectionSnapshot snapshot : snapshots) {
            if (snapshot.data() instanceof SectionMeshData) {
                ((SectionMeshData) snapshot.data()).release();
            }
        }
    }

    private static void disposePages(BlockMeshBuilder.MeshPage[] pages) {
        if (pages == null) return;
        for (BlockMeshBuilder.MeshPage page : pages) {
            if (page != null) page.mesh().disposeData();
        }
    }

    private static long estimatePageBytes(BlockMeshBuilder.MeshPage[] pages) {
        long bytes = 0;
        if (pages == null) return bytes;
        for (BlockMeshBuilder.MeshPage page : pages) {
            if (page != null) bytes = Math.addExact(bytes, page.estimatedNativeBytes());
        }
        return bytes;
    }

    private static long columnDistanceSq(ColumnRenderState state, int cx, int cz) {
        long dx = (long) state.cx - cx;
        long dz = (long) state.cz - cz;
        return dx * dx + dz * dz;
    }

    private static long packChunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static InstanceInfoBlendEXT blendExtForCategory(int category) {
        switch (category) {
            case TerrainCategory.CUTOUT:
                return InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(128);
            case TerrainCategory.OVERLAY:
                return InstanceInfoBlendEXT.textureTimesVertexColorAlphaBlend(1);
            default:
                return InstanceInfoBlendEXT.textureTimesVertexColor();
        }
    }

    private static Transform columnTransform(int cx, int cz) {
        Transform transform = Transform.identity();
        transform.setTranslation(cx * 16f, 0f, cz * 16f);
        return transform;
    }

    private static final class ColumnRenderState {
        final long key;
        final int cx;
        final int cz;
        final List<InstalledMeshPage> activePages = new ArrayList<InstalledMeshPage>();
        final int[] categoryFingerprints = new int[TerrainCategory.COUNT];
        int generation;
        int dirtyMask;
        boolean buildInFlight;
        boolean hasPublishedState;
        boolean urgentBuild;
        long dirtySinceFrame = Long.MIN_VALUE;
        long settleUntilFrame;

        ColumnRenderState(long key, int cx, int cz) {
            this.key = key;
            this.cx = cx;
            this.cz = cz;
        }
    }

    private static final class MeshBuildResult {
        final ColumnRenderState owner;
        final int generation;
        final int categoryMask;
        final int[] categoryFingerprints;
        final int epoch;
        volatile BlockMeshBuilder.MeshPage[] pages;
        volatile long accountedBytes;
        volatile Throwable failure;
        volatile boolean complete;

        MeshBuildResult(ColumnRenderState owner, int generation, int categoryMask,
                        int[] categoryFingerprints, int epoch, long estimatedBytes) {
            this.owner = owner;
            this.generation = generation;
            this.categoryMask = categoryMask;
            this.categoryFingerprints = categoryFingerprints;
            this.epoch = epoch;
            this.accountedBytes = estimatedBytes;
        }
    }

    private static final class MeshInstallCursor {
        final MeshBuildResult result;
        final List<InstalledMeshPage> installedPages =
                new ArrayList<InstalledMeshPage>();
        int nextPage;
        int nextActivation;
        boolean staged;
        boolean failed;
        boolean abortRequested;
        boolean rollbackFailed;

        MeshInstallCursor(MeshBuildResult result) {
            this.result = result;
        }
    }

    private static final class InstalledMeshPage {
        final RemixInstance instance;
        final int category;
        final int categoryFlags;

        InstalledMeshPage(RemixInstance instance, int category, int categoryFlags) {
            this.instance = instance;
            this.category = category;
            this.categoryFlags = categoryFlags;
        }
    }

    /** Stable publication gauges consumed by loader-specific diagnostics. */
    public static final class Diagnostics {
        public static final Diagnostics EMPTY =
                new Diagnostics(0, 0, 0, 0, 0, 0, 0, 0, 0);
        private final int columnsPublished;
        private final int columnsWithGeometry;
        private final int columnsMissingGeometry;
        private final int missingGeometrySections;
        private final int retainedPages;
        private final int pendingBuilds;
        private final int readyMeshes;
        private final int captureQueue;
        private final long pendingMeshBytes;

        Diagnostics(int columnsPublished, int columnsWithGeometry,
                    int columnsMissingGeometry, int missingGeometrySections,
                    int retainedPages, int pendingBuilds, int readyMeshes,
                    int captureQueue, long pendingMeshBytes) {
            this.columnsPublished = columnsPublished;
            this.columnsWithGeometry = columnsWithGeometry;
            this.columnsMissingGeometry = columnsMissingGeometry;
            this.missingGeometrySections = missingGeometrySections;
            this.retainedPages = retainedPages;
            this.pendingBuilds = pendingBuilds;
            this.readyMeshes = readyMeshes;
            this.captureQueue = captureQueue;
            this.pendingMeshBytes = pendingMeshBytes;
        }

        public int columnsPublished() { return columnsPublished; }
        public int columnsWithGeometry() { return columnsWithGeometry; }
        public int columnsMissingGeometry() { return columnsMissingGeometry; }
        public int missingGeometrySections() { return missingGeometrySections; }
        public int retainedPages() { return retainedPages; }
        public int pendingBuilds() { return pendingBuilds; }
        public int readyMeshes() { return readyMeshes; }
        public int captureQueue() { return captureQueue; }
        public long pendingMeshBytes() { return pendingMeshBytes; }
    }
}
