# AGENTS.md: Developer Tooling

The repository-root `AGENTS.md` also applies here. This file adds rules for the
developer tooling under `tools/`.

## Boundary

The benchmark harness, developer launcher, and profiling tools belong here.
Nothing needed by a Gradle build or the packaged mod may depend on this
directory.

The tools and their guides work with public services only. They must not
reference private hosts, credentials, CI runners or bundles.
`tests/benchmark/test_public_boundary.py` scans tracked files and commit
messages for the leak patterns it knows, such as hosts and ticket keys; review
covers the rest. `README.md` owns the directory map and path anchors.

## Source Of Truth

Use `src/rtx_tools/`, root `project-state.json`, the neutral root
`build.gradle`, declared loader modules, and Java instrumentation as behavioral
authority. The guides
organize that contract:

1. `README.md` for entry points and the documentation map
2. `docs/BENCHMARKING.md` for benchmark behavior, counters, and diagnostics
3. `docs/BENCHMARK_FIXTURES.md` for world/camera fixture workflows
4. `docs/LAUNCHER.md` for the dashboard, SDK choices, and client ownership
5. `docs/PROFILING.md` for JFR and Tracy analysis

## Primary Commands

The canonical command list is Common Commands in `README.md`; run everything
from the repository root.

`rtx.bat` with no arguments, or `rtx.bat menu`, opens a thin terminal UI over the
CLI; it must not reimplement SDK, world, resource-pack, or staging logic. Python
tooling is one uv project in `tools/`, requiring Python >=3.15 and uv >=0.13,
with a shared `.venv/` and
tracked `uv.lock`. From `tools/`, use `uv run rtx` or
`uv run python -m rtx_tools` for the same CLI. The package lives under
`src/rtx_tools/`; import it normally rather than editing `sys.path`.

SDK source management is exposed through `rtx.bat sdk status|local|restage|packman`.
Keep source detection, sentinel parsing, local-copy freshness checks, snapshot
copying, and explicit Packman restores in `src/rtx_tools/sdk.py`. Gradle fetches
missing runtime directories itself, without depending on the Python tooling.

Every client launch goes through the client launcher that `client.active()` in
`src/rtx_tools/client.py` returns, which owns the game directory
(`util.RUN_DIR`). The client launcher is `PrismLauncher` in
`src/rtx_tools/prism_launcher.py`: it builds and installs into a
checkout-specific Prism instance and starts the game through Prism as a
signed-in Microsoft account that owns Minecraft. Installed Prism uses its
normal account profile; portable Prism uses `run-prism/`. `rtx.bat prism
signin` opens that profile, and the dashboard's Launcher row shows the
launcher's description. There is no player-name option. Besides the root
`AGENTS.md` launch invariant, never download Prism, and read only an account's
type, profile name, ownership, and active flag from `accounts.json`, never its
tokens.

Loader selection is one launch concern, not a separate workflow. The typed
registry reads the `project-state.json` loader mapping; CLI choices, dashboard,
Gradle tasks, Prism components, installed JARs, companion dependencies, and
built-in pack names come from it. Keep provider hooks narrow: Fabric companion
API handling and Forge JarJar selection are genuine differences, while
NeoForge uses its ordinary module JAR and selection and staging flow are
catalog behavior. Forge development launches must build a fresh JarJar
artifact; never select a stale thin JAR in place of its `-all.jar`.

`launch`, benchmark `run`, and development `install` select a Minecraft version
through an explicit tooling parameter where offered, then
`RTXREMIX_MINECRAFT_VERSION`, then the manifest default. They accept a loader ID
declared by that version and default through the environment and merged
configuration to the version's manifest default. Every Gradle invocation passes
both target properties and uses that loader's declared tasks.

`rtx.bat install` stages an already-built development mod into an existing
game directory for a declared loader, separate from the repository-local launch workflow.
Keep its runtime/bridge architecture selection, unchanged-SDK marker, copies of
`rtx.conf`, the conf payload and the runtime textures, and author-created file
preservation in `src/rtx_tools/install.py`.
It must not build, launch Minecraft, or install loader dependencies.

