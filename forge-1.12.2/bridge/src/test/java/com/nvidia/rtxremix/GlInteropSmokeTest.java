/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import com.nvidia.rtxremix.forge.interop.LegacyGlInterop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.imageio.ImageIO;

/** Renders a known triangle through the shared images in a separate WGL context. */
public final class GlInteropSmokeTest {
    private static native int createContext();
    private static native void setContextCurrent(boolean current);
    private static native void resizeContext(int width, int height);
    private static native byte[] readImageAndVerifyState();
    private static native float[] readDepthTexture(int texture, int width, int height);
    private static native void destroyContext();

    public static void main(String[] args) throws Exception {
        System.load(new File(args[0]).getAbsolutePath());
        System.load(new File(args[1]).getAbsolutePath());
        int framebuffer = createContext();
        RemixAPI api = null;
        LegacyGlInterop interop = null;
        try {
            api = RemixAPI.load(new File(args[2]).getAbsolutePath());
            long hwnd = LegacyGlInterop.currentWindowHandle();
            check(hwnd != 0, "Test context must have an HWND");
            System.out.println("GPU: starting runtime");
            api.startup(StartupInfo.builder().hwnd(hwnd).forceNoVkSwapchain(false).interopImage(true).build());
            interop = new LegacyGlInterop(api.dxvkGetInteropInfo());
            verifyContextOwnership(interop);
            long material = api.createMaterial(MaterialInfo.builder().hash(0x12340001L)
                    .emissiveIntensity(4).emissiveColorConstant(1, 0, 0)
                    .opaqueExt(MaterialInfoOpaqueEXT.builder().albedoConstant(1, 0, 0).build()).build());
            long mesh = createTriangle(api, material);
            long instance = api.sceneInstanceAdd(mesh, Transform.identity().matrix, 0, 1, 0);
            System.out.println("GPU: rendering 320x240");
            renderFrames(api, interop, framebuffer, hwnd, 320, 240);
            verifyColor(320, 240, "interop-color.png");
            System.out.println("GPU: resizing to 400x300");
            resizeContext(400, 300);
            renderFrames(api, interop, framebuffer, hwnd, 400, 300);
            verifyColor(400, 300, "interop-resized.png");
            api.sceneInstanceRemove(instance);
            api.destroyMesh(mesh);
            api.destroyMaterial(material);
            interop.close();
            expectIllegalState(interop::validateContext);
            interop.close();
            System.out.println("PASS: color/depth import, resize, timeline ownership, bidirectional copies, context guards, GL state, and idempotent close");
        } catch (Throwable failure) {
            failure.printStackTrace();
            throw failure;
        } finally {
            try { if (interop != null) interop.close(); }
            finally {
                // The runtime must release its window before its device is destroyed.
                destroyContext();
                System.out.println("GPU: shutting down runtime");
                if (api != null) api.shutdown();
                System.out.println("GPU: shutdown complete");
            }
        }
    }

    private static long createTriangle(RemixAPI api, long material) {
        MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
        surface.vertexBuffer = ByteBuffer.allocateDirect(3 * HardcodedVertex.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        HardcodedVertex.create(-1, -1, 3, 0, 0, -1, 0, 0, 0xFFFFFFFF).writeTo(surface.vertexBuffer);
        HardcodedVertex.create(1, -1, 3, 0, 0, -1, 1, 0, 0xFFFFFFFF).writeTo(surface.vertexBuffer);
        HardcodedVertex.create(0, 1, 3, 0, 0, -1, 0.5f, 1, 0xFFFFFFFF).writeTo(surface.vertexBuffer);
        surface.vertexBuffer.flip();
        surface.indexBuffer = ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN);
        surface.indexBuffer.putInt(0).putInt(1).putInt(2).flip();
        surface.vertexCount = surface.indexCount = 3;
        surface.material = material;
        MeshInfo mesh = new MeshInfo();
        mesh.hash = 0x12340002L;
        mesh.surfaces = new MeshInfoSurfaceTriangles[] {surface};
        return api.createMesh(mesh);
    }

