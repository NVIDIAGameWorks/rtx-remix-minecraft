/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import java.nio.ByteBuffer;

/**
 * Low-level JNI bridge to the Remix C API. Every method maps to a single
 * {@code JNIEXPORT} in {@code remixbridge.dll}. All handles are raw
 * {@code long} addresses; extension structs are allocated on the C heap via
 * {@code alloc*Ext} and freed with {@link #freeExt}.
 *
 * <p>Thread safety: same as the underlying {@code remixapi_Interface}:
 * the vtable uses internal locking, JNI native methods are reentrant.
 */
public final class RemixBridge {

    private RemixBridge() {}

    /**
     * Loads d3d9.dll via SetDllDirectory + LoadLibrary, calls
     * remixapi_InitializeLibrary to fill the vtable.
     *
     * @return REMIXAPI_ERROR_CODE_SUCCESS (0) on success
     */
    public static native int initialize(String dllPath);

    // ─── process command line ────────────────────────────────────────────

    /** @return this process's command line as its PEB holds it, which crash dumps capture */
    public static native String processCommandLine();

    /**
     * Overwrites this process's command line in place: the PEB copy and the
     * copies {@code GetCommandLineW} and {@code GetCommandLineA} return.
     *
     * @param replacement a line exactly as long as {@link #processCommandLine()}
     * @return REMIXAPI_ERROR_CODE_SUCCESS (0), or REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS
     *         when the replacement does not fit
     */
    public static native int overwriteProcessCommandLine(String replacement);

    // ─── crash dumps ─────────────────────────────────────────────────────

    /**
     * Sets whether the minidump the bridge writes after a native crash also
     * copies the memory that stack values point at. Off by default, because
     * that memory includes the process's launch arguments.
     */
    public static native void setCrashDumpDetail(boolean full);

    // ─── lifecycle ───────────────────────────────────────────────────────

    public static native int startup(long hwnd, boolean disableSrgb,
                                     boolean forceNoVkSwapchain,
                                     boolean editorMode,
                                     boolean combineGui,
                                     boolean interopImage,
                                     boolean useRemixOwnedChildWindow);

    public static native int shutdown();

    public static native int submit(int interopFlags);

    public static native int present(long hwndOverride,
                    int skipVulkanSwapchain,
                    int interopFlipY,
                    long clientDoneSemaphore_ValueToWait,
                    int disableCpuWaitForExternalSemaphores);

    /**
     * Deliberately faults inside a guarded Remix call on the calling thread,
     * to check where a native crash there is reported: in {@code d3d9.dll},
     * which reaches the runtime's crash reporter, or with {@code insideJvm}
     * in {@code jvm.dll}, which HotSpot keeps. Diagnostic only.
     *
     * @return an error code only if the runtime rejected the call before faulting
     */
    public static native int faultInRemixCall(boolean insideJvm);

    /**
     * Sets whether a fatal fault inside a Remix call goes to the runtime's
     * crash reporter first, which is the default on x64. Off, HotSpot reports
     * it in {@code hs_err_pid*.log} with the Java frames that led to the call.
     * Callable before {@link #initialize}.
     */
    public static native void setRemixFaultForwarding(boolean enabled);

    public static native int dxvkGetInteropInfo(InteropInfo outInfo);

    // ─── materials ───────────────────────────────────────────────────────

    /**
     * @param pNext C-allocated extension chain (0 = none).
     *              Freed by the caller after this returns.
     * @return native handle, or 0 on failure
     */
    public static native long createMaterial(
            long hash,
            long albedoTexture, long normalTexture,
            long tangentTexture, long emissiveTexture,
            float emissiveIntensity,
            float emissiveR, float emissiveG, float emissiveB,
            int spriteSheetRow, int spriteSheetCol, int spriteSheetFps,
            int filterMode, int wrapModeU, int wrapModeV,
            long pNext);

    public static native int updateMaterial(
            long handle, long hash,
            long albedoTexture, long normalTexture,
            long tangentTexture, long emissiveTexture,
            float emissiveIntensity,
            float emissiveR, float emissiveG, float emissiveB,
            int spriteSheetRow, int spriteSheetCol, int spriteSheetFps,
            int filterMode, int wrapModeU, int wrapModeV,
            long pNext);

    public static native int destroyMaterial(long handle);

    // ─── textures ────────────────────────────────────────────────────────