After editing Python tooling, run `tools\rtx.bat check`. It runs Ruff format
checks and linting over `src/` and `tests/`, followed by ty type checks.
Before pushing, run `tools\rtx.bat check --tests` to add one pytest
invocation covering the benchmark, profiler, launcher, and public-boundary tests.

The gate uses the shared `uv.lock`; globally installed Ruff/ty and hand-run
unit tests are not equivalent. `--no-types` skips ty explicitly. In typed
Python tests, narrow an optional with `assert value is not None` before
subscripting it; `self.assertIsNotNone` does not narrow. Cast a lightweight
process double to the declared `subprocess.Popen` type in the test rather than
widening a production signature to accommodate the double.

Version-catalog reporting is read-only. It validates and reports every
Minecraft version and loader declared by the current checkout. Target status
reports proportionate build, artifact, semantic-fixture, and live evidence for
one selected version and all of its loaders without rewriting source.

Identify development clients by command line and PID, never only window title,
and run at most one client per checkout's game directory. The benchmark runner's
`kill_leftovers()` is machine-wide, not checkout-scoped. Read
`docs/LAUNCHER.md` Development Clients before working on a shared machine.

`rtx.bat diag hang` captures Java and native stacks without killing the target.
`rtx.bat diag focus-torture` cycles focus and minimize/restore, suspends a hung
client for its native dump, then resumes it before running the target JDK's
`jstack.exe`. Both commands write under `hang-dumps/` in the game directory;
`--print-output-root` prints that checkout-anchored path without discovering or
touching a process.

## Sibling Checkouts And SDK Staging

The repository-root `AGENTS.md` and `docs/GETTING_STARTED.md` document the
default path: Gradle fetches missing runtimes from the pinned Packman manifest,
and the Python CLI stages or refreshes a local override when requested.

In a sibling checkout layout, `../dxvk-remix` is the native runtime and SDK
producer. The Java wrapper and JNI bridge are tracked here under root
`rtxremix-java/`.

`rtx.bat sdk local` with no path reuses the recorded local source, then
`sdk.localPath` from ignored `config.local.json`, then `..\dxvk-remix\_output`.
Relative paths resolve from the repository root. Use that local setting to
choose another checkout once, or pass `--path` explicitly.

A runtime reaches root `remix-runtime/<arch>/` one of two ways.
`rtx.bat sdk local --arch <arch> --path <dir>` copies a local build in and writes
a `.local-sdk-source` sentinel; `rtx.bat sdk packman --arch <arch>` restores the
Packman-managed package as a junction into the shared cache.
Gradle fetches a missing runtime automatically but never replaces an existing
directory or local override. After changing `packman/remix-sdk.packman.xml`,
use `sdk packman` to move an existing junction to the new pin.

The root build reads the sentinel, and every loader's dual-architecture
`package` rejects one architecture as a local copy and the other as
Packman-managed. Stage both the same way, or build a single architecture with
`-Parch=`.

## Shared Configuration And Assets

- `config.json` owns shared defaults: the demo world and art pack are pinned,
  with no graphics preset selected.
- Configuration deep-merges in order: `config.json`,
  `src/rtx_tools/benchmark/config.json`, ignored `config.local.json`, selected
  `assets/worlds/<world>_<version>/config.json`, then explicit CLI overrides.
  The fixture's `world` key is ignored. A layer that names a different world
  or art pack drops the pin keys it would inherit (`version`, `camera` and
  `package` for `world`; `version` and `package` for `resourcePack`), while
  other keys such as `remote`, `mode` and `stageName` still merge.
- Resource-pack and preset CLI overrides are reapplied after the fixture merge.
  Run flags and environment overrides have separate run-preparation paths.
  Use `run --dry-run` to inspect effective config.
- `rtx_tools.config` owns shared loading, layer merging with that pin rule,
  required-setting checks, and local updates. Import its helpers rather than
  duplicating this logic.
- Change the world or art-pack pin by editing ignored `config.local.json`; no
  command rewrites pins. Commands that change a setting, such as SDK
  auto-restage, write only to `config.local.json`, never tracked defaults.

Worlds and art packs, imported locally or fetched, are ignored by git:

- `assets/worlds/<world>_<version>/`
- `assets/resourcepacks/<name>_<version>/`

