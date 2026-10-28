# Getting Started (Developers)

How to build and run the mod from source. If you only want to play, read
[Playing](PLAYING.md) instead.

## Prerequisites

- **Windows 10 or 11**, on x64 or ARM64. The JNI bridge is compiled with MSVC,
  so there is no Linux or macOS build.
- **Java 21**, a JDK matching your machine's architecture. Eclipse Adoptium is
  a good choice. Set `JAVA_HOME` to that JDK: the native build reads it to find
  `jni.h`. Prism runs Minecraft 1.21.1 only on Java 21, not a newer version,
  so the developer tools launch the game on `JAVA_HOME` when it is a JDK 21
  and otherwise look for one in Gradle's toolchains and in the Eclipse
  Adoptium, Microsoft, and Java folders under Program Files.
- **Visual Studio 2022 Build Tools** with the Desktop development with C++
  workload (MSVC v143). Cross-building the ARM64 bridge additionally needs the
  toolset pinned in `rtxremix-java/native/build_native.bat` (currently 14.44).
- **Git.** Everything is in the clone except the native runtime and the two
  large runtime textures, which the build pulls through Packman on first use.
- **uv 0.13 or newer and Python 3.15 or newer**, for the developer tools that
  launch the mod. The first `tools\rtx.bat` command installs
  [uv](https://docs.astral.sh/uv/) for your user when it is missing, and uv
  downloads Python 3.15 when no such Python is installed, so that first
  command needs network access.
- **An NVIDIA RTX GPU**, 30-series or newer, to run what you build.
- **Prism Launcher 9.0 or newer, signed in to a Microsoft account that owns
  Minecraft: Java Edition**, to launch what you build. Install it from
  [prismlauncher.org](https://prismlauncher.org); the developer tools never
  download it. [Sign In To Prism](#sign-in-to-prism) covers
  the sign-in.
- **Network access to the pinned RTX Remix runtime** for the first build.
  Gradle fetches it automatically. See [Get A Runtime](#get-a-runtime) for local overrides.

## Get A Runtime

The mod is a renderer front end. It needs the native Remix runtime, which is a
directory containing `d3d9.dll` and its supporting files, and it will not draw
anything without one.

The runtime must match the wrapper's checked-in C API header,
`rtxremix-java/native/include/remix_c.h`. The version that does is the
one pinned in `packman/remix-sdk.packman.xml`. A runtime on another API minor
fails the version check with error code 8, `INCOMPATIBLE_VERSION`. A launch
without the developer preflight reports `Failed to initialize RTX Remix` in
`logs/latest.log` and continues with ordinary Minecraft rendering.

Gradle fetches the pinned runtime through the bootstrap in `packman/` whenever
the selected `remix-runtime/<arch>/` directory is absent. A fresh clone needs no
SDK setup command: run `gradlew.bat build` or `test`, or `tools\rtx.bat launch`,
normally. Gradle needs no Python installation. Packaging fetches only the
architectures being packaged.

Pinned runtimes are published on NVIDIA's public Packman service, so the fetch
needs no credentials. A matching local runtime is an alternative for
development.

A runtime already on disk works too: the `native/remix-sdk-<arch>/` directory
inside a packaged release JAR, an installed game's `remix-sdk/` directory
(which the mod extracts from the JAR on first launch), or your own dxvk-remix
build at a matching version. Stage it for your architecture:

```powershell
.\tools\rtx.bat sdk local --arch win64 --path <runtime dir>
```

Local SDK commands use the [developer tooling](../tools/README.md).
`tools\rtx.bat` installs [uv](https://docs.astral.sh/uv/getting-started/installation/)
if it is missing and manages the shared Python environment (Python >=3.15,
uv >=0.13). The staging command requires `d3d9.dll` in the source directory
and checks its architecture against `--arch`. It copies the runtime into
`remix-runtime/<arch>/`; it does not link to the source. Run
`tools\rtx.bat sdk restage` after rebuilding the source runtime. `package`
rejects a dual-architecture JAR whose runtimes came from different kinds of
source, so stage both architectures the same way.

Without a path, `sdk local` reuses the recorded local source, then
`sdk.localPath` from ignored `tools/config.local.json`, then
`../dxvk-remix/_output`. Configure another checkout directory once, or use
`--path` to select a source explicitly:

```json
{
    "sdk": {
        "localPath": "../my-runtime/_output"
    }
}
```

Relative paths resolve from the mod repository root. For a local dxvk-remix
build, refresh that output first with
`meson install -C <build-dir> --tags output` in the runtime checkout.

Local staging writes a `.local-sdk-source` sentinel. Gradle leaves that override
alone, including when the pinned runtime changes. `tools\rtx.bat sdk status`
reports the source and whether its staged copy needs refreshing.
To return to the pinned runtime, run `.\tools\rtx.bat sdk packman --arch win64`;
use `--arch all` to restore both architectures. Packman staging uses read-only junctions into
its shared cache at `remix-runtime/{win64|arm64}/`. They stay outside
`build/` so Gradle `clean` cannot traverse them. The JNI bridge is built
separately into `build/native/{win64|arm64}/` and combined with the runtime
when staging or packaging; never copy it into a Packman-managed directory.

`sdk status` compares the source and staged `d3d9.dll` for a local override;
it does not prove that the source build succeeded, matches the API, or came
from the intended branch. A failed native build can leave the old `_output`
untouched. Confirm the build and `meson install` completed and that the output
DLL changed before restaging. Gradle does not move an existing Packman
junction when the manifest changes: use `sdk packman` to adopt the new pin.

The dxvk-remix source of the pinned runtime is not public yet, so a matching
runtime cannot be built from public source. Use the pinned package, or a
matching runtime already on disk as described above.

Before building a runtime branch, compare its
`public/include/remix/remix_c.h` with this repository's copied header.
SDK staging checks the DLL architecture, not its API version. While the API
major version is zero, the runtime requires an exact minor match; the patch
component is not compared. Before Prism starts, `tools\rtx.bat launch` uses the
freshly built JNI bridge to initialize the runtime the installer selected,
including a checkout-relative or absolute `REMIX_BUILD`, in a short-lived
process. An interactive launch warns on `INCOMPATIBLE_VERSION` and asks whether
to continue; automated launches warn without waiting for input. Continuing is
useful for coordinated WIP staging, but the renderer will not initialize. The
tools Python process must match the selected architecture; otherwise the
warning explains that the check was unavailable and how to use a matching
Python/uv environment.

For a local runtime iteration, build a single flavor in the dxvk-remix
checkout, then restage and launch from the mod checkout:

```powershell
# In dxvk-remix, after its documented dependency setup:
.\build_dxvk.ps1 -BuildFlavour release -BuildSubDir _Comp64Release -Backend ninja -EnableTracy false
# In this mod checkout, after selecting that output with sdk local:
.\tools\rtx.bat sdk restage
.\tools\rtx.bat launch
```

The runtime's build script installs into `_output` after compiling. Follow
that revision's README for prerequisites and other build targets. Use
`-EnableTracy true` when you need native Tracy capture; choosing a Tracy
variant in the launcher cannot add instrumentation to a local release build.

## Checklist

```powershell
git clone https://github.com/NVIDIAGameWorks/rtx-remix-minecraft.git
cd rtx-remix-minecraft
.\tools\rtx.bat prism signin
.\tools\rtx.bat launch
```

The two large runtime textures are not in git: the first build pulls them
through Packman into `remix-runtime/textures/`, the same way the runtime is
fetched, and later builds reuse the cached copy. The build checks that both
files arrived. The pin lives in
`packman/textures.packman.xml`. The water normals in the package are generated
by `tools\rtx.bat textures water-normals`. The build automatically fetches
`rtx_remix_minecraft_runtime_textures` version `1.1.0` through Packman.

The Java wrapper and JNI bridge are tracked under `rtxremix-java/` and arrive
with the clone; no recursive checkout or hook setup is needed.

`tools\rtx.bat launch` fetches a missing runtime and builds the mod and bridge
with Gradle. It installs the mod, the pinned Fabric API, the runtime,
`rtx.conf`, the gameplay conf payload, and the runtime textures into this
checkout's Prism instance, then starts Minecraft at the main menu through
Prism and waits until the game exits. Prism's own console output stays out of
the terminal; Prism keeps it in its launcher profile's `logs` folder. The
launch never touches `%APPDATA%\.minecraft` or your existing Prism instances,
and it needs no separate build or install step. `tools\rtx.bat` with no
arguments opens the [developer dashboard](../tools/docs/LAUNCHER.md) instead,
which launches into the pinned demo world.

Each launch writes the enabled resource packs in the game directory's
`options.txt`: vanilla, then Fabric's `fabric` pack with the built-in baseline,
then any world packs and the selected art pack. It removes an old generated
`rtx-remix` baseline pack, which it recognizes by its metadata and texture
inventory, leaving other packs alone.

### Sign In To Prism

The developer tools launch Minecraft only through Prism Launcher, signed in to
a Microsoft account that owns Minecraft: Java Edition. They look for
`prismlauncher.exe` in this order: `prism.executable` in ignored
`tools/config.local.json`, the copy the release installer manages under
`%LOCALAPPDATA%\RtxRemixMinecraft\PrismLauncher`, `prismlauncher` on `PATH`,
then `%LOCALAPPDATA%\Programs\PrismLauncher`. A configured path that does not
exist stops the launch rather than falling back. The tools need Prism
Launcher 9.0 or newer, the first release whose `--help` lists `--world` beside
`--profile`, and check for both before a launch.

An installed Prism uses its normal `%APPDATA%\PrismLauncher` profile and
existing Microsoft sign-in. The tools create a distinct `RTX Remix Dev`
instance there for each checkout, identified by the checkout name and a short
path hash; they never modify your existing instances. An official portable
Prism instead keeps its profile and fixed `RTX Remix Dev` instance in this
checkout's ignored `run-prism\` folder. Do not share, copy, or attach either
profile's `accounts.json`; it contains account tokens. Open the selected
profile when you need to sign in:

```powershell
.\tools\rtx.bat prism signin
```

In the Prism window that opens, sign in on the page Prism shows (or choose
Accounts > Manage Accounts > Add Microsoft) with the account that owns
Minecraft: Java Edition, and close Prism. A launch without such an account
opens the same window and stops with these steps. Installed Prism shares that
sign-in across worktrees; a portable Prism profile signs in separately. With
several accounts signed in, set `prism.account` to the profile name to play as:

```json
{
    "prism": {
        "account": "<profile name>"
    }
}
```

The tools read only each account's type, profile name, ownership, and active
flag from Prism's account list, never its tokens. The first launch of a
checkout's instance also downloads Minecraft through Prism, so it takes
longer. Close the selected Prism before launching: an existing process could
take over the launch without the command's settings, so the tools refuse to
start while it is running. `tools\rtx.bat doctor` reports which account a
launch will use, or why it cannot start.

## Verify

In game, run `/rtx status`. It should report `Renderer Initialized: true` and
`SDK Available: true`.
In `logs/latest.log` in the game directory, the exact success message is
`RTX Remix renderer initialized successfully!`; there must be no
`Failed to initialize RTX Remix` message. The similarly worded mod-entrypoint
startup message with a capital `Renderer` is not proof of native initialization.

The baseline PBR textures are built into the mod JAR and load through Fabric's
`fabric` resource pack, below user packs. No separate baseline pack needs
enabling.

The first launch of a new runtime compiles shaders, so expect a partial or empty
scene for the first few minutes.
`/rtx status` does not report completion of native shader compilation. Judge
interactive readiness from the settled image.

## Other Commands

```powershell
.\gradlew.bat build                         # development JAR in build/libs/
.\gradlew.bat test                          # JUnit suites
.\gradlew.bat package -Parch=win64           # installable JAR in build/distributions/
.\gradlew.bat releaseArchive -Parch=win64    # installer bundle ZIP in build/distributions/
.\gradlew.bat stageReleaseAssets             # the four release files in build/release-assets/
```

`.\gradlew.bat compileJava` checks Java compilation without fetching or staging
the runtime or compiling the JNI bridge. It is a useful iteration check, not
validation of native integration.

The JAR from `package` is the one players get: it embeds the runtime, gameplay
conf payload, runtime textures, and baseline PBR textures. To try it in a
normal installation of Minecraft 1.21.1 that already has Fabric Loader and
Fabric API, drop it into the instance's `mods` folder; the mod needs nothing
else to run. The release's installer also adds the RTX Remix Materials pack
and the showcase world, migrates old generated baseline packs, and preserves
user-pack ordering.

Every task that reaches `classes` runs `setupRuntime` first, which compiles the
bridge and stages the runtime. `test` and `build` therefore need a staged
runtime and the C++ toolchain just as `tools\rtx.bat launch` does.
`setupRuntime` also syncs a full copy of the runtime, PDBs included, with the
bridge, `rtx.conf`, the gameplay conf payload, and the runtime textures into
`run/`, the game directory of Gradle's `runClient` task, so every build
refreshes it. The developer tools' Prism launches use `run-prism\` instead.

`build` produces a lightweight JAR that is not installable on its own.
`package` embeds the runtime, `rtx.conf`, the gameplay conf payload, and the
runtime textures.

`verifyPackageJar` rejects Python interpreter and binding payloads because the
runtime uses Python-free OpenUSD. Plain `.py` templates are not interpreters:
`usd/usd/resources/codegenTemplates/__init__.py` is deliberately permitted.
The task also requires every `packaging/licenses/` file in the JAR.

| `package` invocation | Output |
|---|---|
| `package` | Dual-architecture fat JAR. Needs both architectures staged from the same kind of source |
| `package -Parch=win64` | win64 only, skips the arm64 bridge compile |
| `package -Parch=arm64` | arm64 only, useful for cross-building for device testing |

`releaseArchive` builds the installer bundle. It runs `package` and its
`verifyPackageJar` check, then stages the verified JAR under
`build/release/mods/`, the RTX Remix Materials zip under
`build/release/resourcepacks/`, the RTX Remix Showcase World zip under
`build/release/saves/`, and the installer scripts, documentation, and license
notices beside them. It writes that layout as
`rtx-remix-renderer-<version>-installer.zip` in `build/distributions/`. The
same `-Parch` selection applies; omitting it includes both architectures. The
baseline resource pack is built into the mod.

`fetchReleaseContent` pulls the pack and the world through Packman into
`remix-runtime/art-pack/` and `remix-runtime/world/` from the pins in
`tools/config.json`: `resourcePack.package` and `resourcePack.version` for the
pack, `world.name` and `world.version` for the world. Development launches and
benchmarks stage the same pins, so players and developers see the same
content. Because the installer finds the zips by name, `fetchReleaseContent`
refuses a pack version that does not read `1.2.3` or a world version that does
not read `v1.2.3`, its `v` optional. `materialsArchive` and `worldArchive` zip
the two with the files in `packaging/content/`, and `stageReleaseAssets`
collects the four files a release carries, the JAR, both zips, and the bundle,
in `build/release-assets/`. Only these release tasks fetch the pack and world;
`build`, `test`, and `package` never do.

## Tooling And CI

The Python CLI under `tools/` launches the mod and provides local world
import, benchmarks, and JFR/Tracy analysis. Run its checks from the repository
root:

```powershell
.\tools\rtx.bat check --tests
```

This uses the tracked `tools/uv.lock` to run Ruff format checks and linting,
ty type checks, and the pytest suite, including the public-boundary
gate. It does not build or launch Minecraft. Without `--tests`, it runs only
format, lint, and type checks.

The shipped defaults pin the demo world and the art pack and select no
graphics preset, so `.\tools\rtx.bat` launches into the world with the pack;
both download on first launch. If the art pack cannot be downloaded, the launch
warns, names the `.\tools\rtx.bat resourcepack fetch` command that retries it,
and starts without it. Import your own save, or use the
[local fixture workflow](../tools/docs/BENCHMARK_FIXTURES.md#authoring-and-updating)
to record a benchmark camera. Save local choices in ignored
`tools/config.local.json`, not the tracked defaults.

The CLI merges shared defaults, benchmark defaults, ignored local settings,
the selected fixture's configuration, and explicit CLI overrides in that order.
`check --tests` runs the pytest suite and needs no staged assets or runtime.
See [Configuration](../tools/README.md#configuration).

Pull requests and pushes to `main` run the GitHub
[`check`](../.github/workflows/check.yml) and
[`build`](../.github/workflows/build.yml) workflows on Windows.
They run `tools\rtx.bat check --tests` and `gradlew.bat build test`,
respectively, with read-only repository permissions and no secrets. Pull
request checks also scan the added commit messages. These workflows do not
launch a GPU client, run benchmarks, or produce a release archive; run those
locally when they are relevant. The [`release`](../.github/workflows/release.yml)
workflow builds the release files and runs only when a maintainer starts it;
see [Releases](../CONTRIBUTING.md#releases). Runtime fetching has the publication
prerequisite described in [Get A Runtime](#get-a-runtime).

## Choosing A Launch Workflow

| Goal | Use | Minecraft directory |
|---|---|---|
| Iterate on this repository | `tools\rtx.bat launch`, or `tools\rtx.bat` for the developer dashboard, through this checkout's Prism instance | Installed Prism's profile, or `run-prism\instances\rtx-remix-dev\.minecraft` for portable Prism |
| Produce an artifact | `gradlew build`, `gradlew package`, or `gradlew releaseArchive` | None |
| Test inside an existing Microsoft Launcher install | `tools\rtx.bat install` after `gradlew build` | `%APPDATA%\.minecraft`, or `--game-dir DIR` |

`build.gradle` also keeps `runClient`, Fabric's standard Gradle task. It runs
from the repository-local `run/` directory, not the Prism instance, so the two
share no saves, settings, or logs. The developer tools do not use it.

`rtx install` is an alternative to `tools\rtx.bat launch`, not a step before
it. Build, then install from the repository root:

```powershell
.\gradlew.bat build
.\tools\rtx.bat install
```

The CLI bootstrap manages uv and its Python environment. The target installation
must already have a compatible Fabric Loader profile and Fabric API. The command
warns if Fabric API is missing; it does not install Fabric or launch Minecraft.
Add `--game-dir DIR` to select another installation and `--arch win64` or
`--arch arm64` to select its architecture. Relative paths use the repository
root. Without `--arch`, `REMIX_ARCH` wins, then the host architecture.

The command installs the newest non-sources development JAR, the staged runtime
from `remix-runtime/<arch>/`, the matching bridge from `build/native/<arch>/`,
`packaging/rtx.conf`, the `packaging/rtx-remix/` gameplay conf payload, and runtime
textures. Baseline PBR textures are built into the JAR. The command removes its
former generated `rtx-remix-labpbr` pack and orders vanilla and Fabric below
existing user packs. Shipped gameplay confs are refreshed while extra
author-created profiles are preserved. An unchanged runtime is not recopied,
but the bridge and other payloads are always refreshed.

Set `REMIX_BUILD` to install a custom runtime directory containing `d3d9.dll`
instead of the staged runtime. Set `REMIX_FORCE_SDK_COPY=1` to force a full SDK
recopy. Run `.\tools\rtx.bat install --help` for the command reference.

## Profiling

The mod can emit Java phase events to JDK Flight Recorder, and can mirror the
same phases as Tracy zones. JFR works with any runtime. Tracy zones only appear
when the staged runtime was built with Tracy support, because the zones are
submitted through the runtime's own profiler.

`tools\rtx.bat run` records each benchmark run with JFR, Java phases on.
For an interactive client, put the recording arguments in `launch.jvmArgs`, as
[Profiling](../tools/docs/PROFILING.md#manual-capture-beside-another-client)
shows. The individual switches, and the rest of the JVM system properties the
mod reads, are in [Tuning](TUNING.md).

## Viewing A Remix Debug View

`tools\rtx.bat launch` starts Prism with the shell's environment, and Prism
passes it on to the game. Set a debug-view override there instead of editing
`rtx.conf` in the game directory, which every launch replaces, or saving a User
Setting through Alt+X:

```powershell
$env:DXVK_RTX_DEBUG_VIEW_INDEX = 51
.\tools\rtx.bat launch
# Clear the override before the next normal launch.
Remove-Item Env:DXVK_RTX_DEBUG_VIEW_INDEX
```

The runtime reads the override at startup, so changing it needs a relaunch.
The authority for the numbers is `DEBUG_VIEW_*` in the matching dxvk-remix
revision's `src/dxvk/shaders/rtx/utility/debug_view_indices.h`. This mod's CI
cannot detect renumbering there. Useful values in the pinned runtime are:

| Index | View |
|---|---|
| 0 | Normal image |
| 12 | Texture coordinates |
| 15 | Geometry normal |
| 30 | Emissive radiance |
| 51 | `DEBUG_VIEW_VOLUME_PREINTEGRATION`, pre-integrated volumetric in-scatter |
| 277 | Geometry hash, one color per distinct mesh |

For a fog contribution, view 51 shows a light as in-scatter or its absence.
`/weather thunder` selects the densest shipped weather fog. The
[profile authoring guide](../packaging/rtx-remix/mods/minecraft-logic/README.md)
explains which profile owns it. See
[Rendering Diagnostics](RENDERING_DIAGNOSTICS.md) for capture-versus-shading
triage; confirm the indices against the runtime you are actually testing.

## Trying A Runtime Option

The runtime reads `dxvk.conf` from the process working directory, which for
`tools\rtx.bat launch` is the checkout-specific Prism instance's game
directory. Put dxvk-level options such as
`d3d9.*` swapchain settings or `dxvk.enableAftermath` there without rebuilding.
It is the lowest config layer: built-in application defaults and `rtx.conf`
outrank it. An `rtx.*` key already authored in `rtx.conf` can therefore appear
inert in `dxvk.conf`. `DXVK_CONFIG_FILE` redirects the lookup with comma-separated
paths, as `DXVK_RTX_CONFIG_FILE` does for the Remix config.

Neither a launch's install nor benchmark stale-state parking removes
`dxvk.conf`. A forgotten one in the game directory silently affects later
measured runs. Remove the experiment when finished. For a value meant to blend
live, use a gameplay conf layer instead of a User Settings write.

`DXVK_HUD=fps,frametimes`, or `dxvk.hud = fps,frametimes` in that file, draws
a runtime HUD at present time. It helps distinguish an old scene being
presented from a whole frame repeated by the compositor. Disable it for
measurements. To confirm which DLSS libraries a release runtime carries,
inspect its loaded modules or the `nvngx_dlss.dll`, `nvngx_dlssd.dll`,
`nvngx_dlssg.dll` and `nvngx_dlssnr.dll` payloads, not an absent feature
announcement in `latest.log`.

## Working In A Worktree

A new `git worktree add` checkout includes the integrated Java and JNI sources,
but not its ignored runtime directories, saves, or options. Installed Prism
reuses its normal sign-in and gives the worktree a distinct instance. Portable
Prism uses that worktree's ignored `run-prism\` profile, so sign it in before
its first launch. Portable copies can run worktrees side by side; one installed
Prism executable serves one launch at a time. Gradle fetches the runtime that
worktree pins on its first build.
For local native work, run `tools\rtx.bat sdk local --path <absolute output path>`
there; do not copy a runtime from another checkout whose API or pin may differ.
Art packs, saves and player settings also need deliberate staging for a visual
comparison.

Before removing a worktree, stop its client, close its Prism, and run
`gradlew.bat --stop` there so open files do not block removal. Use ordinary
`git worktree remove <path>`; preserve any local saves and uncommitted work
first. If a directory was already removed manually, `git worktree prune`
removes its stale registration.

## When It Does Not Work

| Symptom | Cause |
|---|---|
| `vswhere.exe not found` or `vcvarsall.bat failed` | Visual Studio Build Tools missing, or without the Desktop C++ workload. ARM64 also needs toolset 14.44 |
| `JAVA_HOME is not set`, or `jni.h not found` | `JAVA_HOME` unset or pointing at a JRE rather than a JDK 21 |
| Gradle cannot fetch the SDK | Check network access to the Packman remote, or stage a matching local runtime. See [Get A Runtime](#get-a-runtime) |
| A launch reports that Prism Launcher is not installed | Install it from [prismlauncher.org](https://prismlauncher.org), or point `prism.executable` in `tools/config.local.json` at its `prismlauncher.exe` |
| A launch opens Prism and stops with sign-in steps | No Microsoft account that owns Minecraft is signed in to the selected Prism profile, or `prism.account` names another. See [Sign In To Prism](#sign-in-to-prism) |
| A launch reports that Prism is already running | Close the selected Prism window and launch again |
| A launch reports that Minecraft 1.21.1 in Prism needs a 64-bit JDK 21 | `JAVA_HOME` names another Java version or architecture, and no JDK 21 for this machine is installed where the tools look. Install one and set `JAVA_HOME` to it |
| A JVM option set in `JAVA_TOOL_OPTIONS` has no effect | Prism does not pass it to the game. Use `launch.jvmArgs`; see [JVM Arguments](TUNING.md#jvm-arguments) |
| `Failed to initialize RTX Remix` with error code 8, and the game renders vanilla | The staged runtime predates the API in `rtxremix-java/native/include/remix_c.h`. Stage one of the runtimes listed in [Get A Runtime](#get-a-runtime) |
| `UnsatisfiedLinkError` naming `remixbridge` | The bridge did not compile, or it was compiled for the other architecture. Re-run the build and check that `-Parch` matches the staged runtime |
| Minecraft still uses an old local runtime build | The staged runtime is a copy; run `tools\rtx.bat sdk restage` |
| The build fails at `fetchRuntimeTextures` | Packman could not pull the runtime textures pinned in `packman/textures.packman.xml`. Check access to Packman's download host |
| `releaseArchive` fails at `fetchReleaseContent` | Packman could not pull the pack or world pinned in `tools/config.json`, or the cached copy has no `pack.mcmeta` or `world/level.dat`. Check access to Packman's download host, or delete the folder the message names and build again. A message quoting both pins means a version the installer would not find by name; see [Other Commands](#other-commands) |
| The build reports that the runtime textures are missing files | The shared Packman cache holds the package without its textures. Delete the folder the message names and build again; Packman downloads the package afresh |
| `package` reports mixed sources | Stage both architectures from one kind of source, or package one with `-Parch=` |
| Empty or partial scene on first launch | Shader compilation. Wait for it to settle before reporting a defect |
| Frame-time spikes at high render distance | Garbage collection is one possible cause. Every launch gives the Prism instance the tuned JVM arguments and their heap limit; check that `launch.jvmArgs` does not override them |
| A clone or checkout reports `Filename too long` | Enable `git config --global core.longpaths true` before cloning, or choose a shorter checkout path |
| `package` cannot find the ARM64 toolset | Install the version pinned by `build_native.bat`, or use `package -Parch=win64` for a single-architecture artifact |
| Initialization reports `DRIVER_VERSION_BELOW_MINIMUM` | Update the driver. The minimum is a capability gate; lowering `dxvk.nvidiaMinDriver` can enable unsupported paths and cause GPU faults |
| `Startup` unexpectedly returns `REMIX_DEVICE_WAS_NOT_REGISTERED` after an API change | Check header, bridge and runtime together for a mismatched `remixapi_Interface` layout before continuing native calls |
| `/rtx off` is slower than `/rtx on` | The off path renders vanilla GL at full resolution through the Remix swapchain; the on path can use DLSS's lower internal resolution. The toggle is not a standalone vanilla performance baseline |

For `INCOMPATIBLE_VERSION`, restore the published runtime with
`tools\rtx.bat sdk packman` or stage a build with a matching header, then
rebuild the bridge. The bridge also returns this error if a custom runtime
claims a matching version but lacks a required vtable tail entry; stderr then
says `FATAL: loaded Remix runtime is missing required vtable tail entries`.
An `UnsatisfiedLinkError` after changing a Java native declaration instead
points to a bridge that was not rebuilt or loaded, or the wrong architecture.

For an ARM64 toolset failure, `verifyPackageJar -Parch=win64` checks the
single-architecture package contents without running the tests. The
dual-architecture artifact still needs both toolchains.

## Where To Go Next

- [Contributing](../CONTRIBUTING.md): which repository a change belongs in, the
  rules that bite, code standards, and how a pull request is reviewed. Read it
  before your first change.
- [Tuning](TUNING.md): JVM system properties, Gradle pass-throughs, and the
  files the mod owns in the game directory.
- [In-Game Guide](IN_GAME_GUIDE.md): the `/rtx` command tree and the keys.
- [Camera Paths](CAMERA_PATHS.md): recording and replaying camera routes, which
  is also how before-and-after comparisons are made.
- [Settings](SETTINGS.md): the settings model and persistence guide.
- [Resource Packs](RESOURCE_PACKS.md): the texture and block-light inputs the
  renderer reads.
- [Gameplay-driven profiles](../packaging/rtx-remix/mods/minecraft-logic/README.md):
  authoring conf profiles for zones, biomes, weather, and time of day.
- [Rendering Diagnostics](RENDERING_DIAGNOSTICS.md): lifecycle, terrain
  recapture, material updates, and native scene symptoms.
- `AGENTS.md` in the repository root: the architecture summary and the
  invariants to respect.
