/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Collects animated sprite upload patches each tick and flushes them to Remix
 * in a single batched call before present.
 *
 * <p>LabPBR-aware: sprites that have {@code _n} / {@code _s} siblings are
 * re-processed on every animation frame so AO (from {@code _n.b}) and the
 * animated LabPBR material channels in {@code _s} stay in phase with albedo.
 * Without this, later albedo frames would overwrite the AO-modulated atlas
 * while roughness, metallic, SSS, and emissive atlases stayed stuck on the
 * init-time sidecar frame. Non-LabPBR sprites are copied as albedo only.
 *
 * <p>All patches for one frame across every animated atlas texture go out in
 * one {@code remixapi_UpdateTextureFromMemory} call using the multi-handle
 * batch API. This amortizes the JNI crossing and, on the dxvk side, lets mip
 * regeneration be scoped to the dirty rect per atlas instead of regenerating
 * the whole atlas once per atlas.
 */
public final class AtlasAnimationForwarder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AtlasAnimationForwarder.class);

    // Gate on patch queueing: read by addPatch and toggled by
    // init/clear/pause/resume, all of which run on the render thread. Each
    // toggle is sequenced against the state it guards at its own call site.
    private static volatile boolean active = false;

    private static Thread expectedThread;
    private static final List<PatchRecord> pendingPatches = new ArrayList<>();
    private static final Map<ResourceLocation, AnimationPlayback> animationStates = new HashMap<>();
    private static Map<String, BlockTextureManager.AnimatedSpriteLabPBR> labPBRMap;
    private static Set<ResourceLocation> blockAtlasSpriteIds;
    private static final ThreadLocal<ResourceLocation> currentAtlasId = new ThreadLocal<>();

    // Reusable scratch between CPU-side patch composition and the final
    // DirectBuffer copy. Pre-sized for a typical 64x64 RGBA sprite so the
    // first frame of every animation lands without a realloc; grown
    // monotonically beyond that and never shrunk, so steady-state allocates
    // no garbage.
    private static final int kInitialScratchBytes = 64 * 64 * 4;
    private static byte[] scratch = new byte[kInitialScratchBytes];

    private static final class PatchRecord {
        final ResourceLocation id;
        final int dstX;
        final int dstY;
        final int srcX;
        final int srcY;
        final int width;
        final int height;
        final int srcImageWidth;
        final int srcImageHeight;
        /**
         * Raw pointer into the owning NativeImage's mip-0 backing memory.
         * Read-only and valid only until flush() is called on the same
         * render-thread frame that produced this record:
         *   1. The owning SpriteContents must not be closed (atlas rebuild
         *      or world unload invalidates).
         *   2. The block atlas must not be re-stitched between capture
         *      and flush. RemixRenderer clears the forwarder on
         *      pendingTextureReload before rebuild to enforce this.
         *   3. Capture and flush must both run on the render thread
         *      (thread check in addPatch). Moving flush off the render
         *      thread breaks this contract and requires copying bytes
         *      at capture time.
         */
        final long pixelPtr;
        final int rowPitch;

        PatchRecord(ResourceLocation id, int dstX, int dstY, int srcX, int srcY,
                    int width, int height, int srcImageWidth, int srcImageHeight,
                    long pixelPtr, int rowPitch) {
            this.id = id;
            this.dstX = dstX;
            this.dstY = dstY;
            this.srcX = srcX;
            this.srcY = srcY;
            this.width = width;
            this.height = height;
            this.srcImageWidth = srcImageWidth;
            this.srcImageHeight = srcImageHeight;
            this.pixelPtr = pixelPtr;
            this.rowPitch = rowPitch;
        }

        boolean isInterpolatedUpload() {
            return srcX == 0 && srcY == 0 &&
                   srcImageWidth == width && srcImageHeight == height;
        }
    }

    private static final class FrameOrigin {
        final int x;
        final int y;

        FrameOrigin(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class FrameSampling {
        final FrameOrigin current;
        final FrameOrigin next;
        final float nextBlend;

        FrameSampling(FrameOrigin current, FrameOrigin next, float nextBlend) {
            this.current = current;
            this.next = next;
            this.nextBlend = nextBlend;
        }
    }

    private static final class AnimationPlayback {
        int sequenceIndex;
        int interpolationTick;
        boolean initialized;
    }

    public static void init(
            Map<String, BlockTextureManager.AnimatedSpriteLabPBR> labPBR,
            Set<ResourceLocation> blockAtlasSprites) {
        labPBRMap = labPBR;
        blockAtlasSpriteIds = Set.copyOf(blockAtlasSprites);
        pendingPatches.clear();
        animationStates.clear();
        expectedThread = null;
        // Flip active on LAST so any addPatch probe that races initialization
        // sees a fully constructed forwarder (or no forwarder at all).
        active = true;
        LOGGER.info("AtlasAnimationForwarder initialized ({} LabPBR sprites, {} atlas sprites)",
                    labPBR.size(), blockAtlasSpriteIds.size());
    }

    public static void addPatch(ResourceLocation spriteId, int dstX, int dstY,
                                int srcX, int srcY, int width, int height,
                                int srcImageWidth, int srcImageHeight,
                                long pixelPtr, int rowPitch) {
        // Drop patches when the forwarder is not active (SDK missing, RTX
        // disabled, or post-shutdown/reload). Prevents an unbounded queue
        // when SpriteContents.upload still fires but we'll never flush.
        if (!active) return;

        if (!BlockTextureManager.BLOCK_ATLAS_ID.equals(currentAtlasId.get())) {
            return;
        }

        Set<ResourceLocation> allowedSprites = blockAtlasSpriteIds;
        if (allowedSprites == null || !allowedSprites.contains(spriteId)) {
            return;
        }

        Thread current = Thread.currentThread();
        if (expectedThread == null) {
            expectedThread = current;
        } else if (current != expectedThread) {
            // Java assertions are stripped without -ea, so log + drop instead:
            // a stray cross-thread call would race the render-thread flush
            // and produce torn batches. Drop the patch and surface the
            // violation in production logs.
            LOGGER.error("addPatch called from {} but expected {}; dropping patch",
                         current.getName(), expectedThread.getName());
            return;
        }

        pendingPatches.add(new PatchRecord(spriteId, dstX, dstY, srcX, srcY,
                                           width, height, srcImageWidth, srcImageHeight,
                                           pixelPtr, rowPitch));
    }

    private static FrameSampling resolveLabPBRFrameSampling(
            PatchRecord p, BlockTextureManager.AnimatedSpriteLabPBR pbr) {
        if (pbr == null || pbr.frameIndices.length == 0) {
            FrameOrigin origin = new FrameOrigin(p.srcX, p.srcY);
            return new FrameSampling(origin, origin, 0.0f);
        }

        AnimationPlayback playback = animationStates.computeIfAbsent(p.id, ignored -> new AnimationPlayback());

        if (p.isInterpolatedUpload()) {
            if (!playback.initialized) {
                playback.sequenceIndex = 0;
                playback.interpolationTick = 0;
                playback.initialized = true;
            }

            int currentSequenceIndex = playback.sequenceIndex;
            int nextSequenceIndex = (currentSequenceIndex + 1) % pbr.frameIndices.length;
            int frameTime = Math.max(1, pbr.frameTimes[currentSequenceIndex]);
            int interpolationTick = Math.min(frameTime - 1, playback.interpolationTick + 1);
            playback.interpolationTick = interpolationTick;

            FrameOrigin current = frameOriginFromFrameIndex(
                pbr.frameIndices[currentSequenceIndex], pbr.frameColumns, p.width, p.height);
            FrameOrigin next = frameOriginFromFrameIndex(
                pbr.frameIndices[nextSequenceIndex], pbr.frameColumns, p.width, p.height);
            float nextBlend = frameTime > 1 ? (float) interpolationTick / (float) frameTime : 0.0f;
            return new FrameSampling(current, next, nextBlend);
        }

        int frameIndex = frameIndexFromOrigin(p);
        int expectedNext = playback.initialized
            ? (playback.sequenceIndex + 1) % pbr.frameIndices.length
            : -1;
        playback.sequenceIndex = findSequenceIndex(pbr.frameIndices, frameIndex, expectedNext);
        playback.interpolationTick = 0;
        playback.initialized = true;

        FrameOrigin origin = new FrameOrigin(p.srcX, p.srcY);
        return new FrameSampling(origin, origin, 0.0f);
    }

    private static int frameIndexFromOrigin(PatchRecord p) {
        int columns = Math.max(1, p.srcImageWidth / Math.max(1, p.width));
        return (p.srcY / Math.max(1, p.height)) * columns + (p.srcX / Math.max(1, p.width));
    }

    private static FrameOrigin frameOriginFromFrameIndex(int frameIndex, int frameColumns, int width, int height) {
        int columns = Math.max(1, frameColumns);
        int x = (frameIndex % columns) * width;
        int y = (frameIndex / columns) * height;
        return new FrameOrigin(x, y);
    }

    private static int findSequenceIndex(int[] frameIndices, int frameIndex, int expectedNext) {
        if (expectedNext >= 0 && expectedNext < frameIndices.length && frameIndices[expectedNext] == frameIndex) {
            return expectedNext;
        }

        for (int i = 0; i < frameIndices.length; i++) {
            if (frameIndices[i] == frameIndex) {
                return i;
            }
        }

        return 0;
    }

    /**
     * Builds one multi-handle batch covering animated albedo and LabPBR atlas
     * sidecars, then issues a single {@code remixapi_UpdateTextureFromMemory}
     * call. LabPBR data is applied per patch using pre-computed tables cached
     * at init.
     *
     * @param api            the Remix API handle
     * @param albedoHandle   handle of the albedo atlas texture (required)
     * @param emissiveHandle handle of the emissive atlas, or 0 if none exists
     * @param roughnessHandle handle of the roughness atlas, or 0 if none exists
     * @param metallicHandle handle of the metallic atlas, or 0 if none exists
     * @param sssHandle      handle of the SSS atlas, or 0 if none exists
     */
    public static void flush(RemixAPI api, long albedoHandle, long emissiveHandle,
                             long roughnessHandle, long metallicHandle, long sssHandle) {
        int count = pendingPatches.size();
        if (count == 0 || albedoHandle == 0) {
            return;
        }

        List<ByteBuffer> ownedBuffers = new ArrayList<>(count * 2);

        try {
            TexturePatch[] albedoPatches = new TexturePatch[count];
            List<TexturePatch> roughnessPatches = new ArrayList<>();
            List<TexturePatch> metallicPatches = new ArrayList<>();
            List<TexturePatch> emissivePatches = new ArrayList<>();
            List<TexturePatch> sssPatches = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                PatchRecord p = pendingPatches.get(i);
                BlockTextureManager.AnimatedSpriteLabPBR pbr =
                    (labPBRMap != null) ? labPBRMap.get(p.id.toString()) : null;

                final int patchBytes = p.width * p.height * 4;
                final int patchRowBytes = p.width * 4;
                ensureScratch(patchBytes);
                ByteBuffer srcBuf = MemoryUtil.memByteBuffer(p.pixelPtr, p.rowPitch * p.height);
                FrameSampling labPbrSampling = resolveLabPBRFrameSampling(p, pbr);

                // --- Albedo patch ---
                if (pbr != null && pbr.aoTable != null) {
                    LabPBRConverter.applyAOToPatch(
                        scratch, patchRowBytes,
                        srcBuf, p.rowPitch,
                        pbr.aoTable, pbr.aoW, pbr.aoH,
                        labPbrSampling.current.x, labPbrSampling.current.y,
                        p.width, p.height);
                } else {
                    LabPBRConverter.copyAlbedoPatch(
                        scratch, patchRowBytes,
                        srcBuf, p.rowPitch,
                        p.width, p.height);
                }
                ByteBuffer albedoDst = DirectBufferPool.acquire(patchBytes);
                albedoDst.put(scratch, 0, patchBytes);
                albedoDst.flip();
                ownedBuffers.add(albedoDst);

                albedoPatches[i] = createPatch(p, albedoDst, patchRowBytes, TextureFormat.RGBA8_UNORM);

                // --- _s RGB material channels ---
                // LabPBR packs roughness/metallic/SSS into _s.rgb. These
                // atlases are separate Remix textures, so they need matching
                // animation patches just like _s.alpha emissive.
                if (pbr != null && pbr.sRoughnessTable != null && roughnessHandle != 0) {
                    int patchR8Bytes = p.width * p.height;
                    ensureScratch(patchR8Bytes);
                    LabPBRConverter.buildR8Patch(
                        scratch, p.width,
                        pbr.sRoughnessTable, pbr.sW, pbr.sH,
                        labPbrSampling.current.x, labPbrSampling.current.y,
                        labPbrSampling.next.x, labPbrSampling.next.y,
                        labPbrSampling.nextBlend,
                        p.width, p.height);
                    ByteBuffer roughnessDst = DirectBufferPool.acquire(patchR8Bytes);
                    roughnessDst.put(scratch, 0, patchR8Bytes);
                    roughnessDst.flip();
                    ownedBuffers.add(roughnessDst);
                    roughnessPatches.add(createPatch(p, roughnessDst, p.width, TextureFormat.R8_UNORM));
                }

                if (pbr != null && pbr.sMetallicTable != null && metallicHandle != 0) {
                    int patchR8Bytes = p.width * p.height;
                    ensureScratch(patchR8Bytes);
                    LabPBRConverter.buildR8Patch(
                        scratch, p.width,
                        pbr.sMetallicTable, pbr.sW, pbr.sH,
                        labPbrSampling.current.x, labPbrSampling.current.y,
                        labPbrSampling.next.x, labPbrSampling.next.y,
                        labPbrSampling.nextBlend,
                        p.width, p.height);
                    ByteBuffer metallicDst = DirectBufferPool.acquire(patchR8Bytes);
                    metallicDst.put(scratch, 0, patchR8Bytes);
                    metallicDst.flip();
                    ownedBuffers.add(metallicDst);
                    metallicPatches.add(createPatch(p, metallicDst, p.width, TextureFormat.R8_UNORM));
                }

                if (pbr != null && pbr.hasSss && pbr.sSssTable != null && sssHandle != 0) {
                    LabPBRConverter.buildSssPatch(
                        scratch, patchRowBytes,
                        pbr.sSssTable, pbr.sW, pbr.sH,
                        labPbrSampling.current.x, labPbrSampling.current.y,
                        labPbrSampling.next.x, labPbrSampling.next.y,
                        labPbrSampling.nextBlend,
                        p.width, p.height);
                    ByteBuffer sssDst = DirectBufferPool.acquire(patchBytes);
                    sssDst.put(scratch, 0, patchBytes);
                    sssDst.flip();
                    ownedBuffers.add(sssDst);
                    sssPatches.add(createPatch(p, sssDst, patchRowBytes, TextureFormat.RGBA8_UNORM));
                }

                // --- Emissive patch (authored _s alpha, else a built-in mask) ---
                if (emissiveHandle == 0) continue;
                final boolean hasLabPBREmissive = pbr != null && pbr.hasPerPixelEmissive;
                BuiltinEmission.Mask builtinMask = hasLabPBREmissive ? null : BuiltinEmission.mask(p.id);

                // Skip sprites with neither. Vanilla block luminance affects
                // cast light, not surface emission.
                if (!hasLabPBREmissive && builtinMask == null) continue;

                if (hasLabPBREmissive) {
                    LabPBRConverter.buildEmissivePatch(
                        scratch, patchRowBytes,
                        srcBuf, p.rowPitch,
                        pbr.sAlphaTable, pbr.sW, pbr.sH,
                        labPbrSampling.current.x, labPbrSampling.current.y,
                        labPbrSampling.next.x, labPbrSampling.next.y,
                        labPbrSampling.nextBlend,
                        p.width, p.height,
                        0f);
                } else {
                    // The mask is over the sprite's own UV space, which every animation frame shares.
                    LabPBRConverter.buildEmissiveMaskPatch(
                        scratch, patchRowBytes,
                        srcBuf, p.rowPitch,
                        p.width, p.height, builtinMask);
                }

                ByteBuffer emissiveDst = DirectBufferPool.acquire(patchBytes);
                emissiveDst.put(scratch, 0, patchBytes);
                emissiveDst.flip();
                ownedBuffers.add(emissiveDst);

                emissivePatches.add(createPatch(p, emissiveDst, patchRowBytes, TextureFormat.RGBA8_UNORM));
            }

            // One batch per frame: the runtime handles each entry's texture
            // independently, so all five channels travel in a single call.
            List<TextureBatchEntry> entries = new ArrayList<>(5);
            addBatchEntry(entries, albedoHandle, albedoPatches);
            addBatchEntry(entries, roughnessHandle, roughnessPatches);
            addBatchEntry(entries, metallicHandle, metallicPatches);
            addBatchEntry(entries, emissiveHandle, emissivePatches);
            addBatchEntry(entries, sssHandle, sssPatches);

            TexturePatchInfo info = new TexturePatchInfo();
            info.entries = entries.toArray(new TextureBatchEntry[0]);
            api.updateTextureFromMemory(info);

        } catch (Exception e) {
            LOGGER.error("Error flushing animation patches", e);
        } finally {
            for (ByteBuffer buffer : ownedBuffers) {
                DirectBufferPool.release(buffer);
            }
            pendingPatches.clear();
        }
    }

    private static void ensureScratch(int byteCount) {
        if (scratch.length < byteCount) {
            scratch = new byte[byteCount];
        }
    }

    private static TexturePatch createPatch(PatchRecord p, ByteBuffer data, int rowPitch, int format) {
        TexturePatch patch = new TexturePatch();
        patch.dstX = p.dstX;
        patch.dstY = p.dstY;
        patch.width = p.width;
        patch.height = p.height;
        patch.pixelData = data;
        patch.rowPitch = rowPitch;
        patch.format = format;
        return patch;
    }

    private static void addBatchEntry(List<TextureBatchEntry> entries, long handle, TexturePatch[] patches) {
        if (handle == 0 || patches.length == 0) {
            return;
        }
        TextureBatchEntry entry = new TextureBatchEntry();
        entry.handle = handle;
        entry.patches = patches;
        entries.add(entry);
    }

    private static void addBatchEntry(List<TextureBatchEntry> entries, long handle, List<TexturePatch> patches) {
        if (handle == 0 || patches.isEmpty()) {
            return;
        }
        addBatchEntry(entries, handle, patches.toArray(new TexturePatch[0]));
    }

    public static void clear() {
        // Flip active OFF first so a concurrent addPatch drops its record
        // rather than inserting into a list we're about to wipe.
        active = false;
        pendingPatches.clear();
        labPBRMap = null;
        blockAtlasSpriteIds = null;
        animationStates.clear();
        expectedThread = null;
        currentAtlasId.remove();
        LOGGER.info("AtlasAnimationForwarder cleared");
    }

    public static void beginAtlasUpload(ResourceLocation atlasId) {
        currentAtlasId.set(atlasId);
    }

    public static void endAtlasUpload() {
        currentAtlasId.remove();
    }

    /**
     * Deactivates queueing and drops any pending records without wiping
     * {@link #labPBRMap} / {@link #blockAtlasSpriteIds}.
     * Use for reversible pauses like the RTX-toggle path where
     * {@link #resume()} should be a cheap flip without a resource-pack reload.
     */
    public static void pause() {
        active = false;
        pendingPatches.clear();
        animationStates.clear();
    }

    public static void resume() {
        active = blockAtlasSpriteIds != null;
    }

    private AtlasAnimationForwarder() {}
}
