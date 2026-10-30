/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import java.nio.ByteBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.MaterialInfoOpaqueSubsurfaceEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.Transform;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Renders either deterministic Perlin-Worley clouds or the voxelized vanilla
 * cloud texture, as one Remix material shared by all clouds and one submitted
 * transform per visible cloud or texture chunk each frame.
 *
 * <p>Ownership: only the render thread creates or destroys Remix assets here.
 * Mesh generation runs on worker tasks that return plain vertex data, and every
 * worker result carries the generation epoch it was started under, so a result
 * that lands after a world change, a settings change or a teardown is dropped
 * instead of publishing a mesh into the wrong scene.
 */
public final class CloudRenderer {
    private static final ResourceLocation VANILLA_CLOUD_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/environment/clouds.png");
    static final int PALETTE_SIZE = 32;
    static final long PALETTE_SEED = 0x434C_4F55_4450_5546L;
    private static final long MAINTENANCE_INTERVAL_TICKS = 40L;
    private static final double CURVATURE_RADIUS = 8000.0;
    private static final float DEFAULT_VANILLA_EDGE_SMOOTHNESS = 1.5f;
    private static final float VANILLA_BASE_THICKNESS = 4.0f;
    /** Vanilla's small offset above the nominal cloud height, which avoids z-fighting with block tops. */
    private static final float VANILLA_LAYER_LIFT = 0.33f;
    private static final float DEFAULT_CLOUD_SIZE = 1.0f;
    private static final float DEFAULT_CLOUDS_HEIGHT = 0.0f;
    private static final int INSTANCE_CATEGORIES = InstanceCategoryBit.IGNORE_BAKED_LIGHTING;
    private static final float CLOUD_ALBEDO = 0.99f;
    private static final double VANILLA_COLOR_INFLUENCE = 0.2;
    private static final float SSS_RADIUS_SCALE = 5000.0f;
    private static final float SSS_MAX_SAMPLE_RADIUS = 1.0f;

    private final RemixRenderer renderer;
    private final MeshCreator meshCreator;
    private final PaletteEntry[] palette = new PaletteEntry[PALETTE_SIZE];
    private final Map<Long, ActiveCloud> clouds = new HashMap<>();
    private final Set<Long> visibleKeys = new HashSet<>();
    private final Map<Long, PaletteEntry> vanillaChunks = new HashMap<>();
    private final Set<Long> emptyVanillaChunks = new HashSet<>();
    private final Set<Long> pendingVanillaChunks = new HashSet<>();
    private final ConcurrentLinkedQueue<PaletteGenerationResult> completedPaletteMeshes =
            new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<VanillaChunkResult> completedVanillaChunks =
            new ConcurrentLinkedQueue<>();
    private final Transform vanillaTransform = new Transform();

    private ClientLevel currentWorld;
    private RemixMaterial material;
    private int cloudColor = 0x00ff_ffff;
    private int materialColor = -1;
    private boolean[] vanillaOccupancy;
    private int vanillaTextureWidth;
    private int vanillaTextureHeight;
    private RuntimeException vanillaFailure;
    private RuntimeException paletteFailure;
    private volatile long paletteGenerationEpoch;
    private boolean paletteGenerationScheduled;
    private CompletableFuture<?> paletteGenerationTask;
    private long vanillaGenerationEpoch;
    private boolean proceduralClouds;
    private float vanillaEdgeSmoothness = DEFAULT_VANILLA_EDGE_SMOOTHNESS;
    private float cloudSize = DEFAULT_CLOUD_SIZE;
    private float cloudsHeight = DEFAULT_CLOUDS_HEIGHT;
    private long lastMaintenanceBucket = Long.MIN_VALUE;
    private double lastVisibilityCameraX = Double.NaN;
    private double lastVisibilityCameraZ = Double.NaN;
    private double placementWorldTime;
    private int lastCloudCount;
    private int lastInstanceCount;
    private int lastPaletteCreateCount;
    private int lastDrawCount;
    private int lastVertexCount;
    private int lastTriangleCount;
    private long lastGenerationNanos;

    public CloudRenderer(RemixRenderer renderer) {
        this(renderer, null);
    }

    CloudRenderer(RemixRenderer renderer, MeshCreator meshCreator) {
        this.renderer = renderer;
        this.meshCreator = meshCreator;
    }

