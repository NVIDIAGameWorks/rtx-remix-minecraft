/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * Vanilla gives every {@code BuiltChunk} a {@link VertexBuffer} per block render layer: three
 * GL objects and about 150 bytes of heap each, five per section, at construction. While
 * Remix renders, nothing ever draws or fills them: every block is reported invisible to the
 * section builder, so a build produces no layer buffers, and {@code renderLayer} is
 * cancelled. At a high render distance that is hundreds of thousands of dead vertex buffers,
 * their GL names created on the render thread at world load, and tens of megabytes of heap.
 *
 * <p>A section constructed while Remix is rendering therefore gets an empty buffer map. The
 * RTX toggle reloads the world renderer, which constructs fresh sections in the new mode, so
 * sections built for vanilla's GL path keep vanilla's buffers. A build that completes for an
 * old section after the toggle but before the reload replaces it would upload into
 * {@code getBuffer(layer)}; that returns a shared scratch buffer per layer here instead of
 * null, so the upload lands harmlessly in a buffer nothing draws.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class BuiltChunkBuffersMixin {

    /** Per-layer scratch buffers for the toggle window; created on the render thread at first need. */
    private static final Map<RenderType, VertexBuffer> rtxremix$scratchBuffers = new HashMap<>();

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/stream/Collectors;toMap(Ljava/util/function/Function;Ljava/util/function/Function;)Ljava/util/stream/Collector;"))
    private static <T, K, U> Collector<T, ?, Map<K, U>> rtxremix$layerBuffers(
            Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends U> valueMapper) {
        if (!RtxRemixMod.isRtxEnabled()) {
            return Collectors.toMap(keyMapper, valueMapper);
        }
        // Constructed on the render thread, like the sections themselves.
        if (rtxremix$scratchBuffers.isEmpty()) {
            for (RenderType layer : RenderType.chunkBufferLayers()) {
                rtxremix$scratchBuffers.put(layer, new VertexBuffer(VertexBuffer.Usage.STATIC));
            }
        }
        return Collector.of(
                () -> Collections.<K, U>emptyMap(),
                (map, element) -> { },
                (left, right) -> left);
    }

    @Inject(method = "getBuffer", at = @At("RETURN"), cancellable = true)
    private void rtxremix$scratchForMissingLayer(RenderType layer, CallbackInfoReturnable<VertexBuffer> cir) {
        if (cir.getReturnValue() == null) {
            cir.setReturnValue(rtxremix$scratchBuffers.get(layer));
        }
    }
}
