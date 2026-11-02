/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import java.io.File;
import com.nvidia.rtxremix.forge.interop.LegacyGlInterop;

/** Exercises JNI allocation and optional runtime negotiation without a game or GPU window. */
public final class NativeBridgeSmokeTest {
    public static void main(String[] args) {
        System.load(new File(args[0]).getAbsolutePath());
        BridgeContractTest.check(LegacyGlInterop.currentWindowHandle() == 0,
                "No current WGL context must report no window");
        InteropInfo handles = new InteropInfo();
        handles.dxvkDoneSemaphore_Handle = 1;
        handles.clientDoneSemaphore_Handle = 2;
        try {
            new LegacyGlInterop(handles);
            throw new AssertionError("Interop creation accepted a missing GL context");
        } catch (IllegalStateException expected) {
            BridgeContractTest.check(expected.getMessage().contains("No current"), expected.getMessage());
        }
        InstanceInfoBoneTransformsEXT bones = new InstanceInfoBoneTransformsEXT();
        bones.setBoneTransforms(new Transform[] {Transform.identity()});
        long boneExt = RemixBridge.allocBoneTransformsExt(bones.transformData, bones.count, 0);
        BridgeContractTest.check(boneExt != 0, "Native bone extension allocation failed");
        RemixBridge.freeExt(boneExt);
        long instanceExt = RemixBridge.allocGpuInstancingExt(bones.transformData, bones.count, 0);
        BridgeContractTest.check(instanceExt != 0, "Native instancing extension allocation failed");
        RemixBridge.freeExt(instanceExt);
        long distantExt = RemixBridge.allocDistantExt(0, -1, 0, 0.5f, 1, 0);
        BridgeContractTest.check(distantExt != 0, "Native distant-light extension allocation failed");
        RemixBridge.freeExt(distantExt);
        BridgeContractTest.check(RemixBridge.submit(0) == ErrorCode.NOT_INITIALIZED,
                "An uninitialized runtime must reject submission");
        System.out.println("PASS: Java 8 loaded JNI DLL, allocated/copied/freed native extensions, checked uninitialized guard");
        if (args.length > 1) {
            int result = RemixBridge.initialize(new File(args[1]).getAbsolutePath());
            BridgeContractTest.check(result == ErrorCode.SUCCESS,
                    "Runtime initialization failed: " + ErrorCode.toString(result));
            System.out.println("PASS: pinned runtime loaded and accepted Remix API 0.16.0 from Java 8");
            // No Startup or renderer resources exist; the short-lived process owns DLL cleanup.
        }
    }
}