A configured benchmark world pin is reproducible and version-qualified. `--ci`
requires a pin; local runs can use imported unversioned worlds. Packman downloads
always use root `packman/config.packman.xml` and concrete versions.
`world versions` and `resourcepack versions` report the configured pin and local
staged versions only.

Fixture cameras must use the strict version 1 schema documented in
`../docs/CAMERA_PATHS.md`; the Camera paths paragraph in the root `AGENTS.md`
is authoritative for playback behavior. Two fixture-side rules are this
file's own: `rtx.bat camera list` inventories filenames but does not validate
schemas, and benchmark staging uses the exact versioned fixture directory as
`cameraStorageKey`.

If a fixture requires entity sanitization, configure
`worldSanitizer.stripEntitiesOnStage`. The sanitizer may alter only the staged
copy under the game directory's `saves/`; it must never mutate the source
fixture under `assets/`.

## Profiling

`src/rtx_tools/profiler/` owns the JFR and Tracy analysis modules. Use the shared
uv environment, start with `rtx_tools.profiler.jfr_summary`, then choose the
phase, allocation, GC, lock, thread, or comparison tool in `docs/PROFILING.md`.
A sampled hot line may be waiting on memory it dereferences rather than spending
time on the arithmetic it displays.

A flat timing aggregate is not enough: compare scene counters, workload inputs,
and matched screenshots before claiming a performance win. Long counter recipes,
frame/section semantics, warmup limitations, and capture-health diagnosis belong
in `docs/BENCHMARKING.md` and `docs/PROFILING.md`, not in this file.

Benchmark manifests and per-run JFR/Tracy artifacts live in `results/<timestamp>/`.
The generated dashboard and catalog must remain usable directly from disk.

Machine-health expectations come from `machine.runner_expectations()`:
`RTXREMIX_EXPECT_POWER_PLAN` and `RTXREMIX_EXPECT_LOGICAL_PROCESSORS`.
An absent variable disables its corresponding check. Keep host-specific
expectations outside tracked configuration and code.

Tracked profile-data changes require coordinated updates to:

1. `src/rtx_tools/profiler/profile-schema.json`
2. the relevant generators under `src/rtx_tools/profiler/`
3. aggregation logic when the field is merged across runs
4. `src/rtx_tools/benchmark/catalog.py` and `src/rtx_tools/benchmark/dashboard.py`
   when it is displayed

Benchmark runs default to the release runtime and record JFR only;
`--sdk-variant tracy` adds native Tracy capture and requires a Tracy-enabled
runtime. Keep runtime instrumentation, the protocol pin in
`src/rtx_tools/benchmark/tracy.py`, and `TRACY_NO_SYSTEM_TRACING` aligned.
Tracy capture starts before Minecraft and runs until the process exits; do not
attach only after warmup. Java spans mirrored into Tracy must close
inside their parent zone, while phases that cross existing zones remain JFR-only.

## Runtime And Wrapper Integration

The repository-root
[Runtime And Java API Changes](../AGENTS.md#runtime-and-java-api-changes)
section owns cross-layer change order, publication requirements, and the
coordinated runtime, wrapper, and mod updates.

When native packaging behavior changes, verify normal build artifacts,
benchmark SDK staging, and both target architectures.

## JVM Argument Copies

`../docs/TUNING.md` documents the unconditional tuning arguments: the initial
`vmArgs` in the `client` run configuration of
`versions/1.21.1/fabric/build.gradle` and
`RECOMMENDED_JVM_ARGS` in `packaging/installer.ps1`. Keep those two copies in sync.
When `RECOMMENDED_JVM_ARGS` changes, also append its outgoing value to
`PREVIOUS_RECOMMENDED_JVM_ARGS` beside it, so that upgrades replace what earlier
installers wrote. `tests/benchmark/test_installer_prism_jvm.py` checks that list
against the git history and skips in a shallow clone.
The developer tools read `RECOMMENDED_JVM_ARGS` for every Prism launch, then
add `launch.jvmArgs` from the tools configuration. This invariant excludes
opt-in benchmark/profiling arguments, including the conditional
`benchmarkJfrPath` recording, Java-phase/Tracy switches, `launch.jvmArgs`, and
experiment-specific properties. Those are not player-launch defaults.
