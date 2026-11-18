/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.nvidia.rtxremix.core.LauncherAccountOptions;
import com.nvidia.rtxremix.core.HostGraphicsIntegration;
import com.nvidia.rtxremix.core.RemixAssetStores;
import com.nvidia.rtxremix.core.RendererFrameCoordinator;
import com.nvidia.rtxremix.core.RemixRendererSession;
import com.nvidia.rtxremix.core.RendererScene;
import com.nvidia.rtxremix.core.JoinBurstHold;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.CameraInfo;
import com.nvidia.rtxremix.CameraInfoParameterizedEXT;
import com.nvidia.rtxremix.CameraType;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.StartupInfo;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.benchmark.BenchmarkController;
import com.nvidia.rtxremix.minecraft.light.LightManager;
import com.nvidia.rtxremix.minecraft.logic.ViewDistanceFade;
import com.nvidia.rtxremix.minecraft.profiling.PerformanceTimer;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.settings.PostProcessingSettings;
import com.nvidia.rtxremix.minecraft.sky.SkyRenderer;
import com.nvidia.rtxremix.minecraft.sky.AtmosphericSkyGenerator;
import com.nvidia.rtxremix.minecraft.sky.MinecraftSkyResourceSource;
import com.nvidia.rtxremix.minecraft.sky.SkyFrame;
import com.nvidia.rtxremix.minecraft.sky.SkyLightRenderer;
import com.nvidia.rtxremix.minecraft.sky.SkyLightState;
import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import com.nvidia.rtxremix.minecraft.terrain.MinecraftTerrainHost;
import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.texture.MinecraftBlockAtlasSource;
import com.nvidia.rtxremix.minecraft.texture.MinecraftParticleAtlasSource;
import com.nvidia.rtxremix.minecraft.texture.ParticleAtlasManager;
import com.nvidia.rtxremix.minecraft.particle.RemixParticleRenderer.PreparedFrame;
import com.nvidia.rtxremix.minecraft.particle.RemixParticleRenderer;
import com.nvidia.rtxremix.minecraft.particle.MinecraftParticleCapture;
import com.nvidia.rtxremix.minecraft.weather.RemixWeatherRenderer;
import com.nvidia.rtxremix.minecraft.camera.CameraController;
import com.nvidia.rtxremix.minecraft.logic.ConfLayerDriver;
import com.nvidia.rtxremix.minecraft.logic.MinecraftGameplayFrameContext;
import com.nvidia.rtxremix.minecraft.logic.ZoneRegistry;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.RemixBlockDamagePass;
import com.nvidia.rtxremix.minecraft.entity.RemixBlockEntityPass;
import com.nvidia.rtxremix.minecraft.entity.RemixBlockOutlinePass;
import com.nvidia.rtxremix.minecraft.entity.RemixEntityPass;
import com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter;
import com.nvidia.rtxremix.minecraft.entity.MinecraftEntitySubmissionAdapter;
import com.nvidia.rtxremix.minecraft.entity.MinecraftEntityTextureSource;
import com.nvidia.rtxremix.minecraft.entity.RemixFirstPersonPass;
import com.nvidia.rtxremix.minecraft.cloud.CloudRenderer;
import com.nvidia.rtxremix.minecraft.cloud.MinecraftCloudCapture;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Render-thread bridge between Minecraft's world and the RTX Remix runtime.
 *
 * <p>Owns Remix initialization against the Minecraft window, per-frame capture
 * of the scene (terrain, entities, block entities, particles, weather, sky,
 * clouds and lights) into Remix meshes, materials and instances, and camera and
 * frame submission. All of it runs once per rendered frame on the render
 * thread, not once per client tick.
 *
 * <p>Host window, framebuffer, graphics-state, and presentation behavior is
 * delegated through {@link HostGraphicsIntegration}; this class owns scene
 * policy and canonical frame capture only. The current Minecraft host uses
 * OpenGL/Vulkan interop without making that backend part of renderer policy.
 */