    /**
     * @param pixelData direct ByteBuffer containing raw pixel data
     * @return native handle, or 0 on failure
     */
    public static native long createTextureFromMemory(
            long hash, ByteBuffer pixelData,
            int width, int height, int format, int purpose);

    /** @return native handle, or 0 on failure */
    public static native long createTextureFromFile(String filePath);

    public static native int destroyTexture(long handle);

    /**
     * Updates mip-0 subregions across one or more existing textures in a
     * single call. Patches are flattened across all entries; the per-entry
     * split is reconstructed on the native side via {@code entryPatchCounts}.
     *
     * @param handles           one native texture handle per entry
     * @param entryPatchCounts  number of patches in each entry
     *                          (length must equal {@code handles.length})
     * @param dstXs             flat per-patch array; length = sum(entryPatchCounts)
     * @param dstYs             flat per-patch array
     * @param widths            flat per-patch array
     * @param heights           flat per-patch array
     * @param pixelBuffers      flat per-patch direct ByteBuffers
     * @param rowPitches        flat per-patch array
     * @param formats           flat per-patch array
     */
    public static native int updateTextureFromMemory(
            long[] handles,
            int[] entryPatchCounts,
            int[] dstXs, int[] dstYs,
            int[] widths, int[] heights,
            ByteBuffer[] pixelBuffers,
            int[] rowPitches, int[] formats);

    // ─── meshes ──────────────────────────────────────────────────────────

    /**
     * Creates a mesh with one or more surfaces. Each parallel array element
     * describes one surface.
     *
     * @param vertexBuffers  direct ByteBuffers of remixapi_HardcodedVertex
     * @param vertexCounts   number of vertices per surface
     * @param indexBuffers   direct ByteBuffers of uint32_t indices
     * @param indexCounts    number of indices per surface
     * @param materialHandles material handle per surface
     * @param flags           surface triangles flags (see {@link MeshInfoSurfaceTriangles})
     * @param bonesPerVertex 0 = no skinning for that surface
     * @param blendWeightBuffers direct ByteBuffers (null entries = no skinning)
     * @param blendWeightCounts  element counts (float count, not byte count)
     * @param blendIndexBuffers  direct ByteBuffers (null entries = no skinning)
     * @param blendIndexCounts   element counts
     * @return native handle, or 0 on failure
     */
    public static native long createMesh(
            long hash, int surfaceCount,
            ByteBuffer[] vertexBuffers, int[] vertexCounts,
            ByteBuffer[] indexBuffers, int[] indexCounts,
            long[] materialHandles,
            int[] flags,
            int[] bonesPerVertex,
            ByteBuffer[] blendWeightBuffers, int[] blendWeightCounts,
            ByteBuffer[] blendIndexBuffers, int[] blendIndexCounts);

    public static native int destroyMesh(long handle);

    // ─── camera ──────────────────────────────────────────────────────────

    /** @param pNext C-allocated CameraInfoParameterizedEXT (0 = none) */
    public static native int setupCamera(int type, float[] view16,
                                         float[] projection16, long pNext);

    /** @param materialHandle 0 to clear the medium for the current frame */
    public static native int setCameraMediumMaterial(long materialHandle);

    // ─── instances ───────────────────────────────────────────────────────

    /** @param pNext C-allocated extension chain (BlendEXT → BoneEXT → …) */
    public static native int drawInstance(long meshHandle, float[] transform12,
                                          int categoryFlags, int doubleSided,
                                          long pNext);

    /** @return retained scene-instance handle, or 0 on failure */
    public static native long sceneInstanceAdd(long meshHandle, float[] transform12,
                                               int categoryFlags, int doubleSided,
                                               long pNext);

    public static native int sceneInstanceUpdate(long sceneInstanceHandle,
                                                 long meshHandle, float[] transform12,
                                                 int categoryFlags, int doubleSided,
                                                 long pNext);

    public static native int sceneInstanceRemove(long sceneInstanceHandle);

    // ─── lights ──────────────────────────────────────────────────────────

    /** @param pNext C-allocated light extension (sphere/distant/rect/dome/…) */
    public static native long createLight(long hash,
                                          float radianceR, float radianceG,
                                          float radianceB, long pNext);

    public static native int destroyLight(long handle);

    public static native int drawLightInstance(long handle);

    // ─── config ──────────────────────────────────────────────────────────

