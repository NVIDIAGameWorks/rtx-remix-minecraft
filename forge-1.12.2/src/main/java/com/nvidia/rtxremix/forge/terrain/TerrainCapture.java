/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.LightInfoDistantEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixBridge;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import com.nvidia.rtxremix.TextureFormat;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/**
 * Render-thread capture of loaded chunk sections inside Minecraft's render distance.
 * Discovery and block-model evaluation are incremental; native pages are uploaded one
 * per frame, and an old section stays visible until its complete replacement is ready.
 * This owns no world or chunk loads. Baked models and vanilla liquids share publication;
 * custom non-baked renderers require their own capture integration. Illumination
 * uses fixed daylight until the world sky and time-of-day integration is available.
 *
 * <p>The 2 ms budget is cooperative: a model callback or native call cannot be preempted.
 * Each page is at most 1.1 MiB, each section at most 144 pages, and retirement is capped
 * at four pages per frame. Atomic section publication may add/remove up to 144 instances.
 * Mesh bytes include staging and retirement. Capacity pressure evicts farther sections
 * and defers work that cannot improve residency, while block edits retain priority.
 */
public final class TerrainCapture implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger("ForgeRemixTerrain");
    private static final int SECTION_COUNT = 16;
    private static final long MAX_ATLAS_BYTES = 256L * 1024 * 1024;
    private static final AtomicLong HASHES = new AtomicLong(0x524c544552520000L);
    private static final BlockRenderLayer[] LAYERS = BlockRenderLayer.values();
    private static final int WATER = LAYERS.length;
    private static final int LAVA = WATER + 1;
    private static final int CLEAR_GLASS = LAVA + 1;
    private static final int STAINED_GLASS = CLEAR_GLASS + 1;
    private static final int OVERLAY = STAINED_GLASS + 1;
    private static final int MATERIAL_COUNT = OVERLAY + 1;

    private final RemixAPI api;
    private final Minecraft mc;
    private final WorldClient world;
    private final FluidCapture fluids;
    private final BakedBlockCapture baked;
    private final IWorldEventListener worldUpdates = new WorldUpdates();
    private final TerrainSchedule schedule = new TerrainSchedule();
    private final TerrainBudget budget = new TerrainBudget();
    private final TerrainAdmission admission = new TerrainAdmission(schedule, budget);
    private final TerrainClose closing = new TerrainClose();
    private final PageQueue retired = new PageQueue();
    private final TerrainSchedule.RemovedColumn removeColumn = new TerrainSchedule.RemovedColumn() {
        @Override public void remove(TerrainSchedule.Column column) { retireColumn(column); }
    };
    private final MeshPacking[] builders = new MeshPacking[MATERIAL_COUNT];
    private final MeshPacking overlayBuilder = new MeshPacking();
    private final BlockOverlaySink blockSink = new BlockOverlaySink(overlayBuilder);
    private final int[] checkpoints = new int[MATERIAL_COUNT];
    private final BlockPos.MutableBlockPos blockPosition = new BlockPos.MutableBlockPos();
    private final long[] materials = new long[MATERIAL_COUNT];
    private long atlas;
    private long daylight;
    private long vertexColorExtension;
    private long overlayExtension;
    private boolean closed;
    private boolean warnedUnsupportedModel;
    private boolean warnedUnsupportedFluid;
    private boolean warnedCapacity;
    private int pageResources;
    private Build active;

    public TerrainCapture(RemixAPI api, Minecraft mc) {
        if (api == null || mc == null || mc.world == null) {
            throw new IllegalArgumentException("TerrainCapture requires Remix and a joined client world");
        }
        this.api = api;
        this.mc = mc;
        this.world = mc.world;
        this.fluids = new FluidCapture(mc);
        this.baked = new BakedBlockCapture(mc);
        builders[OVERLAY] = overlayBuilder;
        world.addEventListener(worldUpdates);
    }

    /** Advances bounded discovery, capture, upload and retirement before Remix submit. */
    public void update() {
        checkThread();
        if (closed) return;
        if (mc.world != world) { close(); return; }
        Entity view = mc.getRenderViewEntity();
        if (view == null) return;
        if (atlas == 0) uploadAtlas();
        if (daylight == 0) {
            daylight = api.createLight(LightInfo.builder().hash(HASHES.incrementAndGet())
                    .radiance(8, 8, 8).distantExt(LightInfoDistantEXT.builder()
                            .direction(0.36f, -0.8f, 0.48f).angularDiameterDegrees(0.5f).build()).build());
        }
        api.drawLightInstance(daylight);
        baked.beginFrame();
        long deadline = System.nanoTime() + TerrainBudget.FRAME_NANOS;
        schedule.move(MathHelper.floor(view.posX) >> 4,
                MathHelper.clamp(MathHelper.floor(view.posY) >> 4, 0, SECTION_COUNT - 1),
                MathHelper.floor(view.posZ) >> 4, mc.gameSettings.renderDistanceChunks, removeColumn);
        for (int i = 0; i < TerrainBudget.RETIREMENTS_PER_FRAME && !retired.isEmpty(); i++) {
            release(retired.peekFirst());
            retired.removeFirst();
            if (System.nanoTime() >= deadline) break;
        }
        int probes = Math.min(TerrainBudget.COLUMNS_PER_FRAME, schedule.windowSize());
        for (int i = 0; i < probes; i++) {
            TerrainSchedule.Column column = schedule.nextDiscovery();
            reconcile(column);
            schedule.retryDeferred(column, budget.available());
        }
        if (active != null && !valid(active)) discardBuild();
        if (active != null && !active.replacement && schedule.hasRefresh()) discardBuild();
        if (System.nanoTime() >= deadline) return;
        int blocks = 0;
        int emptySections = 0;
        while (blocks < TerrainBudget.BLOCKS_PER_FRAME && System.nanoTime() < deadline) {
            if (active == null) {
                TerrainSchedule.Section section = schedule.next();
                if (section == null) return;
                reconcile(section.column);
                if (section.column.source == null) { schedule.discard(section); continue; }
                // A measured, deferred section cannot become admissible merely
                // by recapturing the same revision. Avoid repeating that work.
                if (!section.refresh && section.requiredBytes >= 0) {
                    TerrainAdmission.Result result = admission.decide(section, section.requiredBytes);
                    if (result == TerrainAdmission.Result.DEFER || result == TerrainAdmission.Result.WAIT) {
                        schedule.defer(section, section.requiredBytes);
                        if (++emptySections >= 64) return;
                        continue;
                    }
                }
                active = new Build(section, (Chunk) section.column.source);
                ExtendedBlockStorage storage = active.chunk.getBlockStorageArray()[section.y];
                if (storage == null || storage.isEmpty()) {
                    publishBuild();
                    if (++emptySections >= 64) return;
                    continue;
                }
            }
            if (active.block < 4096) {
                int visited = captureBlocks(Math.min(32, TerrainBudget.BLOCKS_PER_FRAME - blocks));
                blocks += visited;
                continue;
            }
            if (!active.admitted && !admitBuild()) {
                if (active != null) return;
                continue;
            }
            if (uploadPage()) return; // At most one mesh allocation per frame.
            publishBuild();
        }
    }

    private void reconcile(TerrainSchedule.Column column) {
        Chunk loaded = world.getChunkProvider().getLoadedChunk(column.x, column.z);
        if (loaded == column.source) return;
        column.source = loaded;
        if (loaded != null) {
            schedule.invalidate(column);
        } else {
            retireColumnAssets(column);
            for (TerrainSchedule.Section section : column.sections) {
                schedule.forget(section);
                section.revision++;
                section.publication = 0;
            }
        }
        // Face culling and extended states can inspect diagonal neighbouring columns.
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 || dz != 0) schedule.invalidate(schedule.column(column.x + dx, column.z + dz));
            }
        }
    }

    private void markRange(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        checkThread();
        if (closed || schedule.radius() < 0) return;
        int radius = schedule.radius();
        int padding = 1;
        int fromX = Math.max(schedule.centerX() - radius, (minX - padding) >> 4);
        int toX = Math.min(schedule.centerX() + radius, (maxX + padding) >> 4);
        int fromY = Math.max(0, (minY - padding) >> 4);
        int toY = Math.min(SECTION_COUNT - 1, (maxY + padding) >> 4);
        int fromZ = Math.max(schedule.centerZ() - radius, (minZ - padding) >> 4);
        int toZ = Math.min(schedule.centerZ() + radius, (maxZ + padding) >> 4);
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                TerrainSchedule.Column column = schedule.column(x, z);
                reconcile(column);
                for (int y = fromY; y <= toY; y++) schedule.invalidate(column.sections[y]);
            }
        }
    }

    private boolean valid(Build build) {
        return schedule.valid(build.section, build.chunk, build.revision)
                && world.getChunkProvider().getLoadedChunk(build.section.column.x, build.section.column.z) == build.chunk;
    }

    private int captureBlocks(int limit) {
        Build build = active;
        TerrainSchedule.Section section = build.section;
        ExtendedBlockStorage storage = build.chunk.getBlockStorageArray()[section.y];
        BlockRenderLayer previousLayer = MinecraftForgeClient.getRenderLayer();
        int count = 0;
        try {
            while (count < limit && build.block < 4096) {
                int index = build.block++;
                count++;
                int x = index & 15, z = (index >> 4) & 15, y = index >> 8;
                IBlockState state = storage.get(x, y, z);
                blockPosition.setPos(section.column.x * 16 + x, section.y * 16 + y, section.column.z * 16 + z);
                EnumBlockRenderType type = state.getRenderType();
                if (type != EnumBlockRenderType.MODEL && type != EnumBlockRenderType.LIQUID) {
                    continue;
                }
                if (type == EnumBlockRenderType.LIQUID && !FluidCapture.supported(state)) {
                    if (!warnedUnsupportedFluid) {
                        warnedUnsupportedFluid = true;
                        LOGGER.warn("Unsupported LIQUID renderer for {}; only BlockLiquid water/lava is captured; further warnings are suppressed",
                                state.getBlock().getRegistryName());
                    }
                    continue;
                }
                for (int i = 0; i < builders.length; i++) checkpoints[i] = builders[i] == null ? 0 : builders[i].vertexCount();
                try {
                    if (type == EnumBlockRenderType.LIQUID) {
                        int layer = state.getMaterial() == Material.LAVA ? LAVA : WATER;
                        if (builders[layer] == null) builders[layer] = new MeshPacking();
                        fluids.capture(state, world, blockPosition, section.column.x, section.y, section.column.z, builders[layer]);
                    } else {
                        emitBlock(builders, state, blockPosition, x, y, z);
                    }
                } catch (MeshPacking.CapacityExceededException error) {
                    LOGGER.error("Terrain section {},{},{} exceeds the bounded model buffer; keeping its previous geometry",
                            section.column.x, section.y, section.column.z, error);
                    discardBuild();
                    // Capacity failures are retried only when the section changes again.
                    schedule.fail(section);
                    return count;
                } catch (RuntimeException error) {
                    for (int i = 0; i < builders.length; i++) if (builders[i] != null) builders[i].truncate(checkpoints[i]);
                    if (!warnedUnsupportedModel) {
                        warnedUnsupportedModel = true;
                        LOGGER.warn("A terrain block could not be captured; further model warnings are suppressed", error);
                    }
                }
            }
        } finally { ForgeHooksClient.setRenderLayer(previousLayer); }
        return count;
    }

    private boolean admitBuild() {
        Build build = active;
        if (build.meshBytes < 0) {
            long bytes = 0;
            for (int i = 0; i < builders.length; i++) {
                if (builders[i] != null) bytes = Math.addExact(bytes, TerrainBudget.bytes(builders[i].vertexCount()));
            }
            build.meshBytes = bytes;
            build.section.requiredBytes = bytes;
        }
        TerrainAdmission.Result result = admission.decide(build.section, build.meshBytes);
        if (result == TerrainAdmission.Result.ADMIT) { build.admitted = true; return true; }
        if (!warnedCapacity) {
            warnedCapacity = true;
            LOGGER.warn("Terrain mesh budget {} MiB reached; prioritizing edits and nearby sections, deferring farther terrain",
                    budget.limit() / (1024 * 1024));
        }
        if (result == TerrainAdmission.Result.EVICT) {
            TerrainSchedule.Section victim = admission.victim();
            evict(victim, victim != build.section);
        } else if (result == TerrainAdmission.Result.DEFER) {
            schedule.defer(build.section, build.meshBytes);
            active = null;
            resetBuilders();
        }
        return false;
    }

    private boolean uploadPage() {
        Build build = active;
        while (build.layer < builders.length) {
            MeshPacking packed = builders[build.layer];
            if (packed == null || build.vertex == packed.vertexCount()) {
                build.layer++;
                build.vertex = 0;
                continue;
            }
            int count = Math.min(TerrainBudget.PAGE_VERTICES, packed.vertexCount() - build.vertex);
            long bytes = TerrainBudget.bytes(count);
            if (!budget.canReserve(bytes)) {
                throw new IllegalStateException("An admitted terrain section exceeded its complete mesh reservation");
            }
            MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
            surface.vertexBuffer = packed.vertices(build.vertex, count);
            surface.vertexCount = count;
            surface.indexBuffer = packed.indices(count);
            surface.indexCount = count / 4 * 6;
            surface.material = materials[build.layer];
            surface.flags = MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS;
            MeshInfo mesh = new MeshInfo(HASHES.incrementAndGet());
            mesh.surfaces = new MeshInfoSurfaceTriangles[] {surface};
            long handle = api.createMesh(mesh);
            budget.reserve(bytes);
            build.uploadedBytes += bytes;
            pageResources++;
            build.pages.add(new Page(handle, bytes, build.layer == OVERLAY));
            build.vertex += count;
            return true;
        }
        return false;
    }

    private void publishBuild() {
        Build build = active;
        if (!valid(build)) { discardBuild(); return; }
        TerrainSchedule.Section section = build.section;
        float[] transform = {1, 0, 0, section.column.x * 16f, 0, 1, 0, section.y * 16f, 0, 0, 1, section.column.z * 16f};
        // Mesh staging is budgeted; instances become visible together before the next submit.
        for (Page page : build.pages) {
            page.instance = api.sceneInstanceAdd(page.mesh, transform,
                    page.overlay ? InstanceCategoryBit.DECAL_STATIC : 0, 1,
                    page.overlay ? overlayExtension : vertexColorExtension);
            pageResources++;
        }
        SectionAsset old = (SectionAsset) section.asset;
        if (old != null) {
            for (Page page : old.pages) detach(page);
            retire(old, true);
        }
        section.asset = build.pages.isEmpty() ? null : new SectionAsset(build.pages, build.uploadedBytes);
        schedule.publish(section, build.revision);
        active = null;
        resetBuilders();
    }

    private void discardBuild() {
        if (active == null) return;
        retirePages(active.pages, active.uploadedBytes, true);
        schedule.discard(active.section);
        active = null;
        resetBuilders();
    }
    private void resetBuilders() { for (MeshPacking packed : builders) if (packed != null) packed.reset(); }

    private void emitBlock(MeshPacking[] layers, IBlockState raw, BlockPos pos, int x, int y, int z) {
        IBlockState actual = raw.getActualState(world, pos);
        GlassMaterials.Kind glass = GlassMaterials.kind(actual.getBlock().getRegistryName());
        int originX = pos.getX() - x, originY = pos.getY() - y, originZ = pos.getZ() - z;
        for (BlockRenderLayer layer : LAYERS) {
            if (!actual.getBlock().canRenderInLayer(actual, layer)) continue;
            ForgeHooksClient.setRenderLayer(layer);
            int layerIndex = glass == GlassMaterials.Kind.CLEAR ? CLEAR_GLASS
                    : glass == GlassMaterials.Kind.STAINED ? STAINED_GLASS : layer.ordinal();
            if (layers[layerIndex] == null) layers[layerIndex] = new MeshPacking();
            baked.classifyOverlays(glass == null
                    && (layer == BlockRenderLayer.CUTOUT || layer == BlockRenderLayer.CUTOUT_MIPPED));
            blockSink.target(layers[layerIndex]);
            baked.emit(raw, world, pos, originX, originY, originZ, blockSink);
        }
    }
    private void uploadAtlas() {
        int oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int oldPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] packNames = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS};
        int[] packValues = new int[packNames.length];
        for (int i = 0; i < packNames.length; i++) packValues[i] = GL11.glGetInteger(packNames[i]);
        try {
            mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            long bytes = (long) width * height * 4;
            if (width <= 0 || height <= 0 || bytes > MAX_ATLAS_BYTES) {
                throw new IllegalStateException("Unsupported block atlas dimensions: " + width + "x" + height);
            }
            ByteBuffer pixels = MeshPacking.direct((int) bytes);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            for (int name : packNames) GL11.glPixelStorei(name, name == GL11.GL_PACK_ALIGNMENT ? 1 : 0);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            atlas = api.createTextureFromMemory(TextureFromMemoryInfo.builder()
                    .hash(HASHES.incrementAndGet()).pixelData(pixels).size(width, height)
                    .format(TextureFormat.RGBA8_SRGB).build());
            for (BlockRenderLayer layer : LAYERS) {
                boolean cutout = layer == BlockRenderLayer.CUTOUT || layer == BlockRenderLayer.CUTOUT_MIPPED;
                MaterialInfo material = new MaterialInfo(HASHES.incrementAndGet());
                material.albedoTexture = atlas;
                material.filterMode = 0;
                material.wrapModeU = MaterialInfo.WRAP_MODE_CLAMP;
                material.wrapModeV = MaterialInfo.WRAP_MODE_CLAMP;
                material.opaqueExt = MaterialInfoOpaqueEXT.builder()
                        .albedoConstant(1, 1, 1).roughnessConstant(1).metallicConstant(0)
                        .useDrawCallAlphaState(false)
                        .alphaTestType(cutout ? 4 : 7).alphaReferenceValue(cutout ? 128 : 0)
                        .blendEnabled(layer == BlockRenderLayer.TRANSLUCENT).build();
                materials[layer.ordinal()] = api.createMaterial(material);
            }
            materials[WATER] = api.createMaterial(FluidMaterials.water(HASHES.incrementAndGet(), atlas));
            materials[LAVA] = api.createMaterial(FluidMaterials.lava(HASHES.incrementAndGet(), atlas));
            materials[CLEAR_GLASS] = api.createMaterial(GlassMaterials.clear(HASHES.incrementAndGet(), atlas));
            materials[STAINED_GLASS] = api.createMaterial(GlassMaterials.stained(HASHES.incrementAndGet(), atlas));
            MaterialInfo overlay = BlockOverlayMaterial.create(HASHES.incrementAndGet(), atlas);
            materials[OVERLAY] = api.createMaterial(overlay);
            fluids.refreshSprites();
            vertexColorExtension = InstanceInfoBlendEXT.textureTimesVertexColor().allocNative(0);
            overlayExtension = InstanceInfoBlendEXT.textureTimesVertexColorAlphaBlend(1).allocNative(0);
            LOGGER.info("Captured block atlas {}x{}; terrain follows Minecraft render distance", width, height);
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldPackBuffer);
            for (int i = 0; i < packNames.length; i++) GL11.glPixelStorei(packNames[i], packValues[i]);
            GlStateManager.bindTexture(oldTexture);
            GlStateManager.setActiveTexture(oldActive);
        }
    }

    private void retireColumn(TerrainSchedule.Column column) {
        column.source = null;
        retireColumnAssets(column);
    }
    private void retireColumnAssets(TerrainSchedule.Column column) {
        for (TerrainSchedule.Section section : column.sections) {
            schedule.forget(section);
            if (section.asset == null) continue;
            retire((SectionAsset) section.asset, true);
            section.asset = null;
        }
    }
    private void evict(TerrainSchedule.Section section, boolean defer) {
        SectionAsset asset = (SectionAsset) section.asset;
        schedule.evict(section, asset.bytes, defer);
        retire(asset, false);
    }
    private void retire(SectionAsset asset, boolean reconsider) {
        retirePages(asset.pages, asset.bytes, reconsider);
    }
    private void retirePages(List<Page> pages, long bytes, boolean reconsider) {
        budget.retire(bytes);
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            page.reconsiderOnRelease = reconsider;
            retired.addLast(page);
        }
    }
    private void detach(Page page) {
        if (page.instance == 0) return;
        api.sceneInstanceRemove(page.instance);
        page.instance = 0;
        pageResources--;
    }
    private void release(Page page) {
        Throwable failure = null;
        try { detach(page); }
        catch (RuntimeException | LinkageError error) { failure = error; }
        if (page.mesh != 0 && page.instance == 0) {
            try {
                api.destroyMesh(page.mesh);
                page.mesh = 0;
                pageResources--;
                budget.releaseRetired(page.bytes);
                if (page.reconsiderOnRelease) schedule.capacityChanged();
            } catch (RuntimeException | LinkageError error) { failure = appendFailure(failure, error); }
        }
        if (failure != null) throw new IllegalStateException("Cannot release terrain page", failure);
    }
    private void clearTerrain() {
        discardBuild();
        schedule.clear(removeColumn);
        Throwable failure = null;
        int count = retired.size();
        for (int i = 0; i < count; i++) {
            Page page = retired.removeFirst();
            try { release(page); }
            catch (RuntimeException | LinkageError error) { failure = appendFailure(failure, error); }
            if (page.mesh != 0 || page.instance != 0) retired.addLast(page);
        }
        warnedCapacity = false;
        if (failure != null) throw new IllegalStateException("Cannot clear terrain", failure);
    }

    private void clearTextureResources() {
        if (pageResources != 0) throw new IllegalStateException("Terrain pages still reference atlas materials");
        Throwable failure = null;
        if (daylight != 0) {
            try { api.destroyLight(daylight); daylight = 0; }
            catch (RuntimeException | LinkageError error) { failure = error; }
        }
        if (vertexColorExtension != 0) {
            try {
                RemixBridge.freeExt(vertexColorExtension);
                vertexColorExtension = 0;
            } catch (RuntimeException | LinkageError error) { failure = appendFailure(failure, error); }
        }
        if (overlayExtension != 0) {
            try {
                RemixBridge.freeExt(overlayExtension);
                overlayExtension = 0;
            } catch (RuntimeException | LinkageError error) { failure = appendFailure(failure, error); }
        }
        for (int i = 0; i < materials.length; i++) {
            if (materials[i] == 0) continue;
            try {
                api.destroyMaterial(materials[i]);
                materials[i] = 0;
            } catch (RuntimeException | LinkageError error) { failure = appendFailure(failure, error); }
        }
        boolean materialsReleased = true;
        for (long material : materials) if (material != 0) materialsReleased = false;
        if (atlas != 0 && materialsReleased) {
            try {
                api.destroyTexture(atlas);
                atlas = 0;
            } catch (RuntimeException | LinkageError error) { failure = appendFailure(failure, error); }
        }
        if (failure != null) throw new IllegalStateException("Cannot release terrain textures", failure);
    }

    @Override public void close() {
        checkThread();
        // Stop new capture immediately, but keep retrying any native owners whose
        // release failed. Only the coordinator marks the teardown complete.
        closed = true;
        closing.close(() -> world.removeEventListener(worldUpdates), this::clearTerrain,
                this::clearTextureResources);
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private void checkThread() {
        if (!mc.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Terrain capture must run on the Minecraft render thread");
        }
    }

    private final class WorldUpdates implements IWorldEventListener {
        @Override public void notifyBlockUpdate(World source, BlockPos pos, IBlockState oldState,
                                                IBlockState newState, int flags) {
            if (source == world) markRange(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
        }
        @Override public void markBlockRangeForRenderUpdate(int minX, int minY, int minZ,
                                                            int maxX, int maxY, int maxZ) {
            markRange(minX, minY, minZ, maxX, maxY, maxZ);
        }
        @Override public void notifyLightSet(BlockPos pos) { }
        @Override public void playSoundToAllNearExcept(EntityPlayer player, SoundEvent sound, SoundCategory category,
                                                       double x, double y, double z, float volume, float pitch) { }
        @Override public void playRecord(SoundEvent sound, BlockPos pos) { }
        @Override public void spawnParticle(int id, boolean ignoreRange, double x, double y, double z,
                                            double vx, double vy, double vz, int... args) { }
        @Override public void spawnParticle(int id, boolean ignoreRange, boolean minimumParticles,
                                            double x, double y, double z, double vx, double vy, double vz,
                                            int... args) { }
        @Override public void onEntityAdded(Entity entity) { }
        @Override public void onEntityRemoved(Entity entity) { }
        @Override public void broadcastSound(int id, BlockPos pos, int data) { }
        @Override public void playEvent(EntityPlayer player, int type, BlockPos pos, int data) { }
        @Override public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) { }
    }

    private static final class Build {
        final TerrainSchedule.Section section;
        final Chunk chunk;
        final long revision;
        final boolean replacement;
        final List<Page> pages = new ArrayList<Page>();
        int block;
        int layer;
        int vertex;
        boolean admitted;
        long meshBytes = -1;
        long uploadedBytes;
        Build(TerrainSchedule.Section section, Chunk chunk) {
            this.section = section;
            this.chunk = chunk;
            this.revision = section.revision;
            this.replacement = section.refresh;
        }
    }
    private static final class Page {
        long mesh;
        final long bytes;
        final boolean overlay;
        long instance;
        Page nextRetired;
        boolean reconsiderOnRelease;
        Page(long mesh, long bytes, boolean overlay) { this.mesh = mesh; this.bytes = bytes; this.overlay = overlay; }
    }
    /** Intrusive retirement links keep pressure eviction allocation-free. */
    private static final class PageQueue {
        private Page first, last;
        private int size;
        boolean isEmpty() { return first == null; }
        int size() { return size; }
        Page peekFirst() { return first; }
        void addLast(Page page) {
            if (last == null) first = page;
            else last.nextRetired = page;
            last = page;
            size++;
        }
        Page removeFirst() {
            Page page = first;
            first = page.nextRetired;
            page.nextRetired = null;
            if (first == null) last = null;
            size--;
            return page;
        }
    }
    private static final class SectionAsset {
        final List<Page> pages;
        final long bytes;
        SectionAsset(List<Page> pages, long bytes) { this.pages = pages; this.bytes = bytes; }
    }
}
