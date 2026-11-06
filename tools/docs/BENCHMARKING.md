# Benchmarking

Reproducible benchmark runs measure the performance impact of code changes and
produce timing, JFR, Tracy, and dashboard artifacts.

Fixtures (worlds, cameras, per-world config, fetching) have their own guide:
[Benchmark Fixtures](BENCHMARK_FIXTURES.md).

## Quick Start

The shipped configuration pins a demo world with a recorded `benchmark`
camera. One run on it fetches the world and the art pack on first use:

```batch
tools\rtx.bat run --runs 1
tools\rtx.bat open
```

To benchmark your own save, import it and record a camera as described in
[Authoring and updating](BENCHMARK_FIXTURES.md#authoring-and-updating), then
name it on the command line:

```batch
tools\rtx.bat run --world MyWorld --version v0.1.0 --camera benchmark --resource-pack none
```

`tools\rtx.bat` manages worlds and resource packs, launches the game, and runs
benchmarks. These examples do not require access to a fixture catalog. From `tools/`,
`uv run rtx` or `uv run python -m rtx_tools` invokes the same CLI. The shared
project requires Python >=3.15 and uv >=0.13 and uses `tools/.venv/` with the
tracked `tools/uv.lock`.

No quality preset is selected, and both examples use the release runtime,
which records JFR only. Select a preset explicitly for comparisons.
`--sdk-variant tracy` adds native Tracy capture and requires a Tracy-enabled
local runtime or access to the configured runtime download service.

`run` builds the checkout, stages the world and camera into the game directory
of this checkout's Prism instance (`<gameDir>` below), launches Minecraft
through Prism, captures the benchmark,
and writes artifacts under `tools/results/<timestamp>/`. The game plays as the
Microsoft account selected in that Prism profile; use
`tools\rtx.bat prism signin` if needed. See
[Sign In To Prism](../../docs/GETTING_STARTED.md#sign-in-to-prism).

## How A Run Works

A benchmark is two processes with a small file handshake between them. Most
confusion about this harness comes from not knowing which side owns what.

| Python runner (`tools/src/rtx_tools/benchmark/`) | Mod (`BenchmarkController`) |
|---|---|
| Resolves config, fetches the SDK, builds | Detects the trigger, waits for the world |
| Stages the world and camera fixture into `<gameDir>` | Warmup, recording, camera sampling |
| Launches Minecraft and watches it | Writes status heartbeats and the result |
| Watchdogs, artifact collection, aggregation | Screenshots, and the exit after results |

One measured run, repeated `runs` times:

1. **Prepare.** The runner clears `<gameDir>/benchmark/results/`, stages the
   pinned fixture (world save, `cameras/`, resource packs) into `<gameDir>`,
   and writes the render settings into `<gameDir>/options.txt`.
2. **Arm.** It writes `<gameDir>/benchmark/auto_benchmark.json`: the mode, camera
   name, warmup and record frame counts, screenshot schedule, and whether the
   client should exit when finished. `benchmarkWorld` is required; the mod
   rejects a trigger without it rather than guessing a world.
   `cameraCanonicalDirectory` is the absolute path of the fixture's `cameras/`
   directory; the runner always writes it, and the mod treats it as optional.
3. **Launch.** It writes the benchmark's JVM arguments into the Prism
   instance, starts Prism with that instance ID, account profile, and
   `rtx-benchmark` world, and follows the game's JVM, plus a Tracy capture
   client when the SDK is Tracy-enabled. Portable Prism also receives
   `--dir <checkout>\run-prism`; installed Prism uses its normal profile.
4. **Trigger.** In game, `BenchmarkController` sees the armed trigger from its
   tick loop and waits for vanilla QuickPlay to finish loading the world. This
   is why a client left open from an earlier `world launch` breaks the next run:
   it consumes the trigger first.
5. **Warm up.** On world load the controller enters `WARMUP` and writes its
   first status. It holds the frame counter at 0 while the world-load screen is
   still up, so recording cannot begin on a loading screen, then counts
   `warmupFrames` rendered frames. `JoinBurstHold` owns the bounded loading-screen
   hold; it hides the initial join burst, not every later streaming cost.
6. **Record.** In `RECORDING` it samples the camera path at its own virtual
   60 Hz index (`round(i * 1000 / 60)` ms, clamped to the path duration),
   captures per-frame timings and counters, and takes the scheduled screenshots.
   The interactive playback clocks are not involved. The frame duration covers
   the whole `MinecraftClient.render()` span, recorded as
   `metadata.frameTimeSource`; see
   [Reading frame metrics, sections and zones](#reading-frame-metrics-sections-and-zones).
7. **Report.** It writes `benchmark.json` into `<gameDir>/benchmark/results/`, then
   exits after `postResultExitDelaySeconds` so profilers can drain.
8. **Collect.** The runner has been polling for that file throughout. It copies
   the result, logs, profiles and screenshots into
   `tools/results/<timestamp>/run_N/`, generates
   `profile-data.json`, aggregates across runs, and writes `manifest.json`.

Three files are the entire contract between the two sides:

| File | Direction | Meaning |
|---|---|---|
| `<gameDir>/benchmark/auto_benchmark.json` | runner to mod | Run this benchmark, with these parameters |
| `<gameDir>/benchmark/results/benchmark_status.json` | mod to runner | Where I am now: state, phase, frame, terrain counters |
| `<gameDir>/benchmark/results/benchmark.json` | mod to runner | Finished, here are the numbers |

While polling, the runner watches for four outcomes: the result file appears, a
fatal error is classified out of the log, the status stops progressing (see
[Stall Watchdogs](#stall-watchdogs)), or the process exits. Whichever happens
first decides the run.

## Launcher

`tools\rtx.bat` with no arguments opens a dashboard that can start a benchmark. See
[Launcher](LAUNCHER.md).

## Commands

| Command | Purpose |
|---|---|
| `run [--world W] [--camera C] [--runs N]` | One local run by default; `--ci` uses configured `runs`; `--runs N` overrides either |
| `run --dry-run` | Resolve the plan without fetching, building, staging, or launching |
| `run --ci` | CI run counts and Tracy drain behavior |
| `run --resource-pack SPEC` | Override the pack pin for one run |
| `run --strip-entities` | Drop saved entities from the staged copy |
| `launch` / `world launch <world>` | Start Minecraft for interactive play |
| `prism signin` | Open the selected Prism profile to sign in to a Microsoft account that owns Minecraft |
| `world list` / `versions` / `fetch` / `import` / `update` | Fixture management, see [Benchmark Fixtures](BENCHMARK_FIXTURES.md) |
| `camera list <world>` | Filename inventory, not schema validation |
| `screenshots list` / `refresh [NAME ...]` | Recapture the documentation screenshots from the marked still shots |
| `resourcepack fetch` / `versions` | Art-pack downloads and pinned/local version inventory |
| `sdk status` / `local [--path PATH]` / `restage` / `packman` | SDK source selection |
| `doctor [--json]` | Validate config, world, camera, Java arch, the launcher and its sign-in, SDK, and with `--sdk-variant tracy` the Tracy tools |
| `list` / `show` / `compare` / `open` / `catalog` | Inspect results |
| `annotate <run> --tag T --note "..."` | Label a result |
| `prune --older-than 14d` / `prune --keep 1` | Preview results to remove; add `--yes` to delete them |
| `delete <run>` | Delete the specified result |
| `check [--fix] [--tests]` | Format, lint, and type-check the shared Python tooling project |

`doctor` and `run --dry-run` are read-only: neither fetches SDKs, stages worlds,
opens Prism, launches Minecraft, nor creates a result directory. `doctor` fails
when a launch would be blocked, for example with no owning account signed in to
the selected Prism profile. `check` runs Ruff format
checks and linting over `src/` and `tests/`, followed by ty type checks.
`--tests` adds one pytest suite covering the benchmark, profiler,
launcher, and public-boundary tests.
Use the uv-locked gate, not global Ruff/ty installations. `--no-types` explicitly
skips ty; `--fix` writes formatting and safe lint fixes.

`run`, `launch`, and `world launch` refuse to start when a client from this
checkout is still running. It can consume the new trigger or hold
`<gameDir>/saves/rtx-benchmark/session.lock`, otherwise causing a late
`ProcessExited`. `BENCHMARK_SKIP_LIVE_CLIENT_CHECK=1` bypasses only that
preflight.

Before staging, they also check the launcher. With no Microsoft account that
owns Minecraft signed in to the selected Prism profile, they open Prism for
sign-in and stop; `run --ci` stops without opening a window. They also stop
while that Prism profile is running, which could take the launch over without
its settings. See
[Development clients](LAUNCHER.md#development-clients).

**The benchmark cleanup is machine-wide.** `runner.kill_leftovers()` calls
`taskkill /f /im` for every `java.exe`, `javaw.exe`, and `capture.exe` before
launches, after runs, and on exit. Other clients, including another worktree's
game, IDE JVMs, and Gradle daemons can be killed without warning. The
checkout-scoped preflight does not limit this, and skipping it does not disable
cleanup. Wait until the machine is free,
or use [manual profiling](PROFILING.md#manual-capture-beside-another-client).
Interactive `launch` and `world launch` do not perform that cleanup.

Every CLI launch also parks `javaw.dxvk-cache`, `java.dxvk-cache`,
`nrc_session_log.txt`, and `metrics.txt` from `<gameDir>` under
`<gameDir>/run-state-aside/<timestamp>/` before starting the game.
`STALE_RUNTIME_STATE_FILES` and `RUN_STATE_ASIDE_KEEP` in `runner.py` own the
inventory and retention (currently five sets). This protects against
runtime builds that crash when reusing stale state. Preserve the old logs when
diagnosing an exit; -1073741819 alone does not identify its cause.

The SDK architecture follows the detected Java/host architecture; set
`BENCHMARK_ARCH=arm64` only to override detection deliberately.

### Recipes

First run after staging a local fixture and camera:

```batch
tools\rtx.bat doctor --world MyWorld --camera benchmark
tools\rtx.bat run --world MyWorld --version v0.1.0 --camera benchmark --resource-pack none --dry-run
tools\rtx.bat run --world MyWorld --version v0.1.0 --camera benchmark --resource-pack none --runs 1 --open
```

A second checkout has its own ignored assets. Copy complete directories under
`tools/assets/worlds/` and `tools/assets/resourcepacks/`, or import your own
save, rather than assuming Git supplies them. Tracy tools are downloaded
separately. Check [Worktrees](LAUNCHER.md#worktrees) for SDK staging.

`doctor` has no `--version` or `--resource-pack` option: it probes the configured
asset selection and selected local world. Put the intended fixture pin in
ignored `tools/config.local.json` when several local versions exist, and set
`resourcePack.mode` to `none` for a run without the art pack. Do not edit tracked
defaults for local choices. `run --dry-run` accepts the explicit version/pack
overrides above.

Benchmark a local dxvk build by staging its `_output` snapshot first. `run`
refreshes a changed local source by default, controlled by `sdk.autoRestage`;
`tools\rtx.bat sdk auto-restage off` warns and keeps the staged copy instead.
There is no per-run restage-disable option. See the
[SDK source workflow](../README.md#sdk-source-workflow) and
[launcher source/variant rules](LAUNCHER.md#sdk-variant-and-source). A local
override serves both variant requests, and selecting release does not remove
Tracy instrumentation already compiled into that DLL.

Local before/after comparison:

```batch
tools\rtx.bat run --world MyWorld --version v0.1.0 --camera benchmark --resource-pack none --preset high --runs 3 --tag local --note "before terrain queue change"
tools\rtx.bat run --world MyWorld --version v0.1.0 --camera benchmark --resource-pack none --preset high --runs 3 --tag local --note "after terrain queue change"
tools\rtx.bat open
```

`open` lets you pick the two runs in the dashboard without knowing their ids.
See [Comparing Runs](#comparing-runs) for the terminal equivalent.

One run can guide iteration on a large section-level change, but cannot measure
between-run drift. Use at least three runs per arm before quoting a frame-time
or tail improvement, and compare the changed section against its own spread.
Do not turn a single `avgFps` or p99 difference into a claim with a caveat.
`catalog.frame_resolution` derives `summary.resolution` from between-run means
when there are at least two runs; otherwise it uses within-run batch means and
sets `lowerBound`, because thermal/session drift is unobserved. The reported
95% interval is an estimate, not a guarantee of significance.

A result's `manifest.json` and the dashboard record the machine's host name
and your Windows user name, a locally staged runtime adds who staged it
(`stagedBy`), and the JVM crash reports (`hs_err_pid*.log`) that
`runClient.log` can include carry your user name, so remove these before you
post a result.

These recipes use JFR-only measurement on the release runtime, which `run`,
`doctor`, and interactive launches use by default. For native Tracy capture,
first stage a Tracy-enabled local SDK or configure a deployment that supplies
one, then select `--sdk-variant tracy`. Do not compare those variants against
each other. The A/B example explicitly chooses `high`; the shipped
configuration selects no preset.

`--ci` requires a configured world pin and selects configured run counts and
longer profiler drain behavior. It is an unattended-run option, not access to
a CI service; the GitHub workflows do not run GPU benchmarks. The flag
does not make a machine or workload comparable by itself.

## Unattended And Scripted Runs

- Invoke the intended checkout's `tools\rtx.bat` and `gradlew.bat`. The CLI's
  `benchmark/util.py` anchors configuration, staging, assets, and results to its
  own location, not the caller's current directory.
- Reserve the machine for `run`: its cleanup kills unrelated Java processes.
- Pass `--runs` explicitly for quoted results. The absolute poll deadline still
  applies even when status advances; see [Stall watchdogs](#stall-watchdogs).
- `warmupFrames` counts rendered frames, not completed terrain streaming.
  Select a warmup strategy deliberately; see
  [Environment overrides](#environment-overrides).
- Put experiment JVM flags and system properties in `launch.jvmArgs` in
  `tools/config.local.json`; `JAVA_TOOL_OPTIONS` does not reach a game Prism
  launches. Vary mod settings through `render.modSettings` and Remix settings
  through the preset or `RTXREMIX_EXTRA_RTXCONF`, rather than assuming chat
  toggles have JVM aliases.
- Camera `record` only arms the `R` key. For unattended route creation, author
  strict version 1 JSON in the fixture's `cameras/` directory; the runner
  validates it before SDK/build/launch work.
- A manual client recording through `launch.jvmArgs` needs an explicit JFR dump.
  See [Profiling](PROFILING.md#manual-capture-beside-another-client).

## Configuration

Configuration is deep-merged in this order, with later values winning:

1. `tools/config.json`: world and art-pack pins and render defaults. No
   graphics preset is selected.
2. `tools/src/rtx_tools/benchmark/config.json`: benchmark run count, profiling,
   and screenshots.
3. Git-ignored `tools/config.local.json`, if present.
4. The selected fixture's `config.json`. Its `world` key is ignored, so it
   cannot redirect world selection.
5. Explicit CLI selections and overrides. Resource-pack and preset overrides
   are applied again after the fixture merge, so fixture keys cannot replace
   them.

A layer that names a different world or art pack drops the pin keys it would
inherit (`version`, `camera` and `package` for `world`; `version` and `package`
for `resourcePack`), while other keys such as `remote`, `mode` and `stageName`
still merge.

Run-count/entity-strip flags and `config_for_run` environment overrides are
applied when preparing the run. Use `run --dry-run` to inspect effective config.

See [Configuration](../README.md#configuration) for the shared settings and
Packman contracts. Keep local pin changes in `config.local.json`.
SDK auto-restage commands also write only to that file, never tracked defaults.

`screenshots.count` schedules evenly spaced in-game framebuffer captures across
the measured traversal. The benchmark package config currently selects five
(start, end, three interior); the config, not this example, owns the default.
`screenshots.frames` adds explicit zero-based measured frame indices, which are
not camera waypoint durations. Captures go through Minecraft's
`ScreenshotRecorder` after the render frame completes, so they are the real
client framebuffer rather than a desktop grab.

### Documentation Screenshots

Documentation images can be exported from named still shots and hosted as
GitHub attachments. The repository ships no shot set. Mark poses in the
selected world, whether the demo world or one you import, then refresh them
when the build or art pack changes.

`screenshots refresh` uses the shared selected world and pack, not the last
`launch` arguments, so mark poses in that world with that pack. The shipped
configuration selects the demo world and the art pack and needs no change; if
you use your own world, select it and the pack mode in
`tools/config.local.json` first. Close the client between marking and
refreshing; refresh uses the same machine-wide process cleanup as benchmark
capture. The example launches an imported world without the art pack, as such
a configuration would select:

```batch
tools\rtx.bat launch --world MyWorld --resource-pack none
```

In game, fly to the desired frame, set time and weather, and mark the pose:

```text
/rtx shot mark village-night
```

Close the client, then list the marks and refresh all of them or one by name:

```batch
tools\rtx.bat screenshots list
tools\rtx.bat screenshots refresh
tools\rtx.bat screenshots refresh village-night
```

A mark records the pose, the world time, the weather, and whether path tracing
and procedural clouds are on. It creates or updates the authored set at
`tools/assets/shots/docs.shots.json` because the trigger names that directory
as `shotSetDirectory`. Shot sets are separate from world fixtures: keep the
corresponding save and asset versions available so the poses remain reproducible.

`refresh` stages the selected world and art pack at the configured render size
(currently 1920x1080 in `tools/config.json`), writes a `stills` trigger with the
shot names, and lets the client walk the set. For each shot it
teleports the server-side player (so the chunks around the pose load), applies
the scene, hides the HUD, and holds the pose and time of day every frame.
It captures once at least `screenshots.settleFrames` frames have rendered
since the teleport and the terrain pipeline (pending builds, ready meshes,
capture queue, unpublished columns, newly arrived columns) has been idle for
`screenshots.quietFrames` consecutive frames,
or when `screenshots.maxSettleFrames` have passed regardless, so a river or a
crowd still produces its image. Frames under a world-load screen count for
nothing. `BenchmarkController` and `StillShotSession` own the fallback settling
counts, currently 600, 60, and 3600 at the client's frame rate. The
capture itself is taken at the end of Minecraft's frame, after the path-traced
image has landed in the framebuffer, and the pass ends only after every PNG
write has finished, since the encoder runs on the IO worker and the exit would
otherwise cut the last file short. The status heartbeat reports
`stills:<name>` per shot and `stills-complete` at the end, which is what the
harness waits for; a set the mod rejects or a write that fails reports
`stills-failed` instead and the harness stops at once. The configured
`render.preset` is passed as it is for a benchmark, so a recapture renders at
the same tier as the launch the shots were marked in.

`screenshots.MAX_EDGE_PX` and `JPEG_QUALITY` currently select a maximum
1600-pixel longest edge (never upscaled) and JPEG quality 85. Exports land in
`build/screenshots/<name>.jpg`. A full refresh also rewrites the
generated section of `build/screenshots/README.md` from the set and the staged
configuration; text outside that section is kept. A refresh of named shots
leaves the README alone, because its provenance describes one capture of the
whole set.

The generated images stay under `build/`, outside the tracked source. To embed
one in a public guide, upload the JPEG and its provenance to the project's
chosen GitHub issue or pull request, then use the attachment URL GitHub returns.
A recaptured image gets a new URL, so update the guide along with it.

### Measured Scene

The runner pins the world every launch renders, `run`, `launch` and
`world launch` alike, by writing these `render` keys before the game starts:

The following values are the current `tools/config.json` defaults, not fixed
benchmark requirements. Inspect the effective plan with `run --dry-run`.

| Key | Written to | Current shared value |
|---|---|---|
| `renderDistance` | `<gameDir>/options.txt` | 32 |
| `simulationDistance` | `<gameDir>/options.txt` | 12 |
| `graphicsMode` | `<gameDir>/options.txt` | 1 (Fancy) |
| `modSettings` | `<gameDir>/config/rtxremix.json` | `dist.light` 0, `dist.entity` 0, `light.reach` 192 |

`modSettings` maps mod setting ids to values. They are merged into the settings
file: the ids it names win and every other entry the file holds is kept. A file
the mod would not read, another format version or unparseable, is replaced. `0`
is Auto for both cull distances, which follow Minecraft's view distance.

```json
"render": {
    "fullscreen": false,
    "overrideWidth": 1920,
    "overrideHeight": 1080,
    "renderDistance": 32,
    "simulationDistance": 12,
    "graphicsMode": 1,
    "fov": 90,
    "maxFps": 260,
    "vsync": false,
    "modSettings": {
        "dist.light": 0,
        "dist.entity": 0,
        "light.reach": 192
    }
}
```

### Quality Preset

`render.preset` names one of Remix's graphics tiers: `low`, `medium`, `high`,
or `ultra`. The runner passes it to the client as `-Drtxremix.preset=<name>` in
the instance's JVM arguments, and the mod writes the tier to
`rtx.graphicsPreset` for that launch only. It changes
nothing else and saves nothing, so the scene above is the same whichever tier is
measured. `Custom` passes nothing and leaves Remix on its own tier; any other
name must be one of the four, and a typo fails the run rather than quietly
measuring a different tier.

There is no default preset. An absent `render.preset` also means
`Custom`. Set it in `tools/config.local.json` for repeatable captures, or pass
`--preset` explicitly.

```batch
tools\rtx.bat preset list
tools\rtx.bat run --world MyWorld --camera benchmark --preset high
tools\rtx.bat launch --world MyWorld --resource-pack none --preset low
```

`--preset` overrides the pin for one run without editing config.

**A preset is necessary but not sufficient for comparable runs.** Without an
explicit tier, the runtime's Auto selection can vary with GPU and runtime
revision. `rtx_graphics_preset.h`, `rtx_gpu_overrides.cpp`, and `rtx_options.cpp`
in the source matching the selected dxvk-remix build own that policy, not a
universal VRAM threshold in this guide.

Changing the preset changes the workload and establishes a new baseline.
Measure candidate tiers on the same host, then repeat the chosen tier with
multiple runs before quoting its frame-level result. Record the resolved tier,
scene settings, SDK variant, and asset versions together. Pinning a tier does
not make different architectures or hosts directly comparable.

### Fullscreen Resolution

A benchmark pins the fullscreen mode to the configured window dimensions.
`runner.fullscreen_resolution_option_value` currently formats a 1920x1080
request as `1920x1080@60:24` (refresh rate and bit depth follow the `@`).
A play launch leaves the key empty, the **Current** video-settings entry,
so fullscreen follows the attached display.

This is not a config key, because it cannot be one. `render` lives in
`tools/config.json` but `load_config()` merges
`tools/src/rtx_tools/benchmark/config.json` over it for *every* path, play
launches included, so no layer can hold a value
that only benchmarks see. The distinction is which operation is running, so
`write_options(..., pin_display=)` carries it: `run` pins, `launch` and
`world launch` do not.

`validate_benchmark_display` rejects a completed run whose captured resolution
differs from the requested width and height. Failed display-mode setup can
leave Minecraft at its 854x480 startup size and produce plausible but
incomparable timings, so a window's apparent size is not sufficient.

### Environment Overrides

Per-run environment variables, applied on top of the layered config. Useful
for A/B matrices where editing config files between runs would be error-prone:

| Variable | Effect |
|---|---|
| `BENCHMARK_RENDER_FULLSCREEN` | overrides `render.fullscreen` (`true`/`false`) |
| `BENCHMARK_RENDER_WIDTH` / `BENCHMARK_RENDER_HEIGHT` | override the window size (e.g. 4K runs) |
| `BENCHMARK_WARMUP_LAP` | overrides `warmupLap` (see below) |
| `BENCHMARK_POLL_TIMEOUT_SECONDS` / `BENCHMARK_INTER_RUN_COOLDOWN_SECONDS` | override `pollTimeoutSeconds` and `interRunCooldownSeconds` |
| `RTXREMIX_WINDOW_POS` | `x,y`: moves the window before Remix startup so the swapchain is created on the monitor that will display it (multi-monitor 4K runs; coordinates may be negative) |
| `RTXREMIX_EXTRA_RTXCONF` | `key=value;key2=value2`: Remix options applied through `SetConfigVariable` before startup, so options read only at device creation, such as `rtx.dlfg.enable=True`, take effect |

The two `RTXREMIX_*` variables are read by the mod, not the runner, so they also
apply to `launch` and to a hand-started client. Each `RTXREMIX_EXTRA_RTXCONF`
entry becomes a User Settings opinion, the strongest layer: for that process it
masks `rtx.conf` and the gameplay-driven profiles for that key (the layer stack
is drawn in the
[conf author guide](../../packaging/rtx-remix/mods/minecraft-logic/README.md)).
A key Remix rejects aborts renderer initialization, so a typo shows up as a
Remix startup failure in the log rather than as a run that quietly measured the
default. The variable does not reach options the mod owns: the mod re-applies
its persisted settings and then the preset's `rtx.graphicsPreset` through the
same API after these overrides, so vary those through `modSettings`, the
settings menu, or `--preset` instead.

`gradle_env()` copies the parent's environment and fills in `JAVA_HOME` when
needed. The CLI starts Prism with that environment, and Prism passes it on to
the game, so the `RTXREMIX_*` and `DXVK_*` variables reach the client. Prism
removes `JAVA_TOOL_OPTIONS` from the game's environment, so JVM arguments for
an experiment, including `-Drtxremix.*=...` properties, go in `launch.jvmArgs`
in `tools/config.local.json` instead; the CLI warns when `JAVA_TOOL_OPTIONS` is
set. Those arguments reach every launch from the checkout, benchmark runs
included, until removed. See [JVM Arguments](../../docs/TUNING.md#jvm-arguments).

On Windows, Python uppercases environment keys. A camelCase project property
exported as `ORG_GRADLE_PROJECT_someCamelName` can therefore reach Gradle as
`ORG_GRADLE_PROJECT_SOMECAMELNAME` through the CLI and stop matching the
property `build.gradle` reads. Pass an explicitly supported `-P` argument
through the command construction instead of relying on that spelling.

The `entities`, `blockentities`, `particles`, `world`, and `sky` `/rtx` commands
are not JVM-property aliases. Where a corresponding persisted setting exists,
use its real setting id through `render.modSettings`; check `RtxSettingsFactory`
instead of inventing a startup flag.

The benchmark package config currently sets `warmupFrames` to 600. At long
view distances, a stationary path can finish those frames while terrain is
still arriving. Increase warmup or enable a lap, and inspect
`terrain.captureQueue` in `benchmark_status.json`, `Chunk.CaptureQueue` in
the result, and missing-section counters before calling a scene settled.
A queue of zero at one instant is useful evidence, not proof that every later
path position is streamed.

`warmupLap` defaults to false in the controller when omitted. When enabled,
warmup sweeps the path and settles at its start before recording.
`BenchmarkController.WARMUP_LAP_MAX_SPEED` and `WARMUP_SETTLE_FRAMES` own the
current two-times speed limit and 120-frame settle target (the settle function
also caps it to a quarter of the warmup). The controller extends a short warmup
to fit the sweep and logs the new count. `benchmark.json` records the effective
`warmupFrames` and `warmupLap`. Use it for steady-state comparisons; leave it
off to measure cold first-visit costs intentionally.

### Resource Packs

The baseline LabPBR textures ship inside the mod JAR and load through Fabric's
`fabric` resource pack. The runner lists `vanilla`, then `fabric` in
`options.txt`, below every `file/` pack. Omitting `fabric` would make Fabric
append it above user packs.

Per-world resource packs override the mod's built-in baseline in their
configured order. An optional selected art pack is enabled last, so it
overrides both the baseline and per-world packs. For a folder pack,
`resourcePack.stageName` controls its name under `<gameDir>/resourcepacks/`, with
`rtx-tools-art` as the fallback; a ZIP keeps its source filename. The shipped
defaults select the art pack, and the runner stages no separate baseline pack.

For older fixtures, a declared `rtx-remix` pack is dropped with a warning on
stderr unless the fixture supplies a real folder or ZIP with that name.
`doctor --json` keeps stdout machine-readable. See
[Per-World Config](BENCHMARK_FIXTURES.md#per-world-config) for legacy cleanup
and world-pack preservation.

Use `--resource-pack none` to omit the optional art pack; configured world
packs still load. For an existing local pack, pass
`--resource-pack "local:<pack-path>"`. A relative path there or in
`resourcePack.localPath` starts at the repository root, not the directory you
run the command from. The shared `resourcePack` object has `mode` (`none`,
`local`, or `pinned`), `localPath`, and, for a published pack, `name`,
`version`, and an optional `package` override. Downloads also need
`resourcePack.remote`, defined in the mod's `packman/config.packman.xml`.

`resourcepack versions` reports the configured pin and locally staged versions
only, including an unstaged pin. `resourcepack fetch` downloads the configured
version; pass `--version <version>` to fetch another concrete version without
changing the pin. Use `--resource-pack <name>@<version>` for a single run.
Fetched packs remain under
`tools/assets/resourcepacks/<name>_<version>/`, so compare content hashes
between old and new directories to explain the workload change, excluding
`.packman.sha1` and package install markers.

Hold the resource-pack content constant in a code A/B. A pin change or an
in-place edited pack can alter geometry/materials and render cost without
appearing as a camera/world-name change. `doctor` reports the configured mode
and warns about a missing staged pack without downloading it.

## Camera Paths

The command tree, playback clocks, JSON schema, and storage contract are
specified in [Camera Paths](../../docs/CAMERA_PATHS.md). This section covers
only what differs inside a benchmark.

The commands that matter in a managed world:

```text
/rtx benchmark            /rtx camera record <name>
/rtx benchmark status     /rtx camera play <name> [time|fixed-60]
/rtx benchmark stop       /rtx camera seek <seconds>
                          /rtx camera pause | resume | stop | status
```

`record` arms the plain `R` key: press `R` to start, fly the route, press `R`
again to save. `R` is intercepted only while armed and with no screen open.
While recording, `=` and `-` scrub time of day in 10-tick steps, or 250 with
`Ctrl` held. Numpad `+`/`-` are deliberately left alone because Remix debug
shortcuts may see them first.

Benchmark and camera control are mutually exclusive: playing, paused, armed, or
recording camera states block a benchmark start, and camera controls are
unavailable while a benchmark runs. Bare `/rtx benchmark` uses the most recently
loaded or recorded camera, otherwise the one `world launch` selected.

A managed recording writes two copies, a runtime one under
`<gameDir>/camera-paths/<fixture-directory>/` and the canonical one under the
fixture's `cameras/`. The exact directory name, version suffix included, travels
as `cameraStorageKey` so paths from different world versions cannot collide, and
the canonical destination travels as `cameraCanonicalDirectory`. Without that
key the mod keeps only the runtime copy, because it has no way to locate this
fixture tree. If the canonical write fails, the in-game result says so and keeps
the runtime copy.

### How benchmarks sample a path

Benchmarks ignore the interactive playback clocks entirely and direct-sample a
deterministic virtual 60 Hz index. Warmup holds `t=0`. Measured frame `i` samples
`round(i * 1000 / 60)` ms, clamped to the path duration. With no explicit
`recordFrames`, the measured count is the larger of 60 and
`ceil(durationMs * 60 / 1000) + 1`, which includes the first and exact final
poses; a short path holds its endpoint for the remainder. An explicit
`recordFrames` can truncate the route or hold the endpoint longer.

The interactive token is `fixed-60`; benchmark status and result metadata use
`"fixed-60hz"`. Related 60 Hz schedules, not a shared mode selection.

## Artifacts

Raw in-game results land in `<gameDir>/benchmark/results/` and are copied into
`tools/results/<timestamp>/run_N/`:

| Artifact | Contents |
|---|---|
| `benchmark.json` | Recording-only `perFrame` and `perFrameSections` nanoseconds, `perFrameCounters`, summary sections/counters, and camera/clock/`frameTimeSource` provenance |
| `profile.jfr` | Java Flight Recorder capture |
| `profile.tracy`, `profile.csv`, `profile.tracy.*.log` | Tracy capture, CSV export, and capture diagnostics, when the runtime is Tracy-enabled |
| `profile-data.json` | Normalized per-run profile data |
| `runClient.log` | The run's client log: Prism's output, then what the run wrote of the game's `logs/latest.log`, the runtime's `rtx-remix/logs/remix-dxvk.log`, `crash-reports/crash-*-client.txt` Minecraft crash reports, and `hs_err_pid*.log` JVM crash reports |
| `failure.txt`, `failure.json` | Classification, NTSTATUS, last status, exit code, dump links, and bounded `logTail` (up to 80 lines/16000 chars in `runner.log_tail_excerpt`) |
| `crash/` | Windows Error Reporting dumps staged after a native crash; not the JNI bridge's separate crash directory |
| `diagnostics/` | Process tree, desktop screenshot, and jcmd snapshots taken when the harness gave up |
| `diagnostics-preview/` | The same minus the JFR dump, taken mid-stall while the client still runs, with two spaced thread dumps. Kept even when the run recovers, since that near-miss leaves no other trace |
| `frame_*.png` | In-game framebuffer screenshots from the measured traversal |

The result root adds `manifest.json` (git, SDK, machine, config, the
launcher, tags, outcome, run counts, artifact links) and `aggregate.json`
(cross-run profile data). Its `launcher` is `prism`: the runs went through
this checkout's dedicated Prism instance.
`machine.selectedGpu.DriverVersion` uses the WMI driver version notation;
`machine.benchmarkGpu.GL_VERSION` is the client's GL driver string and is
available only when a result supplied it. `machine.gpus` inventories adapters.
Read the actual OpenGL renderer as well as the selected adapter on multi-GPU
machines.

A valid result can count as completed even if the client then crashes during
teardown. `outcome.status` may remain `passed`; inspect
`outcome.postCompletionFailures`, the linked `run_N/failure.json`, and any
`run_N/crash/` dumps before calling a soak clean.

`continue_after_run_failure` defaults to continuing under `--ci` and stopping
locally, unless `continueAfterRunFailure` or
`BENCHMARK_CONTINUE_AFTER_RUN_FAILURE` overrides it. Failed results remain
visible, but the final invocation fails if requested measurements did not all
complete successfully. Automatic dashboard selection does not replace
inspection of post-completion failures.

## Comparing Runs

```batch
tools\rtx.bat list
tools\rtx.bat compare 20260501_103000 20260501_111500
tools\rtx.bat open --a 20260501_103000 --b 20260501_111500
```

The dashboard (`tools/results/index.html` and `catalog.js`) opens from disk.
`compare` prints headline metrics only. `show` and the dashboard
expose section summaries. Full section data is in `aggregate.json` under
`frame.sections`, each `benchmark.json` under `summary.sections`, and the
client's final `Sections:` log block.

Local comparisons do not enforce workload equivalence. Hold the checkout base,
fixture version and camera contents, pack contents, render settings, preset,
warmup policy, SDK revision/variant, driver, and machine state constant except
for the variable being tested. A camera re-recorded under the same name is still
a workload change. A stale checkout can stage stale pins even when its command
line looks identical.

### Scene Completeness

Compare `Chunk.Sections`, `Chunk.RetainedPages`, `Chunk.CaptureQueue`, and
`Chunk.MissingSections` in `aggregate.json` at `frame.counters.<name>.avg`.
`catalog.summarize_aggregate` carries these into `manifest.json` as
`summary.scene`. Different drawn-page counts mean the two measurements may be
rendering different amounts of world; do not credit missing geometry as a win,
even when a reporting threshold would allow a small difference.

A capture queue near its pending cap, nonzero `Chunk.CaptureBlocked` or
`Chunk.CaptureEvicted`, and reduced sections/pages suggest the render-thread
drain is not keeping up with producers. `BlockDataQueue` owns the cap and its
`rtxremix.blockDataQueue.maxPendingItems` override (currently 512 by default).
Check the per-frame series as well as averages. A change that restores previously
missing terrain resets absolute frame-time comparability.

### Hosts And Architectures

Compare each architecture, host, driver, and SDK variant against its own
same-configuration baseline. A Tracy-instrumented runtime has a different cost
from release. Directional agreement between two architectures' own A/B deltas
is a useful cross-check; their absolute timings are not interchangeable.

Inspect `machine.hostname`, `machine.power.activeScheme`,
`machine.logicalCores`, `machine.uptimeSeconds`, GPU identity, and recording GPU
telemetry. An unchanged host name does not prove unchanged power/core configuration.

Machine-health expectations are opt-in environment settings read by
`machine.runner_expectations()`:

| Variable | Expected value |
|---|---|
| `RTXREMIX_EXPECT_POWER_PLAN` | Active power-plan name |
| `RTXREMIX_EXPECT_LOGICAL_PROCESSORS` | Logical processor count |

Set the values for the machine running the benchmark, not a different host's
baseline, in the environment or as `NAME=value` lines in git-ignored
`tools/.env`; a variable already in the environment wins. Each absent variable
disables its corresponding check; with neither set, no power-plan or
processor-count expectation is checked. Machine metadata is still recorded.
These checks do not configure the machine or establish overall comparability.

Broad inflation of unrelated sections and `minMs`, stable scene/draw counters,
and a wider `cvPct` suggest a machine-state problem rather than a localized
code regression. Check reboot/uptime, background work, thermals, clocks, and
power configuration. JFR `jdk.OSInformation` carries uptime when only a capture
is available. Repeating the same commit healthy and slow on the same host is
stronger evidence than comparing a saved number from another day.

### What A Flat Delta Cannot Show

The harness measures an uninterrupted RTX-on path through one world in a fresh
QuickPlay client. It does not test toggling RTX, reloading assets, swapping worlds
or dimensions, or opening settings. Exercise those paths separately when they
are affected, including repeated toggles in a dense high-view-distance scene.
Compare failures with a same-configuration pre-change run before attributing
them to the change; a flat flythrough does not validate teardown or recapture.

An entity-selection change needs a workload that reaches the selection budget.
`RemixEntityPass.MAX_ENTITIES` is a compile-time limit, not a runtime option.
`Ent.Skipped` counts in-range candidates that lost the off-screen closest-first
selection, so nonzero values and the `Entity capture cap (...) reached` warning
confirm that path engaged. `Ent.Count` alone cannot: the budget limits candidates,
not successful captures, and light-owning/in-place paths have their own rules.
Use a sufficiently populated fixture, or lower the same constant in both local
arms and label that experiment. Entity stripping removes that coverage.

### Benchmark FPS Is Not Play FPS

The benchmark runner forces `rtx.dlfg.enable = false` for
measured processes (`BENCHMARK_RTX_CONFIG`). The benchmark measures rendered
`MinecraftClient.render` spans, never generated display frames. A play session
with frame generation can therefore report a different presented rate.

Match effective frame-generation and DLSS settings, not just output resolution.
A named tier is expanded by the selected runtime; a Custom/user opinion may
choose another internal resolution. The runtime's graphics-preset and DLSS
sources own the exact expansion. `RTXREMIX_EXTRA_RTXCONF=rtx.dlfg.enable=True`
can deliberately enable supported frame generation for an experiment, but it
changes the workload and does not turn the result into a presented-FPS counter.

### Reading Frame Metrics, Sections And Zones

`MinecraftClientMixin` timestamps the entry and return of
`MinecraftClient.render`; `BenchmarkController` records that duration as
`perFrame` in nanoseconds and `metadata.frameTimeSource`. It includes ticks,
client tasks, vanilla rendering, capture/submit, blit, and present. Instrumented
sections cover only subsets and can overlap or include worker time; do not add
every section and subtract it from the frame as if they partitioned it.

`perFrame` contains only measured frames. Read join/warmup duration from
`runClient.log`: `World loaded, starting warmup`, `Holding warmup`, and
`Warmup complete`. For p99.9 or counts above two/three times the median, analyze
each run's series and state whether counts were summed across runs. Prefer
relative thresholds and repeated runs over a universal millisecond hitch limit.

The `registerSection`, `begin/end`, and `injectNanos` sites in `RemixRenderer`
and `ChunkManager` define section meaning. Check both revisions before quoting
a delta across a rename or re-scope:

- `Entities` wraps `RemixEntityPass.finishFrame`, not all in-place entity
  captures in vanilla's loop. `Ent.Capture`, `Ent.Submit`, and `Ent.OffSweep`
  are flat injected siblings; their sum is the like-for-like entity work, not
  children that must fit inside `Entities`. `Ent.Capture` can exceed it.
- `Particles` in `frame.sections` is render-thread publication plus worker
  join time. The Tracy/JFR `Particles` scope is preparation on the particle
  worker. A worker-zone improvement is not automatically a critical-path win.
- `Cloud.Gen` injects worker generation time, not render-thread work.
- `Submit` wraps `submitFrame`; present/blit is elsewhere. JFR cannot split
  native runtime work from waits inside that call. See
  [Attributing time inside the frame](PROFILING.md#attributing-time-inside-the-frame).
- Entity counts, draws, and bones vary with live entities. Prefer repeated
  controlled workloads and same-session checks to unrelated sessions.

`aggregate_profile_data.agg_frame` averages non-degenerate runs and records
`runsAggregated`/`excludedRuns`. If all are degenerate, it falls back to all
with `allRunsDegenerate`, not a healthy measurement. Tracy zone means use the
runs containing each zone without that frame exclusion. Empty zones with
`tracy.health.valid=false` are a capture problem, not an optimization.

### Screenshots

Open matched `frame_*.png` pairs at the same measured index and compare terrain,
lighting, textures, entities, and first-person elements. Use every reference
run, not only `run_1`, because a reference can contain a one-off artifact.
Before attributing a difference, repeat the pre-change revision in the same
session on the same host.

The game plays as the Microsoft account selected in Prism's profile, so
the first-person arm wears that account's skin. Compare screenshots from runs
made with the same account. Even then, downloaded skins and their materials
arrive asynchronously: an early-frame-only difference needs a loading-timing
check before being called a rendering regression.

## Shader Compilation Guard

Remix compiles its shader pipeline on first use of an SDK build. By default that
happens asynchronously: the game presents frames while Remix renders nothing and
throttles each one by `rtx.shader.asyncCompilationThrottleMilliseconds`, so a
benchmark recording through that window measures fabricated numbers.

Each measured launch therefore writes a unique
`<gameDir>/benchmark/benchmark-rtx-*.conf` setting
`rtx.initializer.asyncShaderFinalizing=false` and
`rtx.shader.enableAsyncCompilation=false`, and passes
`DXVK_RTX_CONFIG_FILE=<gameDir>/rtx.conf,<benchmark-rtx-*.conf>` to that launch
alone, so the normal baseline loads first and the two benchmark values override
it. The file is deleted when the process exits. The measured child also forces
`RTX_ENABLE_ASYNC_COMPILATION=false` so an inherited environment variable cannot
mask the config. Saved Remix User Settings outrank the temporary config layer, so
the runner fails before launch if `user.conf` sets either option to anything but
false.

`world launch` and `mode: play` launches keep the async default, and manual
in-game `/rtx benchmark` runs do not arm the guard, so prefer `rtx.bat run` for
numbers you intend to trust.

Startup blocks until the runtime's prewarm queue drains, and a later pipeline
can still block a frame. The configured startup budget applies only before the
first status file. `warmup-start` is written at the world-join transition before
the first world frame, so a frame-0 freeze uses the heartbeat budget instead.
Do not widen it on the assumption that every long frame-0 stall is cold prewarm;
capture its Java/native state and compare the runtime log with a passing run.

There are two distinct guards, whose source constants are authoritative:

- `runner.degenerate_frame_report` currently rejects a recording with more than
  1% of frames below `DEGENERATE_FRAME_FLOOR_MS` (3.5 ms). It is an absolute
  near-empty-frame floor, not derived from `maxFps`: Minecraft's 260 value means
  Unlimited, not a 260 FPS cap.
- `aggregate_profile_data.DEGENERATE_MAX_FRAME_MS` currently excludes runs with
  a frame over 5000 ms from headline frame aggregation when healthy runs exist.
  Inspect excluded runs rather than hiding the stall behind the average.

## NRC And Sparse-Rendering Guard

The harness does not validate whether every runtime lighting feature requested
by a preset actually initialized. Before trusting an NRC or sparse-rendering
experiment, inspect `runClient.log` and
`<gameDir>/rtx-remix/logs/remix-dxvk.log` for `Failed to create NRC context`, the NRC
library's `Context successfully initialized`, and the active indirect-integrator
line. The loaded library version in that log is the authority, not a nearby
runtime source checkout. `nrc_session_log.txt` adds the library's own outcome
and is parked by the next CLI launch.

DLSS/Ray Reconstruction initializing successfully does not prove an NRC context
did too. Check the context failure's reported reason and the driver requirements
of that exact NRC/runtime build; do not apply a minimum driver version copied
from another release.

Sparse-rendering prerequisites are runtime-version-specific. Check
`rtx_sparse_rendering.cpp`, `rtx_nrc_context.cpp`, and the preset implementation
at the revision that built the selected SDK. Some revisions require both DLSS
Ray Reconstruction and NRC and log
`Neural Radiance Cache is disabled; sparse rendering will not run`; other
revisions support different integrators and sampling controls. A feature that
silently fell back can turn a parameter sweep into a misleading null result.
Record the actual feature state and do not switch integrators as a hidden
workaround while claiming the old baseline remains comparable.

## Stall Watchdogs

Three profiling budgets and an absolute poll timeout bound a run. The values
below come from `tools/src/rtx_tools/benchmark/config.json`; inspect the effective
config and `runner.py` when changing them.

| Setting | Applies when | Current configured seconds | On expiry |
|---|---|---|---|
| `profiling.benchmarkStartupStallSeconds` | no status yet and launch log stopped growing | 480 | fail `StartupHang` |
| `profiling.benchmarkStallPreviewSeconds` | status progress signature frozen | 45 | snapshot only |
| `profiling.benchmarkHeartbeatStallSeconds` | status progress signature frozen | 90 | fail `Stalled:<status>`, terminate |
| `pollTimeoutSeconds` | since launch, regardless of status progress | 600 | fail `Timeout`, terminate |

The matching overrides are `BENCHMARK_STARTUP_STALL_SECONDS`,
`BENCHMARK_STALL_PREVIEW_SECONDS`, `BENCHMARK_HEARTBEAT_STALL_SECONDS`, and
`BENCHMARK_POLL_TIMEOUT_SECONDS`. Zero disables the preview, not the absolute
poll timeout. `BENCHMARK_INTER_RUN_COOLDOWN_SECONDS` changes the delay between
launches.

These budgets start once Prism has started the game's JVM. Until then the
launch waits for that JVM for up to `benchmarkStartupStallSeconds`, including
its `BENCHMARK_STARTUP_STALL_SECONDS` override. A checkout's first launch, until
the game has run on the pinned Minecraft version, waits at least ten minutes
while Prism downloads Minecraft, and so does a launch whose budget is zero. The
wait fails sooner when Prism exits without starting the game, pointing at
Prism's own logs in the selected launcher profile. A game that writes its logs and exits
before the launch finds it fails that run like any other crash: its crash
report or `hs_err` log classifies it, and the benchmark continues as configured.

`benchmark_status_signature` deliberately ignores the heartbeat `sequence`, so
"frozen" means no forward progress, not a dead process. The status is written
from `onFrameEnd`, which only fires on a world render, so a client that is alive
but has not rendered a world frame looks identical to one that is wedged.

`benchmarkStallPreviewSeconds` exists because of that ambiguity. The kill-time
capture regularly lands after the block has cleared, leaving a thread dump of a
healthy client and no evidence at all. The preview fires at half the kill budget
by default (`BENCHMARK_STALL_PREVIEW_SECONDS` overrides, 0 disables), once per
frozen signature, and skips the JFR dump because the poll loop is blocked while
it runs.

Triaging a `Stalled:` failure:

1. `run_N/diagnostics-preview/java-*.threads.txt` and `.threads-2.txt`. Two dumps
   taken during the block: compare them, and check `cpu=` against `elapsed=` on
   the Render thread. Low CPU across a long elapsed means blocked, not spinning.
2. `logTail` in `run_N/diagnostics/failure.json`, which shows whether the dxvk
   runtime went silent and where.
3. `run_N/failure.json` -> `benchmarkStatus`, for state, phase, frame, and the
   terrain counters when the harness gave up.
4. Compare the failing log segment with a passing same-configuration run,
   especially `Auto-trigger starting` through `Holding warmup`. Establish a
   same-revision failure rate before blaming the code under test.

A device loss can leave the process alive with the render thread blocked inside
native `RemixBridge.submit`. The poll loop classifies fatal log text before the
heartbeat, so a visible `VK_ERROR_DEVICE_LOST` becomes `DeviceLost`; without a
logged signature the same lack of progress can age into `Stalled`. Low thread
CPU time in that stack shows waiting, not its native cause.

## Native Crashes

`runner.classify_run_failure` prioritizes a reported native NTSTATUS over log
signatures. The NTSTATUS comes from an `NTSTATUS 0x...` or `finished with
non-zero exit value` line in the run's client log when there is one, else from
the game process's exit code when that is a Windows error status (`0xC0000000`
to `0xCFFFFFFF`). Minecraft's own crash exits, -1 and -2, are not. The current
map names `0xC0000005` as `AccessViolation` and `0xC0000374` as
`NativeHeapCorruption`; unmapped codes such as `0xC0000409` become
`NativeCrash:<code>`. Without an NTSTATUS, fatal-JVM text from an
`hs_err_pid*.log` classifies as `JvmCrash`, a Minecraft crash report as
`MinecraftCrash`, and `VK_ERROR_DEVICE_LOST` in the runtime's
`rtx-remix/logs/remix-dxvk.log` as `DeviceLost`. Prism keeps the game's
console output to itself, so the run's client log reads these from the game's
files. These labels identify evidence, not which change caused it.

Three locations hold different evidence:

- `run_N/crash/` contains Windows Error Reporting LocalDumps copied by the
  runner. `BENCHMARK_CRASH_DUMP_DIR` overrides the directory
  `windows_crash_dump_dir` selects (currently `C:\CrashDumps` on Windows);
  `BENCHMARK_CRASH_DUMP_WAIT_SECONDS` controls its wait. The runner waits only
  for failures with native-termination evidence, not an ordinary watchdog kill.
  These dumps hold the game's memory, including the signed-in Minecraft
  session, so do not share them publicly.
- `<gameDir>/crash/minidump_*.dmp` and `crash_report_*.txt` are written by the
  unhandled-exception filter in `rtxremix-java/native/remixbridge.cpp`.
  They are not automatically copied into the result's WER directory.
- `hs_err_pid*.log` is a JVM crash report in its working directory, the game
  directory `<gameDir>`; one the run wrote is appended to the run's client log.
  A fast-fail can leave neither this file nor a bridge report; use the
  launch PID and Windows Application event log for attribution.

For a live hang, capture `tools\rtx.bat diag hang --pid N` before termination.
It produces Java stacks and a full native dump under `<gameDir>/hang-dumps/`, unlike
the harness's Java-focused watchdog snapshots. Like the crash dumps, that dump
holds your Minecraft session; do not share it publicly. See
[Development clients](LAUNCHER.md#development-clients) for safe PID selection.

Classify the faulting module and operation before assigning blame. A stack in
the OpenGL driver under `TextureOverlay.renderTextureOverlay` identifies the
GL draw in flight, not proof that the overlay supplied bad data. Likewise a
native heap abort just after NRC initialization is a runtime-side clue, not
proof that mod code is innocent. Compare same-host pre-change runs and loaded
runtime/driver identities, including the SDK pin in
`packman/remix-sdk.packman.xml`; architecture-only failures narrow the search
but do not prove causality.

GPU device loss needs GPU fault evidence, not only a CPU stack. For runtimes
that ship Aftermath, enable `dxvk.enableAftermath = true` in the launching
checkout's `<gameDir>/dxvk.conf` before a dedicated repro, then preserve the runtime
fault output. `src/dxvk/dxvk_options.cpp` and `dxvk_instance.cpp` in the selected
runtime source own availability and behavior. Instrumented fault capture can
change performance and is not a normal A/B baseline.

## Deep Profiling

Benchmark runs enable Java phase profiling. Most `ProfileScope` events mirror
into both Tracy zones and JFR `minecraft.JavaPhase` events, so the dashboard can
show headline health while the raw captures stay available for drill-down. Broad
vanilla render spans that would overlap existing Tracy transition zones are
recorded as JFR-only phases plus Tracy plot lanes, which keeps the capture valid
while still making main-thread gaps measurable.

The runner fetches Tracy dependencies through `rtx_tools.benchmark.tracy`.
Capture and CSV export use the pinned upstream v0.8 Windows tools (protocol 55)
at `tools/tracy-tools/capture/capture.exe` and
`tools/tracy-tools/csvexport/csvexport.exe`. Fetching requires `tar.exe` on PATH;
ARM64 hosts run these x86_64 tools under emulation. Stage a matching
Tracy-enabled local runtime with `tools\rtx.bat sdk local --path <runtime-dir>`.
Alternatively, the runner can fetch the `-debugoptimized` sibling of the
release pin in `packman/remix-sdk.packman.xml` using the configured
`sdk.tracyRemote` in the mod's `packman/config.packman.xml`. The selected
runtime is staged at `remix-runtime/<arch>/`. A failed pull leaves the existing
SDK in place, and local SDK overrides are never replaced by the Tracy fetch.

From `tools/`, use the shared project environment:

```batch
uv run python -m rtx_tools.profiler.jfr_phase_summary results\<timestamp>\run_1\profile.jfr -t "^Render thread$|^main$"
uv run python -m rtx_tools.profiler.jfr_phase_hotspots results\<timestamp>\run_1\profile.jfr --phase "Chunk.Gen"
```

[Profiling](PROFILING.md) covers the analysis modules and their common flags.
The normalized profile-data schema is
`tools/src/rtx_tools/profiler/profile-schema.json`.

See [Profiling](PROFILING.md) for manual JFR dumps, Tracy file/tool compatibility,
capture health, native waits, process CPU, and memory-counter interpretation.
