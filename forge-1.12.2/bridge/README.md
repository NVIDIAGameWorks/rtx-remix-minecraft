# Legacy OpenGL interop

`LegacyGlInterop` bridges Remix external memory and timeline semaphores into a
current Windows OpenGL context. It uses WGL entry points so clients using LWJGL 2
can share images without adding another Java OpenGL binding. It has no Minecraft
or Forge dependency. The facade, JNI implementation, and C API header remain in
repository-root `rtxremix-java/`.

## Build and check

Run from the repository root with Java 8 and Visual Studio x64 C++ tools:

```powershell
.\forge-1.12.2\scripts\build-bridge.ps1 -JdkPath C:\path\to\jdk8
.\forge-1.12.2\scripts\build-bridge.ps1 -JdkPath C:\path\to\jdk8 -RuntimeDirectory .\remix-runtime\win64 -GpuSmoke
```

`JAVA_HOME` supplies the default JDK. The build downloads JOML 1.10.5 from Maven
Central and checks its SHA-256; `-JomlJar` selects a local copy. JOML remains a
separate runtime dependency. Outputs under `bridge/build/` are
`libs/rtxremix-java8.jar` and `native/win64/remixbridge.dll`.

The default command needs no runtime or GPU. It checks Java/C API constants and
buffer layouts, compiles the native implementation against `javac -h` headers,
and loads the DLL to exercise JNI allocation and missing-context guards under
Java 8. `-RuntimeDirectory` also checks runtime API negotiation.

`-GpuSmoke` uses the pinned runtime and a nonactivating offscreen WGL window. It
renders a known emissive triangle, verifies color and primary depth, resizes the
shared images, and checks bidirectional copies, ownership guards, optional depth,
GL-state restoration, and shutdown. Readback images are written under
`bridge/build/gpu-smoke/`. It does not launch Minecraft or replace an in-game
visual check. Runtime packages and cache junctions are read-only inputs.

## Frame ownership

Load the bridge through `RemixAPI`, start the runtime with `interopImage=true`,
and construct one adapter from `dxvkGetInteropInfo()`. Every adapter call belongs
to its creating render thread with the original GL context current.

After `submit`, pass the latest `InteropInfo` to `acquireToFramebuffer`. This
waits for the runtime timeline, imports replaced color/depth images, and copies
color into the client FBO. Draw client overlays, then call
`finishFromFramebuffer` to copy the final color back and signal completion. Pass
that returned value to `PresentInfo.clientDoneSemaphore_ValueToWait`. For
runtime-owned presentation, leave `skipVulkanSwapchain=0` and supply the current
window handle. If both copies use `flipY=true`, leave `interopFlipY=0`.

While acquired, `getDepthTexture` borrows a read-only `GL_R32F` texture; zero
means no optional depth image. Its orientation matches the unflipped native
color, and its dimensions are supplied separately in `InteropInfo`. Never retain
or sample it after release. The adapter does not overwrite the client depth
attachment.

`acquire` waits without replacing client color. `releaseAcquired` releases an
aborted frame without copying and is idempotent. `copyToFramebuffer` combines
acquire and release for clients with their own swapchain; use
`forceNoVkSwapchain=true` and `skipVulkanSwapchain=1` in that mode. It cannot
include a runtime menu drawn during presentation. The pinned runtime does not
resize its interop images with the window in this mode; use runtime-owned
presentation for resize support.

Framebuffer bindings, texture binding, scissor, and sRGB state are restored.
Keep the adapter alive across render toggles: a new adapter would restart its
counter against the same runtime semaphore. Close it before destroying its GL
context, then shut down the runtime.

## Distribution

The JAR includes this repository's license and notices. JOML is not bundled.
Applications distributing the runtime must also include its original licenses
and notices. No Minecraft, Forge, JDK, or runtime binaries are included here.