    public static native int setConfigVariable(String key, String value);

    /**
     * Reads an option's resolved value as NUL-terminated UTF-8 into {@code outValue}, whose length
     * is the capacity. {@code outValue} is an ordinary heap array, copied through
     * {@code GetByteArrayElements}, so no direct buffer is needed. A null {@code outValue} only
     * queries the size.
     *
     * @return the size of the value including its terminator, which exceeds the capacity when the
     *         value did not fit. Returns the negated Remix error code for any other failure, which
     *         is {@link ErrorCode#INVALID_ARGUMENTS} for a null key.
     */
    public static native int getConfigVariableResolved(String key, byte[] outValue);

    /** @return config-layer handle, or 0 on failure */
    public static native long acquireConfigLayer(String path, float priority, float blendThreshold);

    public static native int setConfigLayerBlending(long handle, float strength, float blendThreshold);

    public static native int releaseConfigLayer(long handle);

    /** @return true when the loaded runtime exposes the config-layer API (API >= 0.9.0) */
    public static native boolean isConfigLayersSupported();

    // ─── DXVK interop ────────────────────────────────────────────────────

    /** @return IDirect3D9Ex* as long, or 0 on failure */
    public static native long dxvkCreateD3D9(boolean editorMode);

    public static native int dxvkRegisterD3D9Device(long device);

    /** @return long[3]: {vkImage, semRenderDone, semResume} */
    public static native long[] dxvkGetExternalSwapchain();

    public static native long dxvkGetVkImage(long surface);

    public static native int dxvkCopyRenderingOutput(long surface, int type);

    public static native int dxvkSetDefaultOutput(int type,
                                                   float r, float g,
                                                   float b, float a);

    // ─── D3D9 COM helpers ────────────────────────────────────────────────

    /** IDirect3D9Ex::CreateDeviceEx → returns IDirect3DDevice9Ex* */
    public static native long d3d9CreateDevice(long pD3D9Ex, long hwnd,
                                               int width, int height);

    /** IDirect3DDevice9::CreateRenderTarget → returns IDirect3DSurface9* */
    public static native long d3d9CreateRenderTarget(long pDevice,
                                                     int width, int height,
                                                     int format);

    /** IDirect3DDevice9Ex::Reset */
    public static native int d3d9Reset(long pDevice, int width, int height);

    /** IDirect3DDevice9::Present (all NULL rects) */
    public static native int d3d9Present(long pDevice);

    /** IUnknown::Release on any COM pointer */
    public static native int d3d9Release(long comPtr);

    // ─── extension allocation (C-heap) ───────────────────────────────────
    // Every alloc returns a pointer-as-long to a C struct whose pNext is
    // set to `chainNext`. Free with freeExt() when no longer needed.

    public static native long allocOpaqueExt(
            long roughnessTex, long metallicTex, float anisotropy,
            float albedoR, float albedoG, float albedoB,
            float opacity, float roughness, float metallic,
            boolean hasThinFilm, float thinFilmThickness,
            boolean alphaIsThinFilm,
            long heightTex, float displaceIn,
            boolean useDrawCallAlpha,
            boolean hasBlendType, int blendType, boolean invertedBlend,
            int alphaTestType, int alphaRefValue, float displaceOut,
            long chainNext);

    public static native long allocOpaqueSubsurfaceExt(
            long transmittanceTex, long thicknessTex,
            long singleScatterAlbedoTex,
            float transR, float transG, float transB,
            float measurementDist,
            float ssaR, float ssaG, float ssaB,
            float volumetricAniso, boolean diffusionProfile,
            float radiusR, float radiusG, float radiusB,
            float radiusScale, float maxSampleRadius,
            long radiusTex,
            long chainNext);

    public static native long allocTranslucentExt(
            long transmittanceTex, float refractiveIndex,
            float transR, float transG, float transB,
            float measurementDist,
            boolean hasThinWall, float thinWallThickness,
            boolean useDiffuseLayer,
            long chainNext);

    public static native long allocBlendExt(
            boolean alphaTestEnabled, int alphaTestRef, int alphaTestCompareOp,
            boolean alphaBlendEnabled,
            int srcColorBlend, int dstColorBlend, int colorBlendOp,
            int texColorArg1, int texColorArg2, int texColorOp,
            int texAlphaArg1, int texAlphaArg2, int texAlphaOp,
            int tFactor, boolean isTexFactorBlend,
            int srcAlphaBlend, int dstAlphaBlend, int alphaBlendOp,
            int writeMask, boolean isVertexColorBakedLighting,
            long chainNext);