    public void render(ClientLevel world, Vec3 cameraPos, float tickDelta) {
        resetFrameStats();
        if (world != currentWorld) {
            clearScene();
            currentWorld = world;
        }

        Minecraft client = Minecraft.getInstance();
        float cloudHeight = world.effects().getCloudHeight();
        if (client.options.getCloudsType() == CloudStatus.OFF
                || Float.isNaN(cloudHeight)) {
            releaseResources();
            return;
        }
        if (!shouldSuppressVanillaClouds()) {
            return;
        }
        updateCloudColor(world.getCloudColor(tickDelta));

        if (!proceduralClouds) {
            completedPaletteMeshes.clear();
            clouds.clear();
            visibleKeys.clear();
            int cloudTicks =
                    ((WorldRendererCloudAccess) client.levelRenderer).rtxremix$getCloudTicks();
            drawVanillaClouds(cameraPos, cloudHeight, cloudTicks + (double) tickDelta);
            return;
        }

        double worldTime = world.getGameTime() + (double) tickDelta;
        if (!ensurePalette()) {
            return;
        }
        long maintenanceBucket = (long) Math.floor(worldTime / MAINTENANCE_INTERVAL_TICKS);
        if (needsVisibilityRefresh(
                maintenanceBucket,
                lastMaintenanceBucket,
                cameraPos.x,
                cameraPos.z,
                lastVisibilityCameraX,
                lastVisibilityCameraZ)) {
            updateVisibleClouds(cameraPos, worldTime);
            lastMaintenanceBucket = maintenanceBucket;
            lastVisibilityCameraX = cameraPos.x;
            lastVisibilityCameraZ = cameraPos.z;
            placementWorldTime = worldTime;
        }

        drawClouds(cameraPos, cloudHeight, worldTime);
        lastCloudCount = clouds.size();
    }

    static boolean needsVisibilityRefresh(
            long maintenanceBucket,
            long lastMaintenanceBucket,
            double cameraX,
            double cameraZ,
            double lastCameraX,
            double lastCameraZ) {
        if (maintenanceBucket != lastMaintenanceBucket
                || !Double.isFinite(lastCameraX)
                || !Double.isFinite(lastCameraZ)) {
            return true;
        }
        double dx = cameraX - lastCameraX;
        double dz = cameraZ - lastCameraZ;
        return dx * dx + dz * dz
                >= CloudPlacement.CELL_SIZE * CloudPlacement.CELL_SIZE;
    }

    private void updateVisibleClouds(Vec3 cameraPos, double worldTime) {
        visibleKeys.clear();
        try (ProfileScope ignored = ProfileScope.begin("Cloud.Visibility")) {
            CloudPlacement.forEachVisible(
                    cameraPos.x,
                    cameraPos.z,
                    worldTime,
                    placement -> {
                        visibleKeys.add(placement.key());
                        ActiveCloud cloud = clouds.get(placement.key());
                        if (cloud == null) {
                            clouds.put(
                                    placement.key(),
                                    new ActiveCloud(
                                            placement,
                                            paletteIndex(placement.seed())));
                        } else {
                            cloud.placement = placement;
                        }
                    });
        }

        Iterator<Map.Entry<Long, ActiveCloud>> iterator = clouds.entrySet().iterator();
        while (iterator.hasNext()) {
            if (!visibleKeys.contains(iterator.next().getKey())) {
                iterator.remove();
            }
        }
    }

    static int paletteIndex(long seed) {
        return (int) Long.remainderUnsigned(
                CloudPlacement.mix(seed + 5L), PALETTE_SIZE);
    }

    private boolean ensurePalette() {
        if (paletteFailure != null) {
            throw paletteFailure;
        }
        publishNextPaletteMesh();
        if (isPaletteReady()) {
            return true;
        }
        if (!paletteGenerationScheduled) {
            schedulePaletteGeneration();
        }
        return false;
    }