public class RemixRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixRenderer.class);

    /**
     * Whether a fatal fault inside a Remix call goes to the runtime's crash
     * reporter. False leaves it to HotSpot, whose {@code hs_err_pid*.log} keeps
     * the Java frames that led to the call.
     */
    private static final boolean FORWARD_REMIX_FAULTS =
            Boolean.parseBoolean(System.getProperty("rtxremix.forwardRemixFaults", "true"));

    /**
     * {@code runtime} crashes the game inside a Remix call on the render thread
     * once {@link #FAULT_IN_REMIX_CALL_FRAME} frames have been presented, and
     * {@code jvm} faults inside {@code jvm.dll} under the same call instead.
     * Checks where a native crash on the render thread is reported.
     */
    private static final String FAULT_IN_REMIX_CALL = System.getProperty("rtxremix.debug.faultInRemixCall", "");
    private static final int FAULT_IN_REMIX_CALL_FRAME = 120;

    // Remix API instance
    private RemixAPI remixApi;
    private RemixRendererSession rendererSession;
    private RemixAssetStores assetStores;
    private RendererScene rendererScene;
    private boolean initialized = false;
    private boolean shutdown;
    private final HostGraphicsIntegration graphicsIntegration;
    private int presentedFrames = 0;

    // Non-owning construction and hot-path aliases. RendererScene becomes the
    // sole lifecycle owner after initialization; direct references here keep
    // Minecraft capture allocation- and accessor-free.
    private ChunkManager chunkManager;
    // Zones authored via command blocks / commands; written on the server and
    // client threads, read per-frame on the render thread.
    private final ZoneRegistry zoneRegistry = new ZoneRegistry();
    // Drives the minecraft-logic conf option-layers per frame; acquired during
    // initialize(), so it is null only before the renderer comes up.
    private ConfLayerDriver confLayerDriver;
    private final MinecraftGameplayFrameContext gameplayFrameContext =
            new MinecraftGameplayFrameContext();
    private LightManager lightManager;
    private SkyLightRenderer skyLightRenderer;
    private SkyRenderer skyRenderer;
    private CloudRenderer cloudRenderer;
    private MinecraftCloudCapture cloudCapture;
    private BlockTextureManager textureManager;
    private PostProcessingSettings postProcessingSettings;
    
    // Entity rendering pipeline: vanilla capture + skinned submission
    private RemixEntityPass entityPass;
    private RemixEntitySubmitter entitySubmitter;
    private RemixFirstPersonPass firstPersonPass;

    // Block entity rendering: captured in place from vanilla's dispatcher and
    // submitted through the entity submitter with the block entity as owner
    private RemixBlockEntityPass blockEntityPass;
    private boolean entityAtlasPreloadPending = true;

    // Block-breaking cracks: captured in place from vanilla's damage render and
    // submitted through the entity submitter as terrain decals
    private RemixBlockDamagePass blockDamagePass;

    // Targeted-block outline: loader event capture + dynamic Remix submission
    private RemixBlockOutlinePass blockOutlinePass;

    // Particle rendering: atlas manager + per-frame renderer
    private ParticleAtlasManager particleAtlasManager;
    private RemixParticleRenderer particleRenderer;
    private MinecraftParticleCapture particleCapture;
    private RemixWeatherRenderer weatherRenderer;
    private final ExecutorService particleExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RTX Remix Particles");
        thread.setDaemon(true);
        return thread;
    });
    private volatile CompletableFuture<ParticleRenderResult> particleRenderFuture;
    private final RendererFrameCoordinator frameCoordinator =
            new RendererFrameCoordinator();
    private final RendererFrameCoordinator.Host frameHost = new MinecraftFrameHost();
    private final Runnable additionalHostPasses = this::renderAdditionalHostPasses;
    private int compositeColorTexture;
    private ClientLevel frameWorld;
    private Camera frameCamera;
    private Vec3 frameCameraPos;
    private float frameTickDelta;
    private CompletableFuture<ParticleRenderResult> frameParticleFuture;
    private boolean frameEntityClosed;
    private ProfileScope frameProfileScope;
    // Double-buffer: holds frame N's prepared particles for submission during frame N+1.
    private PreparedFrame pendingParticleFrame;
    private long particleNanosThisFrame;
    /** World the pending payload was prepared against, so a swap cannot publish it. */
    private ClientLevel pendingParticleFrameWorld;
    
    // Deferred operations: flags are set from any thread, executed on the render thread.
    private volatile boolean pendingTextureReload = false;
    private volatile boolean pendingTerrainRecaptureAfterTextureReload = false;
    private final RendererScene.ResourceReloadHooks resourceReloadHooks =
            new RendererScene.ResourceReloadHooks() {
                @Override
                public void discardEntityPoseSnapshots() {
                    if (entityPass != null) {
                        entityPass.discardPoseSnapshots();
                    }
                }

                @Override
                public void cleanupHostRenderResources() {
                    if (weatherRenderer != null) {
                        weatherRenderer.cleanup();
                    }
                }
            };
    
    // Performance timing & benchmarking
    private final PerformanceTimer perfTimer = new PerformanceTimer();
    private final CameraController cameraController = new CameraController();
    private final BenchmarkController benchmarkController = new BenchmarkController(cameraController);
    private int perfCamera = -1;
    private int perfLights = -1;
    private int perfChunkGen = -1;
    private int perfChunkDraw = -1;
    private int perfEntities = -1;
    private int perfSky = -1;
    private int perfClouds = -1;
    private int perfCloudGen = -1;
    private int perfAtlas = -1;
    private int perfSubmit = -1;
    private int cntEntities = -1;
    private int cntEntLayers = -1;
    private int cntEntDraws = -1;
    private int cntEntBones = -1;
    private int cntEntMeshCreates = -1;
    private int cntCloudVisible = -1;
    private int cntCloudInstances = -1;
    private int cntCloudPaletteCreates = -1;
    private int cntCloudDraws = -1;
    private int cntCloudVertices = -1;
    private int cntCloudTriangles = -1;
    private int cntEntSkipped = -1;
    private int perfEntCapture = -1;
    private int perfEntSubmit = -1;
    private int perfEntOffSweep = -1;
    private int cntEntOffFrustum = -1;
    private int cntEntOffDraws = -1;
    private int cntEntOffReused = -1;
    private int cntEntPoolSize = -1;
    private int perfBlockEntityCapture = -1;
    private int perfBlockEntitySubmit = -1;
    private int cntBlockEntities = -1;
    private int cntBlockEntityParts = -1;
    private int cntBlockEntityMeshCreates = -1;
    private long lastCameraMediumHandle = Long.MIN_VALUE;
    private final ViewDistanceFade viewDistanceFade = new ViewDistanceFade();
    private int perfParticles = -1;
    private int cntParticles = -1;
    private int cntParticleDraws = -1;
    private int cntParticleDynamicQuads = -1;
    private int cntParticleNewSlots = -1;
    private int cntParticleRemovedSlots = -1;
    private int cntParticleCapacityGrows = -1;
    private int cntParticleCapacity = -1;
    private int cntParticleMeshUpdates = -1;
    
    public RemixRenderer() {
        this(MinecraftPlatform.services().createGraphicsIntegration());
    }

    RemixRenderer(HostGraphicsIntegration graphicsIntegration) {
        this.graphicsIntegration = java.util.Objects.requireNonNull(
                graphicsIntegration, "graphicsIntegration");
        PerformanceTimer.setProfileSink(new PerformanceTimer.ProfileSink() {
            @Override
            public boolean isEnabled() {
                return ProfileScope.isDeepProfilingEnabled();
            }

            @Override
            public void plot(String name, double value) {
                ProfileScope.plot(name, value);
            }

            @Override
            public PerformanceTimer.ProfileScopeHandle begin(String name) {
                final ProfileScope scope = ProfileScope.begin(name);
                return new PerformanceTimer.ProfileScopeHandle() {
                    @Override
                    public PerformanceTimer.ProfileScopeHandle vertexCount(int value) {
                        scope.vertexCount(value);
                        return this;
                    }

                    @Override
                    public PerformanceTimer.ProfileScopeHandle surfaceCount(int value) {
                        scope.surfaceCount(value);
                        return this;
                    }

                    @Override
                    public PerformanceTimer.ProfileScopeHandle bytes(long value) {
                        scope.bytes(value);
                        return this;
                    }

                    @Override
                    public void close() {
                        scope.close();
                    }
                };
            }
        });
    }
    
    /**
     * Initializes the RTX Remix runtime with the Minecraft window
     */
    public void initialize() {
        LOGGER.info("Initializing RTX Remix renderer...");

        try {
            lastCameraMediumHandle = Long.MIN_VALUE;

            StartupInfo startupInfo = graphicsIntegration.prepareStartup();
            Path dll = runtimeDll(MinecraftPlatform.services().gameDirectory());
            if (!Files.isRegularFile(dll)) {
                throw new IllegalStateException("RTX Remix runtime not found at " + dll
                        + "; reinstall the mod or run gradlew setupRuntime");
            }
            LOGGER.info("Loading Remix from: {}", dll);

            String dllPath = dll.toAbsolutePath().toString();
            // RTXREMIX_EXTRA_RTXCONF="rtx.a=1;rtx.b=true" applies arbitrary rtx.conf
            // overrides for this session. Applied before startup() so options that
            // are only read at device/swapchain creation (e.g. frame generation)
            // take effect. Benchmark runs use this for per-run config matrices.
            String extraConf = System.getenv("RTXREMIX_EXTRA_RTXCONF");
            Map<String, String> configOverrides =
                    RemixRendererSession.parseConfigOverrides(extraConf);
            for (Map.Entry<String, String> entry : configOverrides.entrySet()) {
                LOGGER.info(
                        "RTXREMIX_EXTRA_RTXCONF: {} = {}",
                        entry.getKey(),
                        entry.getValue());
            }

            RemixRendererSession.OpenResult opened = RemixRendererSession.open(
                    dllPath,
                    startupInfo,
                    LauncherAccountOptions.ALL,
                    configOverrides);
            rendererSession = opened.session();
            remixApi = rendererSession.api();
            if (!FORWARD_REMIX_FAULTS) {
                remixApi.setFaultForwarding(false);
                LOGGER.info("Native crashes in Remix calls go to hs_err_pid*.log, "
                        + "as rtxremix.forwardRemixFaults=false asks");
            }
            if (Boolean.getBoolean("rtxremix.crashDumpDetail")) {
                remixApi.setCrashDumpDetail(true);
                LOGGER.warn("rtxremix.crashDumpDetail is on: a crash dump in crash\\ can include "
                        + "the launcher's access token, so keep it private");
            }
            LOGGER.info(
                    "Blanked {} account values in the process command line",
                    opened.redactedCommandLineValues());
            LOGGER.info("RTX Remix API loaded successfully");
            LOGGER.info("RTX Remix API started");
            graphicsIntegration.attach(remixApi);

            postProcessingSettings = new PostProcessingSettings(remixApi::setConfigVariable);
            
            textureManager = new BlockTextureManager(remixApi, new MinecraftBlockAtlasSource());
            chunkManager = new ChunkManager(new MinecraftTerrainHost(remixApi, textureManager));
            firstPersonPass = new RemixFirstPersonPass();
            skyLightRenderer = new SkyLightRenderer(remixApi);
            lightManager = new LightManager(
                    remixApi,
                    textureManager,
                    firstPersonPass::getPendingHeldLightPosition,
                    skyLightRenderer);
            skyRenderer = new SkyRenderer(
                    remixApi,
                    new AtmosphericSkyGenerator(),
                    new MinecraftSkyResourceSource());
            cloudCapture = new MinecraftCloudCapture(remixApi);
            cloudRenderer = cloudCapture.renderer();
            
            particleAtlasManager = new ParticleAtlasManager(
                    remixApi, new MinecraftParticleAtlasSource());
            entityPass = new RemixEntityPass(lightManager::ownsLightThisFrame);
            entitySubmitter = new RemixEntitySubmitter(
                    remixApi,
                    particleAtlasManager,
                    new MinecraftEntityTextureSource(),
                    new MinecraftEntitySubmissionAdapter());

            blockEntityPass = new RemixBlockEntityPass();
            blockDamagePass = new RemixBlockDamagePass();

            particleRenderer = new RemixParticleRenderer(remixApi);
            particleCapture = new MinecraftParticleCapture(
                    this, particleAtlasManager, particleRenderer);
            weatherRenderer = new RemixWeatherRenderer();
            
            perfCamera = perfTimer.registerSection("Camera");
            perfLights = perfTimer.registerSection("Lights");
            perfChunkGen = perfTimer.registerSection("Chunk.Gen");
            perfChunkDraw = perfTimer.registerSection("Chunk.Draw");
            perfEntities = perfTimer.registerSection("Entities");
            perfSky = perfTimer.registerSection("Sky");
            perfClouds = perfTimer.registerSection("Clouds");
            perfCloudGen = perfTimer.registerSection("Cloud.Gen");
            cntEntities = perfTimer.registerCounter("Ent.Count");
            cntEntLayers = perfTimer.registerCounter("Ent.Layers");
            cntEntDraws = perfTimer.registerCounter("Ent.Draws");
            cntEntBones = perfTimer.registerCounter("Ent.Bones");
            cntEntMeshCreates = perfTimer.registerCounter("Ent.MeshCreates");
            cntCloudVisible = perfTimer.registerCounter("Cloud.Visible");
            cntCloudInstances = perfTimer.registerCounter("Cloud.Instances");
            cntCloudPaletteCreates = perfTimer.registerCounter("Cloud.PaletteCreates");
            cntCloudDraws = perfTimer.registerCounter("Cloud.Draws");
            cntCloudVertices = perfTimer.registerCounter("Cloud.Vertices");
            cntCloudTriangles = perfTimer.registerCounter("Cloud.Triangles");
            cntEntSkipped = perfTimer.registerCounter("Ent.Skipped");
            // Only the capture half is addressable from Java; the submit half is
            // almost entirely native, so judging them together hides which one moved.
            perfEntCapture = perfTimer.registerSection("Ent.Capture");
            perfEntSubmit = perfTimer.registerSection("Ent.Submit");
            // Selecting which off-screen entities to capture: the world walk and the
            // closest-first heap. The capture and submit work that follows is reported by
            // Ent.Capture and Ent.Submit, so these three do not overlap and can be summed.
            perfEntOffSweep = perfTimer.registerSection("Ent.OffSweep");
            cntEntOffFrustum = perfTimer.registerCounter("Ent.OffFrustum");
            cntEntOffDraws = perfTimer.registerCounter("Ent.OffDraws");
            cntEntOffReused = perfTimer.registerCounter("Ent.OffReused");
            cntEntPoolSize = perfTimer.registerCounter("Ent.PoolSize");
            // Block entities are captured and submitted in place during vanilla's
            // world render, so both halves are timed there and injected here.
            perfBlockEntityCapture = perfTimer.registerSection("BE.Capture");
            perfBlockEntitySubmit = perfTimer.registerSection("BE.Submit");
            perfSubmit = perfTimer.registerSection("Submit");
            cntBlockEntities = perfTimer.registerCounter("BE.Count");
            cntBlockEntityParts = perfTimer.registerCounter("BE.Parts");
            cntBlockEntityMeshCreates = perfTimer.registerCounter("BE.MeshCreates");
            perfAtlas = perfTimer.registerSection("Atlas");
            perfParticles = perfTimer.registerSection("Particles");
            cntParticles = perfTimer.registerCounter("Ptcl.Count");
            cntParticleDraws = perfTimer.registerCounter("Ptcl.MeshBuckets");
            cntParticleDynamicQuads = perfTimer.registerCounter("Ptcl.DynamicQuads");
            cntParticleNewSlots = perfTimer.registerCounter("Ptcl.NewSlots");
            cntParticleRemovedSlots = perfTimer.registerCounter("Ptcl.RemovedSlots");
            cntParticleCapacityGrows = perfTimer.registerCounter("Ptcl.CapacityGrows");
            cntParticleCapacity = perfTimer.registerCounter("Ptcl.Capacity");
            cntParticleMeshUpdates = perfTimer.registerCounter("Ptcl.MeshUpdates");
            
            assetStores = RemixAssetStores.open(remixApi);
            blockOutlinePass = new RemixBlockOutlinePass(remixApi);
            rendererScene = new RendererScene(
                    assetStores,
                    textureManager,
                    chunkManager,
                    skyLightRenderer,
                    skyRenderer,
                    cloudRenderer,
                    particleAtlasManager,
                    entitySubmitter,
                    particleRenderer,
                    blockOutlinePass);
            
            // Conf layers own every dynamic gameplay look, so a runtime or an
            // install that cannot provide them fails initialization instead of
            // rendering a scene nothing drives. The gate runs before the
            // renderer calls itself initialized, so a failing install never
            // logs success.
            if (!remixApi.isConfigLayersSupported()) {
                throw new IllegalStateException("runtime lacks the config-layer API (needs API >= 0.9.0)");
            }
            Path gameDir = MinecraftPlatform.services().gameDirectory();
            Path waterConf = gameDir.resolve("rtx-remix").resolve("mods").resolve("minecraft-logic")
                    .resolve("confs").resolve("water.conf");
            if (!Files.exists(waterConf)) {
                throw new IllegalStateException("conf payload missing (restored on the next launch): "
                        + waterConf);
            }
            ConfLayerDriver driver = new ConfLayerDriver(
                    MinecraftGameplayFrameContext.vanillaBiomeNames());
            if (!driver.acquireAll(remixApi, gameDir)) {
                throw new IllegalStateException("conf failed to acquire (see log): " + waterConf);
            }
            confLayerDriver = driver;
            LOGGER.info("Config option-layers acquired - conf-layer blending active");

            initialized = true;
            LOGGER.info("RTX Remix renderer initialized successfully!");

        } catch (RemixException e) {
            LOGGER.error("Failed to initialize RTX Remix: {} (error code: {})",
                e.getMessage(), e.getErrorCode());
            releasePartialInitialization();
            throw e;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize RTX Remix", e);
            releasePartialInitialization();
            throw new RuntimeException("RTX Remix initialization failed", e);
        }
    }

    /**
     * Tears down whatever initialization managed to build. A failure can land
     * after the runtime has started and the subsystems own native resources,
     * so the session must not carry a half-built renderer. A secondary failure
     * inside the teardown is logged rather than thrown, because the caller is
     * about to rethrow the original cause.
     */
    private void releasePartialInitialization() {
        initialized = false;
        try {
            shutdown();
        } catch (Exception e) {
            LOGGER.error("Error tearing down a failed RTX Remix initialization", e);
        }
    }

    private void gcAssetStores() {
        if (rendererScene != null) {
            rendererScene.collectGarbage();
        } else if (assetStores != null) {
            assetStores.collectGarbage();
        }
    }

    /**
     * The single location of the RTX Remix runtime. The extractor installs the
     * packaged runtime here and development launches stage it here, so no other
     * path is searched.
     */
    static Path runtimeDll(Path gameDir) {
        return gameDir.resolve("remix-sdk").resolve("d3d9.dll");
    }
    
    /**
     * Applies pending world/resource invalidation and preloads the large
     * block-entity atlases. Call before vanilla's world render, which is where
     * block entities are captured in place.
     */
    public void beginWorldRenderFrame() {
        if (!initialized) return;

        applyPendingWorldResourceChanges();
        if (MinecraftRuntime.isBlockEntitiesEnabled()) {
            preloadEntityAtlasesForCapture();
        }
    }

    private void applyPendingWorldResourceChanges() {
        if (!pendingTextureReload) return;

        boolean recaptureTerrain = pendingTerrainRecaptureAfterTextureReload;
        pendingTextureReload = false;
        LOGGER.info("Reloading textures, materials, and terrain");
        joinInFlightParticleRenderTask();
        discardPendingParticleFrame();
        AtlasAnimationForwarder.clear();
        // Retained meshes go before the atlas materials they borrow; the
        // canonical scene owns that dependency order.
        rendererScene.reloadResources(resourceReloadHooks);
        entityAtlasPreloadPending = true;
        if (recaptureTerrain) {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.levelRenderer != null) {
                LongSet built = MinecraftRuntime.builtSections(client);
                client.levelRenderer.allChanged();
                MinecraftRuntime.recaptureTerrainForRemix(client, built);
            }
            pendingTerrainRecaptureAfterTextureReload = false;
        }
    }

    private void preloadEntityAtlasesForCapture() {
        if (textureManager == null || entitySubmitter == null) return;
        if (!entityAtlasPreloadPending && entitySubmitter.hasLiveLargeBlockEntityAtlasMaterial()) return;

        textureManager.initialize();
        if (!textureManager.isInitialized()) return;

        try (ProfileScope ignored = ProfileScope.begin("EntityAtlas.preload")) {
            entityAtlasPreloadPending = !entitySubmitter.preloadLargeBlockEntityAtlases();
        }
    }

    private final JoinBurstHold joinBurstHold = new JoinBurstHold(
            JoinBurstHold.ENABLED,
            new JoinBurstHold.Observer() {
                @Override
                public void onSettled(long elapsedMs, int frameCount, long thresholdMs) {
                    LOGGER.info(
                            "World-join burst settled after {} ms ({} consecutive frames under {} ms)",
                            elapsedMs,
                            frameCount,
                            thresholdMs);
                }

                @Override
                public void onTimedOut(long maximumSeconds) {
                    LOGGER.info(
                            "World-join burst did not settle within {} s; releasing the terrain-download screen",
                            maximumSeconds);
                }
            });

    /**
     * The join hold of the renderer that is drawing the world, or null while Remix is not
     * rendering (no renderer, initialization failed, RTX toggled off), in which case vanilla
     * closes its terrain-download screen as usual.
     */
    public static JoinBurstHold activeJoinBurstHold() {
        RemixRenderer renderer = MinecraftRuntime.renderer();
        if (renderer == null || !renderer.initialized || !MinecraftRuntime.isRtxEnabled()) {
            return null;
        }
        return renderer.joinBurstHold;
    }
    private boolean renderThreadNamed;

    /**
     * Advances camera and benchmark frame-start state before Minecraft builds
     * its {@link Camera} for a world render.
     *
     * <p>This is called from the invocation boundary immediately before
     * {@code GameRenderer.render}. The eligibility check mirrors
     * {@code GameRenderer.render}: menu/loading frames that will not call
     * {@code renderWorld} must not advance an interactive camera timeline or a
     * deterministic benchmark frame.</p>
     */
    public void onMinecraftWorldRenderFrameStart(Minecraft client, boolean tick) {
        if (!initialized || remixApi == null || client == null) {
            return;
        }
        if (!shouldAdvanceWorldRenderFrame(tick, client.isGameLoadFinished(), client.level != null)) {
            return;
        }

        cameraController.onFrameStart(client);
        benchmarkController.onFrameStart(perfTimer);
    }

    static boolean shouldAdvanceWorldRenderFrame(boolean tick, boolean finishedLoading, boolean worldLoaded) {
        return tick && finishedLoading && worldLoaded;
    }

    /**
     * Opens a fresh entity capture window for the world render that is starting.
     *
     * <p>{@code WorldRenderEvents.END} does not fire during loading screens, so a
     * capture window can be opened and never closed. Resetting at the start of
     * every world render stops a dropped frame bleeding into the next one, and
     * opening the submitter's frame here (rather than in
     * {@link #renderAndSubmitFrame}) is what lets parts captured in place be
     * stamped with the frame that will decide whether to retire them.
     */
    public void beginEntityFrame() {
        if (entityPass == null || entitySubmitter == null) {
            return;
        }
        entityPass.resetFrame();
        if (initialized && MinecraftRuntime.isRtxEnabled() && MinecraftRuntime.isEntitiesEnabled()) {
            entityPass.beginFrame(entitySubmitter);
        }
    }

    /**
     * Captures one entity at vanilla's own traversal point, in place of the render
     * that would have gone to OpenGL.
     *
     * <p>Called from {@code WorldRendererMixin}'s wrap of
     * {@code WorldRenderer.renderEntity}. Returning true tells the mixin to skip
     * vanilla's call, which is what reduces each entity to a single model
     * evaluation per frame. Returning false hands the entity back to vanilla, so
     * an uninitialised renderer or a disabled toggle still renders normally.
     */
    public boolean renderEntityInPlace(Entity entity, float tickDelta) {
        if (!initialized || remixApi == null || entityPass == null || entitySubmitter == null) {
            return false;
        }
        if (!MinecraftRuntime.isRtxEnabled() || !MinecraftRuntime.isEntitiesEnabled()) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = client == null ? null : client.getEntityRenderDispatcher();
        if (dispatcher == null) {
            return false;
        }
        entityPass.beginFrame(entitySubmitter);
        entityPass.captureAndSubmit(entity, dispatcher, tickDelta, entitySubmitter);
        return true;
    }

    /**
     * Captures one block entity at vanilla's own dispatch point, in place of the
     * render that would have gone to OpenGL.
     *
     * <p>Called from {@code BlockEntityRenderDispatcherMixin}. Returning true tells
     * the mixin to skip vanilla's call; returning false hands the block entity back
     * to vanilla, so an uninitialised renderer or a disabled toggle still renders
     * normally. A block entity dispatched while a capture is already open is left
     * to that capture's owner.
     *
     * <p>The submitter's frame is opened here, idempotently, because retaining a
     * part requires an open frame and block entities are captured even while the
     * entity toggle, whose pass normally opens it, is off.
     */
    public boolean renderBlockEntityInPlace(BlockEntity be,
                                            RemixBlockEntityPass.BlockEntityRenderCall call) {
        if (!initialized || remixApi == null || blockEntityPass == null || entitySubmitter == null) {
            return false;
        }
        if (!MinecraftRuntime.isRtxEnabled() || !MinecraftRuntime.isBlockEntitiesEnabled()) {
            return false;
        }
        if (EntityRenderContext.get().isActive()) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = client == null ? null : client.getEntityRenderDispatcher();
        if (dispatcher == null) {
            return false;
        }
        entitySubmitter.beginFrame();
        blockEntityPass.capture(be, call, dispatcher, entitySubmitter);
        return true;
    }

    /**
     * Captures one block's breaking crack at vanilla's own render point, in place of
     * the render that would have gone to OpenGL.
     *
     * <p>Called from {@code WorldRendererMixin}'s wrap of
     * {@code BlockRenderManager.renderDamage}. Returning true tells the mixin to skip
     * vanilla's call; returning false hands the crack back to vanilla, so an
     * uninitialised renderer or a disabled toggle still renders normally. The crack is
     * terrain decoration, so it follows the world toggle rather than the entity toggle.
     * The submitter's frame is opened here, idempotently, because retaining a part
     * requires an open frame and the entity pass, which normally opens it, runs only
     * while the entity toggle is on.
     */
    public boolean renderBlockDamageInPlace(BlockPos pos, int stage,
                                            RemixBlockDamagePass.BlockDamageRenderCall call) {
        if (!initialized || remixApi == null || blockDamagePass == null || entitySubmitter == null) {
            return false;
        }
        if (!MinecraftRuntime.isRtxEnabled() || !MinecraftRuntime.isWorldEnabled()) {
            return false;
        }
        entitySubmitter.beginFrame();
        blockDamagePass.capture(pos, stage, call, entitySubmitter);
        return true;
    }

    public void renderAndSubmitFrame() {
        if (!initialized || remixApi == null) {
            return;
        }
        frameCoordinator.render(frameHost);
    }

    private final class MinecraftFrameHost implements RendererFrameCoordinator.Host {
        @Override
        public void beginFrame() {
            frameWorld = null;
            frameCamera = null;
            frameCameraPos = null;
            frameParticleFuture = null;
            frameEntityClosed = false;
            joinBurstHold.onFrame(System.nanoTime());
            if (!renderThreadNamed) {
                RemixAPI.tracyThreadName("Render");
                renderThreadNamed = true;
            }
            perfTimer.beginFrame();
            frameProfileScope = ProfileScope.begin("renderAndSubmitFrame");
        }

        @Override
        public boolean prepareFrame() {
            Minecraft client = Minecraft.getInstance();
            frameWorld = client.level;
            return frameWorld != null && client.gameRenderer != null;
        }

        @Override
        public boolean isCaptureEnabled() {
            return MinecraftRuntime.isRtxEnabled();
        }

        @Override
        public void suspendCapture() {
            if (chunkManager != null) {
                chunkManager.hideRetainedInstances();
            }
            // A prepared payload contains world-space vertices and must never
            // cross a no-world, world-swap, or capture-disabled frame.
            discardPendingParticleFrame();
        }

        @Override
        public void publishPreparedParticles() {
            if (pendingParticleFrame != null && pendingParticleFrameWorld != frameWorld) {
                discardPendingParticleFrame();
            }
            submitPendingParticleFrame();
        }

        @Override
        public void startParticlePreparation() {
            Minecraft client = Minecraft.getInstance();
            frameCamera = client.gameRenderer.getMainCamera();
            frameTickDelta = client.getTimer().getGameTimeDeltaPartialTick(true);
            frameParticleFuture = startParticleRenderTask(
                    frameTickDelta, ProfileScope.currentFrameIndex());
        }

        @Override
        public void updateCamera() {
            perfTimer.begin(perfCamera);
            try (ProfileScope ignored = ProfileScope.begin("Camera")) {
                RemixRenderer.this.updateCamera(frameCamera, frameTickDelta);
            } finally {
                perfTimer.end(perfCamera);
            }
            frameCameraPos = frameCamera.getPosition();
        }

        @Override
        public void updateLights() {
            perfTimer.begin(perfLights);
            try (ProfileScope ignored = ProfileScope.begin("Lights")) {
                // Entity lights must move before they are drawn.
                lightManager.updateEntityLights(frameWorld, frameCamera, frameTickDelta);
                lightManager.updateLights(frameWorld);
            } finally {
                perfTimer.end(perfLights);
            }
        }

        @Override
        public void updateTerrain() {
            if (!MinecraftRuntime.isWorldEnabled()) {
                chunkManager.hideRetainedInstances();
                return;
            }
            try {
                chunkManager.updateVisibleChunks(
                        frameCameraPos.x,
                        frameCameraPos.y,
                        frameCameraPos.z,
                        perfTimer,
                        perfChunkGen,
                        perfChunkDraw);
            } catch (Exception exception) {
                LOGGER.error("Error updating chunks", exception);
            }
        }

        @Override
        public void captureEntities() {
            entitySubmitter.beginFrame();
            try {
                captureEntityFrame();
                if (firstPersonPass != null && firstPersonPass.hasPendingCapture()) {
                    try (ProfileScope ignored = ProfileScope.begin("FirstPersonModel")) {
                        firstPersonPass.submitPending(entitySubmitter);
                    }
                }
            } finally {
                closeEntityFrame();
                frameEntityClosed = true;
            }
            blockDamagePass.finishFrame();
        }

        @Override
        public void submitBlockOutline() {
            if (blockOutlinePass == null) {
                return;
            }
            try (ProfileScope ignored = ProfileScope.begin("BlockOutline")) {
                blockOutlinePass.submit(frameWorld);
            } catch (Exception exception) {
                LOGGER.error("Error rendering targeted-block outline", exception);
            }
        }

        @Override
        public void renderSky() {
            if (!MinecraftRuntime.isSkyEnabled()) {
                return;
            }
            perfTimer.begin(perfSky);
            try (ProfileScope ignored = ProfileScope.begin("Sky")) {
                skyRenderer.render(captureSkyFrame(
                        frameWorld, frameCameraPos, frameTickDelta));
            } catch (Exception exception) {
                LOGGER.error("Error rendering sky", exception);
            } finally {
                perfTimer.end(perfSky);
            }
        }

        @Override
        public void renderClouds() {
            if (!MinecraftRuntime.isWorldActive()) {
                try {
                    cloudRenderer.disable();
                } catch (RuntimeException exception) {
                    LOGGER.error("Failed to disable cloud rendering", exception);
                }
                return;
            }
            perfTimer.begin(perfClouds);
            try (ProfileScope scope = ProfileScope.begin("Clouds")) {
                cloudCapture.render(frameWorld, frameCameraPos, frameTickDelta);
                perfTimer.injectNanos(perfCloudGen, cloudRenderer.getLastGenerationNanos());
                perfTimer.count(cntCloudVisible, cloudRenderer.getLastCloudCount());
                perfTimer.count(cntCloudInstances, cloudRenderer.getLastInstanceCount());
                perfTimer.count(
                        cntCloudPaletteCreates,
                        cloudRenderer.getLastPaletteCreateCount());
                perfTimer.count(cntCloudDraws, cloudRenderer.getLastDrawCount());
                perfTimer.count(cntCloudVertices, cloudRenderer.getLastVertexCount());
                perfTimer.count(cntCloudTriangles, cloudRenderer.getLastTriangleCount());
                scope.drawCalls(cloudRenderer.getLastDrawCount())
                        .vertexCount(cloudRenderer.getLastVertexCount());
            } catch (Exception exception) {
                LOGGER.error("Error rendering clouds", exception);
            } finally {
                perfTimer.end(perfClouds);
            }
        }

        @Override
        public void flushAtlasAnimations() {
            if (rendererScene == null) {
                return;
            }
            perfTimer.begin(perfAtlas);
            try (ProfileScope ignored = ProfileScope.begin("Atlas")) {
                rendererScene.flushAtlasAnimations();
            } finally {
                perfTimer.end(perfAtlas);
            }
        }

        @Override
        public void submitFrame() {
            perfTimer.begin(perfSubmit);
            try (ProfileScope ignored = ProfileScope.begin("Submit")) {
                RemixRenderer.this.submitFrame();
            } finally {
                perfTimer.end(perfSubmit);
            }
        }

        @Override
        public void finishParticlePreparation() {
            // Joining after submit keeps a slow worker in the inter-frame gap.
            joinAndStoreParticleFrame(frameParticleFuture, frameWorld);
            frameParticleFuture = null;
        }

        @Override
        public void reportFrameFailure(Exception exception) {
            // Match try-with-resources semantics: the frame scope is closed before
            // control reaches the failure handler.
            closeFrameProfileScope();
            if (exception instanceof RemixException remixException) {
                LOGGER.error(
                        "Remix error during frame render: {} (code: {})",
                        remixException.getMessage(),
                        remixException.getErrorCode(),
                        remixException);
            } else {
                LOGGER.error("Error during frame render", exception);
            }
        }

        @Override
        public void endFrame() {
            closeFrameProfileScope();
            if (!frameEntityClosed) {
                try {
                    closeEntityFrame();
                } catch (RuntimeException exception) {
                    LOGGER.error("Error closing the entity frame", exception);
                }
            }
            if (frameParticleFuture != null) {
                discardParticleRenderTask(frameParticleFuture);
            }
            flushParticleFrameTiming();
            try (ProfileScope ignored = ProfileScope.begin("gcAssetStores")) {
                gcAssetStores();
            }
            perfTimer.endFrame();
            benchmarkController.onFrameEnd(perfTimer);
            frameWorld = null;
            frameCamera = null;
            frameCameraPos = null;
            frameParticleFuture = null;
        }

        private void closeFrameProfileScope() {
            if (frameProfileScope != null) {
                try {
                    frameProfileScope.close();
                } catch (RuntimeException exception) {
                    LOGGER.error("Error closing frame profiling scope", exception);
                } finally {
                    frameProfileScope = null;
                }
            }
        }
    }

    private void captureEntityFrame() {
        if (!MinecraftRuntime.isEntitiesEnabled()) {
            return;
        }
        perfTimer.begin(perfEntities);
        try (ProfileScope scope = ProfileScope.begin("Entities")) {
            RemixEntityPass.FrameStats stats =
                    entityPass.finishFrame(
                            frameWorld,
                            frameCameraPos,
                            frameTickDelta,
                            lightManager.getFirstPersonShadowModelOffset(),
                            entitySubmitter);
            scope.entityCount(stats.entityCount())
                    .layerCount(stats.layerCount())
                    .boneCount(stats.boneCount())
                    .drawCalls(stats.drawCount())
                    .meshCreates(stats.meshCreates());
            perfTimer.count(cntEntities, stats.entityCount());
            perfTimer.count(cntEntLayers, stats.layerCount());
            perfTimer.count(cntEntBones, stats.boneCount());
            perfTimer.count(cntEntDraws, stats.drawCount());
            perfTimer.count(cntEntMeshCreates, stats.meshCreates());
            perfTimer.count(cntEntSkipped, stats.capSkipped());
            perfTimer.injectNanos(perfEntCapture, stats.captureMicros() * 1000L);
            perfTimer.injectNanos(perfEntSubmit, stats.submitMicros() * 1000L);
            perfTimer.count(cntEntOffFrustum, stats.offFrustumCount());
            perfTimer.injectNanos(perfEntOffSweep, stats.offSweepMicros() * 1000L);
            perfTimer.count(cntEntOffDraws, stats.offFrustumDraws());
            perfTimer.count(cntEntOffReused, stats.offFrustumReused());
            perfTimer.count(cntEntPoolSize, stats.poolSize());
        } catch (Exception exception) {
            LOGGER.error("Error rendering entities", exception);
        } finally {
            perfTimer.end(perfEntities);
        }
    }

    private SkyFrame captureSkyFrame(ClientLevel world, Vec3 cameraPos, float tickDelta) {
        ConfLayerDriver.WeatherStrengths weather = confLayerDriver != null
                && confLayerDriver.isActive()
                ? confLayerDriver.weatherStrengths()
                : new ConfLayerDriver.WeatherStrengths(
                        world.getRainLevel(tickDelta),
                        world.getThunderLevel(tickDelta));
        SkyFrame.Type skyType;
        switch (world.effects().skyType()) {
            case NORMAL:
                skyType = SkyFrame.Type.NORMAL;
                break;
            case END:
                skyType = SkyFrame.Type.END;
                break;
            case NONE:
                skyType = SkyFrame.Type.NONE;
                break;
            default:
                throw new IllegalStateException("Unknown Minecraft sky type");
        }
        return new SkyFrame(
                world,
                skyType,
                cameraPos.x,
                cameraPos.y,
                cameraPos.z,
                world.getTimeOfDay(tickDelta),
                world.getStarBrightness(tickDelta),
                world.getMoonPhase(),
                weather.rain(),
                weather.thunder(),
                skyLightRenderer != null ? skyLightRenderer.state() : SkyLightState.empty());
    }

    /**
     * Retires entity parts and closes the block-entity frame, publishing its counters
     * while the block-entity toggle is on so a disabled frame contributes no zero sample.
     * Block entities are captured and submitted in place during vanilla's world render,
     * inside the entity frame this closes. Retirement runs before Submit: an instance
     * destroyed after the submit is still in that frame's scene.
     */
    private void closeEntityFrame() {
        entitySubmitter.endFrame();
        RemixBlockEntityPass.FrameStats stats = blockEntityPass.finishFrame();
        if (!MinecraftRuntime.isBlockEntitiesEnabled()) {
            return;
        }
        perfTimer.count(cntBlockEntities, stats.count());
        perfTimer.count(cntBlockEntityParts, stats.parts());
        perfTimer.count(cntBlockEntityMeshCreates, stats.meshCreates());
        perfTimer.injectNanos(perfBlockEntityCapture, stats.captureMicros() * 1000L);
        perfTimer.injectNanos(perfBlockEntitySubmit, stats.submitMicros() * 1000L);
    }

    private CompletableFuture<ParticleRenderResult> startParticleRenderTask(float tickDelta, long frameIndex) {
        if (!MinecraftRuntime.isParticlesEnabled()
                || particleRenderer == null
                || particleCapture == null
                || particleAtlasManager == null) {
            return null;
        }

        if (!particleAtlasManager.isInitialized()) {
            particleAtlasManager.initialize();
        }
        if (!particleAtlasManager.isInitialized()) {
            return null;
        }

        CompletableFuture<ParticleRenderResult> future = CompletableFuture.supplyAsync(
                () -> renderParticlesOnWorker(tickDelta, frameIndex),
                particleExecutor);
        particleRenderFuture = future;
        return future;
    }

    private ParticleRenderResult renderParticlesOnWorker(float tickDelta, long frameIndex) {
        RemixAPI.tracyThreadName("Particles");
        long previousFrameIndex = ProfileScope.setFrameIndexForCurrentThread(frameIndex);
        PreparedFrame preparedFrame = null;
        // The scope records the worker's own duration on the worker thread, which is where it
        // belongs. It is deliberately not returned to the render thread: it is off the critical
        // path, so charging it to a render-thread section overstates that section.
        try (ProfileScope particleScope = ProfileScope.begin("Particles")) {
            preparedFrame = particleCapture.prepare(tickDelta);
            particleScope
                    .captureCount(preparedFrame.particleCount())
                    .drawCalls(preparedFrame.plannedDrawCount())
                    .vertexCount(preparedFrame.plannedActiveQuadCount() * 4);
            return new ParticleRenderResult(preparedFrame, null);
        } catch (Exception e) {
            if (preparedFrame != null) preparedFrame.close();
            return new ParticleRenderResult(null, e);
        } catch (Error e) {
            if (preparedFrame != null) preparedFrame.close();
            throw e;
        } finally {
            ProfileScope.restoreFrameIndexForCurrentThread(previousFrameIndex);
        }
    }

    /**
     * Joins the particle worker and releases its payload without publishing it. Used on the
     * frame error path and by every teardown path, where publishing transient native assets
     * moments before {@code particleRenderer.cleanup()} would leak them.
     */
    private void discardParticleRenderTask(CompletableFuture<ParticleRenderResult> future) {
        if (future == null) {
            return;
        }

        try {
            ParticleRenderResult result = future.join();
            PreparedFrame preparedFrame = result.preparedFrame();
            if (preparedFrame != null) {
                preparedFrame.close();
            }
            if (result.error() != null) {
                LOGGER.error("Error rendering particles", result.error());
            }
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.error("Error rendering particles", cause);
        } finally {
            if (particleRenderFuture == future) {
                particleRenderFuture = null;
            }
        }
    }

    /** Submits the frame prepared by the previous render call. No blocking: the work is already done. */
    private void submitPendingParticleFrame() {
        PreparedFrame frame = pendingParticleFrame;
        pendingParticleFrame = null;
        if (frame == null) return;

        long submitStart = System.nanoTime();
        try (ProfileScope scope = ProfileScope.begin("Particles.Submit")) {
            particleRenderer.submit(frame);
            scope.captureCount(particleRenderer.getLastParticleCount())
                 .drawCalls(particleRenderer.getLastDrawCount())
                 .vertexCount(particleRenderer.getLastActiveQuadCount() * 4);
            recordParticleCounters();
        } catch (Exception e) {
            LOGGER.error("Error submitting particles", e);
        } finally {
            frame.close();
            particleNanosThisFrame += System.nanoTime() - submitStart;
        }
    }

    /**
     * Joins the particle worker after {@code submitFrame()} and stores the result for the
     * next frame's submit. Never submits native Remix calls, which happen in
     * {@link #submitPendingParticleFrame()}.
     *
     * <p>Only the wait itself is charged to the frame, not the worker's own run time. Since the
     * prepare task is dispatched at the top of the frame and joined after Submit, it has
     * normally finished long before this runs, so its duration is not on the critical path.
     * Charging it made the section report several times what the render thread actually paid,
     * and made it appear to grow on slow frames it had not contributed to. Timing the wait
     * keeps a worker that genuinely does stall the frame visible.
     *
     * @param preparedFor the world the prepare task was dispatched against, recorded with the
     *                    payload so the next frame can refuse it after a world swap
     */
    private void joinAndStoreParticleFrame(CompletableFuture<ParticleRenderResult> future,
                                           ClientLevel preparedFor) {
        if (future == null) return;
        try {
            long waitStart = System.nanoTime();
            ParticleRenderResult result = future.join();
            particleNanosThisFrame += System.nanoTime() - waitStart;

            if (result.error() != null) {
                LOGGER.error("Error rendering particles", result.error());
            } else {
                pendingParticleFrame = result.preparedFrame();
                pendingParticleFrameWorld = preparedFor;
            }
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.error("Error rendering particles", cause);
        } finally {
            if (particleRenderFuture == future) particleRenderFuture = null;
        }
    }

    /**
     * Publishes the frame's total particle cost as one sample. {@code injectNanos} overwrites
     * rather than accumulates, so the double-buffer's two contributions (the previous frame's
     * submit and this frame's prepare) must be summed before a single injection.
     */
    private void flushParticleFrameTiming() {
        perfTimer.injectNanos(perfParticles, particleNanosThisFrame);
        particleNanosThisFrame = 0L;
    }

    /** Closes the pending particle frame without submitting it. Used during cleanup and world changes. */
    private void discardPendingParticleFrame() {
        PreparedFrame frame = pendingParticleFrame;
        pendingParticleFrame = null;
        pendingParticleFrameWorld = null;
        if (frame != null) frame.close();
    }

    private void joinInFlightParticleRenderTask() {
        CompletableFuture<ParticleRenderResult> future = particleRenderFuture;
        if (future != null) {
            discardParticleRenderTask(future);
        }
    }

    private void recordParticleCounters() {
        perfTimer.count(cntParticles, particleRenderer.getLastParticleCount());
        perfTimer.count(cntParticleDraws, particleRenderer.getLastDrawCount());
        perfTimer.count(cntParticleDynamicQuads, particleRenderer.getLastActiveQuadCount());
        perfTimer.count(cntParticleNewSlots, particleRenderer.getLastNewSlotCount());
        perfTimer.count(cntParticleRemovedSlots, particleRenderer.getLastRemovedSlotCount());
        perfTimer.count(cntParticleCapacityGrows, particleRenderer.getLastCapacityGrowCount());
        perfTimer.count(cntParticleCapacity, particleRenderer.getLastCapacityCount());
        perfTimer.count(cntParticleMeshUpdates, particleRenderer.getLastMeshUpdateCount());
    }

    /** Exactly one component is set; a prepare that failed has already closed its frame. */
    private record ParticleRenderResult(
            PreparedFrame preparedFrame,
            Exception error) {}

    public void onMinecraftRenderFrameEnd(long fullFrameNanos) {
        if (!initialized || benchmarkController == null) {
            return;
        }
        benchmarkController.onMinecraftRenderFrameEnd(fullFrameNanos, perfTimer);
    }

    /**
     * Updates the Remix camera from Minecraft's camera
     */
    private void updateCamera(Camera camera, float tickDelta) {
        try {
            Vec3 pos = camera.getPosition();
            float pitch = camera.getXRot();
            float yaw = camera.getYRot();
    
            float yawRad = (float) Math.toRadians(-yaw);
            float pitchRad = (float) Math.toRadians(pitch);
    
            float forwardX = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
            float forwardY = (float) (-Math.sin(pitchRad));
            float forwardZ = (float) (Math.cos(yawRad) * Math.cos(pitchRad));
    
            float rightX = (float) (-Math.cos(yawRad));
            float rightY = 0.0f;
            float rightZ = (float) (Math.sin(yawRad));
    
            float upX = rightY * forwardZ - rightZ * forwardY;
            float upY = rightZ * forwardX - rightX * forwardZ;
            float upZ = rightX * forwardY - rightY * forwardX;
    
            Minecraft client = Minecraft.getInstance();
            float worldFov = (float) client.gameRenderer.getFov(camera, tickDelta, true);
            int width = client.getWindow().getWidth();
            int height = client.getWindow().getHeight();
            float aspect = (float) width / (float) height;
            if (weatherRenderer != null) {
                weatherRenderer.updateCamera(
                        camera,
                        worldFov,
                        aspect);
            }
    
            CameraInfoParameterizedEXT worldCameraParams = CameraInfoParameterizedEXT.builder()
                .position((float) pos.x, (float) pos.y, (float) pos.z)
                .forward(forwardX, forwardY, forwardZ)
                .up(upX, upY, upZ)
                .right(rightX, rightY, rightZ)
                .fovYInDegrees(worldFov)
                .aspect(aspect)
                .nearPlane(0.05f)
                .farPlane(1000.0f)
                .build();
    
            CameraInfo worldCameraInfo = CameraInfo.builder()
                .type(CameraType.WORLD)
                .parameterizedExt(worldCameraParams)
                .build();
    
            CameraInfo viewModelCameraInfo = CameraInfo.builder()
                .type(CameraType.VIEW_MODEL)
                // The hand-FOV adjustment is already applied to first-person
                // bones. Supplying the exact world camera keeps Remix's
                // perspective-correction matrix at identity while retaining
                // its per-frame view-model transform/history update.
                .parameterizedExt(worldCameraParams)
                .build();
    
            remixApi.setupCamera(worldCameraInfo);
            remixApi.setupCamera(viewModelCameraInfo);
            float viewDistanceStrength = viewDistanceFade.layerStrength(ClientViewDistance.chunks());
            updateCameraMedium(camera, pos, viewDistanceStrength, tickDelta);
        } catch (RemixException e) {
            LOGGER.error("Error updating camera: {} (code: {})", e.getMessage(), e.getErrorCode());
        } catch (Exception e) {
            LOGGER.error("Error updating camera", e);
        }
    }

    ViewDistanceFade getViewDistanceFade() {
        return viewDistanceFade;
    }

    public boolean isViewDistanceFadeAuto() {
        return viewDistanceFade.isAuto();
    }

    public void setViewDistanceFadeAuto(boolean auto) {
        viewDistanceFade.setAuto(auto);
        syncViewDistanceFade();
    }

    /**
     * Recomputes the band that {@code /rtx viewdistance} and the settings
     * screen report, so a mode change is visible before the next rendered
     * frame recomputes it anyway. The driver takes its strength from
     * {@code updateCamera}.
     */
    private void syncViewDistanceFade() {
        if (!initialized) {
            return;
        }
        viewDistanceFade.layerStrength(ClientViewDistance.chunks());
    }

    private void updateCameraMedium(Camera camera, Vec3 cameraPos, float viewDistanceStrength, float tickDelta) {
        try {
            Minecraft client = Minecraft.getInstance();
            ClientLevel world = client != null ? client.level : null;
    
            // Submersion is read before material readiness so the driver sees
            // it on early frames.
            boolean underwater = camera.getFluidInCamera() == FogType.WATER;
            long materialHandle = 0;
            if (underwater && textureManager != null) {
                RemixMaterial cameraMediumMaterial = textureManager.getWaterCameraMediumMaterial();
                if (cameraMediumMaterial != null && !cameraMediumMaterial.isDeleted()) {
                    materialHandle = cameraMediumMaterial.getHandle();
                }
            }
    
            remixApi.setCameraMediumMaterial(materialHandle);
            // Conf layers own the volumetric profile and the view-distance
            // fade. A driver left inactive by a failed reload drives nothing
            // until the next successful /rtx conf reload.
            if (confLayerDriver != null && confLayerDriver.isActive() && world != null) {
                confLayerDriver.update(
                        gameplayFrameContext.update(
                                world,
                                cameraPos,
                                underwater,
                                viewDistanceStrength,
                                tickDelta),
                        zoneRegistry);
            }
            if (materialHandle != lastCameraMediumHandle) {
                LOGGER.info("Camera medium updated: {}", materialHandle != 0 ? "water" : "clear");
                lastCameraMediumHandle = materialHandle;
            }
        } catch (RemixException e) {
            LOGGER.error("Error updating camera medium: {} (code: {})", e.getMessage(), e.getErrorCode());
        } catch (Exception e) {
            LOGGER.error("Error updating camera medium", e);
        }
    }
    
    private void submitFrame() {
        rendererSession.submit();
    }
    
    /**
     * Presents the frame through Remix, which owns the swapchain for as long as the
     * renderer is initialized: this method replaces the GLFW buffer swap, so returning
     * without calling present leaves the window frozen on the last presented image.
     * A failed GL-to-Remix copy therefore still presents, and a failure is logged once
     * per run rather than once per frame.
     */
    public void presentHostFrame() {
        if (!FAULT_IN_REMIX_CALL.isEmpty() && presentedFrames == FAULT_IN_REMIX_CALL_FRAME) {
            faultInRemixCall();
        }
        if (graphicsIntegration.present(rendererSession)) {
            presentedFrames++;
        }
    }

    private void faultInRemixCall() {
        boolean insideJvm = FAULT_IN_REMIX_CALL.equals("jvm");
        if (!insideJvm && !FAULT_IN_REMIX_CALL.equals("runtime")) {
            LOGGER.warn("Ignoring rtxremix.debug.faultInRemixCall={}: expected runtime or jvm",
                    FAULT_IN_REMIX_CALL);
            return;
        }
        LOGGER.warn("Crashing inside a Remix call ({}), as rtxremix.debug.faultInRemixCall asks",
                FAULT_IN_REMIX_CALL);
        try {
            remixApi.faultInRemixCall(insideJvm);
        } catch (RemixException e) {
            LOGGER.error("Could not crash inside a Remix call: {} (code: {})", e.getMessage(), e.getErrorCode());
        }
    }

    /**
     * Returns the host texture containing the Remix output for this frame, or
     * zero when no host-visible output exists; the caller then skips compositing.
     */
    public int acquireHostOutputTexture() {
        return graphicsIntegration.acquireOutputTexture();
    }

    /** Composites host graphics passes while the host owns graphics state. */
    public void compositeHostOutput(int colorTexture) {
        compositeColorTexture = colorTexture;
        graphicsIntegration.compositeOutput(
                colorTexture, additionalHostPasses);
    }

    private void renderAdditionalHostPasses() {
        if (weatherRenderer == null) {
            return;
        }
        try (ProfileScope weatherScope = ProfileScope.begin("Weather")) {
            weatherRenderer.render(
                    compositeColorTexture, graphicsIntegration.depthTexture());
            weatherScope
                    .drawCalls(weatherRenderer.getLastDrawCount())
                    .vertexCount(weatherRenderer.getLastQuadCount() * 4);
        }
    }

    /** Runs on the client executor so the clear lands during teardown, before the next world initializes. */
    public void clearWorld() {
        LOGGER.info("World clear requested (will execute on render thread)");
        Minecraft.getInstance().execute(() -> {
            if (initialized) {
                doClearWorld();
            }
        });
    }

    /**
     * Requests a full texture/material rebuild at the next world-render start.
     * Call when the sprite atlas changes (resource pack add/remove/reorder).
     */
    public void requestTextureReload() {
        if (initialized) pendingTextureReload = true;
    }

    /** Requests a material reload followed by one complete loaded-terrain recapture. */
    public void requestMaterialSettingsReload() {
        if (!initialized) return;
        pendingTerrainRecaptureAfterTextureReload = true;
        pendingTextureReload = true;
    }

    /** Clears stale outline capture at the start of vanilla world rendering. */
    public void beginBlockOutlineFrame() {
        if (blockOutlinePass != null) {
            blockOutlinePass.beginFrame();
        }
    }

    /**
     * Captures vanilla's targeted-block outline for Remix submission later in
     * the same frame.
     */
    public RemixBlockOutlinePass getBlockOutlinePass() {
        return blockOutlinePass;
    }
    
    /**
     * Actually clears all world-specific Remix resources (meshes, materials, lights)
     * without tearing down the API or GL state. Must be called on the render thread.
     */
    private void doClearWorld() {
        LOGGER.info("Clearing world resources...");
        joinInFlightParticleRenderTask();
        discardPendingParticleFrame();
        
        if (lightManager != null) {
            lightManager.clear();
        }
        entityAtlasPreloadPending = true;
        if (firstPersonPass != null) {
            firstPersonPass.clearPending();
        }
        if (weatherRenderer != null) {
            weatherRenderer.cleanup();
        }
        sceneForTeardown().clearWorld();
        LOGGER.info("World resources cleared");
    }
    
    /**
     * Shuts down the renderer
     */
    public void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        LOGGER.info("Shutting down RTX Remix renderer...");

        initialized = false;
        pendingTextureReload = false;
        pendingTerrainRecaptureAfterTextureReload = false;

        // Deactivate patch forwarding so the sprite-upload mixin stops queuing
        // records into a list that will never be drained.
        AtlasAnimationForwarder.clear();

        joinInFlightParticleRenderTask();
        discardPendingParticleFrame();
        shutdownParticleExecutor();
        doClearWorld();

        // Release the conf option-layers while the runtime is still alive
        // (remixApi shutdown itself is deferred until window close).
        if (confLayerDriver != null) {
            confLayerDriver.releaseAll();
            confLayerDriver = null;
        }

        particleCapture = null;
        if (weatherRenderer != null) {
            weatherRenderer.cleanup();
            weatherRenderer = null;
        }
        if (firstPersonPass != null) {
            firstPersonPass.close();
            firstPersonPass = null;
        }
        if (rendererScene != null) {
            rendererScene.close();
            rendererScene = null;
        } else {
            sceneForTeardown().close();
        }
        skyRenderer = null;
        skyLightRenderer = null;
        chunkManager = null;
        entitySubmitter = null;
        particleRenderer = null;
        particleAtlasManager = null;
        textureManager = null;
        blockOutlinePass = null;
        assetStores = null;

        graphicsIntegration.close();

        LOGGER.info("RTX Remix native runtime shutdown deferred until Minecraft window close");
        LOGGER.info("RTX Remix renderer shut down");
    }

    private RendererScene sceneForTeardown() {
        return rendererScene != null
                ? rendererScene
                : RendererScene.partiallyInitialized(
                        assetStores,
                        textureManager,
                        chunkManager,
                        skyLightRenderer,
                        skyRenderer,
                        cloudRenderer,
                        particleAtlasManager,
                        entitySubmitter,
                        particleRenderer,
                        blockOutlinePass);
    }

    /**
     * Completes native runtime shutdown after Minecraft has destroyed its GLFW window.
     */
    public void shutdownNativeRuntime() {
        shutdownRemixApi();
    }

    private void shutdownRemixApi() {
        if (remixApi != null) {
            try {
                rendererSession.shutdown();
            } catch (Exception e) {
                LOGGER.error("Error during Remix shutdown", e);
            }
            remixApi = null;
            rendererSession = null;
            LOGGER.info("RTX Remix native runtime shut down");
        }
    }

    private void shutdownParticleExecutor() {
        particleExecutor.shutdown();
        try {
            if (!particleExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                particleExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            particleExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    public RemixAPI getRemixApi() {
        return remixApi;
    }
    
    public ChunkManager getChunkManager() {
        return rendererScene != null ? rendererScene.terrain() : chunkManager;
    }

    public ZoneRegistry getZoneRegistry() {
        return zoneRegistry;
    }

    /**
     * Null before initialize() acquires the layers and after shutdown. Can
     * also be non-null but inactive ({@code isActive() == false}) after a
     * failed reload; a later successful {@link ConfLayerDriver#reload()}
     * reactivates it.
     */
    public ConfLayerDriver getConfLayerDriver() {
        return confLayerDriver;
    }

    public LightManager getLightManager() {
        return lightManager;
    }

    public BlockTextureManager getTextureManager() {
        return rendererScene != null ? rendererScene.blockTextures() : textureManager;
    }

    public RemixWeatherRenderer getWeatherRenderer() {
        return weatherRenderer;
    }
    
    public PerformanceTimer getPerfTimer() {
        return perfTimer;
    }

    public BenchmarkController getBenchmarkController() {
        return benchmarkController;
    }

    public CameraController getCameraController() {
        return cameraController;
    }

    public SkyRenderer getSkyRenderer() {
        return rendererScene != null ? rendererScene.sky() : skyRenderer;
    }

    public CloudRenderer getCloudRenderer() {
        return rendererScene != null ? rendererScene.clouds() : cloudRenderer;
    }

    public PostProcessingSettings getPostProcessingSettings() {
        return postProcessingSettings;
    }

    public RemixEntityPass getEntityPass() {
        return entityPass;
    }

    public void captureFirstPersonModel(
            ItemInHandRenderer heldItemRenderer,
            float tickDelta,
            PoseStack matrices,
            LocalPlayer player,
            int light) {
        if (firstPersonPass != null && initialized && MinecraftRuntime.isRtxEnabled()) {
            firstPersonPass.capture(heldItemRenderer, tickDelta, matrices, player, light);
        }
    }
}
