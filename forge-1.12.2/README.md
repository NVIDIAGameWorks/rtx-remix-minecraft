# Experimental Forge 1.12.2 renderer

This standalone Java 8 module hosts the shared Remix facade in Forge
14.23.5.2860 on Windows x64. It captures baked terrain models, cutout foliage,
vanilla water and lava, and glass. Terrain updates follow block edits, loaded
chunks, and resource reloads. Entities, particles, sky, and mod-specific renderers
remain outside this experimental renderer.

Vanilla owns the OpenGL window, input, and HUD; Remix owns the final buffer swap.
Fullscreen uses a borderless window while Remix is active. The native
Remix overlay is not integrated. Remix is disabled until `/remix on`; use
`/remix off`, `/remix status`, or assign the toggle in Controls. Anaglyph
and Minecraft post-processing shaders are unsupported.

## Build

Use a 64-bit JDK 8 and the Visual Studio C++ tools described in the root
[getting-started guide](../docs/GETTING_STARTED.md). The root Fabric build still
uses Java 21. Run these commands from this directory with `JAVA_HOME` set to
the JDK 8 directory:

```powershell
.\gradlew.bat build
.\gradlew.bat diagnosticJar
```

JOML 1.10.5 is bundled because the shared facade exposes its quaternion type.
The shared Java sources are compiled directly; there is no private Forge copy.

Stage the published runtime through the root tool, then package here:

```powershell
..\tools\rtx.bat sdk packman --arch win64
.\gradlew.bat packageForge
```

`packageForge` builds the native adapter, verifies the runtime inventory and
licenses, and writes `build/distributions/rtx-remix-forge-0.1.0-win64.jar`.
The ordinary `build/libs` JAR has no runtime payload.

On first launch the packaged JAR extracts `remix-sdk/` and `rtx.conf`
into the game directory. These files are mod-owned and replaced when the
inventory changes. Existing configuration is preserved between equal versions;
missing dependencies are restored. Authored `rtx-remix/` content is untouched.
For development, `-Drtxremix.forge.runtime=<sdk-directory>` uses an already
staged SDK containing the Forge `remixbridge.dll`.

## Run and validate

Create a separate Prism instance with Minecraft 1.12.2, Forge 14.23.5.2860,
and Java 8. Sign in with a Microsoft account that owns Minecraft: Java Edition.
Use a new test world; this module does not yet provide a complete game renderer.

Close the instance before installing or rebuilding its native files:

```powershell
.\scripts\install.ps1 -GameDirectory 'C:\Prism\instances\forge-test\.minecraft' `
  -Package '.\build\distributions\rtx-remix-forge-0.1.0-win64.jar'
```

Launch through signed-in Prism. A successful native initialization and first
copied frame are logged in `logs/latest.log`. Quit normally and verify the
native shutdown message.

For a reproducible rendering check, additionally install
`build/libs/rtx-remix-forge-0.1.0-diagnostic.jar` using `-DiagnosticJar`.
Add `-Drtxremix.forge.diagnostic=true` to that instance's Java arguments.
The separate test mod creates a fresh flat world and renders a green panel
with directional lighting. After 120 frames it logs
`BOOTSTRAP_DIAGNOSTIC`; add `-Drtxremix.forge.diagnostic.exit=true` to quit
automatically. Inspect the panel, resize the window, toggle Remix off and on,
and quit. Remove the diagnostic JAR and arguments after testing.

The diagnostic classes never enter the production or packaged JAR.
Unit tests cover the actual pinned Minecraft shutdown bytecode and verified
runtime extraction; they do not replace the live rendering check.

## Terrain

Capture and native submission run on the render thread. Section replacements
publish together after all pages are uploaded; revisions discard work invalidated
by block edits or chunk replacement. Discovery, capture, upload, and retirement
have separate limits under a cooperative 2 ms frame budget. One model callback
or native call can exceed that budget.

Native meshes default to a 512 MiB ownership limit, including unpublished and
retiring pages. `-Drtxremix.terrainMeshMiB=8` reduces it for pressure tests;
accepted values are 8 through 512. At capacity, nearer sections replace farther
ones, and edits keep priority. A replacement that cannot coexist with its old
mesh can temporarily leave a hole until retirement frees space.

Lighting is fixed daylight. The atlas is a snapshot refreshed on resource reload;
animated texture uploads, PBR maps, world sky/time-of-day lighting, and custom
non-baked block renderers require subsequent integrations. Vanilla's world
traversal remains intact.

The diagnostic JAR also has a terrain mode. Add
`-Drtxremix.forge.diagnostic.terrain=true` alongside the diagnostic arguments
above. In its own disposable world it places and removes a wall, moves beyond
the captured chunk window, reloads resources, and disables Remix. Each phase
waits for actual section publication; a timeout fails the check. Screenshots
are saved as `screenshots/remix-terrain-{placed,removed,streamed,reloaded}.png`.
A successful run logs `TERRAIN_DIAGNOSTIC: PASS`. This check requires render
distance 8 and `-Drtxremix.terrainMeshMiB=8`: it waits for the mesh budget to fill
before testing edits. The default 512 MiB limit will time out in this flat world.
