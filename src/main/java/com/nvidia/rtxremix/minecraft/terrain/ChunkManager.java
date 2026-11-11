/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.minecraft.util.LongObjectMap;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionMeshData;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionBlockData;
import com.nvidia.rtxremix.minecraft.terrain.capture.DirectQuadWriter;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.profiling.PerformanceTimer;
import com.nvidia.rtxremix.minecraft.light.LightPower;
import com.nvidia.rtxremix.minecraft.light.LightEntry;
import com.nvidia.rtxremix.minecraft.light.BlockLightRuleManager;
import com.nvidia.rtxremix.minecraft.light.BlockLightKey;
import com.nvidia.rtxremix.minecraft.light.BlockLightIntensityControls;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixLightStore;
import com.nvidia.rtxremix.minecraft.asset.RemixLight;
import com.nvidia.rtxremix.minecraft.asset.RemixInstanceStore;
import com.nvidia.rtxremix.minecraft.asset.RemixInstance;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.ClientViewDistance;
import com.nvidia.rtxremix.Float3D;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.LightInfoCylinderEXT;
import com.nvidia.rtxremix.LightInfoDiskEXT;
import com.nvidia.rtxremix.LightInfoLightShaping;
import com.nvidia.rtxremix.LightInfoRectEXT;
import com.nvidia.rtxremix.LightInfoSphereEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.Transform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * Coordinates the terrain pipeline from worker captures to stable retained
 * chunk-column assets. Camera movement never changes terrain mesh ownership.
 */
