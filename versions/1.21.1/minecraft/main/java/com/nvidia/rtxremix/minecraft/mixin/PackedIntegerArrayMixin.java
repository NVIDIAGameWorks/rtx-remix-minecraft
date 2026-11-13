/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.capture.RenderRegionStoragePool;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a render region snapshot is being taken, copies of packed block storage use a pooled
 * array instead of cloning a new one; the rebuild task returns it when the region is dead.
 * Every other copy (chunk serialisation, packets, world edits) is untouched.
 */
@Mixin(SimpleBitStorage.class)
public abstract class PackedIntegerArrayMixin {

    @Shadow
    @Final
    private long[] data;
    @Shadow
    @Final
    private int bits;
    @Shadow
    @Final
    private int size;

    @Inject(method = "copy", at = @At("HEAD"), cancellable = true)
    private void rtxremix$pooledCopy(CallbackInfoReturnable<BitStorage> cir) {
        if (RenderRegionStoragePool.copying()) {
            long[] copy = RenderRegionStoragePool.acquire(data.length);
            System.arraycopy(data, 0, copy, 0, data.length);
            cir.setReturnValue(new SimpleBitStorage(bits, size, copy));
        }
    }
}
