# Developer Tooling

This directory contains the launch, benchmark, and profiling tools for RTX
Remix for Minecraft. The Gradle build and the packaged mod do not depend on
this directory. The tools launch Minecraft through Prism Launcher, signed in to
a Microsoft account that owns Minecraft: Java Edition.

The launcher and local asset workflows are self-contained. Shipped defaults
pin the demo world, built for this project by
[BlockWorks](https://www.blockworks.uk/), and the art pack, a LabPBR texture
pack created by an art team at NVIDIA, with no graphics preset selected.

## Contributing To The Tools

The tools and guides here work with public services only. They must not
reference private hosts, credentials, CI runners or bundles.
`tests/benchmark/test_public_boundary.py` scans tracked files and commit
messages for the leak patterns it knows, such as hosts and ticket keys; review
covers the rest.

## Recommended Developer First Run

Run commands from the repository root on Windows. A normal clone includes the
Java wrapper and JNI sources tracked at root `rtxremix-java/`; the launcher
stages the separately distributed SDK.

Install [Prism Launcher](https://prismlauncher.org) first; the tools never
download it. Then open its account profile:

```batch
tools\rtx.bat prism signin
```

In the Prism window, sign in on the page Prism shows (or Accounts > Manage
Accounts > Add Microsoft) with the account that owns Minecraft: Java Edition,
and close
Prism. An installed Prism reuses its normal account profile and gives each
checkout a distinct development instance. An official portable Prism uses that
checkout's `run-prism\` profile; see
[Sign In To Prism](../docs/GETTING_STARTED.md#sign-in-to-prism).
Then open the dashboard:

```batch
tools\rtx.bat
```

The launcher combines the developer workflows in one dashboard:

1. Leave **SDK variant** on **release** for normal development.
2. Use **SDK source** to select **Packman** for the published runtime or
   **Local** for a dxvk `_output` working copy.
3. **World** and **Art pack** are on/off toggles, both on by default. On uses
   the configured world and art-pack pins without overriding their versions.
   Off opens the main menu or omits the optional art pack.
4. The shipped configuration pins the demo world and the art pack, so a fresh
   checkout launches into the world with the pack; launch fetches both on first
   use, and starts without the art pack, with a warning, if its download fails.
   Change pins in `tools/config.local.json`. Import your own save with
   `world import`, or use CLI flags for a local art pack. **Custom**, the
   default quality preset, leaves in-game settings in charge.
5. **Launcher** names the account the game will play as, or why it cannot
   launch. Select it to sign in.
6. Choose **Launch game**.

Do not run `gradlew build` or `tools\rtx.bat install` first, or install files
under `%APPDATA%\.minecraft`. `rtx.bat` fetches the selected assets, compiles
the current mod and JNI bridge through Gradle, installs them with the assets
into this checkout's Prism instance and launches that instance through Prism.
For portable Prism its game directory is
`run-prism\instances\rtx-remix-dev\.minecraft`; installed Prism keeps a
checkout-specific instance in its normal profile.

[Launcher](docs/LAUNCHER.md) covers the rest of it: the flags for driving it
without the menu, the mode-to-command mapping, `--dry-run`, and why SDK variant
and SDK source are separate choices.

The first interactive launch compiles Remix shaders asynchronously. A partial
or mostly empty scene can appear temporarily; wait for compilation and terrain
streaming to settle before judging the render. Then run `/rtx status` and look
for `Renderer Initialized: true`, `SDK Available: true`, and nonzero
`Loaded Chunks` and `Retained Pages` once terrain is loaded. These report scene
state, not drawn-mesh counts. `/rtx status` reports initialization, not a native
"all shaders compiled" signal; the settled image is the readiness check.
`Alt+X` opens the Remix menu once the renderer initializes, from gameplay or
over any Minecraft screen.

## Common Commands

```batch
tools\rtx.bat prism signin
tools\rtx.bat doctor
tools\rtx.bat sdk status
tools\rtx.bat run --runs 1
tools\rtx.bat run --world MyWorld --camera benchmark --resource-pack none
tools\rtx.bat open
tools\rtx.bat check
```

The first `run` benchmarks the shipped demo world with its `benchmark` camera;
the second benchmarks a save you imported and recorded a camera for.

- `rtx.bat` with no arguments (or `rtx.bat menu`) opens the terminal launcher for
  normal play and benchmark runs.
- The same CLI exposes SDK sources, worlds, resource packs, launches, benchmarks,
  result comparison, and tooling checks as scriptable subcommands.
- `rtx.bat prism signin` opens the selected Prism profile to sign in to a
  Microsoft account that owns Minecraft: Java Edition.
- `rtx.bat doctor` is a read-only **benchmark** readiness check, including the
  selected world, version 1 camera schema, Java, the launcher and its signed-in
  account, SDK, and Tracy tools. It fails when a launch would be blocked. The
  shipped demo world includes a benchmark camera; record one for your own saves.
  It checks the release runtime unless `--sdk-variant tracy` asks for the
  Tracy runtime and its capture tools.
- `rtx.bat check` runs Ruff format checks and linting over `src/` and `tests/`,
  followed by ty type checks. Add `--tests` to run one pytest suite
  covering the benchmark, profiler, and launcher tests, including the
  public-boundary gate.
  Use the uv-locked gate, not globally installed Ruff or ty;
  `--no-types` deliberately skips type checks.
- `rtx.bat diag hang --pid N` captures `jstack.txt` and a full `native.dmp`
  under `hang-dumps/<yyyyMMdd_HHmmss>/` in the game directory without killing
  the process. The dump holds the game's memory, including your signed-in
  Minecraft session, so do not share it publicly.
  Confirm the PID from the client's command line first. If the PID is absent
  or no longer exists, selection falls back to a Minecraft window owner and
  then the largest `java`/`javaw` process, which may belong to another checkout.
  `jstack.exe` must be beside the target's Java executable.
- `rtx.bat diag focus-torture [--max-cycles N] [--dwell-seconds N]` cycles
  focus against the desktop shell, minimizing and restoring every fifth cycle.
  Defaults are 80 cycles and 3 seconds per focus state. On a hang it suspends
  the client, captures the native dump, resumes it, and collects Java stacks.
  It selects by window title, not checkout, so use it only with one client open.
  Both diagnostics commands accept `--print-output-root` to print the
  `hang-dumps/` root in this checkout's game directory without touching any
  process.
- `rtx.bat install [--game-dir DIR] [--arch win64|arm64]` installs an already-built
  development mod into an existing Fabric installation. It defaults to
  `%APPDATA%\.minecraft` and installs the runtime, JNI bridge, gameplay confs,
  and textures. The baseline materials are built into the mod JAR, not copied
  as a separate pack. It runs `packaging/migrate_resource_packs.ps1` with
  `-LegacyPackName rtx-remix-labpbr` to retire the recognized old baseline,
  preserve user packs and their order, and place `vanilla` then `fabric` below
  them in existing `options.txt`. A migration failure makes installation fail.
  `REMIX_ARCH` overrides host architecture, `REMIX_BUILD` selects a custom
  runtime, and `REMIX_FORCE_SDK_COPY=1` bypasses the runtime copy marker.
  Relative paths use the repository root. It neither builds nor launches
  Minecraft and does not manage Fabric Loader or Fabric API.
- `rtx.bat textures water-normals [--seed N] [--out PATH]` generates
  `water_normals_flipbook.dds` from code, by default under `build/textures/`:
  120 frames of a tiling ocean surface that loops every six seconds, packed
  12 x 10 as BC5 with mips in the layout `BlockTextureManager` plays. A seed
  always produces the same surface. It takes about a minute, and it does not
  publish the result to Packman.

All Python tooling uses one uv-managed project in `tools/`, requiring
Python >=3.15 and uv >=0.13. The benchmark and profiler packages share
`tools/.venv/` and the tracked `tools/uv.lock`; there is no separate profiler
environment. If `uv` is not already installed, `rtx.bat` installs it for the
current user on first use. Direct commands from `tools/` use the same project:

```batch
uv sync
uv run rtx --help
uv run python -m rtx_tools --help
uv run python -m rtx_tools.profiler.jfr_summary <capture.jfr>
```

Python 3.15 defaults to UTF-8 mode. Tool-written JSON and parsed Python/JFR
JSON are explicitly UTF-8. Native Windows console output uses its console
code page, while redirected JVM and Packman text use the system encoding.
Replacement decoding is reserved for informational diagnostics, not structured
profile data.
Update an existing uv installation with `uv self update` when it does not meet
the minimum version.

## Opening And Publishing A Change

Follow [Contributing](../CONTRIBUTING.md) for change submission and review.
Before publishing a change, run `tools\rtx.bat check --tests` with
`PUBLIC_COMMIT_RANGE=main..HEAD` to include the added commit messages in the
boundary check. The GitHub `check` workflow uses this command; `build`
runs `gradlew.bat build test`. Neither workflow runs GPU benchmarks.

A change that depends on a runtime API must wait until the package implementing
it is published. The repository-root
[Runtime And Java API Changes](../AGENTS.md#runtime-and-java-api-changes)
section owns the cross-layer change order and publication requirements.

## Keep The Launch Workflows Separate

| Goal | Use | Instance affected |
|---|---|---|
| Normal development, demo world, or local dxvk testing | `tools\rtx.bat`, through this checkout's Prism instance | Installed Prism's profile, or `run-prism\instances\rtx-remix-dev\.minecraft` for portable Prism |
| Manually test in the regular Microsoft Launcher | `tools\rtx.bat install` after `gradlew.bat build` | `%APPDATA%\.minecraft`, or `--game-dir DIR` |
| Install a produced release | The release archive's `install.bat` | Installer-selected launcher instance |

These are alternatives. Mixing them can leave you inspecting one Minecraft
directory while a different instance is actually running.

## First-Launch Troubleshooting

- If a launch opens Prism and stops with sign-in steps, no Microsoft account
  that owns Minecraft is signed in to the selected Prism profile, or
  `prism.account` names another. Sign in, close Prism, and launch again.
- If a launch reports that Prism is already running on this checkout's data
  folder, close that Prism window and launch again. A launch handed to an open
  Prism would lose the command's settings.
- The first launch in a checkout waits while Prism downloads Minecraft, its
  libraries, and its assets, before the game window appears.
- If a selected map is missing, import your save or select an already staged
  world. Fetching a published fixture requires access to its asset service.
- If the scene has the mod's built-in baseline materials but not the expected
  look, check **Art pack**. **Off (baseline only)** deliberately omits the
  optional art pack. Configure a pin and turn it on, or use a local pack via CLI.
- If `Alt+X` does nothing, wait for initialization and run `/rtx status`. In a
  world, an unavailable renderer reports this in chat. AltGr+X never opens the
  menu, because AltGr arrives as Ctrl+Alt; use the left Alt key.
- If `/rtx status` reports the SDK or renderer unavailable, inspect the
  checkout-specific Prism instance's `logs\latest.log` for
  `RTX Remix renderer initialized successfully!` or a nearby initialization
  error, then run `tools\rtx.bat sdk status`.
- If a rebuilt local dxvk DLL is not in use, run
  `tools\rtx.bat sdk restage` or choose **SDK source** >
  **Re-stage current local build**. Rebuilding `_output` alone does not update
  its staged snapshot.
- If the client exits with -1073741819 shortly after joining, preserve the logs
  and check whether it used a stale `javaw.dxvk-cache` in the game directory.
  The CLI parks that file under `run-state-aside\` there before every launch.
  Move it aside before a controlled retry, but do not treat that exit code
  alone as proof of a cache problem.
- `VK_ERROR_DEVICE_LOST` in `rtx-remix\logs\remix-dxvk.log` in the game
  directory is a native GPU/device failure, not a Java exception. A client can
  remain alive after it.
  Capture evidence before terminating it; see
  [Native crashes](docs/BENCHMARKING.md#native-crashes).
- For an idle-GPU hang without device loss, run
  `tools\rtx.bat diag hang --pid N` while the client still exists. The benchmark
  watchdog captures Java diagnostics but not this full native minidump.
- `[RTX Opacity Micromap] Out of memory budget` is a runtime cache-budget
  warning. Check mesh creation/churn counters, not only retained scene size,
  before changing a budget. The runtime can log it only once per session;
  one line is not an event count. The shipped
  [`rtx.conf`](../packaging/rtx.conf) disables opacity micromaps, so confirm the
  effective setting and runtime revision when investigating it.
- Development launches run `javaw.exe` from a JDK 25 through Prism, which
  refuses other Java versions for Minecraft 26.3: `JAVA_HOME` when it is a
  JDK 25 for the launch's architecture, else the newest such JDK in Gradle's
  toolchains, then the Eclipse Adoptium, Microsoft, and Java folders under
  Program Files. A per-executable driver profile must target that executable,
  not only another Java a player launcher may use. Record the process path
  along with the driver version.

[Development clients](docs/LAUNCHER.md#development-clients) covers PID ownership,
shared-machine safety, background frame limits, and scripted input.
[Runtime telemetry](docs/LAUNCHER.md#runtime-telemetry) explains the distinction
between consent, test transport, and dialog suppression.

## SDK Source Workflow

The SDK **variant** (`release` or `tracy`) and SDK **source** (Packman or a local
dxvk build) are separate choices. Launch and benchmark commands automatically
honor whichever source is staged for the detected Java/host architecture.

```batch
tools\rtx.bat sdk status
tools\rtx.bat sdk local
tools\rtx.bat sdk restage
tools\rtx.bat sdk packman
```

| Command | Purpose |
|---|---|
| `sdk status` | Show the selected source, staging target, and whether a rebuilt local DLL is waiting to be re-staged. Add `--json` for tooling. |
| `sdk local [PATH]` | Stage a local dxvk `_output` directory. `--path PATH` is also accepted. With no path, reuse the recorded source, then configured `sdk.localPath`, then the sibling `../dxvk-remix/_output` checkout. |
| `sdk restage` | Refresh from the source recorded by the last `sdk local` command. This is the normal command after rebuilding dxvk. |
| `sdk packman` | Remove any local override and stage the SDK pinned in `packman/remix-sdk.packman.xml`. Add `--arch all` for both targets, or `--force` to re-fetch an already current junction. |
| `sdk tracy-tools` | Fetch the pinned Tracy capture and CSV-export tools from GitHub without fetching a runtime or assets. |

Add `--arch win64` or `--arch arm64` to the SDK source-management commands
(`status`, `local`, `restage`, or `packman`) when explicit architecture
selection is needed. Otherwise `rtx` follows the Java runtime, then the host
architecture.

To choose another checkout directory once, set `sdk.localPath` in ignored
`tools/config.local.json`. Relative paths resolve from the repository root:

```json
{
    "sdk": {
        "localPath": "../my-runtime/_output"
    }
}
```

Pass `sdk local --path <runtime-dir>` to select a source explicitly. Once
staged, `sdk restage` and pathless `sdk local` reuse its recorded source.

The SDK lives at root `remix-runtime/<arch>/`. Source-management code lives in
`src/rtx_tools/sdk.py`, while the Packman bootstrap and manifest live in root
`packman/`. Gradle fetches a missing SDK directly, preserving local overrides,
and builds the JNI bridge into `build/native/<arch>/`. The runtime stays outside
`build/` because its Packman junction targets shared cache contents.

Local SDK staging is a snapshot copy, not a live link. `sdk status` compares the
source and staged `d3d9.dll` contents, and the `rtx.bat` dashboard shows
`local (refresh available)` when they differ. By default `launch` and `run`
re-stage a changed local build themselves before starting, so a dxvk rebuild is
what gets tested. Turn that off with `rtx.bat sdk auto-restage off` (or the
"SDK auto-restage" row in the dashboard) if your `_output` may still be mid-build
when you launch; the launch then warns and uses the staged copy. The setting is
`sdk.autoRestage`; the command writes it only to ignored `tools\config.local.json`.

## Configuration

The shipped defaults pin the demo world and the art pack and select no graphics
preset. Override them, or choose your own save, in the git-ignored
`tools/config.local.json`. The tracked configuration is not machine-local state.
Make local world and resource-pack pin changes in that ignored file.
SDK auto-restage commands also write only there, leaving tracked defaults unchanged.

Configuration is deep-merged in this order, with later values winning:

1. `tools/config.json`
2. `tools/src/rtx_tools/benchmark/config.json`
3. `tools/config.local.json`, if present
4. The selected world's `config.json`, except its `world` key
5. Explicit CLI selections and overrides, including resource-pack and preset flags

A layer that names a different world or art pack drops the pin keys it would
inherit (`version`, `camera` and `package` for `world`; `version` and `package`
for `resourcePack`), while other keys such as `remote`, `mode` and `stageName`
still merge.

Run-specific environment overrides are applied when preparing a run. Inspect
`run --dry-run` to see the effective configuration.

Three local keys shape every launch. `prism.executable` names the
`prismlauncher.exe` to use instead of the one the tools find, `prism.account`
names the Prism profile to play as when several accounts are signed in, and
`launch.jvmArgs` lists extra JVM arguments for every client the tools launch,
benchmark runs included. See
[Sign In To Prism](../docs/GETTING_STARTED.md#sign-in-to-prism)
and [JVM Arguments](../docs/TUNING.md#jvm-arguments).

```json
{
    "prism": {
        "executable": "<path to prismlauncher.exe>",
        "account": "<profile name>"
    },
    "launch": {
        "jvmArgs": ["-Drtxremix.logVisibleSet=true"]
    }
}
```

World downloads use `world.remote`, art-pack downloads use
`resourcePack.remote`, and Tracy runtime downloads use `sdk.tracyRemote`.
`world.package` and `resourcePack.package` can override derived package names.
Every Packman operation uses the repository's `packman/packman.cmd` bootstrap
and `packman/config.packman.xml` project.

`world versions` and `resourcepack versions` report the configured pin and
locally staged versions only, including an unstaged pin. Both fetch commands
use the configured version or an explicit `--version`; an unpinned name
requires an explicit version. Fetching does not change the pin.
Local saves and CLI-selected local art packs need no package service.
Missing required settings point to `tools/config.local.json`.

The public-boundary gate scans every tracked file except its own module,
including tooling, Packman bootstrap and wrapper sources. Path exceptions
never cover whole directories. The same public rules scan commit messages
when `PUBLIC_COMMIT_RANGE` is set; standard sign-off and review trailers are exempt.

## Documentation

- [Launcher](docs/LAUNCHER.md): the terminal UI over `rtx.bat`, its flags and
  modes, and the SDK variant versus source distinction.
- [Getting started](../docs/GETTING_STARTED.md): building the mod, staging a
  local runtime, and restoring the published SDK.
- [Runtime and Java API changes](../AGENTS.md#runtime-and-java-api-changes):
  updating the runtime, integrated wrapper, and mod together.
- [Camera paths](../docs/CAMERA_PATHS.md): the in-game commands, the playback
  clocks, the JSON schema, and where recordings live.
- [Benchmarking](docs/BENCHMARKING.md): how a run works, commands,
  configuration, artifacts, comparisons, and the stall watchdogs.
- [Benchmark fixtures](docs/BENCHMARK_FIXTURES.md): world and camera fixture
  layout, the pin, per-world config, authoring, and fetching.
- [Profiling](docs/PROFILING.md): Java Flight Recorder and Tracy analysis tools
  in `rtx_tools.profiler`.

## Directory Map

| Path | What it is |
|---|---|
| `src/rtx_tools/` | Python package and CLI entry point, `python -m rtx_tools` |
| `src/rtx_tools/config.py` | Shared configuration loading, merging, and local updates |
| `src/rtx_tools/benchmark/` | Benchmark runner, dashboard, result metadata, and benchmark config |
| `src/rtx_tools/profiler/` | JFR and Tracy analysis modules and `profile-schema.json` |
| `tests/` | One pytest suite for the benchmark, profiler, and launcher |
| `pyproject.toml`, `uv.lock` | Shared Python project configuration and tracked dependency lock |
| `.venv/` | Shared uv-managed Python environment. Git-ignored |
| `rtx.bat` | Primary Windows entry point for `uv run rtx` |
| `src/rtx_tools/launcher.py`, `src/rtx_tools/launcher_console.py` | Developer dashboard over the existing CLI commands |
| `src/rtx_tools/client.py` | The client-launcher interface every launch goes through, and the game directory it owns |
| `src/rtx_tools/prism.py`, `src/rtx_tools/prism_launcher.py` | Prism discovery, installed/portable profile selection, account selection, and the Prism client launcher |
| `config.json` | Shared world, resource-pack, and render defaults |
| `config.local.json` | Ignored local choices and mutable pin/SDK state |
| `docs/` | Benchmark, fixture, profiler, and developer-launcher documentation |
| `src/rtx_tools/diagnostics.py`, `src/rtx_tools/_diag_win32.py` | Hang capture and Win32 focus cycling |
| `results/` | Benchmark manifests, captures, and generated dashboard. Outputs are git-ignored |
| `tracy-tools/` | Downloaded Tracy capture and export binaries. Git-ignored |
| `assets/` | Locally imported or fetched worlds and resource packs in ignored subdirectories; authored screenshot sets under `shots/` |

## Adding Tooling

Anchor paths to the script's own location, never to the caller's working
directory. The existing anchors:

- `src/rtx_tools/benchmark/util.py` exposes `PROJECT_DIR` (repo root) for
  Gradle, `rtxremix-java/`, `packman/`, `remix-runtime/`,
  and `build/native/` targets; `PRISM_DATA_DIR` (`run-prism/`) as the portable
  Prism profile; `RUN_DIR` as the active launcher's game directory;
  `TOOLS_DIR` (`tools/`) for
  shared configuration, assets, results, and the uv project; and `ASSETS_DIR`
  for the shared world and resource-pack stores.
- `src/rtx_tools/diagnostics.py` uses `util.RUN_DIR` for captures under
  `hang-dumps/` in the game directory, independent of the caller's working
  directory.
- `util.project_path` resolves a path a user gives on the command line or in
  configuration, taking a relative one from the repository root.
- Packman operations reuse the bootstrap under root `packman/`. Do not add
  another copy.

Gradle builds and the packaged mod must remain independent of developer
tooling. Keep public boundary checks in
`tests/benchmark/test_public_boundary.py` alongside the tooling tests.
