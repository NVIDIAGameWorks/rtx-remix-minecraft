/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.capture.RenderRegionStoragePool;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A section snapshot for a render region whose block-state copy draws its storage from
 * {@link RenderRegionStoragePool} and gives it back when the last region holding the snapshot
 * has been consumed. Snapshots are shared between the regions one frame's extraction
 * builds, hence the count. The pool flag is set around the constructor by
 * {@link ClientChunkMixin}; vanilla's own snapshot code is untouched.
 */
@Mixin(SectionCopy.class)
public abstract class SectionCopyMixin implements RegionSnapshotRelease {

    @Shadow
    @Final
    @Mutable
    private PalettedContainer<BlockState> section;

    private final AtomicInteger rtxremix$regions = new AtomicInteger();

    @Override
    public void rtxremix$retain() {
        rtxremix$regions.incrementAndGet();
    }

    @Override
    public void rtxremix$release() {
        if (rtxremix$regions.decrementAndGet() != 0) {
            return;
        }
        PalettedContainer<BlockState> container = section;
        if (container == null) {
            return;
        }
        if (container.data.storage() instanceof SimpleBitStorage packed) {
            RenderRegionStoragePool.release(packed.getRaw());
        }
        // Nothing may read the snapshot after its last release; it now reads as air
        // instead of returning whatever the recycled arrays hold next.
        section = null;
    }
}
