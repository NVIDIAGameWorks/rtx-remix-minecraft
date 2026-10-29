/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.BuiltChunkNeighbourhoodAccess;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkStorageSectionAccess;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.SkippedSectionRebuilds;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkStorageTorus;
import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BuiltChunkStorage.class)
public abstract class BuiltChunkStorageMixin
        implements ChunkStorageTorus.StorageAccess, BuiltChunkStorageSectionAccess {
    @Shadow
    protected int sizeX;

    @Shadow
    protected int sizeY;

    @Shadow
    protected int sizeZ;

    @Shadow
    @Final
    protected World world;

    @Shadow
    public ChunkBuilder.BuiltChunk[] chunks;

    @Shadow
    private ChunkSectionPos sectionPos;

    @Shadow
    @Final
    protected WorldRenderer worldRenderer;

    private ChunkStorageTorus.Workspace rtxremix$torusWorkspace;

    @Inject(method = "updateCameraPosition(Lnet/minecraft/util/math/ChunkSectionPos;)V", at = @At("HEAD"), cancellable = true)
    private void rtxremix$updateCameraPosition(ChunkSectionPos cameraSection, CallbackInfo ci) {
        if (!RtxRemixMod.isRtxEnabled()) {
            return;
        }
        if (rtxremix$torusWorkspace == null) {
            rtxremix$torusWorkspace = new ChunkStorageTorus.Workspace();
        }

        try (ProfileScope scope = ProfileScope.begin("ChunkStorage.updateCameraPosition")) {
            int movedSlots = ChunkStorageTorus.reposition(
                    this,
                    rtxremix$torusWorkspace,
                    sizeX,
                    sizeY,
                    sizeZ,
                    world.getBottomY(),
                    cameraSection.getSectionX(),
                    cameraSection.getSectionZ());
            scope.captureCount(movedSlots).scannedChunkCount(sizeX + sizeZ);
        }
        sectionPos = cameraSection;
        worldRenderer.getChunkRenderingDataPreparer().scheduleTerrainUpdate();
        ci.cancel();
    }

    @Override
    public int rtxremix$getOriginX(int slotIndex) {
        return chunks[slotIndex].getOrigin().getX();
    }

    @Override
    public int rtxremix$getOriginZ(int slotIndex) {
        return chunks[slotIndex].getOrigin().getZ();
    }

    @Override
    public void rtxremix$setOrigin(int slotIndex, int x, int y, int z) {
        chunks[slotIndex].setSectionPos(ChunkSectionPos.asLong(x >> 4, y >> 4, z >> 4));
    }

    /** The slot vanilla aliases the section onto, or null unless that slot currently holds the section. */
    private ChunkBuilder.BuiltChunk rtxremix$residentSlot(int cx, int sy, int cz) {
        int slotX = Math.floorMod(cx, sizeX);
        int slotY = Math.floorMod(sy - world.getBottomSectionCoord(), sizeY);
        int slotZ = Math.floorMod(cz, sizeZ);
        ChunkBuilder.BuiltChunk slot = chunks[ChunkStorageTorus.index(slotX, slotY, slotZ, sizeX, sizeY)];
        BlockPos origin = slot.getOrigin();
        boolean resident = ChunkSectionPos.getSectionCoord(origin.getX()) == cx
                && ChunkSectionPos.getSectionCoord(origin.getY()) == sy
                && ChunkSectionPos.getSectionCoord(origin.getZ()) == cz;
        return resident ? slot : null;
    }

    @Override
    public void rtxremix$scheduleRebuildIfResident(int cx, int sy, int cz) {
        ChunkBuilder.BuiltChunk slot = rtxremix$residentSlot(cx, sy, cz);
        if (slot != null) {
            slot.scheduleRebuild(false);
        }
    }

    @Override
    public void rtxremix$flagLoadedSection(int cx, int sy, int cz) {
        if (!NeighbourReflagFilter.sectionInRange(sy, world.getBottomSectionCoord(), sizeY)) {
            return;
        }
        rtxremix$scheduleRebuildIfResident(cx, sy, cz);
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            ChunkBuilder.BuiltChunk slot = rtxremix$residentSlot(
                    cx + NeighbourReflagFilter.DX[k], sy, cz + NeighbourReflagFilter.DZ[k]);
            boolean resident = slot != null;
            boolean awaitingBuild = resident && slot.needsRebuild();
            int snapshotMask = resident ? ((BuiltChunkNeighbourhoodAccess) slot).rtxremix$snapshotMask() : 0;
            int loaderBit = NeighbourReflagFilter.bit(NeighbourReflagFilter.mirroredIndex(k));
            switch (NeighbourReflagFilter.decideNeighbour(resident, awaitingBuild, snapshotMask, loaderBit)) {
                case FLAG -> {
                    slot.scheduleRebuild(false);
                    NeighbourReflagFilter.recordKept();
                }
                case DROP_IN_SNAPSHOT -> SkippedSectionRebuilds.recordNeighbourInSnapshot();
                case SKIP_NOT_RESIDENT, SKIP_AWAITING_BUILD -> {
                }
            }
        }
    }

    @Override
    public void rtxremix$clearNeighbourSnapshotBits(int cx, int cz) {
        int bottomSection = world.getBottomSectionCoord();
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            int bit = NeighbourReflagFilter.bit(NeighbourReflagFilter.mirroredIndex(k));
            int neighbourX = cx + NeighbourReflagFilter.DX[k];
            int neighbourZ = cz + NeighbourReflagFilter.DZ[k];
            for (int row = 0; row < sizeY; row++) {
                ChunkBuilder.BuiltChunk slot = rtxremix$residentSlot(neighbourX, bottomSection + row, neighbourZ);
                if (slot != null) {
                    ((BuiltChunkNeighbourhoodAccess) slot).rtxremix$clearSnapshotBit(bit);
                }
            }
        }
    }
}
