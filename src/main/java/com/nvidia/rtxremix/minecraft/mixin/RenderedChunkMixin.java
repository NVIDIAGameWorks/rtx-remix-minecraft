/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.capture.RenderRegionStoragePool;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.chunk.RenderedChunk;
import net.minecraft.util.collection.PackedIntegerArray;
import net.minecraft.world.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A chunk snapshot for a render region whose block-state copies draw their storage from
 * {@link RenderRegionStoragePool} and give it back when the last region holding the snapshot
 * has been consumed. Snapshots are shared between the regions one frame's rebuild scheduling
 * builds, hence the count. The pool flag is set around the constructor by
 * {@link ClientChunkMixin}; vanilla's own snapshot code is untouched.
 */
@Mixin(RenderedChunk.class)
public abstract class RenderedChunkMixin implements RegionSnapshotRelease {

    @Shadow
    @Final
    private List<PalettedContainer<BlockState>> blockStateContainers;

    private final AtomicInteger rtxremix$regions = new AtomicInteger();

    @Override
    public void rtxremix$retain() {
        rtxremix$regions.incrementAndGet();
    }

    @Override
    public void rtxremix$release() {
        if (rtxremix$regions.decrementAndGet() != 0 || blockStateContainers == null) {
            return;
        }
        for (PalettedContainer<BlockState> container : blockStateContainers) {
            if (container != null && container.data.storage() instanceof PackedIntegerArray packed) {
                RenderRegionStoragePool.release(packed.getData());
            }
        }
        // Nothing may read the snapshot after its last release; make a stale read fail loudly
        // instead of returning whatever the recycled arrays hold next.
        blockStateContainers.clear();
    }
}