    /**
     * @param transforms direct ByteBuffer of float[12] * count (48 bytes each)
     */
    public static native long allocBoneTransformsExt(ByteBuffer transforms,
                                                     int count,
                                                     long chainNext);

    /**
     * @param transforms direct ByteBuffer of float[12] * count (48 bytes each)
     */
    public static native long allocGpuInstancingExt(ByteBuffer transforms,
                                                    int count,
                                                    long chainNext);

    public static native long allocSphereExt(
            float px, float py, float pz, float radius,
            boolean hasShaping,
            float shapeDirX, float shapeDirY, float shapeDirZ,
            float coneAngle, float coneSoftness, float focusExponent,
            float volumetricRadianceScale,
            long chainNext);

    public static native long allocDistantExt(
            float dirX, float dirY, float dirZ,
            float angularDiameter, float volumetricRadianceScale,
            long chainNext);

    public static native long allocRectExt(
            float px, float py, float pz,
            float xAxisX, float xAxisY, float xAxisZ, float xSize,
            float yAxisX, float yAxisY, float yAxisZ, float ySize,
            float dirX, float dirY, float dirZ,
            boolean hasShaping,
            float shapeDirX, float shapeDirY, float shapeDirZ,
            float coneAngle, float coneSoftness, float focusExponent,
            float volumetricRadianceScale,
            long chainNext);

    public static native long allocDiskExt(
            float px, float py, float pz,
            float xAxisX, float xAxisY, float xAxisZ, float xRadius,
            float yAxisX, float yAxisY, float yAxisZ, float yRadius,
            float dirX, float dirY, float dirZ,
            boolean hasShaping,
            float shapeDirX, float shapeDirY, float shapeDirZ,
            float coneAngle, float coneSoftness, float focusExponent,
            float volumetricRadianceScale,
            long chainNext);

    public static native long allocCylinderExt(
            float px, float py, float pz,
            float radius,
            float axisX, float axisY, float axisZ,
            float axisLength,
            float volumetricRadianceScale,
            long chainNext);

    public static native long allocDomeExt(float[] transform12,
                                           long colorTexture,
                                           float opacity,
                                           int blendType,
                                           long chainNext);

    public static native long allocCameraParamExt(
            float posX, float posY, float posZ,
            float fwdX, float fwdY, float fwdZ,
            float upX, float upY, float upZ,
            float rightX, float rightY, float rightZ,
            float fovY, float aspect, float nearPlane, float farPlane,
            long chainNext);

    /** Frees a C-heap extension struct. Safe to call with 0. */
    public static native void freeExt(long extPtr);

    // ─── Tracy profiling ─────────────────────────────────────────────────

    /** Opens a Tracy zone. Returns an opaque context to pass to {@link #tracyZoneEnd}. */
    public static native long tracyZoneBegin(String name);

    /** Closes a Tracy zone opened by {@link #tracyZoneBegin}. */
    public static native void tracyZoneEnd(long ctx);

    /** Sets the Tracy display name for the calling thread. */
    public static native void tracySetThreadName(String name);

    /** Emits a named numeric sample to Tracy's plot lane. */
    public static native void tracyPlot(String name, double value);

    /** Emits a timeline message to Tracy. */
    public static native void tracyMessage(String message);

    /** Emits a named Tracy frame mark. */
    public static native void tracyFrameMark(String name);


    /** Wrapper for 'glSemaphoreParameterui64vEXT' that is not exposed by LWJGL */
    public static native int openglSemaphoreParameterui64vEXT(int semaphore, int pname, long param0);
    /** Wrapper for other NV_timeline_semaphore functions that are not reported by LWJGL */
    public static native int openglSemaphoreParameterivNV(int semaphore, int pname, int param0);
    public static native int openglCreateSemaphoresNV();

    public static int GL_SEMAPHORE_TYPE_NV = 0x95B3;
    public static int GL_SEMAPHORE_TYPE_BINARY_NV = 0x95B4;
    public static int GL_SEMAPHORE_TYPE_TIMELINE_NV = 0x95B5;
    public static int GL_TIMELINE_SEMAPHORE_VALUE_NV = 0x9595;
}
