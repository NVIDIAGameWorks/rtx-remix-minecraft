/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.capture.RenderRegionStoragePool;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * A chunk snapshot for a render region whose block-state copies draw their storage from
 * {@link RenderRegionStoragePool} and give it back when the last region holding the snapshot
 * has been consumed. Snapshots are shared between the regions one frame's rebuild scheduling
 * builds, hence the count. The pool flag is set around the constructor by
 * {@link ClientChunkMixin}; vanilla's own snapshot code is untouched.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.RenderChunk")
public abstract class RenderedChunkMixin implements RegionSnapshotRelease {

    @Shadow
    @Final
    private List<PalettedContainer<BlockState>> sections;

    private final AtomicInteger rtxremix$regions = new AtomicInteger();

    @Override
    public void rtxremix$retain() {
        rtxremix$regions.incrementAndGet();
    }

    @Override
    public void rtxremix$release() {
        if (rtxremix$regions.decrementAndGet() != 0 || sections == null) {
            return;
        }
        for (PalettedContainer<BlockState> container : sections) {
            if (container != null && container.data.storage() instanceof SimpleBitStorage packed) {
                RenderRegionStoragePool.release(packed.getRaw());
            }
        }
        // Nothing may read the snapshot after its last release; make a stale read fail loudly
        // instead of returning whatever the recycled arrays hold next.
        sections.clear();
    }
}
