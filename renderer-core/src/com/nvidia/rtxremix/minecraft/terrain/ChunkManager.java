/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.core.terrain.TerrainCategory;
import com.nvidia.rtxremix.core.terrain.TerrainMaterialSet;
import com.nvidia.rtxremix.core.terrain.TerrainProfile;
import com.nvidia.rtxremix.core.terrain.TerrainSectionData;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionMeshData;
import com.nvidia.rtxremix.minecraft.terrain.capture.DirectQuadWriter;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;
import com.nvidia.rtxremix.minecraft.profiling.PerformanceTimer;
import com.nvidia.rtxremix.minecraft.light.LightEntry;
import com.nvidia.rtxremix.RemixAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Coordinates the terrain pipeline from worker captures to stable retained
 * chunk-column assets. Camera movement never changes terrain mesh ownership.
 */
public class ChunkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkManager.class);
    public static final int CAT_SOLID       = TerrainCategory.SOLID;
    public static final int CAT_CUTOUT      = TerrainCategory.CUTOUT;
    public static final int CAT_TRANSLUCENT = TerrainCategory.TRANSLUCENT;
    public static final int CAT_OVERLAY     = TerrainCategory.OVERLAY;
    public static final int CAT_COUNT       = TerrainCategory.COUNT;

    /** Light cull distance in chunks when the user sets none. */
    public static final int DEFAULT_LIGHT_CULL_CHUNKS = 8;

    /** Distance in blocks at which the shipped torch stops being drawn; brighter lights reach further. */
    public static final int DEFAULT_LIGHT_REACH_BLOCKS = 128;
    public static final int MIN_LIGHT_REACH_BLOCKS = 16;
    public static final int MAX_LIGHT_REACH_BLOCKS = 256;

    private final Host host;
    private final TerrainState terrain;
    private final ColumnPublicationOrchestrator columnPublication;
    private final TerrainLightReconciler lightReconciler;

    private long currentWorldEpoch = Long.MIN_VALUE;

    private final List<BlockDataQueue.QueuedCapture> pending = new ArrayList<>();
    private final List<BlockDataQueue.EvictedSection> evictedSectionScratch = new ArrayList<>();
    private static final int MAX_EVICTED_REFLAGS_PER_FRAME = 128;
    private int pendingCaptureIndex;
    private static final int MAX_SECTION_REMOVALS_PER_FRAME = Math.max(1,
            Integer.getInteger("rtxremix.chunk.maxSectionRemovalsPerFrame", 8));
    private static final int MAX_SECTION_REMOVAL_VALIDATIONS_PER_FRAME = Math.max(
            MAX_SECTION_REMOVALS_PER_FRAME,
            Integer.getInteger("rtxremix.chunk.maxSectionRemovalValidationsPerFrame", 64));
    private final ArrayList<SectionLeftRenderSetQueue.SectionCoord> deferredSectionRemovals =
            new ArrayList<>(MAX_SECTION_REMOVAL_VALIDATIONS_PER_FRAME);
    private static final long MAINTENANCE_TIME_BUDGET_NS = Math.max(250_000L,
            Long.getLong("rtxremix.chunk.maintenanceBudgetNs", 1_000_000L));

    private static final long LIGHT_TIME_BUDGET_NS = Math.max(100_000L,
            Long.getLong("rtxremix.chunk.lightBudgetNs", 750_000L));

    public static final class TerrainDiagnostics {
        public static final TerrainDiagnostics EMPTY =
                new TerrainDiagnostics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        private final int columns;
        private final int columnsPublished;
        private final int columnsWithGeometry;
        private final int columnsMissingGeometry;
        private final int missingGeometrySections;
        private final int retainedPages;
        private final int knownSections;
        private final int pendingBuilds;
        private final int readyMeshes;
        private final int captureQueue;
        private final long pendingMeshBytes;

        public TerrainDiagnostics(
                int columns,
                int columnsPublished,
                int columnsWithGeometry,
                int columnsMissingGeometry,
                int missingGeometrySections,
                int retainedPages,
                int knownSections,
                int pendingBuilds,
                int readyMeshes,
                int captureQueue,
                long pendingMeshBytes) {
            this.columns = columns;
            this.columnsPublished = columnsPublished;
            this.columnsWithGeometry = columnsWithGeometry;
            this.columnsMissingGeometry = columnsMissingGeometry;
            this.missingGeometrySections = missingGeometrySections;
            this.retainedPages = retainedPages;
            this.knownSections = knownSections;
            this.pendingBuilds = pendingBuilds;
            this.readyMeshes = readyMeshes;
            this.captureQueue = captureQueue;
            this.pendingMeshBytes = pendingMeshBytes;
        }

        public int columns() { return columns; }
        public int columnsPublished() { return columnsPublished; }
        public int columnsWithGeometry() { return columnsWithGeometry; }
        public int columnsMissingGeometry() { return columnsMissingGeometry; }
        public int missingGeometrySections() { return missingGeometrySections; }
        public int retainedPages() { return retainedPages; }
        public int knownSections() { return knownSections; }
        public int pendingBuilds() { return pendingBuilds; }
        public int readyMeshes() { return readyMeshes; }
        public int captureQueue() { return captureQueue; }
        public long pendingMeshBytes() { return pendingMeshBytes; }
    }

    private int cntCap = -1, cntLightsCulled, cntLightsDrawn, cntLightsBeyondReach, cntReachable, cntWorkerMem, cntPooledDirectMB,
            cntBucketGrows, cntBucketGrowKB, cntPendingBuilds, cntPendingMeshMB, cntCaptureQueue,
            cntCaptureQueueMB, cntCaptureBackpressure, cntCaptureEvicted, cntLightRebuildsSkipped,
            cntNeighbourReflagsDropped, cntNeighbourReflagsKept, cntIncompleteFirstBuilds, cntPacedChunks,
            cntPacedBacklog, cntChunkApplyUs, cntPacedUnloads, cntPacedUnloadBacklog, cntChunkUnloadUs,
            cntSurfaceScansSeeded, cntReadyMeshes, cntMeshRetireQueue,
            cntMeshRetireMB, cntLightOpsPending, cntLightTasks,
            cntKnownSections, cntColumns, cntPublishedColumns, cntGeometryColumns,
            cntMissingColumns, cntMissingSections, cntRetainedPages,
            cntPagesInstalled, cntInstallBytes, cntInstancesAdded, cntInstancesRemoved,
            cntMeshesDestroyed;
    private int cntFloodIncrementalUs;
    private int perfBfs = -1;
    private volatile TerrainDiagnostics lastTerrainDiagnostics = TerrainDiagnostics.EMPTY;

    // Chunk-unload callbacks can arrive in a border-sized burst. Ingest all
    // keys immediately, then perform the destructive column work gradually.
    private final ConcurrentLinkedQueue<Long> chunkUnloadEvents = new ConcurrentLinkedQueue<>();
    private final LinkedHashSet<Long> pendingColumnUnloads = new LinkedHashSet<>();
    private static final int MAX_COLUMN_UNLOADS_PER_FRAME = Math.max(1,
            Integer.getInteger("rtxremix.chunk.maxColumnUnloadsPerFrame", 4));
    private static final int MAX_COLUMN_UNLOAD_VALIDATIONS_PER_FRAME = 64;
    private static final int ZOMBIE_AUDIT_INTERVAL = 600; // frames (~10 s at 60 fps)
    private static final int ZOMBIE_AUDIT_COLUMNS_PER_FRAME = 64;
    private static final int ZOMBIE_AUDIT_REMOVALS_PER_FRAME = 2;
    private int framesSinceAudit = 0;
    private long terrainFrame;
    /** The user's light cull distance in chunks; 0 means Auto, the render distance. */
    private int lightCullChunks = DEFAULT_LIGHT_CULL_CHUNKS;
    private int lightReachBlocks = DEFAULT_LIGHT_REACH_BLOCKS;
    private float lightReachThreshold;
    /** The radius TerrainState currently holds, so it is only pushed on change. */
    private int appliedLightCullChunks = -1;
    private long[] zombieAuditColumns;
    private int zombieAuditCursor;
    private int zombieAuditRemoved;

    // --- construction ---

    public ChunkManager(Host host) {
        this.host = host;
        lightReachThreshold = TerrainLight.thresholdFor(
                DEFAULT_LIGHT_REACH_BLOCKS, host.torchReferencePower());
        terrain = new TerrainState(DEFAULT_LIGHT_CULL_CHUNKS * 16);
        columnPublication = new ColumnPublicationOrchestrator(
                terrain,
                new ColumnPublicationOrchestrator.Reporter() {
                    @Override
                    public void error(String message, Throwable failure) {
                        LOGGER.error(message, failure);
                    }
                });
        lightReconciler = new TerrainLightReconciler(
                terrain,
                new TerrainLightReconciler.Resolver() {
                    @Override
                    public TerrainLightReconciler.Radiance radiance(LightEntry entry) {
                        return host.resolveRadiance(entry);
                    }
                },
                failure -> LOGGER.warn(
                        "Native block-light operation failed; retaining it for retry",
                        failure));
        LOGGER.info("ChunkManager init");
    }

    public int getLoadedChunkCount() { return terrain.columnCount(); }
    public TerrainDiagnostics getTerrainDiagnostics() { return lastTerrainDiagnostics; }

    /**
     * Sets the light cull distance in chunks. 0 = Auto, Minecraft's render
     * distance; explicit values never exceed it either, so the two distance
     * sliders share one contract.
     */
    public void setLightCullDistance(int chunks) {
        lightCullChunks = Math.max(0, chunks);
    }

    /** Returns the configured light cull distance in chunks, 0 for Auto. */
    public int getLightCullDistance() {
        return lightCullChunks;
    }

    /** Chunks the terrain light cull uses for a configured value and a render distance. */
    static int effectiveLightCullChunks(int userChunks, int viewDistanceChunks) {
        return userChunks == 0 ? viewDistanceChunks : Math.min(userChunks, viewDistanceChunks);
    }

    /**
     * Pushes the resolved radius into TerrainState when it changed. Called once
     * per frame: a changed radius dirties the connectivity flood, so it must
     * not be pushed every frame.
     */
    void syncLightCullDistance() {
        int effective = effectiveLightCullChunks(
                lightCullChunks, host.viewDistanceChunks());
        if (effective == appliedLightCullChunks) return;
        appliedLightCullChunks = effective;
        terrain.setLightCullBlocks(effective * 16);
    }

    /**
     * Sets the light reach in blocks: the camera distance at which a torch
     * stops being drawn. A light of power P is drawn out to
     * reach x sqrt(P / torch power), so brighter lights reach further.
     */
    public void setLightReach(int blocks) {
        lightReachBlocks = Math.max(MIN_LIGHT_REACH_BLOCKS, Math.min(MAX_LIGHT_REACH_BLOCKS, blocks));
        lightReachThreshold = TerrainLight.thresholdFor(
                lightReachBlocks, host.torchReferencePower());
    }

    public int getLightReach() {
        return lightReachBlocks;
    }

    /** The weight floor the draw loop applies this frame, for tests. */
    float lightReachThreshold() {
        return lightReachThreshold;
    }

    /** Per-rule light population, for {@code /rtx lights}. */
    public static final class LightRuleStats {
        private final String ruleId;
        private final int total;
        private final int drawn;

        public LightRuleStats(String ruleId, int total, int drawn) {
            this.ruleId = ruleId;
            this.total = total;
            this.drawn = drawn;
        }

        public String ruleId() { return ruleId; }
        public int total() { return total; }
        public int drawn() { return drawn; }
    }

    /**
     * Counts every terrain light by rule, and how many of them were drawn in
     * the last completed frame, most populous rule first. Render thread only.
     */
    public List<LightRuleStats> lightStatsByRule() {
        Map<String, int[]> counts = new LinkedHashMap<String, int[]>();
        for (TerrainState.ChunkColumn column : terrain.columns()) {
            for (TerrainState.SectionEntry entry : column.sectionEntries()) {
                for (Map.Entry<BlockLightKey, TerrainLight> light : entry.lights().entrySet()) {
                    String ruleId = light.getKey().ruleId();
                    int[] tally = counts.computeIfAbsent(ruleId, ignored -> new int[2]);
                    tally[0]++;
                    if (light.getValue().drawnInFrame(terrainFrame)) tally[1]++;
                }
            }
        }
        List<LightRuleStats> stats = new ArrayList<>(counts.size());
        for (Map.Entry<String, int[]> tally : counts.entrySet()) {
            stats.add(new LightRuleStats(tally.getKey(), tally.getValue()[0], tally.getValue()[1]));
        }
        stats.sort(Comparator.comparingInt(LightRuleStats::total).reversed());
        return stats;
    }

    public void onChunkUnloaded(int chunkX, int chunkZ) {
        BlockDataQueue.markChunkUnloaded(chunkX, chunkZ);
        chunkUnloadEvents.add(packChunkKey(chunkX, chunkZ));
    }

    private static long packChunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public void updateVisibleChunks(
            double cameraX,
            double cameraY,
            double cameraZ,
            PerformanceTimer timer,
            int perfGen,
            int perfDraw) {
        RemixAPI api = host.remixApi();
        if (!host.hasWorld() || api == null) return;
        try (TerrainProfile.Scope updateScope =
                     TerrainProfile.begin("updateVisibleChunks")) {

            long worldEpoch = host.worldEpoch();
            if (worldEpoch != currentWorldEpoch) {
                if (currentWorldEpoch != Long.MIN_VALUE) {
                    LOGGER.info("World changed, clearing terrain");
                    clear();
                }
                currentWorldEpoch = worldEpoch;
            }

            TerrainMaterialSet materials = host.terrainMaterials();
            if (materials == null || !host.areTerrainMaterialsReady()) return;

            if (cntCap == -1) {
                cntCap     = timer.registerCounter("Chunk.Captures");
                cntLightsCulled = timer.registerCounter("Chunk.LightsCulled");
                cntLightsDrawn = timer.registerCounter("Chunk.LightsDrawn");
                cntLightsBeyondReach = timer.registerCounter("Chunk.LightsBeyondReach");
                cntReachable = timer.registerCounter("Chunk.Reachable");
                cntFloodIncrementalUs = timer.registerCounter("Chunk.FloodIncrementalUs");
                cntWorkerMem = timer.registerCounter("Chunk.WorkerMemKB");
                cntPooledDirectMB = timer.registerCounter("Chunk.PooledDirectMB");
                cntBucketGrows = timer.registerCounter("Chunk.BucketGrows");
                cntBucketGrowKB = timer.registerCounter("Chunk.BucketGrowKB");
                cntPendingBuilds = timer.registerCounter("Chunk.PendingBuilds");
                cntPendingMeshMB = timer.registerCounter("Chunk.PendingMeshMB");
                cntReadyMeshes = timer.registerCounter("Chunk.ReadyMeshes");
                cntMeshRetireQueue = timer.registerCounter("Chunk.MeshRetireQueue");
                cntMeshRetireMB = timer.registerCounter("Chunk.MeshRetireMB");
                cntLightOpsPending = timer.registerCounter("Chunk.LightOpsPending");
                cntLightTasks = timer.registerCounter("Chunk.LightTasks");
                cntKnownSections = timer.registerCounter("Chunk.Sections");
                cntColumns = timer.registerCounter("Chunk.Columns");
                cntPublishedColumns = timer.registerCounter("Chunk.PublishedColumns");
                cntGeometryColumns = timer.registerCounter("Chunk.MeshColumns");
                cntMissingColumns = timer.registerCounter("Chunk.MissingColumns");
                cntMissingSections = timer.registerCounter("Chunk.MissingSections");
                cntRetainedPages = timer.registerCounter("Chunk.RetainedPages");
                cntCaptureQueue = timer.registerCounter("Chunk.CaptureQueue");
                cntCaptureQueueMB = timer.registerCounter("Chunk.CaptureQueueMB");
                cntCaptureBackpressure = timer.registerCounter("Chunk.CaptureBlocked");
                cntCaptureEvicted = timer.registerCounter("Chunk.CaptureEvicted");
                cntLightRebuildsSkipped = timer.registerCounter("Chunk.LightRebuildsSkipped");
                cntNeighbourReflagsDropped = timer.registerCounter("Chunk.NeighbourReflagsDropped");
                cntNeighbourReflagsKept = timer.registerCounter("Chunk.NeighbourReflagsKept");
                cntIncompleteFirstBuilds = timer.registerCounter("Chunk.IncompleteFirstBuilds");
                cntPacedChunks = timer.registerCounter("Chunk.PacedChunks");
                cntPacedBacklog = timer.registerCounter("Chunk.PacedBacklog");
                cntChunkApplyUs = timer.registerCounter("Chunk.ApplyUsPerChunk");
                cntPacedUnloads = timer.registerCounter("Chunk.PacedUnloads");
                cntPacedUnloadBacklog = timer.registerCounter("Chunk.PacedUnloadBacklog");
                cntChunkUnloadUs = timer.registerCounter("Chunk.UnloadUsPerChunk");
                cntSurfaceScansSeeded = timer.registerCounter("Chunk.SurfaceScansSeeded");
                cntPagesInstalled = timer.registerCounter("Chunk.PagesInstalled");
                cntInstallBytes = timer.registerCounter("Chunk.InstallBytes");
                cntInstancesAdded = timer.registerCounter("Chunk.InstancesAdded");
                cntInstancesRemoved = timer.registerCounter("Chunk.InstancesRemoved");
                cntMeshesDestroyed = timer.registerCounter("Chunk.MeshesDestroyed");
                perfBfs      = timer.registerSection("Chunk.BFS");
            }

            timer.begin(perfGen);
            try (TerrainProfile.Scope genScope = TerrainProfile.begin("Chunk.Gen")) {
                terrainFrame++;
                columnPublication.beginFrame(terrainFrame);

                int pcx = (int) Math.floor(cameraX) >> 4;
                int pcz = (int) Math.floor(cameraZ) >> 4;
                terrain.setPlayerPos(pcx, pcz);
                terrain.setPlayerBlockPos(
                        (int) Math.floor(cameraX),
                        (int) Math.floor(cameraY),
                        (int) Math.floor(cameraZ));
                syncLightCullDistance();

                long maintenanceDeadline = System.nanoTime() + MAINTENANCE_TIME_BUDGET_NS;
                drainColumnUnloads(maintenanceDeadline);
                drainSectionRemovals(maintenanceDeadline);
                lightReconciler.drain(System.nanoTime() + LIGHT_TIME_BUDGET_NS);
                int capturesProcessed = drainBlockData();
                timer.count(cntCap, capturesProcessed);
                genScope.captureCount(capturesProcessed);
                timer.count(cntWorkerMem, DirectQuadWriter.getTotalAllocatedBytes() / 1024);
                // Retained section meshes live in the pool; this is the off-heap footprint
                // the collector no longer accounts for.
                timer.count(cntPooledDirectMB, DirectBufferPool.allocatedBytes() / (1024 * 1024));
                timer.count(cntBucketGrows, DirectQuadWriter.drainGrowthEvents());
                timer.count(cntBucketGrowKB, DirectQuadWriter.drainGrowthCopiedBytes() / 1024);
                timer.count(cntPendingBuilds, columnPublication.pendingBuilds());
                timer.count(cntPendingMeshMB, columnPublication.pendingMeshBytes() / (1024 * 1024));
                timer.count(cntReadyMeshes, columnPublication.readyMeshes());
                timer.count(cntMeshRetireQueue, columnPublication.pendingRetirements());
                timer.count(cntMeshRetireMB, columnPublication.pendingRetirementBytes() / (1024 * 1024));
                timer.count(cntLightOpsPending, lightReconciler.pendingOps());
                timer.count(cntLightTasks, lightReconciler.taskCount());
                timer.count(cntCaptureQueue, BlockDataQueue.pendingCount());
                timer.count(cntCaptureQueueMB, BlockDataQueue.pendingBytes() / (1024 * 1024));
                timer.count(cntCaptureBackpressure, BlockDataQueue.drainBackpressureWaits());
                timer.count(cntCaptureEvicted, BlockDataQueue.drainBackpressureEvictions());
                reflagEvictedSections();
                timer.count(cntLightRebuildsSkipped, SkippedSectionRebuilds.drainLightOnly());
                timer.count(cntNeighbourReflagsDropped, SkippedSectionRebuilds.drainNeighbourInSnapshot());
                timer.count(cntNeighbourReflagsKept, NeighbourReflagFilter.drainKept());
                timer.count(cntIncompleteFirstBuilds, NeighbourReflagFilter.drainIncompleteFirstBuilds());
                timer.count(cntPacedChunks, LocalChunkBatchPacing.drainPacedChunks());
                timer.count(cntPacedBacklog, LocalChunkBatchPacing.pacedBacklog());
                timer.count(cntChunkApplyUs, LocalChunkBatchPacing.applyMicrosPerChunk());
                timer.count(cntPacedUnloads, LocalChunkBatchPacing.drainPacedUnloads());
                timer.count(cntPacedUnloadBacklog, LocalChunkBatchPacing.pacedUnloadBacklog());
                timer.count(cntChunkUnloadUs, LocalChunkBatchPacing.unloadMicrosPerChunk());
                timer.count(cntSurfaceScansSeeded, SkyLightSurfaceScanPolicy.drainSeeded());

                advanceZombieAudit(maintenanceDeadline);
                columnPublication.submitDirtyBuilds(materials, pcx, pcz);

                try (TerrainProfile.Scope installScope =
                             TerrainProfile.begin("installReadyMeshes")) {
                    columnPublication.installReadyMeshes(api);
                }

                publishTerrainDiagnostics();
                TerrainDiagnostics diag = lastTerrainDiagnostics;
                timer.count(cntKnownSections, diag.knownSections());
                timer.count(cntColumns, diag.columns());
                timer.count(cntPublishedColumns, diag.columnsPublished());
                timer.count(cntGeometryColumns, diag.columnsWithGeometry());
                timer.count(cntMissingColumns, diag.columnsMissingGeometry());
                timer.count(cntMissingSections, diag.missingGeometrySections());
                timer.count(cntRetainedPages, diag.retainedPages());

                columnPublication.drainRetirements();

                TerrainNativeCallStats publicationCalls = columnPublication.nativeCallStats();
                timer.count(cntPagesInstalled, publicationCalls.pagesInstalled());
                timer.count(cntInstallBytes, publicationCalls.installBytes());
                timer.count(cntInstancesAdded, publicationCalls.instancesAdded());
                timer.count(cntInstancesRemoved, publicationCalls.instancesRemoved());
                timer.count(cntMeshesDestroyed, publicationCalls.meshesDestroyed());
            } finally {
                timer.end(perfGen);
            }

            timer.begin(perfDraw);
            try (TerrainProfile.Scope drawScope = TerrainProfile.begin("Chunk.Draw")) {
                drawScope.drawCalls(draw(
                        api, timer, cameraX, cameraY, cameraZ));
            } finally {
                timer.end(perfDraw);
            }
        }
    }
    // --- event draining ---

    private void drainColumnUnloads(long deadline) {
        Long key;
        while ((key = chunkUnloadEvents.poll()) != null) {
            // Cancel light tasks at ingestion, not when the entry reaches the
            // per-frame validation window: under flight-scale unload backlogs
            // an entry can wait minutes, and its retained light ops would latch
            // light backpressure across the whole pipeline in the meantime.
            // A column leaving through this set needs no second cancellation:
            // a task is only created for a live chunk, and drainBlockData
            // removes the column from pendingColumnUnloads before creating
            // one. Columns that vanish without an event are the zombie
            // audit's to cancel.
            if (pendingColumnUnloads.add(key)) {
                cancelLightTasksForColumn((int) (key >> 32), (int) (long) key);
            }
        }
        int removed = 0;
        int examined = 0;
        int unloadBudget = columnUnloadBudget();
        Iterator<Long> iterator = pendingColumnUnloads.iterator();
        while (iterator.hasNext() && examined < MAX_COLUMN_UNLOAD_VALIDATIONS_PER_FRAME) {
            if (examined > 0 && System.nanoTime() >= deadline) return;
            long chunkKey = iterator.next();
            examined++;

            int cx = (int) (chunkKey >> 32);
            int cz = (int) chunkKey;
            if (host.isChunkLoaded(cx, cz)) {
                iterator.remove();
                continue;
            }
            if (columnPublication.retirementBackpressured()
                    || removed >= unloadBudget) {
                continue;
            }

            iterator.remove();
            removeColumnDeferred(cx, cz);
            removed++;
        }
    }

    /**
     * Column removals allowed this frame. The configured value is the
     * steady-state pace and is always honored; on top of it the budget ramps
     * with the unload backlog, one per 64 pending columns up to half the
     * validation window, so flight-speed unloading cannot outrun the
     * incremental path and strand thousands of columns for the zombie audit.
     */
    private int columnUnloadBudget() {
        return MAX_COLUMN_UNLOADS_PER_FRAME + Math.min(
                MAX_COLUMN_UNLOAD_VALIDATIONS_PER_FRAME / 2, pendingColumnUnloads.size() / 64);
    }

    /**
     * Re-flags sections whose captures were sacrificed under backpressure so
     * vanilla rebuilds them and the capture path runs again.
     */
    private void reflagEvictedSections() {
        evictedSectionScratch.clear();
        BlockDataQueue.drainEvictedSections(evictedSectionScratch, MAX_EVICTED_REFLAGS_PER_FRAME);
        if (evictedSectionScratch.isEmpty()) return;

        for (BlockDataQueue.EvictedSection section : evictedSectionScratch) {
            host.scheduleResidentSectionRebuild(
                    section.x(), section.y(), section.z());
        }
    }

    private void drainSectionRemovals(long deadline) {
        deferredSectionRemovals.clear();
        int removed = 0;
        int examined = 0;
        try {
            while (examined < MAX_SECTION_REMOVAL_VALIDATIONS_PER_FRAME) {
                if (examined > 0 && System.nanoTime() >= deadline) return;
                SectionLeftRenderSetQueue.SectionCoord section =
                        SectionLeftRenderSetQueue.pollEmptyCandidate();
                if (section == null) return;
                examined++;

                if (!isLiveSectionEmpty(section.x(), section.y(), section.z())) continue;
                if (removed >= MAX_SECTION_REMOVALS_PER_FRAME
                        || columnPublication.retirementBackpressured()
                        || lightReconciler.isBackpressured()) {
                    deferredSectionRemovals.add(section);
                    continue;
                }

                removeSectionDeferred(section.x(), section.y(), section.z());
                removed++;
            }
        } finally {
            for (SectionLeftRenderSetQueue.SectionCoord section : deferredSectionRemovals) {
                SectionLeftRenderSetQueue.enqueueEmptyCandidate(section);
            }
            deferredSectionRemovals.clear();
        }
    }

    private boolean isLiveSectionEmpty(int cx, int sy, int cz) {
        if (!host.hasWorld()) {
            return false;
        }
        return host.isSectionEmpty(cx, sy, cz);
    }

    private int drainBlockData() {
        if (pendingCaptureIndex >= pending.size()) {
            pending.clear();
            pendingCaptureIndex = 0;
            // Geometry never waits on light bookkeeping: a full capture queue
            // blocks vanilla's shared chunk workers, so pinning this drain on
            // light backpressure freezes the whole build pipeline. Light work
            // stays paced by its own per-frame budget and merely lags.
            BlockDataQueue.drain(pending);
        }

        int skipped = 0;
        int processed = 0;

        while (pendingCaptureIndex < pending.size()) {
            BlockDataQueue.QueuedCapture queued = pending.get(pendingCaptureIndex++);
            processed++;
            TerrainSectionData cap = (TerrainSectionData) queued.data();
            if (!BlockDataQueue.isCurrent(queued)) {
                skipped++;
                releaseMeshData(cap);
                continue;
            }
            int cx = cap.sectionX();
            int sy = cap.sectionY();
            int cz = cap.sectionZ();

            long chunkKey = packChunkKey(cx, cz);
            if (!host.isChunkLoaded(cx, cz)) {
                skipped++;
                releaseMeshData(cap);
                continue;
            }
            if (pendingColumnUnloads.remove(chunkKey)) {
                // A live capture supersedes an unload event that was deferred
                // while native mesh retirement was backpressured.
                LOGGER.debug("Cancelled deferred unload for reloaded chunk ({},{})", cx, cz);
            }

            ColumnPublicationOrchestrator.SectionUpdate update =
                    columnPublication.applySection(cap);
            TerrainState.SectionEntry newEntry = update.current();
            if (newEntry == null) continue;
            TerrainState.SectionEntry oldEntry = update.previous();
            lightReconciler.reconcile(
                    cx, sy, cz, oldEntry, newEntry, cap.lights());
        }

        if (pendingCaptureIndex >= pending.size()) {
            pending.clear();
            pendingCaptureIndex = 0;
        }

        if (skipped > 0 && LOGGER.isDebugEnabled()) {
            LOGGER.debug("Skipped {} stale BlockDataQueue entries for unloaded chunks", skipped);
        }
        return processed;
    }

    /**
     * Keyed lookups over the world's section range replace a scan of
     * active light tasks: O(world height) map lookups per column, plus one
     * linear work-queue removal per task actually cancelled. Ingestion
     * calls this once per unload event, and a single frame can ingest a
     * border-sized burst of them.
     */
    private void cancelLightTasksForColumn(int cx, int cz) {
        int topSection = host.topSectionCoord();
        for (int sy = host.bottomSectionCoord(); sy < topSection; sy++) {
            lightReconciler.cancel(cx, sy, cz);
        }
    }

    private void removeSectionDeferred(int cx, int sy, int cz) {
        TerrainState.SectionEntry oldEntry = terrain.findSection(cx, sy, cz);
        int changedMask = ColumnPublicationOrchestrator.categoryDifferenceMask(
                oldEntry != null ? oldEntry.data : null, null);
        lightReconciler.cancel(cx, sy, cz);
        lightReconciler.retire(terrain.removeSectionForRetirement(cx, sy, cz));
        if (terrain.findColumn(cx, cz) == null) {
            columnPublication.removeColumn(cx, cz);
        } else if (changedMask != 0) {
            columnPublication.markColumnDirty(cx, cz, changedMask, true);
        }
    }

    private void removeColumnDeferred(int cx, int cz) {
        lightReconciler.retire(terrain.removeColumnForRetirement(cx, cz));
        columnPublication.removeColumn(cx, cz);
    }

    /** Drops a capture's mesh data that will never reach a terrain entry. */
    private static void releaseMeshData(TerrainSectionData capture) {
        SectionMeshData data = capture.meshData();
        if (data != null) data.release();
    }

    /**
     * Recomputes the terrain gauges, which costs two walks of every column. The values are
     * slowly-varying counts of streaming state rather than per-frame events, so they are
     * refreshed on a cadence and held in between; callers emit the held value every frame,
     * which they must, because the timer zeroes counters at the start of each frame.
     */
    private void publishTerrainDiagnostics() {
        ColumnPublicationOrchestrator.Diagnostics publication =
                columnPublication.diagnostics(BlockDataQueue.pendingCount());
        lastTerrainDiagnostics = new TerrainDiagnostics(
                terrain.columnCount(), publication.columnsPublished(),
                publication.columnsWithGeometry(), publication.columnsMissingGeometry(),
                publication.missingGeometrySections(), publication.retainedPages(),
                terrain.sectionCount(), publication.pendingBuilds(),
                publication.readyMeshes(), publication.captureQueue(),
                publication.pendingMeshBytes());
    }

    /** @return the number of native instance submits this pass made */
    private int draw(
            RemixAPI api,
            PerformanceTimer t,
            double cameraX,
            double cameraY,
            double cameraZ) {
        t.begin(perfBfs);
        try (TerrainProfile.Scope bfsScope =
                     TerrainProfile.begin("BFS reachability")) {
            terrain.ensureReachabilityOnce();
        }
        t.end(perfBfs);

        int drawCalls = 0;
        int lightsCulled = 0;
        int lightsDrawn = 0;
        int lightsBeyondReach = 0;

        try (TerrainProfile.Scope traversalScope =
                     TerrainProfile.begin("draw traversal")) {
            // Remix keeps a scene instance until it is explicitly removed, and every terrain
            // instance is submitted at the point it is created or mutated, so re-submitting the
            // whole set each frame only re-asserts state the runtime already holds. The one case
            // that does need it is resurrection: hideRetainedInstances() removes every instance,
            // leaving handles inactive until something submits them again.
            drawCalls += columnPublication.resubmitHiddenInstances(api);

            for (TerrainState.ChunkColumn column : terrain.columns()) {
                if (!terrain.isColumnWithinLightCullRange(column.cx, column.cz)) {
                    lightsCulled += column.lightCount();
                    continue;
                }
                List<TerrainState.SectionEntry> sections = column.sectionEntries();
                for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
                    TerrainState.SectionEntry entry = sections.get(sectionIndex);
                    if (!terrain.isSectionReachableForLights(column.cx, column.cz, entry.sy)) {
                        lightsCulled += entry.lightCount();
                        continue;
                    }
                    List<TerrainLight> lights = entry.lightEntries();
                    for (int lightIndex = 0; lightIndex < lights.size(); lightIndex++) {
                        TerrainLight light = lights.get(lightIndex);
                        if (light.shouldDraw(
                                cameraX,
                                cameraY,
                                cameraZ,
                                lightReachThreshold,
                                terrainFrame)) {
                            light.light.drawFast(api);
                            lightsDrawn++;
                        } else {
                            lightsBeyondReach++;
                        }
                    }
                }
            }
            traversalScope.drawCalls(drawCalls);
        }

        t.count(cntLightsCulled, lightsCulled);
        t.count(cntLightsDrawn, lightsDrawn);
        t.count(cntLightsBeyondReach, lightsBeyondReach);
        t.count(cntReachable, terrain.reachableCount());
        t.count(cntFloodIncrementalUs, terrain.takeIncrementalFloodNanos() / 1_000L);
        return drawCalls;
    }

    /** Hides retained terrain instances while preserving cached meshes. */
    public void hideRetainedInstances() {
        columnPublication.hideRetainedInstances(host.remixApi());
    }

    /** Spreads the safety audit across frames so its cost does not scale with view distance in one frame. */
    private void advanceZombieAudit(long deadline) {
        if (!host.hasWorld()) return;
        if (columnPublication.retirementBackpressured()
                || lightReconciler.isBackpressured()) return;

        if (zombieAuditColumns == null) {
            if (++framesSinceAudit < ZOMBIE_AUDIT_INTERVAL) return;
            framesSinceAudit = 0;
            zombieAuditColumns = terrain.columnKeysSnapshot();
            zombieAuditCursor = 0;
            zombieAuditRemoved = 0;
        }

        int end = Math.min(zombieAuditColumns.length,
                zombieAuditCursor + ZOMBIE_AUDIT_COLUMNS_PER_FRAME);
        int examined = 0;
        int removedThisFrame = 0;
        while (zombieAuditCursor < end) {
            if (examined > 0 && System.nanoTime() >= deadline) return;
            long key = zombieAuditColumns[zombieAuditCursor++];
            examined++;
            int cx = (int) (key >> 32);
            int cz = (int) key;
            if (!host.isChunkLoaded(cx, cz)
                    && terrain.findColumn(cx, cz) != null) {
                // The column left without an unload event, so nothing has
                // cancelled its light tasks yet.
                cancelLightTasksForColumn(cx, cz);
                removeColumnDeferred(cx, cz);
                zombieAuditRemoved++;
                if (++removedThisFrame >= ZOMBIE_AUDIT_REMOVALS_PER_FRAME) return;
            }
        }

        if (zombieAuditCursor == zombieAuditColumns.length) {
            if (zombieAuditRemoved > 0) {
                LOGGER.warn("Zombie audit: removed {} orphaned columns", zombieAuditRemoved);
            }
            zombieAuditColumns = null;
        }
    }

    public void clear() {
        columnPublication.clear();
        lightReconciler.retire(terrain.clearForRetirement());
        lightReconciler.clear();
        currentWorldEpoch = Long.MIN_VALUE;
        for (int i = pendingCaptureIndex; i < pending.size(); i++) {
            pending.get(i).data().release();
        }
        pending.clear();
        pendingCaptureIndex = 0;
        BlockDataQueue.clear();
        SectionLeftRenderSetQueue.clear();
        chunkUnloadEvents.clear();
        pendingColumnUnloads.clear();
        framesSinceAudit = 0;
        terrainFrame = 0;
        zombieAuditColumns = null;
        zombieAuditCursor = 0;
        zombieAuditRemoved = 0;
        lastTerrainDiagnostics = TerrainDiagnostics.EMPTY;
        // Recompute on the next frame rather than holding the cleared gauges.
        LOGGER.info("ChunkManager cleared");
    }

    public void destroy() {
        clear();
        columnPublication.shutdown();
    }

    /**
     * Version-specific Minecraft access used by the canonical terrain driver.
     * Implementations translate host state only; terrain policy stays here.
     */
    public interface Host {
        RemixAPI remixApi();
        TerrainMaterialSet terrainMaterials();
        boolean areTerrainMaterialsReady();
        boolean hasWorld();
        long worldEpoch();
        int viewDistanceChunks();
        boolean isChunkLoaded(int chunkX, int chunkZ);
        boolean isSectionEmpty(int chunkX, int sectionY, int chunkZ);
        int bottomSectionCoord();
        int topSectionCoord();
        void scheduleResidentSectionRebuild(int sectionX, int sectionY, int sectionZ);
        TerrainLightReconciler.Radiance resolveRadiance(LightEntry entry);
        float torchReferencePower();
    }

}