public class ChunkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkManager.class);
    public static final int CAT_SOLID       = 0;
    public static final int CAT_CUTOUT      = 1;  // cutout / cutout_mipped, alpha test (binary opacity)
    public static final int CAT_TRANSLUCENT = 2;
    public static final int CAT_OVERLAY     = 3;  // decal overlays, DECAL_STATIC + alpha blend
    public static final int CAT_COUNT       = 4;

    /** Light cull distance in chunks when the user sets none. */
    public static final int DEFAULT_LIGHT_CULL_CHUNKS = 8;

    /** Distance in blocks at which the shipped torch stops being drawn; brighter lights reach further. */
    public static final int DEFAULT_LIGHT_REACH_BLOCKS = 128;
    public static final int MIN_LIGHT_REACH_BLOCKS = 16;
    public static final int MAX_LIGHT_REACH_BLOCKS = 256;

    private final RemixRenderer renderer;
    private final TerrainState terrain;
    private final BlockMeshBuilder meshBuilder;
    private final LongObjectMap<ColumnRenderState> renderColumns = new LongObjectMap<>();
    private final TerrainNativeCallStats nativeCallStats = new TerrainNativeCallStats();
    private final TerrainAssetRetirementQueue retirementQueue = new TerrainAssetRetirementQueue(nativeCallStats);
    private final ArrayList<ColumnRenderState> dirtyBuildScratch = new ArrayList<>();

    private ClientLevel currentWorld;
    /**
     * Live count of pages across every render column, maintained where activePages changes.
     * Published through {@link TerrainDiagnostics#retainedPages()} as Chunk.RetainedPages,
     * which the dashboard, the CI scene-completeness gate and {@code /rtx status} read.
     */
    private int retainedPageCount;
    private static final int DIAGNOSTICS_REFRESH_FRAMES = 8;
    /** Frames left to hold the current gauges. Starts at zero so the first frame computes. */
    private int diagnosticsRefreshCountdown;
    /** Set when every retained instance has been removed and the next draw must resubmit them. */
    private boolean retainedInstancesHidden;

    private final List<BlockDataQueue.QueuedCapture> pending = new ArrayList<>();
    private final List<BlockDataQueue.EvictedSection> evictedSectionScratch = new ArrayList<>();
    private static final int MAX_EVICTED_REFLAGS_PER_FRAME = 128;
    private int pendingCaptureIndex;

    // Light mutations are native API calls just like mesh uploads. Keep each
    // section's desired state in a resumable task and execute only atomic
    // create/update/destroy units under a per-frame budget.
    private final ArrayDeque<LightWorkItem> lightWorkQueue = new ArrayDeque<>();
    private final Map<LightSectionKey, LightReconcileTask> activeLightTasks = new HashMap<>();
    private long pendingLightOps;
    private boolean lightWorkBackpressured;
    private long lastLightFailureLogNanos;
    private static final int MAX_LIGHT_OPS_PER_FRAME = Math.max(1,
            Integer.getInteger("rtxremix.chunk.maxLightOpsPerFrame", 32));
    private static final long LIGHT_TIME_BUDGET_NS = Math.max(100_000L,
            Long.getLong("rtxremix.chunk.lightBudgetNs", 750_000L));
    private static final long MAX_PENDING_LIGHT_OPS = Math.max(256L,
            Long.getLong("rtxremix.chunk.maxPendingLightOps", 4096L));

    private record LightSectionKey(int cx, int sy, int cz) {}

    private enum LightMutationKind { CREATE, UPDATE, REMOVE }

    private record LightMutation(LightMutationKind kind, BlockLightKey key,
                                 LightEntry incoming, RemixLight existing,
                                 int fingerprint) {
        int nativeCallCost() {
            return kind == LightMutationKind.UPDATE ? 2 : 1;
        }
    }

    private interface LightWorkItem {
        int remaining();
        default int nextNativeCallCost() { return 1; }
        void runOne();
        default void onFinished() {}
        default void destroyRemainingOnClear() {}
    }

    private final class LightReconcileTask implements LightWorkItem {
        private final LightSectionKey section;
        private final TerrainState.SectionEntry entry;
        private final BlockTextureManager textures;
        private final List<LightMutation> mutations;
        private int cursor;

        LightReconcileTask(LightSectionKey section,
                           TerrainState.SectionEntry oldEntry,
                           TerrainState.SectionEntry newEntry,
                           List<LightEntry> incomingEntries,
                           BlockTextureManager textures) {
            this.section = section;
            this.entry = newEntry;
            this.textures = textures;

            Map<BlockLightKey, LightEntry> incoming = new LinkedHashMap<>();
            for (LightEntry light : incomingEntries) incoming.put(light.key(), light);
            mutations = new ArrayList<>(incoming.size()
                    + (oldEntry != null ? oldEntry.lightCount() : 0));

            if (oldEntry != null) {
                boolean transferOwnership = oldEntry != newEntry;
                for (Map.Entry<BlockLightKey, TerrainLight> old : oldEntry.lights().entrySet()) {
                    BlockLightKey key = old.getKey();
                    TerrainLight record = old.getValue();
                    RemixLight existing = record.light;
                    LightEntry desired = incoming.remove(key);
                    Integer oldFingerprint = oldEntry.lightFingerprints().get(key);

                    // Keep the last committed native light visible while its
                    // update/removal waits for a render-thread budget slot. The
                    // whole record moves: a fingerprint-equal light gets no UPDATE,
                    // so nothing else would carry its position and power over.
                    if (transferOwnership) {
                        newEntry.putLight(key, record);
                        if (oldFingerprint != null) {
                            newEntry.lightFingerprints().put(key, oldFingerprint);
                        }
                    }

                    if (desired == null) {
                        mutations.add(new LightMutation(
                                LightMutationKind.REMOVE, key, null, existing, 0));
                    } else {
                        int desiredFingerprint = lightFingerprint(desired);
                        if (oldFingerprint == null || oldFingerprint != desiredFingerprint) {
                            mutations.add(new LightMutation(
                                    LightMutationKind.UPDATE, key, desired,
                                    existing, desiredFingerprint));
                        } else {
                            newEntry.lightFingerprints().put(key, desiredFingerprint);
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
                mutations.add(new LightMutation(
                        LightMutationKind.CREATE, added.getKey(), added.getValue(),
                        null, lightFingerprint(added.getValue())));
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
            LightMutation mutation = mutations.get(cursor);
            if (activeLightTasks.get(section) != this
                    || !terrain.isCurrentSectionEntry(
                            section.cx(), section.sy(), section.cz(), entry)) {
                cursor++;
                return;
            }

            try {
                switch (mutation.kind()) {
                    case CREATE -> createLight(mutation);
                    case UPDATE -> updateLight(mutation);
                    case REMOVE -> removeLight(mutation);
                }
            } catch (RuntimeException failure) {
                mutations.add(mutations.remove(cursor));
                throw failure;
            }
            cursor++;
        }

        private void createLight(LightMutation mutation) {
            if (entry.lights().containsKey(mutation.key())) return;
            LightEntry incoming = mutation.incoming();
            BlockLightRadiance radiance = blockLightRadiance(incoming, textures);
            LightInfo info = buildBlockLightInfo(incoming, radiance);
            RemixLight light = new RemixLight(info, null);
            RemixLightStore.get().create(light);
            TerrainLight record = new TerrainLight(light);
            record.place(incoming.x(), incoming.y(), incoming.z(),
                    LightPower.of(incoming, radiance.r(), radiance.g(), radiance.b()));
            entry.putLight(mutation.key(), record);
            entry.lightFingerprints().put(mutation.key(), mutation.fingerprint());
            terrain.adjustSectionLightCount(
                    section.cx(), section.sy(), section.cz(), entry, 1);
        }

        private void updateLight(LightMutation mutation) {
            TerrainLight record = entry.lights().get(mutation.key());
            if (record == null || record.light != mutation.existing()) return;
            LightEntry incoming = mutation.incoming();
            BlockLightRadiance radiance = blockLightRadiance(incoming, textures);
            LightInfo info = buildBlockLightInfo(incoming, radiance);
            RemixLightStore.get().update(mutation.existing(), info);
            record.place(incoming.x(), incoming.y(), incoming.z(),
                    LightPower.of(incoming, radiance.r(), radiance.g(), radiance.b()));
            entry.lightFingerprints().put(mutation.key(), mutation.fingerprint());
        }

        private void removeLight(LightMutation mutation) {
            TerrainLight record = entry.lights().get(mutation.key());
            if (record == null || record.light != mutation.existing()) return;
            if (!mutation.existing().isDeleted()) mutation.existing().destroy();
            if (entry.removeLight(mutation.key()) != null) {
                terrain.adjustSectionLightCount(
                        section.cx(), section.sy(), section.cz(), entry, -1);
            }
        }

        @Override
        public void onFinished() {
            activeLightTasks.remove(section, this);
        }
    }

    private static final class LightRetireTask implements LightWorkItem {
        private final List<RemixLight> lights;
        private int cursor;

        LightRetireTask(List<RemixLight> lights) {
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
                if (!light.isDeleted()) light.destroy();
            } catch (RuntimeException failure) {
                lights.add(lights.remove(cursor));
                throw failure;
            }
            cursor++;
        }

        @Override
        public void destroyRemainingOnClear() {
            while (cursor < lights.size()) runOne();
        }
    }

    // Background workers only merge CPU-side buffers. Native Remix resources
    // are created and retired on the render thread under per-frame budgets.
    private final ConcurrentLinkedQueue<MeshBuildResult> readyQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingResults = new AtomicInteger(0);
    private final AtomicLong pendingMeshBytes = new AtomicLong(0);
    private final ExecutorService meshBuildExecutor;
    private MeshInstallCursor activeInstall;
    private volatile int epoch;
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
    private static final int MAX_SECTION_REMOVALS_PER_FRAME = Math.max(1,
            Integer.getInteger("rtxremix.chunk.maxSectionRemovalsPerFrame", 8));
    private static final int MAX_SECTION_REMOVAL_VALIDATIONS_PER_FRAME = Math.max(
            MAX_SECTION_REMOVALS_PER_FRAME,
            Integer.getInteger("rtxremix.chunk.maxSectionRemovalValidationsPerFrame", 64));
    private final ArrayList<SectionLeftRenderSetQueue.SectionCoord> deferredSectionRemovals =
            new ArrayList<>(MAX_SECTION_REMOVAL_VALIDATIONS_PER_FRAME);
    private static final long MAINTENANCE_TIME_BUDGET_NS = Math.max(250_000L,
            Long.getLong("rtxremix.chunk.maintenanceBudgetNs", 1_000_000L));

    private static final class ColumnRenderState {
        final long key;
        final int cx;
        final int cz;
        final List<InstalledMeshPage> activePages = new ArrayList<>();
        final int[] categoryFingerprints = new int[CAT_COUNT];
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

        boolean hasGeometry() { return !activePages.isEmpty(); }
    }

    private static final class MeshBuildResult {
        private final ColumnRenderState owner;
        private final int generation;
        private final int categoryMask;
        private final int[] categoryFingerprints;
        private final int epoch;
        private volatile BlockMeshBuilder.MeshPage[] pages;
        private volatile long accountedBytes;
        private volatile Throwable failure;
        private volatile boolean complete;

        MeshBuildResult(ColumnRenderState owner, int generation, int categoryMask,
                        int[] categoryFingerprints, int epoch, long estimatedBytes) {
            this.owner = owner;
            this.generation = generation;
            this.categoryMask = categoryMask;
            this.categoryFingerprints = categoryFingerprints;
            this.epoch = epoch;
            this.accountedBytes = estimatedBytes;
        }

        ColumnRenderState owner() { return owner; }
        int generation() { return generation; }
        int categoryMask() { return categoryMask; }
        int[] categoryFingerprints() { return categoryFingerprints; }
        int epoch() { return epoch; }
        BlockMeshBuilder.MeshPage[] pages() { return pages; }
        long accountedBytes() { return accountedBytes; }
        Throwable failure() { return failure; }
        boolean complete() { return complete; }
    }

    private static final class MeshInstallCursor {
        final MeshBuildResult result;
        final List<InstalledMeshPage> installedPages = new ArrayList<>();
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

    public record TerrainDiagnostics(
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
        public static final TerrainDiagnostics EMPTY =
                new TerrainDiagnostics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
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
    private float lightReachThreshold = TerrainLight.thresholdFor(DEFAULT_LIGHT_REACH_BLOCKS);
    /** The radius TerrainState currently holds, so it is only pushed on change. */
    private int appliedLightCullChunks = -1;
    private long[] zombieAuditColumns;
    private int zombieAuditCursor;
    private int zombieAuditRemoved;

    // --- construction ---

    public ChunkManager(RemixRenderer renderer) {
        this.renderer = renderer;

        terrain = new TerrainState(DEFAULT_LIGHT_CULL_CHUNKS * 16);
        meshBuilder = new BlockMeshBuilder();
        AtomicInteger threadNumber = new AtomicInteger();
        meshBuildExecutor = Executors.newFixedThreadPool(MESH_BUILD_THREADS, task -> {
            Thread thread = new Thread(task,
                    "RTX Remix mesh builder " + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        });

        LOGGER.info("ChunkManager init (retained chunk columns, {} mesh workers, {} pending builds)",
                MESH_BUILD_THREADS, MAX_PENDING);
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
        int effective = effectiveLightCullChunks(lightCullChunks, ClientViewDistance.chunks());
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
        lightReachThreshold = TerrainLight.thresholdFor(lightReachBlocks);
    }

    public int getLightReach() {
        return lightReachBlocks;
    }

    /** The weight floor the draw loop applies this frame, for tests. */
    float lightReachThreshold() {
        return lightReachThreshold;
    }

    /** Per-rule light population, for {@code /rtx lights}. */
    public record LightRuleStats(ResourceLocation ruleId, int total, int drawn) {}

    /**
     * Counts every terrain light by rule, and how many of them were drawn in
     * the last completed frame, most populous rule first. Render thread only.
     */
    public List<LightRuleStats> lightStatsByRule() {
        Map<ResourceLocation, int[]> counts = new LinkedHashMap<>();
        for (TerrainState.ChunkColumn column : terrain.columns()) {
            for (TerrainState.SectionEntry entry : column.sectionEntries()) {
                for (Map.Entry<BlockLightKey, TerrainLight> light : entry.lights().entrySet()) {
                    int[] tally = counts.computeIfAbsent(light.getKey().ruleId(), ignored -> new int[2]);
                    tally[0]++;
                    if (light.getValue().drawnInFrame(terrainFrame)) tally[1]++;
                }
            }
        }
        List<LightRuleStats> stats = new ArrayList<>(counts.size());
        for (Map.Entry<ResourceLocation, int[]> tally : counts.entrySet()) {
            stats.add(new LightRuleStats(tally.getKey(), tally.getValue()[0], tally.getValue()[1]));
        }
        stats.sort(Comparator.comparingInt(LightRuleStats::total).reversed());
        return stats;
    }

    public void onChunkUnloaded(ChunkPos p) {
        BlockDataQueue.markChunkUnloaded(p.x, p.z);
        chunkUnloadEvents.add(packChunkKey(p.x, p.z));
    }

    private static long packChunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public void updateVisibleChunks(ClientLevel world, Vec3 cam,
                                     PerformanceTimer timer, int perfGen, int perfDraw) {
        if (world == null || renderer.getRemixApi() == null) return;
        try (ProfileScope updateScope = ProfileScope.begin("updateVisibleChunks")) {

            if (world != currentWorld) {
                if (currentWorld != null) {
                    LOGGER.info("World changed, clearing terrain");
                    clear();
                }
                currentWorld = world;
            }

            BlockTextureManager tex = renderer.getTextureManager();
            if (tex == null || !tex.isInitialized()) return;
            RemixAPI api = renderer.getRemixApi();

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
            try (ProfileScope genScope = ProfileScope.begin("Chunk.Gen")) {
                terrainFrame++;
                nativeCallStats.beginFrame();

                int pcx = (int) Math.floor(cam.x) >> 4;
                int pcz = (int) Math.floor(cam.z) >> 4;
                terrain.setPlayerPos(pcx, pcz);
                terrain.setPlayerBlockPos(
                        (int) Math.floor(cam.x),
                        (int) Math.floor(cam.y),
                        (int) Math.floor(cam.z));
                syncLightCullDistance();

                long maintenanceDeadline = System.nanoTime() + MAINTENANCE_TIME_BUDGET_NS;
                drainColumnUnloads(maintenanceDeadline);
                drainSectionRemovals(maintenanceDeadline);
                drainLightWork(System.nanoTime() + LIGHT_TIME_BUDGET_NS);
                int capturesProcessed = drainBlockData();
                timer.count(cntCap, capturesProcessed);
                genScope.captureCount(capturesProcessed);
                timer.count(cntWorkerMem, DirectQuadWriter.getTotalAllocatedBytes() / 1024);
                // Retained section meshes live in the pool; this is the off-heap footprint
                // the collector no longer accounts for.
                timer.count(cntPooledDirectMB, DirectBufferPool.allocatedBytes() / (1024 * 1024));
                timer.count(cntBucketGrows, DirectQuadWriter.drainGrowthEvents());
                timer.count(cntBucketGrowKB, DirectQuadWriter.drainGrowthCopiedBytes() / 1024);
                timer.count(cntPendingBuilds, pendingResults.get());
                timer.count(cntPendingMeshMB, pendingMeshBytes.get() / (1024 * 1024));
                timer.count(cntReadyMeshes, countCompletedBuilds());
                timer.count(cntMeshRetireQueue, retirementQueue.pendingRetirements());
                timer.count(cntMeshRetireMB, retirementQueue.pendingRetirementBytes() / (1024 * 1024));
                timer.count(cntLightOpsPending, pendingLightOps);
                timer.count(cntLightTasks, lightWorkQueue.size());
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
                submitDirtyBuilds(tex, pcx, pcz);

                try (ProfileScope installScope = ProfileScope.begin("installReadyMeshes")) {
                    installReadyMeshes(api);
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

                retirementQueue.drainFrame();

                timer.count(cntPagesInstalled, nativeCallStats.pagesInstalled());
                timer.count(cntInstallBytes, nativeCallStats.installBytes());
                timer.count(cntInstancesAdded, nativeCallStats.instancesAdded());
                timer.count(cntInstancesRemoved, nativeCallStats.instancesRemoved());
                timer.count(cntMeshesDestroyed, nativeCallStats.meshesDestroyed());
            } finally {
                timer.end(perfGen);
            }

            timer.begin(perfDraw);
            try (ProfileScope drawScope = ProfileScope.begin("Chunk.Draw")) {
                drawScope.drawCalls(draw(api, timer, cam));
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
            LevelChunk liveChunk = currentWorld == null
                    ? null
                    : currentWorld.getChunkSource().getChunk(cx, cz, false);
            if (liveChunk != null) {
                iterator.remove();
                continue;
            }
            if (retirementQueue.retirementBackpressured()
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

        WorldRendererTerrainAccess worldRenderer =
                (WorldRendererTerrainAccess) Minecraft.getInstance().levelRenderer;
        for (BlockDataQueue.EvictedSection section : evictedSectionScratch) {
            worldRenderer.rtxremix$scheduleResidentSectionRebuild(
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
                        || retirementQueue.retirementBackpressured()
                        || lightWorkBackpressured()) {
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
        if (currentWorld == null) {
            return false;
        }

        LevelChunk wc = currentWorld.getChunkSource().getChunk(cx, cz, false);
        return wc != null && wc.isSectionEmpty(sy);
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

        BlockTextureManager tex = renderer.getTextureManager();
        int skipped = 0;
        int processed = 0;

        while (pendingCaptureIndex < pending.size()) {
            BlockDataQueue.QueuedCapture queued = pending.get(pendingCaptureIndex++);
            processed++;
            SectionBlockData cap = queued.data();
            if (!BlockDataQueue.isCurrent(queued)) {
                skipped++;
                releaseMeshData(cap);
                continue;
            }
            SectionPos sp = cap.sectionPos();
            int cx = sp.getX(), sy = sp.getY(), cz = sp.getZ();

            long chunkKey = packChunkKey(cx, cz);
            LevelChunk liveChunk = null;
            if (currentWorld != null) {
                liveChunk = currentWorld.getChunkSource().getChunk(cx, cz, false);
            }
            if (liveChunk == null) {
                skipped++;
                releaseMeshData(cap);
                continue;
            }
            if (pendingColumnUnloads.remove(chunkKey)) {
                // A live capture supersedes an unload event that was deferred
                // while native mesh retirement was backpressured.
                LOGGER.debug("Cancelled deferred unload for reloaded chunk ({},{})", cx, cz);
            }

            int hash = hashCapture(cap);

            TerrainState.SectionEntry newEntry = terrain.addSection(cx, sy, cz, hash, cap.meshData(),
                    cap.topFaceMask(), cap.bottomFaceMask(),
                    cap.eastFaceMask(), cap.westFaceMask(),
                    cap.southFaceMask(), cap.northFaceMask(),
                    cap.opacityBitmap());
            TerrainState.SectionEntry oldEntry;
            if (newEntry == null) {
                newEntry = terrain.findSection(cx, sy, cz);
                if (newEntry == null) continue;
                oldEntry = newEntry;
            } else {
                oldEntry = terrain.getReplacedEntry();
                markColumnDirty(cx, cz, categoryDifferenceMask(
                        oldEntry != null ? oldEntry.data : null,
                        newEntry.data), oldEntry != null);
            }
            LightSectionKey section = new LightSectionKey(cx, sy, cz);
            cancelLightTask(section);
            LightReconcileTask task = new LightReconcileTask(
                    section, oldEntry, newEntry, cap.lights(), tex);
            enqueueLightTask(task);
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

    private void enqueueLightTask(LightWorkItem task) {
        int remaining = task.remaining();
        if (remaining == 0) {
            task.onFinished();
            return;
        }
        lightWorkQueue.addLast(task);
        pendingLightOps += remaining;
        if (task instanceof LightReconcileTask reconcile) {
            activeLightTasks.put(reconcile.section, reconcile);
        }
    }

    private void drainLightWork(long deadline) {
        int nativeCalls = 0;
        int inspected = 0;
        while (true) {
            LightWorkItem task = lightWorkQueue.peekFirst();
            if (task == null) return;
            int nextCost = Math.max(1, task.nextNativeCallCost());
            if (inspected > 0 && (inspected >= MAX_LIGHT_OPS_PER_FRAME
                    || System.nanoTime() >= deadline)) {
                return;
            }
            if (nativeCalls > 0 && nativeCalls + nextCost > MAX_LIGHT_OPS_PER_FRAME) return;
            inspected++;

            try {
                task.runOne();
            } catch (RuntimeException failure) {
                lightWorkQueue.removeFirst();
                lightWorkQueue.addLast(task);
                long now = System.nanoTime();
                if (lastLightFailureLogNanos == 0
                        || now - lastLightFailureLogNanos >= 1_000_000_000L) {
                    lastLightFailureLogNanos = now;
                    LOGGER.warn("Native block-light operation failed; retaining it for retry", failure);
                }
                return;
            }

            pendingLightOps = Math.max(0, pendingLightOps - 1);
            nativeCalls += nextCost;
            if (task.remaining() == 0) {
                lightWorkQueue.removeFirst();
                task.onFinished();
            }
        }
    }

    private boolean lightWorkBackpressured() {
        if (!lightWorkBackpressured) {
            lightWorkBackpressured = pendingLightOps >= MAX_PENDING_LIGHT_OPS;
        } else if (pendingLightOps <= MAX_PENDING_LIGHT_OPS / 2) {
            lightWorkBackpressured = false;
        }
        return lightWorkBackpressured;
    }

    private void cancelLightTask(LightSectionKey section) {
        LightReconcileTask task = activeLightTasks.remove(section);
        if (task != null && lightWorkQueue.remove(task)) {
            pendingLightOps = Math.max(0, pendingLightOps - task.remaining());
        }
    }

    /**
     * Keyed lookups over the world's section range replace a scan of
     * activeLightTasks: O(world height) map lookups per column, plus one
     * linear lightWorkQueue removal per task actually cancelled. Ingestion
     * calls this once per unload event, and a single frame can ingest a
     * border-sized burst of them.
     */
    private void cancelLightTasksForColumn(int cx, int cz) {
        int topSection = currentWorld.getMaxSection();
        for (int sy = currentWorld.getMinSection(); sy < topSection; sy++) {
            cancelLightTask(new LightSectionKey(cx, sy, cz));
        }
    }

    private void enqueueLightRetirement(List<RemixLight> lights) {
        if (!lights.isEmpty()) enqueueLightTask(new LightRetireTask(lights));
    }

    private void removeSectionDeferred(int cx, int sy, int cz) {
        TerrainState.SectionEntry oldEntry = terrain.findSection(cx, sy, cz);
        int changedMask = categoryDifferenceMask(oldEntry != null ? oldEntry.data : null, null);
        cancelLightTask(new LightSectionKey(cx, sy, cz));
        enqueueLightRetirement(terrain.removeSectionForRetirement(cx, sy, cz));
        if (terrain.findColumn(cx, cz) == null) {
            removeRenderColumn(cx, cz);
        } else if (changedMask != 0) {
            markColumnDirty(cx, cz, changedMask, true);
        }
    }

    private void removeColumnDeferred(int cx, int cz) {
        enqueueLightRetirement(terrain.removeColumnForRetirement(cx, cz));
        removeRenderColumn(cx, cz);
    }

    private void removeRenderColumn(int cx, int cz) {
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

    private void clearLightWork() {
        for (LightWorkItem task : lightWorkQueue) {
            task.destroyRemainingOnClear();
        }
        lightWorkQueue.clear();
        activeLightTasks.clear();
        pendingLightOps = 0;
        lightWorkBackpressured = false;
        lastLightFailureLogNanos = 0;
    }

    /** Linear RGB radiance after every multiplier, shared by the native light and its power. */
    private record BlockLightRadiance(float r, float g, float b) {}

    private static BlockLightRadiance blockLightRadiance(LightEntry entry, BlockTextureManager tex) {
        float[] blockColor = (tex != null)
                ? tex.getLightColorForBlock(entry.block())
                : BlockTextureManager.FALLBACK_WARM_WHITE;
        float r = entry.hasColor() ? entry.colorR() : blockColor[0];
        float g = entry.hasColor() ? entry.colorG() : blockColor[1];
        float b = entry.hasColor() ? entry.colorB() : blockColor[2];
        float baseIntensity = entry.hasIntensity()
                ? entry.intensity()
                : entry.lightLevel() * BlockLightRuleManager.RADIANCE_PER_LIGHT_LEVEL;
        baseIntensity *= entry.authoredIntensityMultiplier();
        float intensity =
                BlockLightIntensityControls.apply(entry.key().ruleId(), baseIntensity);
        return new BlockLightRadiance(r * intensity, g * intensity, b * intensity);
    }

    private LightInfo buildBlockLightInfo(LightEntry entry, BlockLightRadiance radiance) {
        LightInfo.Builder builder = LightInfo.builder()
                .radiance(radiance.r(), radiance.g(), radiance.b());
        return switch (entry.shape()) {
            case SPHERE -> {
                LightInfoSphereEXT.Builder sphere = LightInfoSphereEXT.builder()
                        .position(entry.x(), entry.y(), entry.z())
                        .radius(entry.radius())
                        .volumetricRadianceScale(entry.volumetricRadianceScale());
                // Spot lights: only sphere/rect/disk accept shaping in the Remix
                // API, and shapedSphere is currently the only producer of it.
                if (entry.hasShaping()) {
                    sphere.shaping(shapingOf(entry));
                }
                yield builder.sphereExt(sphere.build()).build();
            }
            case CYLINDER -> builder
                    .cylinderExt(LightInfoCylinderEXT.builder()
                            .position(entry.x(), entry.y(), entry.z())
                            .radius(entry.radius())
                            .axis(entry.axisX(), entry.axisY(), entry.axisZ())
                            .axisLength(entry.axisLength())
                            .volumetricRadianceScale(entry.volumetricRadianceScale())
                            .build())
                    .build();
            case RECT -> builder
                    .rectExt(LightInfoRectEXT.builder()
                            .position(entry.x(), entry.y(), entry.z())
                            .xAxis(entry.xAxisX(), entry.xAxisY(), entry.xAxisZ())
                            .xSize(entry.xSize())
                            .yAxis(entry.yAxisX(), entry.yAxisY(), entry.yAxisZ())
                            .ySize(entry.ySize())
                            .direction(entry.directionX(), entry.directionY(), entry.directionZ())
                            .volumetricRadianceScale(entry.volumetricRadianceScale())
                            .build())
                    .build();
            case DISK -> builder
                    .diskExt(LightInfoDiskEXT.builder()
                            .position(entry.x(), entry.y(), entry.z())
                            .xAxis(entry.xAxisX(), entry.xAxisY(), entry.xAxisZ())
                            .xRadius(entry.xSize())
                            .yAxis(entry.yAxisX(), entry.yAxisY(), entry.yAxisZ())
                            .yRadius(entry.ySize())
                            .direction(entry.directionX(), entry.directionY(), entry.directionZ())
                            .volumetricRadianceScale(entry.volumetricRadianceScale())
                            .build())
                    .build();
        };
    }

    private static LightInfoLightShaping shapingOf(LightEntry entry) {
        LightInfoLightShaping shaping = new LightInfoLightShaping();
        shaping.direction = new Float3D(
                entry.shapingDirectionX(), entry.shapingDirectionY(), entry.shapingDirectionZ());
        shaping.coneAngleDegrees = entry.coneAngleDegrees();
        shaping.coneSoftness = entry.coneSoftness();
        shaping.focusExponent = entry.focusExponent();
        return shaping;
    }

    /**
     * Full light-parameter fingerprint for deciding whether a stable
     * {@link BlockLightKey} can keep its existing native light without an
     * update round-trip.
     */
    private static int lightFingerprint(LightEntry le) {
        return Objects.hash(
                BuiltInRegistries.BLOCK.getId(le.block()),
                le.key().ruleId(),
                le.key().lightIndex(),
                le.lightLevel(),
                le.shape(),
                bits(le.x()), bits(le.y()), bits(le.z()),
                bits(le.colorR()), bits(le.colorG()), bits(le.colorB()), le.hasColor(),
                bits(le.intensity()), le.hasIntensity(),
                bits(le.radius()),
                bits(le.axisX()), bits(le.axisY()), bits(le.axisZ()), bits(le.axisLength()),
                bits(le.xAxisX()), bits(le.xAxisY()), bits(le.xAxisZ()), bits(le.xSize()),
                bits(le.yAxisX()), bits(le.yAxisY()), bits(le.yAxisZ()), bits(le.ySize()),
                bits(le.directionX()), bits(le.directionY()), bits(le.directionZ()),
                le.hasShaping(),
                bits(le.shapingDirectionX()), bits(le.shapingDirectionY()), bits(le.shapingDirectionZ()),
                bits(le.coneAngleDegrees()), bits(le.coneSoftness()), bits(le.focusExponent()),
                bits(le.volumetricRadianceScale()));
    }

    private static int bits(float value) {
        return Float.floatToIntBits(value);
    }

    // --- background build / native install ---

    private ColumnRenderState markColumnDirty(
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
        if (state.dirtyMask == 0) state.dirtySinceFrame = terrainFrame;
        state.dirtyMask |= categoryMask;
        if (immediate && state.hasPublishedState) {
            state.urgentBuild = true;
            state.settleUntilFrame = terrainFrame;
        } else if (!state.urgentBuild) {
            state.settleUntilFrame = terrainFrame + COLUMN_STREAM_SETTLE_FRAMES;
        }
        return state;
    }

    private void submitDirtyBuilds(BlockTextureManager textures, int playerCx, int playerCz) {
        dirtyBuildScratch.clear();
        for (ColumnRenderState state : renderColumns.values()) {
            boolean streamSettled = terrainFrame >= state.settleUntilFrame;
            boolean maxWaitElapsed = state.dirtySinceFrame != Long.MIN_VALUE
                    && terrainFrame - state.dirtySinceFrame >= COLUMN_STREAM_MAX_WAIT_FRAMES;
            if (state.dirtyMask != 0 && !state.buildInFlight
                    && (state.urgentBuild || streamSettled || maxWaitElapsed)) {
                dirtyBuildScratch.add(state);
            }
        }
        dirtyBuildScratch.sort((left, right) -> {
            if (left.hasPublishedState != right.hasPublishedState) {
                return left.hasPublishedState ? 1 : -1;
            }
            int distanceOrder = Long.compare(
                    columnDistanceSq(left, playerCx, playerCz),
                    columnDistanceSq(right, playerCx, playerCz));
            return distanceOrder != 0 ? distanceOrder : Long.compare(left.key, right.key);
        });

        int currentEpoch = epoch;
        for (ColumnRenderState state : dirtyBuildScratch) {
            if (pendingResults.get() >= MAX_PENDING) break;
            TerrainState.ChunkColumn column = terrain.findColumn(state.cx, state.cz);
            if (column == null || column.isEmpty()) continue;

            List<TerrainState.SectionSnapshot> snapshots = terrain.createSnapshot(column);
            int[] fingerprints = columnCategoryFingerprints(snapshots);
            int requestedMask = state.dirtyMask;
            int buildMask = requestedMask;
            if (state.hasPublishedState) {
                for (int category = 0; category < CAT_COUNT; category++) {
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
            int submittedMask = buildMask;
            MeshBuildResult result = new MeshBuildResult(
                    state, generation, submittedMask, fingerprints,
                    currentEpoch, estimatedBytes);
            // The build reads the section buffers on the mesh thread while the
            // render thread may replace or unload those sections; the build's
            // own reference keeps the buffers out of the pool until it is done.
            // Taken before the bookkeeping below so that a snapshot already
            // released (retain throws) leaves nothing to unwind.
            retainSnapshots(snapshots);
            state.dirtyMask &= ~requestedMask;
            clearDirtyTimingIfClean(state);
            state.buildInFlight = true;
            pendingResults.incrementAndGet();
            pendingMeshBytes.addAndGet(estimatedBytes);
            try {
                readyQueue.add(result);
                meshBuildExecutor.execute(() -> {
                    try {
                        result.pages = meshBuilder.buildColumnMesh(
                                state.cx, state.cz, snapshots, submittedMask, textures);
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
                });
            } catch (RuntimeException | Error failure) {
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

    private void installReadyMeshes(RemixAPI api) {
        long deadline = System.nanoTime() + INSTALL_TIME_BUDGET_NS;
        int installedColumns = 0;

        while (installedColumns < MAX_INSTALLED_COLUMNS_PER_FRAME) {
            if (activeInstall == null) {
                // Installs pause only for retirement byte pressure, never item
                // counts: teardown backlog must not block presentation, or
                // trailing unloads starve new terrain during sustained flight.
                if (retirementQueue.retirementBytesBackpressured()) return;
                MeshBuildResult result = pollCompletedBuild();
                if (result == null) return;
                pendingResults.decrementAndGet();

                if (result.failure() != null) {
                    pendingMeshBytes.addAndGet(-result.accountedBytes());
                    markForRebuildIfLive(result, true);
                    LOGGER.error("Background mesh build failed", result.failure());
                    continue;
                }
                if (isStale(result)) {
                    disposePages(result.pages());
                    pendingMeshBytes.addAndGet(-result.accountedBytes());
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
                // Activated pages are retained scene objects. Re-hide the
                // visible prefix under this frame's deadline before handing
                // cursor ownership to budgeted retirement.
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
                if (!cursor.failed
                        && cursor.nextPage >= cursor.result.pages().length) {
                    stageHiddenPages(cursor);
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
                // A failed page may already have spent the whole native-call
                // budget. Roll back any activated prefix starting next frame.
                return;
            }
            if (cursor.staged
                    && cursor.nextActivation >= cursor.installedPages.size()) {
                finishInstall(cursor);
                activeInstall = null;
                installedColumns++;
            }

            if (attemptedNativePage && System.nanoTime() >= deadline) {
                return;
            }
        }
    }

    private boolean installNextPage(MeshInstallCursor cursor, RemixAPI api) {
        MeshBuildResult result = cursor.result;
        BlockMeshBuilder.MeshPage[] pages = result.pages();

        while (cursor.nextPage < pages.length) {
            int pageIndex = cursor.nextPage++;
            BlockMeshBuilder.MeshPage pendingPage = pages[pageIndex];
            if (pendingPage == null) continue;
            int category = pendingPage.category();
            RemixMesh pendingMesh = pendingPage.mesh();
            int categoryFlags = category == CAT_OVERLAY
                    ? InstanceCategoryBit.DECAL_STATIC
                    : 0;

            RemixMesh mesh = null;
            RemixInstance instance = null;
            try {
                try (ProfileScope createScope = ProfileScope.begin("createMesh")) {
                    createScope.meshCategory(category);
                    mesh = RemixMeshStore.get().create(pendingMesh);
                    nativeCallStats.pageCreated(mesh);
                }
                pages[pageIndex] = null;

                instance = new RemixInstance(mesh)
                        .setTransform(columnTransform(result.owner().cx, result.owner().cz))
                        .setCategoryFlags(categoryFlags | InstanceCategoryBit.HIDDEN)
                        .setBlendExt(blendExtForCategory(category))
                        .setDoubleSided(true);
                try (ProfileScope instanceScope = ProfileScope.begin("createInstance")) {
                    RemixInstanceStore.get().create(instance);
                    // Create the retained scene object while hidden. This keeps
                    // first submission inside the page budget without exposing
                    // a partial replacement before every page is owned by the column.
                    instance.drawFast(api);
                    nativeCallStats.instanceAdded();
                }
                cursor.installedPages.add(new InstalledMeshPage(
                        instance, category, categoryFlags));
            } catch (RuntimeException | Error e) {
                LOGGER.error("Native terrain install failed for category {}", category, e);
                cursor.failed = true;
                if (instance != null) {
                    destroyInstanceAndMesh(instance);
                } else if (mesh != null && !mesh.isDeleted()) {
                    retirementQueue.retire(mesh);
                } else {
                    pendingMesh.disposeData();
                }
                pages[pageIndex] = null;
            }
            return true;
        }
        return false;
    }

    private void stageHiddenPages(MeshInstallCursor cursor) {
        // Cursor ownership keeps the column's old pages transactional while
        // each hidden retained instance is activated under the same deadline.
        // The complete replacement is attached to the column only after every
        // activation succeeds.
        cursor.staged = true;
    }

    private boolean activateNextPage(MeshInstallCursor cursor, RemixAPI api) {
        if (cursor.nextActivation >= cursor.installedPages.size()) return false;
        InstalledMeshPage page = cursor.installedPages.get(cursor.nextActivation++);
        try (ProfileScope activationScope = ProfileScope.begin("activateTerrainPage")) {
            activationScope.meshCategory(page.category);
            page.instance.setCategoryFlags(page.categoryFlags);
            page.instance.drawFast(api);
        } catch (RuntimeException | Error failure) {
            LOGGER.error("Native terrain activation failed for category {}",
                    page.category, failure);
            cursor.failed = true;
        }
        return true;
    }

    private boolean rollbackNextActiveInstallPage(MeshInstallCursor cursor, RemixAPI api) {
        if (!cursor.staged || cursor.nextActivation == 0) return false;

        InstalledMeshPage page = cursor.installedPages.get(cursor.nextActivation - 1);
        try (ProfileScope rollbackScope = ProfileScope.begin("hideTerrainPage")) {
            rollbackScope.meshCategory(page.category);
            page.instance.setCategoryFlags(
                    page.categoryFlags | InstanceCategoryBit.HIDDEN);
            page.instance.drawFast(api);
            cursor.nextActivation--;
        } catch (RuntimeException | Error failure) {
            LOGGER.error("Failed to hide staged terrain page for category {}",
                    page.category, failure);
            cursor.rollbackFailed = true;
        }
        return true;
    }

    private void rollbackActiveInstallPages(RemixAPI api) {
        MeshInstallCursor cursor = activeInstall;
        if (cursor == null || !cursor.staged || cursor.nextActivation == 0) return;

        long deadline = System.nanoTime() + INSTALL_TIME_BUDGET_NS;
        while (rollbackNextActiveInstallPage(cursor, api)) {
            if (cursor.rollbackFailed || System.nanoTime() >= deadline) return;
        }
    }

    private void finishInstall(MeshInstallCursor cursor) {
        MeshBuildResult result = cursor.result;
        ColumnRenderState owner = result.owner();
        int categoryMask = result.categoryMask();
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
        for (int category = 0; category < CAT_COUNT; category++) {
            if ((categoryMask & (1 << category)) != 0) {
                owner.categoryFingerprints[category] = result.categoryFingerprints()[category];
            }
        }
        owner.hasPublishedState = true;
        owner.buildInFlight = false;
        pendingMeshBytes.addAndGet(-result.accountedBytes());
    }

    private void abortInstall(MeshInstallCursor cursor) {
        MeshBuildResult result = cursor.result;
        for (InstalledMeshPage page : cursor.installedPages) {
            destroyInstanceAndMesh(page.instance);
        }
        disposePages(result.pages());
        pendingMeshBytes.addAndGet(-result.accountedBytes());
        markForRebuildIfLive(result, cursor.failed || cursor.rollbackFailed);
    }

    private void markForRebuildIfLive(MeshBuildResult result, boolean urgent) {
        ColumnRenderState owner = result.owner();
        if (result.epoch() != epoch || renderColumns.get(owner.key) != owner) return;
        owner.buildInFlight = false;
        if (urgent) {
            markBuildRetry(owner, result.categoryMask());
            return;
        }
        if (owner.dirtyMask == 0) {
            owner.dirtySinceFrame = terrainFrame;
            owner.settleUntilFrame = terrainFrame + COLUMN_STREAM_SETTLE_FRAMES;
        }
        owner.dirtyMask |= result.categoryMask();
    }

    private void markBuildRetry(ColumnRenderState state, int categoryMask) {
        if (state.dirtyMask == 0) state.dirtySinceFrame = terrainFrame;
        state.dirtyMask |= categoryMask;
        state.urgentBuild = true;
        state.settleUntilFrame = terrainFrame;
    }

    private static void clearDirtyTimingIfClean(ColumnRenderState state) {
        if (state.dirtyMask != 0) return;
        state.urgentBuild = false;
        state.dirtySinceFrame = Long.MIN_VALUE;
        state.settleUntilFrame = 0;
    }

    private boolean isStale(MeshBuildResult result) {
        ColumnRenderState owner = result.owner();
        return result.epoch() != epoch
                || renderColumns.get(owner.key) != owner
                || result.generation() != owner.generation
                || terrain.findColumn(owner.cx, owner.cz) == null;
    }

    private MeshBuildResult pollCompletedBuild() {
        for (MeshBuildResult result : readyQueue) {
            if (result.complete() && readyQueue.remove(result)) return result;
        }
        return null;
    }

    private int countCompletedBuilds() {
        int count = 0;
        for (MeshBuildResult result : readyQueue) {
            if (result.complete()) count++;
        }
        return count;
    }

    private void destroyInstanceAndMesh(RemixInstance inst) {
        retirementQueue.retire(inst);
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

    private static long estimateSnapshotMeshBytes(
            List<TerrainState.SectionSnapshot> snapshots, int categoryMask) {
        long bytes = 0;
        for (TerrainState.SectionSnapshot snapshot : snapshots) {
            if (!(snapshot.data() instanceof SectionMeshData data)) continue;
            for (int category = 0; category < CAT_COUNT; category++) {
                if ((categoryMask & (1 << category)) == 0) continue;
                long categoryBytes = data.categoryEstimatedMeshBytes(category);
                if (categoryBytes > Long.MAX_VALUE - bytes) return Long.MAX_VALUE;
                bytes += categoryBytes;
            }
        }
        return bytes;
    }

    private static void retainSnapshots(List<TerrainState.SectionSnapshot> snapshots) {
        for (TerrainState.SectionSnapshot snapshot : snapshots) {
            if (snapshot.data() instanceof SectionMeshData data) data.retain();
        }
    }

    private static void releaseSnapshots(List<TerrainState.SectionSnapshot> snapshots) {
        for (TerrainState.SectionSnapshot snapshot : snapshots) {
            if (snapshot.data() instanceof SectionMeshData data) data.release();
        }
    }

    /** Drops a capture's mesh data that will never reach a terrain entry. */
    private static void releaseMeshData(SectionBlockData capture) {
        SectionMeshData data = capture.meshData();
        if (data != null) data.release();
    }

    private static int categoryDifferenceMask(Object previous, Object replacement) {
        SectionMeshData oldData = previous instanceof SectionMeshData data ? data : null;
        SectionMeshData newData = replacement instanceof SectionMeshData data ? data : null;
        int mask = 0;
        for (int category = 0; category < CAT_COUNT; category++) {
            int oldHash = oldData != null ? oldData.categoryContentHash(category) : 0;
            int newHash = newData != null ? newData.categoryContentHash(category) : 0;
            if (oldHash != newHash) mask |= 1 << category;
        }
        return mask;
    }

    private static int[] columnCategoryFingerprints(
            List<TerrainState.SectionSnapshot> snapshots) {
        int[] fingerprints = new int[CAT_COUNT];
        boolean[] present = new boolean[CAT_COUNT];
        for (TerrainState.SectionSnapshot snapshot : snapshots) {
            if (!(snapshot.data() instanceof SectionMeshData data)) continue;
            for (int category = 0; category < CAT_COUNT; category++) {
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
        for (int category = 0; category < CAT_COUNT; category++) {
            if (present[category] && fingerprints[category] == 0) {
                fingerprints[category] = 1;
            }
        }
        return fingerprints;
    }

    private static long columnDistanceSq(ColumnRenderState state, int cx, int cz) {
        long dx = (long) state.cx - cx;
        long dz = (long) state.cz - cz;
        return dx * dx + dz * dz;
    }

    /**
     * Recomputes the terrain gauges, which costs two walks of every column. The values are
     * slowly-varying counts of streaming state rather than per-frame events, so they are
     * refreshed on a cadence and held in between; callers emit the held value every frame,
     * which they must, because the timer zeroes counters at the start of each frame.
     */
    private void publishTerrainDiagnostics() {
        if (diagnosticsRefreshCountdown > 0) {
            diagnosticsRefreshCountdown--;
            return;
        }
        diagnosticsRefreshCountdown = DIAGNOSTICS_REFRESH_FRAMES - 1;

        int published = 0;
        int withGeometry = 0;
        int missing = 0;
        int missingSections = 0;
        for (TerrainState.ChunkColumn column : terrain.columns()) {
            ColumnRenderState state = renderColumns.get(packChunkKey(column.cx, column.cz));
            if (state != null && state.hasPublishedState) {
                published++;
                if (state.hasGeometry()) withGeometry++;
            } else {
                missing++;
                missingSections += column.sectionCount();
            }
        }
        lastTerrainDiagnostics = new TerrainDiagnostics(
                terrain.columnCount(), published, withGeometry, missing,
                missingSections, retainedPageCount, terrain.sectionCount(),
                pendingResults.get(), countCompletedBuilds(),
                BlockDataQueue.pendingCount(), pendingMeshBytes.get());
    }

    /** @return the number of native instance submits this pass made */
    private int draw(RemixAPI api, PerformanceTimer t, Vec3 cam) {
        t.begin(perfBfs);
        try (ProfileScope bfsScope = ProfileScope.begin("BFS reachability")) {
            terrain.ensureReachabilityOnce();
        }
        t.end(perfBfs);

        int drawCalls = 0;
        int lightsCulled = 0;
        int lightsDrawn = 0;
        int lightsBeyondReach = 0;

        try (ProfileScope traversalScope = ProfileScope.begin("draw traversal")) {
            // Remix keeps a scene instance until it is explicitly removed, and every terrain
            // instance is submitted at the point it is created or mutated, so re-submitting the
            // whole set each frame only re-asserts state the runtime already holds. The one case
            // that does need it is resurrection: hideRetainedInstances() removes every instance,
            // leaving handles inactive until something submits them again.
            if (retainedInstancesHidden) {
                for (ColumnRenderState state : renderColumns.values()) {
                    for (int pageIndex = 0; pageIndex < state.activePages.size(); pageIndex++) {
                        RemixInstance instance = state.activePages.get(pageIndex).instance;
                        if (instance != null && !instance.isDeletedFast()) {
                            instance.drawFast(api);
                            drawCalls++;
                        }
                    }
                }
                // Cleared only after a complete pass. A native failure mid-pass propagates out,
                // and clearing on the way out would strand the unvisited half of the world.
                retainedInstancesHidden = false;
            }

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
                        if (light.shouldDraw(cam.x, cam.y, cam.z, lightReachThreshold, terrainFrame)) {
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
        RemixAPI api = renderer.getRemixApi();
        if (api != null) {
            for (ColumnRenderState state : renderColumns.values()) {
                for (InstalledMeshPage page : state.activePages) {
                    RemixInstance instance = page.instance;
                    if (instance == null || instance.isDeletedFast()) continue;
                    instance.hideFromScene(api);
                }
            }
            rollbackActiveInstallPages(api);
            // Instance handles are now inactive. The next draw must resubmit them all.
            retainedInstancesHidden = true;
        }
    }

    /** Spreads the safety audit across frames so its cost does not scale with view distance in one frame. */
    private void advanceZombieAudit(long deadline) {
        if (currentWorld == null) return;
        if (retirementQueue.retirementBackpressured() || lightWorkBackpressured()) return;

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
            LevelChunk chunk = currentWorld.getChunkSource().getChunk(cx, cz, false);
            if (chunk == null && terrain.findColumn(cx, cz) != null) {
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
        epoch++;
        if (activeInstall != null) {
            abortInstall(activeInstall);
            activeInstall = null;
        }

        // Wait for in-flight background builds so they don't read shared
        // resources (e.g. BlockTextureManager) during a concurrent reload.
        // Builds are fast (<100ms) and clear() only runs on world change /
        // texture reload, never during normal gameplay frames.
        while (pendingResults.get() > 0) {
            MeshBuildResult stale = pollCompletedBuild();
            if (stale != null) {
                disposePages(stale.pages());
                pendingMeshBytes.addAndGet(-stale.accountedBytes());
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

        enqueueLightRetirement(terrain.clearForRetirement());
        clearLightWork();
        retirementQueue.clear();
        currentWorld = null;
        for (int i = pendingCaptureIndex; i < pending.size(); i++) {
            releaseMeshData(pending.get(i).data());
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
        diagnosticsRefreshCountdown = 0;
        LOGGER.info("ChunkManager cleared");
    }

    public void destroy() {
        clear();
        meshBuildExecutor.shutdown();
    }

    private static InstanceInfoBlendEXT blendExtForCategory(int cat) {
        return switch (cat) {
            case CAT_CUTOUT   -> InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(128);
            case CAT_OVERLAY  -> InstanceInfoBlendEXT.textureTimesVertexColorAlphaBlend(1);
            default           -> InstanceInfoBlendEXT.textureTimesVertexColor();
        };
    }

    private static Transform columnTransform(int cx, int cz) {
        Transform transform = Transform.identity();
        transform.setTranslation(cx * 16f, 0f, cz * 16f);
        return transform;
    }

    private static int hashCapture(SectionBlockData cap) {
        int hash = cap.meshData().contentHash();
        hash = 31 * hash + Long.hashCode(cap.topFaceMask());
        hash = 31 * hash + Long.hashCode(cap.bottomFaceMask());
        hash = 31 * hash + Long.hashCode(cap.eastFaceMask());
        hash = 31 * hash + Long.hashCode(cap.westFaceMask());
        hash = 31 * hash + Long.hashCode(cap.southFaceMask());
        hash = 31 * hash + Long.hashCode(cap.northFaceMask());
        return 31 * hash + Arrays.hashCode(cap.opacityBitmap());
    }

}