    private static void renderFrames(RemixAPI api, LegacyGlInterop interop, int framebuffer,
                                     long hwnd, int width, int height) {
        CameraInfo camera = CameraInfo.builder().parameterizedExt(CameraInfoParameterizedEXT.builder()
                .aspect((float) width / height).fovYInDegrees(60).nearPlane(0.1f).farPlane(100).build()).build();
        for (int i = 0; i < 30; i++) {
            api.setupCamera(camera);
            api.submit(null);
            InteropInfo info = api.dxvkGetInteropInfo();
            if (i == 27) info.depthImageMemory_Handle = 0;
            long done;
            if (i < 10) {
                done = interop.copyToFramebuffer(info, framebuffer, width, height, true);
            } else if (i == 10) {
                expectIllegalState(() -> interop.acquireToFramebuffer(info, -1, width, height, true));
                check(!interop.hasAcquiredFrame(), "Failed initial copy retained ownership");
                done = interop.releaseAcquired();
            } else {
                if (i == 29) interop.acquire(info);
                else interop.acquireToFramebuffer(info, framebuffer, width, height, true);
                check(interop.hasAcquiredFrame(), "Split copy must retain ownership until release");
                if (i == 27) check(interop.getDepthTexture() == 0, "Absent depth retained a stale GL texture");
                if (i == 28) verifyPrimaryDepth(interop, info);
                expectIllegalState(() -> interop.acquire(info));
                readImageAndVerifyState();
                done = interop.finishFromFramebuffer(framebuffer, width, height, true);
                check(!interop.hasAcquiredFrame(), "Final copy did not release ownership");
                check(done == interop.releaseAcquired(), "Repeated release advanced the timeline");
                expectIllegalState(() -> interop.getDepthTexture());
                expectIllegalState(() -> interop.finishFromFramebuffer(framebuffer, width, height, true));
            }
            PresentInfo present = new PresentInfo();
            present.hwndOverride = hwnd;
            present.skipVulkanSwapchain = 0;
            present.clientDoneSemaphore_ValueToWait = done;
            api.present(present);
            readImageAndVerifyState();
            if (i == 29) check(info.width == width && info.height == height, "Runtime image did not resize");
        }
    }

    private static void verifyContextOwnership(LegacyGlInterop interop) throws InterruptedException {
        interop.validateContext();
        setContextCurrent(false);
        try { expectIllegalState(interop::validateContext); }
        finally { setContextCurrent(true); }
        final Throwable[] result = new Throwable[1];
        Thread other = new Thread(() -> {
            try { interop.validateContext(); }
            catch (Throwable failure) { result[0] = failure; }
        });
        other.start();
        other.join();
        check(result[0] instanceof IllegalStateException, "Another thread accepted the interop session");
        interop.validateContext();
    }

    private static void verifyPrimaryDepth(LegacyGlInterop interop, InteropInfo info) {
        int texture = interop.getDepthTexture();
        check(texture != 0, "Rendered frame must expose primary depth");
        float[] depth = readDepthTexture(texture, info.depthWidth, info.depthHeight);
        float center = depth[(info.depthHeight / 2) * info.depthWidth + info.depthWidth / 2];
        float upper = depth[(int) (info.depthHeight * 0.30f) * info.depthWidth + (int) (info.depthWidth * 0.63f)];
        float lower = depth[(int) (info.depthHeight * 0.70f) * info.depthWidth + (int) (info.depthWidth * 0.63f)];
        float expected = 100f / 99.9f - 10f / (99.9f * 3f);
        check(Math.abs(center - expected) < 0.0001f, "Primary depth disagrees with projection at z=3");
        check(Math.abs(depth[0] - 1f) < 0.0001f && Math.abs(upper - 1f) < 0.0001f
                && Math.abs(lower - expected) < 0.0001f, "Depth misses or image orientation are incorrect");
    }

    private static void verifyColor(int width, int height, String filename) throws Exception {
        byte[] pixels = readImageAndVerifyState();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int redPixels = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                int red = pixels[offset] & 255, green = pixels[offset + 1] & 255, blue = pixels[offset + 2] & 255;
                if (red > 30 && red > green * 1.2 && red > blue * 1.2) redPixels++;
                image.setRGB(x, height - 1 - y, 0xFF000000 | (red << 16) | (green << 8) | blue);
            }
        }
        ImageIO.write(image, "png", new File(filename));
        check(redPixels > 100, "Expected a visible red triangle; red pixels=" + redPixels);
    }

    private static void expectIllegalState(Runnable action) {
        try { action.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected IllegalStateException");
    }

    private static void check(boolean valid, String message) {
        BridgeContractTest.check(valid, message);
    }
}
