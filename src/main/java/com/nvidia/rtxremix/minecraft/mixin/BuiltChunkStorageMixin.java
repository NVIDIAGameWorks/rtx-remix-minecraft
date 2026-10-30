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
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ViewArea.class)
public abstract class BuiltChunkStorageMixin
        implements ChunkStorageTorus.StorageAccess, BuiltChunkStorageSectionAccess {
    @Shadow
    protected int sectionGridSizeX;

    @Shadow
    protected int sectionGridSizeY;

    @Shadow
    protected int sectionGridSizeZ;

    @Shadow
    @Final
    protected Level level;

    @Shadow
    public SectionRenderDispatcher.RenderSection[] sections;

    private ChunkStorageTorus.Workspace rtxremix$torusWorkspace;

    @Inject(method = "repositionCamera(DD)V", at = @At("HEAD"), cancellable = true)
    private void rtxremix$updateCameraPosition(double cameraX, double cameraZ, CallbackInfo ci) {
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
                    sectionGridSizeX,
                    sectionGridSizeY,
                    sectionGridSizeZ,
                    level.getMinBuildHeight(),
                    cameraX,
                    cameraZ);
            scope.captureCount(movedSlots).scannedChunkCount(sectionGridSizeX + sectionGridSizeZ);
        }
        ci.cancel();
    }

    @Override
    public int rtxremix$getOriginX(int slotIndex) {
        return sections[slotIndex].getOrigin().getX();
    }

    @Override
    public int rtxremix$getOriginZ(int slotIndex) {
        return sections[slotIndex].getOrigin().getZ();
    }

    @Override
    public void rtxremix$setOrigin(int slotIndex, int x, int y, int z) {
        sections[slotIndex].setOrigin(x, y, z);
    }

    /** The slot vanilla aliases the section onto, or null unless that slot currently holds the section. */
    private SectionRenderDispatcher.RenderSection rtxremix$residentSlot(int cx, int sy, int cz) {
        int slotX = Math.floorMod(cx, sectionGridSizeX);
        int slotY = Math.floorMod(sy - level.getMinSection(), sectionGridSizeY);
        int slotZ = Math.floorMod(cz, sectionGridSizeZ);
        SectionRenderDispatcher.RenderSection slot = sections[ChunkStorageTorus.index(slotX, slotY, slotZ, sectionGridSizeX, sectionGridSizeY)];
        BlockPos origin = slot.getOrigin();
        boolean resident = SectionPos.blockToSectionCoord(origin.getX()) == cx
                && SectionPos.blockToSectionCoord(origin.getY()) == sy
                && SectionPos.blockToSectionCoord(origin.getZ()) == cz;
        return resident ? slot : null;
    }

    @Override
    public void rtxremix$scheduleRebuildIfResident(int cx, int sy, int cz) {
        SectionRenderDispatcher.RenderSection slot = rtxremix$residentSlot(cx, sy, cz);
        if (slot != null) {
            slot.setDirty(false);
        }
    }

    @Override
    public void rtxremix$flagLoadedSection(int cx, int sy, int cz) {
        if (!NeighbourReflagFilter.sectionInRange(sy, level.getMinSection(), sectionGridSizeY)) {
            return;
        }
        rtxremix$scheduleRebuildIfResident(cx, sy, cz);
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            SectionRenderDispatcher.RenderSection slot = rtxremix$residentSlot(
                    cx + NeighbourReflagFilter.DX[k], sy, cz + NeighbourReflagFilter.DZ[k]);
            boolean resident = slot != null;
            boolean awaitingBuild = resident && slot.isDirty();
            int snapshotMask = resident ? ((BuiltChunkNeighbourhoodAccess) slot).rtxremix$snapshotMask() : 0;
            int loaderBit = NeighbourReflagFilter.bit(NeighbourReflagFilter.mirroredIndex(k));
            switch (NeighbourReflagFilter.decideNeighbour(resident, awaitingBuild, snapshotMask, loaderBit)) {
                case FLAG -> {
                    slot.setDirty(false);
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
        int bottomSection = level.getMinSection();
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            int bit = NeighbourReflagFilter.bit(NeighbourReflagFilter.mirroredIndex(k));
            int neighbourX = cx + NeighbourReflagFilter.DX[k];
            int neighbourZ = cz + NeighbourReflagFilter.DZ[k];
            for (int row = 0; row < sectionGridSizeY; row++) {
                SectionRenderDispatcher.RenderSection slot = rtxremix$residentSlot(neighbourX, bottomSection + row, neighbourZ);
                if (slot != null) {
                    ((BuiltChunkNeighbourhoodAccess) slot).rtxremix$clearSnapshotBit(bit);
                }
            }
        }
    }
}