    private void schedulePaletteGeneration() {
        if (paletteGenerationTask != null && !paletteGenerationTask.isDone()) {
            return;
        }
        try {
            destroyPalette(palette);
        } catch (RuntimeException e) {
            throw failPalette("Failed to reset procedural cloud palette", e);
        }

        long epoch = ++paletteGenerationEpoch;
        paletteGenerationScheduled = true;
        paletteGenerationTask = CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < PALETTE_SIZE; i++) {
                    if (epoch != paletteGenerationEpoch) {
                        return;
                    }
                    CloudMeshGenerator.GeneratedMesh generated =
                            CloudMeshGenerator.generate(
                                    CloudPlacement.mix(PALETTE_SEED + i));
                    if (epoch != paletteGenerationEpoch) {
                        return;
                    }
                    completedPaletteMeshes.add(
                            new GeneratedPaletteMesh(epoch, i, generated));
                }
            } finally {
                CloudMeshGenerator.releaseWorkspace();
            }
        }).whenComplete((unused, failure) -> {
            if (epoch == paletteGenerationEpoch) {
                completedPaletteMeshes.add(
                        failure == null
                                ? new CompletedPaletteGeneration(epoch)
                                : new FailedPaletteGeneration(epoch, failure));
            }
        });
    }

    private void publishNextPaletteMesh() {
        PaletteGenerationResult result;
        while ((result = completedPaletteMeshes.poll()) != null) {
            if (publishPaletteResult(result)) {
                return;
            }
        }
    }

    boolean publishPaletteResult(PaletteGenerationResult result) {
        if (result.epoch() != paletteGenerationEpoch) {
            return false;
        }
        if (result instanceof CompletedPaletteGeneration) {
            paletteGenerationScheduled = false;
            return false;
        }
        if (result instanceof FailedPaletteGeneration failed) {
            throw failPalette(
                    "Failed to generate procedural cloud palette", failed.failure());
        }

        GeneratedPaletteMesh generatedResult = (GeneratedPaletteMesh) result;
        CloudMeshGenerator.GeneratedMesh generated = generatedResult.mesh();
        try {
            RemixMesh mesh = createMesh(generated);
            palette[generatedResult.index()] = new PaletteEntry(
                    mesh,
                    generated.vertexCount(),
                    generated.triangleCount());
        } catch (RuntimeException e) {
            throw failPalette("Failed to publish procedural cloud palette", e);
        }
        lastGenerationNanos += generated.generationNanos();
        lastPaletteCreateCount = 1;
        return true;
    }

    private RuntimeException failPalette(String message, Throwable cause) {
        paletteGenerationEpoch++;
        paletteGenerationScheduled = false;
        completedPaletteMeshes.clear();
        RuntimeException failure = new IllegalStateException(message, cause);
        try {
            destroyPalette(palette);
        } catch (RuntimeException cleanupError) {
            failure.addSuppressed(cleanupError);
        }
        try {
            destroyMaterial();
        } catch (RuntimeException cleanupError) {
            failure.addSuppressed(cleanupError);
        }
        paletteFailure = failure;
        return failure;
    }

    private boolean isPaletteReady() {
        for (PaletteEntry entry : palette) {
            if (entry == null || entry.mesh.isDeleted()) {
                return false;
            }
        }
        return true;
    }

    private static void destroyPalette(PaletteEntry[] entries) {
        try {
            destroyEntries(Arrays.asList(entries));
        } finally {
            Arrays.fill(entries, null);
        }
    }

    private static void destroyEntries(Iterable<PaletteEntry> entries) {
        RuntimeException failure = null;
        for (PaletteEntry entry : entries) {
            try {
                if (entry != null && !entry.mesh.isDeleted()) {
                    entry.mesh.destroy();
                }
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void ensureVanillaCloudMask() {
        if (vanillaFailure != null) {
            throw vanillaFailure;
        }
        if (vanillaOccupancy != null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        try {
            var resource = client.getResourceManager().getResource(VANILLA_CLOUD_TEXTURE)
                    .orElseThrow(() -> new IOException(
                            "Missing texture " + VANILLA_CLOUD_TEXTURE));
            try (NativeImage image = NativeImage.read(resource.open())) {
                vanillaTextureWidth = image.getWidth();
                vanillaTextureHeight = image.getHeight();
                vanillaOccupancy =
                        new boolean[vanillaTextureWidth * vanillaTextureHeight];
                for (int y = 0; y < vanillaTextureHeight; y++) {
                    for (int x = 0; x < vanillaTextureWidth; x++) {
                        vanillaOccupancy[y * vanillaTextureWidth + x] =
                                ((image.getPixelRGBA(x, y) >>> 24) & 0xff) >= 128;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            vanillaFailure = new IllegalStateException(
                    "Failed to load vanilla cloud occupancy", e);
            throw vanillaFailure;
        }
    }

    private void scheduleVanillaChunk(VanillaCloudPlacement.Placement placement) {
        long textureKey = placement.textureKey();
        if (vanillaChunks.containsKey(textureKey)
                || emptyVanillaChunks.contains(textureKey)
                || !pendingVanillaChunks.add(textureKey)) {
            return;
        }

        long epoch = vanillaGenerationEpoch;
        boolean[] occupancy = vanillaOccupancy;
        int textureWidth = vanillaTextureWidth;
        int textureHeight = vanillaTextureHeight;
        float height = VANILLA_BASE_THICKNESS * cloudSize;
        float edgeSmoothness = vanillaEdgeSmoothness;
        CompletableFuture.supplyAsync(() -> VanillaCloudMeshGenerator.generate(
                        occupancy,
                        textureWidth,
                        textureHeight,
                        placement.textureX(),
                        placement.textureZ(),
                        placement.windowWidth(),
                        placement.windowHeight(),
                        placement.cellSizeX(),
                        placement.cellSizeZ(),
                        height,
                        edgeSmoothness))
                .whenComplete((generated, failure) -> completedVanillaChunks.add(
                        failure == null
                                ? new GeneratedVanillaChunk(epoch, textureKey, generated)
                                : new FailedVanillaChunk(epoch, textureKey, failure)));
    }

    private void publishVanillaChunks() {
        VanillaChunkResult completed;
        while ((completed = completedVanillaChunks.poll()) != null) {
            publishVanillaResult(completed);
        }
    }

    void publishVanillaResult(VanillaChunkResult completed) {
        if (completed.epoch() != vanillaGenerationEpoch) {
            return;
        }
        pendingVanillaChunks.remove(completed.textureKey());
        if (completed instanceof FailedVanillaChunk failed) {
            vanillaFailure = new IllegalStateException(
                    "Failed to generate vanilla cloud chunk", failed.failure());
            throw vanillaFailure;
        }
        GeneratedVanillaChunk generatedChunk = (GeneratedVanillaChunk) completed;
        if (vanillaChunks.containsKey(generatedChunk.textureKey())) {
            return;
        }

        VanillaCloudMeshGenerator.GeneratedMesh generated = generatedChunk.mesh();
        lastGenerationNanos += generated.generationNanos();
        if (generated.indices().length == 0) {
            emptyVanillaChunks.add(generatedChunk.textureKey());
            return;
        }
        RemixMesh mesh;
        try {
            mesh = createMesh(generated.vertices(), generated.indices());
        } catch (RuntimeException e) {
            vanillaFailure = new IllegalStateException(
                    "Failed to publish vanilla cloud chunk", e);
            throw vanillaFailure;
        }
        vanillaChunks.put(
                generatedChunk.textureKey(),
                new PaletteEntry(mesh, generated.vertexCount(), generated.triangleCount()));
        lastPaletteCreateCount++;
    }

    private RemixMesh createMesh(CloudMeshGenerator.GeneratedMesh generated) {
        return createMesh(generated.vertices(), generated.indices());
    }

    private RemixMesh createMesh(float[] packed, int[] indices) {
        if (meshCreator != null) {
            return meshCreator.create(packed, indices);
        }
        ensureMaterial();
        int vertexCount = packed.length / 6;
        ByteBuffer vertices = DirectBufferPool.acquire(vertexCount * HardcodedVertex.STRIDE);
        for (int i = 0; i < vertexCount; i++) {
            int base = i * 6;
            RemixMeshSurface.putVertex(vertices,
                    packed[base], packed[base + 1], packed[base + 2],
                    packed[base + 3], packed[base + 4], packed[base + 5],
                    0.0f, 0.0f,
                    0xffff_ffff, 0, 0);
        }
        vertices.flip();
        RemixMeshSurface surface = new RemixMeshSurface(material)
                .setVertexData(vertices, vertexCount)
                .setIndexDataFrom(indices, indices.length);
        RemixMesh mesh = new RemixMesh(new RemixMeshSurface[]{surface});
        RemixMeshStore.get().create(mesh);
        return mesh;
    }

    private void ensureMaterial() {
        if (material != null && !material.isDeleted()) {
            return;
        }

        RemixMaterial created = new RemixMaterial(createMaterialInfo(cloudColor));
        RemixMaterialStore.get().create(created);
        material = created;
        materialColor = cloudColor;
    }

    private void updateCloudColor(Vec3 color) {
        int packedColor = packCloudColor(color.x, color.y, color.z);
        cloudColor = packedColor;
        if (material == null || material.isDeleted() || materialColor == packedColor) {
            return;
        }

        try {
            RemixMaterialStore.get().update(material, createMaterialInfo(packedColor));
            materialColor = packedColor;
        } catch (RuntimeException e) {
            RuntimeException failure =
                    new IllegalStateException("Failed to update cloud material color", e);
            if (proceduralClouds) {
                paletteFailure = failure;
            } else {
                vanillaFailure = failure;
            }
            throw failure;
        }
    }

    private static MaterialInfo createMaterialInfo(int packedColor) {
        float red = CLOUD_ALBEDO * ((packedColor >>> 16) & 0xff) / 255.0f;
        float green = CLOUD_ALBEDO * ((packedColor >>> 8) & 0xff) / 255.0f;
        float blue = CLOUD_ALBEDO * (packedColor & 0xff) / 255.0f;
        MaterialInfoOpaqueSubsurfaceEXT subsurface =
                MaterialInfoOpaqueSubsurfaceEXT.builder()
                        .diffusionProfile(true)
                        .transmittanceColor(0.999f, 0.999f, 0.999f)
                        .volumetricAnisotropy(0.0f)
                        .radius(0.98f, 0.98f, 0.98f)
                        .radiusScale(SSS_RADIUS_SCALE)
                        .maxSampleRadius(SSS_MAX_SAMPLE_RADIUS)
                        .build();
        MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(red, green, blue)
                .roughnessConstant(1.0f)
                .metallicConstant(0.0f)
                .subsurfaceExt(subsurface)
                .build();
        return MaterialInfo.builder().opaqueExt(opaque).build();
    }

    static int packCloudColor(double red, double green, double blue) {
        return quantizeCloudColor(red) << 16
                | quantizeCloudColor(green) << 8
                | quantizeCloudColor(blue);
    }

    private static int quantizeCloudColor(double value) {
        if (Double.isNaN(value)) {
            value = 1.0;
        }
        double vanillaColor = Math.max(0.0, Math.min(1.0, value));
        double adjustedColor = 1.0 - VANILLA_COLOR_INFLUENCE * (1.0 - vanillaColor);
        return (int) Math.round(adjustedColor * 255.0);
    }

    private void drawVanillaClouds(Vec3 cameraPos, float cloudHeight, double worldTime) {
        ensureVanillaCloudMask();

        publishVanillaChunks();
        RemixAPI api = renderer.getRemixApi();
        VanillaCloudPlacement.forEachVisible(
                cameraPos.x,
                cameraPos.z,
                worldTime,
                vanillaTextureWidth,
                vanillaTextureHeight,
                placement -> {
                    PaletteEntry entry = vanillaChunks.get(placement.textureKey);
                    if (entry == null) {
                        if (!emptyVanillaChunks.contains(placement.textureKey)) {
                            scheduleVanillaChunk(placement.snapshot());
                        }
                        return;
                    }

                    float[] matrix = vanillaTransform.matrix;
                    matrix[0] = 1.0f;
                    matrix[1] = 0.0f;
                    matrix[2] = 0.0f;
                    matrix[3] = (float) placement.x;
                    matrix[4] = 0.0f;
                    matrix[5] = 1.0f;
                    matrix[6] = 0.0f;
                    matrix[7] = cloudHeight + cloudsHeight + VANILLA_LAYER_LIFT;
                    matrix[8] = 0.0f;
                    matrix[9] = 0.0f;
                    matrix[10] = 1.0f;
                    matrix[11] = (float) placement.z;
                    api.drawInstance(
                            entry.mesh.getHandle(),
                            matrix,
                            INSTANCE_CATEGORIES,
                            0,
                            0L);
                    lastDrawCount++;
                    lastInstanceCount++;
                    lastVertexCount += entry.vertexCount;
                    lastTriangleCount += entry.triangleCount;
                });
        lastCloudCount = lastInstanceCount;
    }

    private void drawClouds(Vec3 cameraPos, float cloudHeight, double worldTime) {
        double elapsedTicks = worldTime - placementWorldTime;
        double windX = elapsedTicks * CloudPlacement.WIND_X_PER_TICK;
        double windZ = elapsedTicks * CloudPlacement.WIND_Z_PER_TICK;
        RemixAPI api = renderer.getRemixApi();
        for (ActiveCloud cloud : clouds.values()) {
            updateTransform(cloud, cameraPos, cloudHeight, windX, windZ);
            PaletteEntry entry = palette[cloud.paletteIndex];
            api.drawInstance(
                    entry.mesh.getHandle(),
                    cloud.transform.matrix,
                    INSTANCE_CATEGORIES,
                    0,
                    0L);
            lastDrawCount++;
            lastInstanceCount++;
            lastVertexCount += entry.vertexCount;
            lastTriangleCount += entry.triangleCount;
        }
    }

    private void updateTransform(
            ActiveCloud cloud,
            Vec3 cameraPos,
            float cloudHeight,
            double windX,
            double windZ) {
        CloudPlacement.Placement placement = cloud.placement;
        double worldX = placement.x() + windX;
        double worldZ = placement.z() + windZ;
        double dx = worldX - cameraPos.x;
        double dz = worldZ - cameraPos.z;
        double horizontalDistanceSquared = dx * dx + dz * dz;
        double clampedDistanceSquared = Math.min(
                horizontalDistanceSquared,
                CURVATURE_RADIUS * CURVATURE_RADIUS);
        double curvatureDrop = CURVATURE_RADIUS
                - Math.sqrt(CURVATURE_RADIUS * CURVATURE_RADIUS - clampedDistanceSquared);
        float worldY = cloudHeight
                + cloudsHeight
                + VANILLA_LAYER_LIFT
                + placement.altitudeOffset()
                - (float) curvatureDrop;
        float scale = placement.scale() * cloudSize;
        float[] matrix = cloud.transform.matrix;
        matrix[0] = scale;
        matrix[1] = 0.0f;
        matrix[2] = 0.0f;
        matrix[3] = (float) worldX;
        matrix[4] = 0.0f;
        matrix[5] = scale;
        matrix[6] = 0.0f;
        matrix[7] = worldY;
        matrix[8] = 0.0f;
        matrix[9] = 0.0f;
        matrix[10] = scale;
        matrix[11] = (float) worldZ;
    }

    public void cleanup() {
        try {
            releaseResources();
        } finally {
            currentWorld = null;
        }
    }

    public void disable() {
        try {
            releaseResources();
        } finally {
            resetFrameStats();
        }
    }

    private void releaseResources() {
        clearScene();
        CloudMeshGenerator.releaseWorkspace();
        paletteGenerationEpoch++;
        paletteGenerationScheduled = false;
        completedPaletteMeshes.clear();
        RuntimeException failure = null;
        try {
            destroyPalette(palette);
        } catch (RuntimeException e) {
            failure = e;
        }
        paletteFailure = null;

        try {
            invalidateVanillaChunks();
        } catch (RuntimeException e) {
            failure = accumulateFailure(failure, e);
        }
        vanillaOccupancy = null;
        vanillaTextureWidth = 0;
        vanillaTextureHeight = 0;
        completedVanillaChunks.clear();

        try {
            destroyMaterial();
        } catch (RuntimeException e) {
            failure = accumulateFailure(failure, e);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void destroyMaterial() {
        try {
            if (material != null && !material.isDeleted()) {
                material.destroy();
            }
        } finally {
            material = null;
            materialColor = -1;
        }
    }

    private static RuntimeException accumulateFailure(
            RuntimeException failure, RuntimeException additionalFailure) {
        if (failure == null) {
            return additionalFailure;
        }
        failure.addSuppressed(additionalFailure);
        return failure;
    }

    private void clearScene() {
        lastMaintenanceBucket = Long.MIN_VALUE;
        lastVisibilityCameraX = Double.NaN;
        lastVisibilityCameraZ = Double.NaN;
        clouds.clear();
        visibleKeys.clear();
        lastCloudCount = 0;
    }

    public boolean isProceduralClouds() {
        return proceduralClouds;
    }

    /** Whether the active cloud mode is healthy enough to replace Minecraft's draw. */
    public boolean shouldSuppressVanillaClouds() {
        return proceduralClouds ? paletteFailure == null : vanillaFailure == null;
    }

    long paletteGenerationEpoch() {
        return paletteGenerationEpoch;
    }

    long vanillaGenerationEpoch() {
        return vanillaGenerationEpoch;
    }

    public void setProceduralClouds(boolean enabled) {
        if (proceduralClouds == enabled) {
            return;
        }
        proceduralClouds = enabled;
        releaseResources();
    }

    public float getVanillaEdgeSmoothness() {
        return vanillaEdgeSmoothness;
    }

    public void setVanillaEdgeSmoothness(float edgeSmoothness) {
        if (!Float.isFinite(edgeSmoothness) || edgeSmoothness < 0.0f) {
            throw new IllegalArgumentException("Vanilla cloud edge smoothness must be nonnegative");
        }
        if (Float.compare(vanillaEdgeSmoothness, edgeSmoothness) == 0) {
            return;
        }
        vanillaEdgeSmoothness = edgeSmoothness;
        invalidateVanillaChunks();
    }

    public float getCloudSize() {
        return cloudSize;
    }

    public void setCloudSize(float size) {
        if (!Float.isFinite(size) || size <= 0.0f) {
            throw new IllegalArgumentException("Cloud size must be positive");
        }
        if (Float.compare(cloudSize, size) == 0) {
            return;
        }
        cloudSize = size;
        invalidateVanillaChunks();
    }

    public float getCloudsHeight() {
        return cloudsHeight;
    }

    public void setCloudsHeight(float height) {
        if (!Float.isFinite(height)) {
            throw new IllegalArgumentException("Clouds height must be finite");
        }
        cloudsHeight = height;
    }

    private void invalidateVanillaChunks() {
        vanillaGenerationEpoch++;
        pendingVanillaChunks.clear();
        try {
            destroyEntries(vanillaChunks.values());
        } finally {
            vanillaChunks.clear();
            emptyVanillaChunks.clear();
            vanillaFailure = null;
        }
    }

    private void resetFrameStats() {
        lastInstanceCount = 0;
        lastPaletteCreateCount = 0;
        lastDrawCount = 0;
        lastVertexCount = 0;
        lastTriangleCount = 0;
        lastGenerationNanos = 0L;
    }

    public int getLastCloudCount() { return lastCloudCount; }
    public int getLastInstanceCount() { return lastInstanceCount; }
    public int getLastPaletteCreateCount() { return lastPaletteCreateCount; }
    public int getLastDrawCount() { return lastDrawCount; }
    public int getLastVertexCount() { return lastVertexCount; }
    public int getLastTriangleCount() { return lastTriangleCount; }
    public long getLastGenerationNanos() { return lastGenerationNanos; }

    private static final class ActiveCloud {
        private CloudPlacement.Placement placement;
        private final int paletteIndex;
        private final Transform transform = new Transform();

        private ActiveCloud(
                CloudPlacement.Placement placement,
                int paletteIndex) {
            this.placement = placement;
            this.paletteIndex = paletteIndex;
        }
    }

    sealed interface PaletteGenerationResult
            permits GeneratedPaletteMesh,
                    CompletedPaletteGeneration,
                    FailedPaletteGeneration {
        long epoch();
    }

    record GeneratedPaletteMesh(
            long epoch,
            int index,
            CloudMeshGenerator.GeneratedMesh mesh) implements PaletteGenerationResult {
    }

    record CompletedPaletteGeneration(long epoch)
            implements PaletteGenerationResult {
    }

    record FailedPaletteGeneration(long epoch, Throwable failure)
            implements PaletteGenerationResult {
    }

    sealed interface VanillaChunkResult
            permits GeneratedVanillaChunk, FailedVanillaChunk {
        long epoch();
        long textureKey();
    }

    record GeneratedVanillaChunk(
            long epoch,
            long textureKey,
            VanillaCloudMeshGenerator.GeneratedMesh mesh) implements VanillaChunkResult {
    }

    record FailedVanillaChunk(
            long epoch,
            long textureKey,
            Throwable failure) implements VanillaChunkResult {
    }

    private record PaletteEntry(
            RemixMesh mesh,
            int vertexCount,
            int triangleCount) {
    }

    @FunctionalInterface
    interface MeshCreator {
        RemixMesh create(float[] vertices, int[] indices);
    }
}
