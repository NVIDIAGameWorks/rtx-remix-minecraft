# Launcher

`tools\rtx.bat` with no arguments, or `tools\rtx.bat menu`, opens the developer
dashboard in `rtx_tools.launcher`. It is a terminal UI for the two things
developers do most: launch the mod for play, and run a benchmark.

It owns no staging or SDK logic. It resolves your choices and calls the existing
`rtx` commands, which do the fetching, staging, building, and launching. If
something goes wrong during a launch, inspect the CLI or build output.

For the first-run walkthrough, see the
[tooling README](../README.md#recommended-developer-first-run).

## Interactive

```batch
tools\rtx.bat
```

Arrow keys move, `Enter` selects, `Esc` goes back. `Ctrl+C` or end-of-input
(EOF) exits the dashboard successfully and restores the console. The dashboard
keeps the current world, art pack, mod loader, launcher, quality preset, SDK
variant, SDK source, and SDK auto-restage setting visible at once, so you can
confirm what you are about to launch without leaving the screen.

The read-only **Launcher** row says how the game will start, for example
`Prism, signed in as <name>`, or why it cannot, such as `Prism (not installed)`
or `Prism (no Minecraft account signed in)`. Select it to run
`rtx.bat prism signin`, which opens the selected Prism profile: sign in on the
page Prism shows (or Accounts > Manage Accounts > Add Microsoft) with the
account that owns Minecraft: Java Edition, and close Prism. The row then names
the account. The game always plays as that account; there is no player name to
choose. See [Sign In To Prism](../../docs/GETTING_STARTED.md#sign-in-to-prism).

**World** and **Art pack** are on/off toggles, both on by default. Select a row
to flip it. On uses the configured world name and version, and passes no
`--resource-pack`, so `resourcePack.mode` in the merged configuration decides
as it does for the CLI: the pin for `pinned`, the `localPath` working copy for
`local`, and no art pack for `none`. Off opens the main menu instead of a
world, or omits the optional art pack (`--resource-pack none`). World resource
packs still load above the mod's built-in baseline when the art pack is off.

The shipped configuration pins the demo world and the art pack, so both
toggles start on with them and **Launch game** fetches both on first use. If
the configured art pack cannot be downloaded, a launch warns, names the
`tools\rtx.bat resourcepack fetch` command that retries it, and starts without
it. An explicit `--resource-pack <name>@<version>`, a benchmark run, and
`screenshots refresh` still stop instead. If the world pin is
cleared in `tools/config.local.json`, its toggle shows **On (unconfigured)**;
if `resourcePack.mode` is `none` there, the art pack toggle shows
**On (mode none in configuration)**. **Launch game** then says once that it is
launching without it. No graphics preset is selected by default; **Custom**
keeps the in-game settings.

The dashboard has no world-name, local-pack, or version picker and never
overrides fixture versions. Use the developer CLI for explicit names, versions,
or local art packs. Camera selection remains under **Run benchmark**; a
benchmark still needs a configured world with its toggle on.

The UI uses Python's standard-library Windows console input and VT output,
supported by Windows Terminal and Windows 10+ conhost. It needs a real console;
use `--mode` for automation or redirected input. From `tools/`, `uv run rtx`
opens the same dashboard.

Toggles and other choices are saved in the gitignored
`tools/.launcher-state.json`. Benchmark-only camera and run-count settings
remain under **Run benchmark**.

## Non-Interactive

Pass `--mode` to skip the dashboard and resolve one command. Without `--mode`,
the dashboard opens; it uses its saved choices rather than the flag-mode options.

| Flag | Values | Meaning |
|------|--------|---------|
| `--mode` | `menu`, `play`, `benchmark` | What to do. See the mapping below |
| `--minecraft-version` | a version from `project-state.json` | Minecraft target to build, stage, and run. Defaults to the environment, then catalog default |
| `--loader` | a loader ID from `project-state.json` (`fabric`, `forge`, or `neoforge`) | Mod loader to build, stage in Prism, and run. Defaults to the environment/config selection |
| `--sdk` | `release`, `tracy` | Forwarded as `--sdk-variant`. Defaults to `release` |
| `--world` | `<name>`, `none` | World to launch or benchmark. The sentinel `none` is case-insensitive; in play mode it opens the main menu, and benchmarks reject it |
| `--resource-pack` | `none`, `local:<path>`, `<name>@<version>`, `pinned` | Art pack layered over the mod's built-in baseline and world packs. Defaults to merged config, which ships the art-pack pin. A relative `local:` path starts at the repository root |
| `--camera` | `<name>` | Benchmark camera path |
| `--preset` | `low`, `medium`, `high`, `ultra`, `Custom` | Remix graphics tier, forwarded as `--preset`. Omit to use merged config, which ships no tier override |
| `--runs` | positive integer | Benchmark run count. Omitted or invalid values leave the harness default |
| `--dry-run` | switch | Print the resolved `rtx.bat` invocation without running it |

The modes map onto the CLI directly:

| Mode | Runs |
|---|---|
| `menu` | `rtx.bat launch` (main menu, no world) |
| `play` | `rtx.bat launch --world ...` |
| `benchmark` | `rtx.bat run ...`, adding `--world` and `--camera` when supplied |

```batch
tools\rtx.bat menu --minecraft-version 1.21.1 --mode play --loader forge --sdk release --world MyWorld --resource-pack none
tools\rtx.bat menu --minecraft-version 1.21.1 --mode benchmark --loader neoforge --sdk tracy --world MyWorld --camera benchmark --resource-pack none --dry-run
```

The resolved `launch` and `run` commands also accept both target selectors
directly.
Switching updates the checkout-specific Prism instance component and mod set:
Fabric stages Fabric API, Forge installs its fresh JarJar artifact, and
NeoForge installs its ordinary module JAR. Non-Fabric selections remove Fabric
API. The switch does not modify `%APPDATA%\.minecraft` or any unrelated Prism
instance.

`--dry-run` is the fastest way to answer "what would this actually run". With
`--mode`, it prints the invocation and exits without opening the UI or writing
state. Without `--mode`, choose an action in the dashboard to preview it; state
and SDK mutations are not saved.

The launcher and benchmark harness can select a Remix tier before launch.
`--preset Custom` passes no tier override. Presets do not replace the scene
settings in `render.modSettings`; see
[Quality preset](BENCHMARKING.md#quality-preset) and the
[conf author guide](../../packaging/rtx-remix/mods/minecraft-logic/README.md).

## SDK Variant And Source

The dashboard shows SDK **variant** (`release` or `tracy`) and SDK **source**
(Packman or a local dxvk build) separately. A `.local-sdk-source` snapshot at
`remix-runtime/<arch>/` serves both variant requests. A `tracy` request requires
the instrumentation symbol checked by `runner.sdk_has_tracy`; it does not fetch
over a local override. A `release` request does not strip instrumentation from
a locally built Tracy DLL. Compare actual runtime build/variant identity, not
only the selected label.

Local staging copies files; rebuilding the source does not mutate that copy.
On `run`, `launch`, and `world launch`, `runner.fetch_sdk` checks freshness and,
by default, refreshes a changed local DLL before use. The dashboard also shows
`local (refresh available)` and offers explicit restaging.

```batch
tools\rtx.bat sdk auto-restage off
tools\rtx.bat sdk restage
tools\rtx.bat sdk auto-restage on
```

`sdk auto-restage` without an argument reports the merged `sdk.autoRestage`
setting; changes are written only to ignored `tools/config.local.json`.
Off warns and uses the staged copy, which is
useful while the source `_output` is mid-build. On is the default. If the
recorded source is missing, the launcher warns and retains the existing snapshot.
This CLI has no per-launch restage-disable flag. Plain Gradle does not invoke
this Python freshness check.

Keep runtime instrumentation identical in A/B arms: an instrumented build is
not comparable to an uninstrumented release build. See the
[SDK source workflow](../README.md#sdk-source-workflow) for restoring the pinned
runtime and [Getting started](../../docs/GETTING_STARTED.md) for native builds.

## Configuration

Shared defaults in `tools/config.json`, benchmark defaults, and ignored
`tools/config.local.json` are merged in that order. The selected fixture's
config follows, then explicit CLI overrides. In particular, a fixture cannot
replace an explicit preset or resource-pack flag.

Mutable world/art pins and SDK auto-restage state stay in the local file rather
than modifying tracked defaults. See
[Configuration](../README.md#configuration).

Fixture fetches use the configured `world.remote` or `resourcePack.remote`
from the mod's `packman/config.packman.xml`. The developer CLI's
`world versions` and `resourcepack versions` report the configured pin and
locally staged versions only. Fetch a concrete version or import a local save.
CLI-selected local resource packs and a matching local SDK also need no asset
service. `check --tests` needs no asset service either.

## Development Clients

Each checkout launches through a distinct Prism instance. Installed Prism uses
its normal account profile and an instance ID derived from the checkout name
and path; portable Prism uses `run-prism/instances/rtx-remix-dev/.minecraft`.
Portable copies can run worktrees side by side; one installed Prism executable
serves one launch at a time. Run one client per checkout: two from one checkout
would share its `logs/latest.log`, `rtx-remix/logs/remix-dxvk.log`, the staged
save's `session.lock`, and the GPU, and their output would not be attributable. `run`,
`launch`, and `world launch` check for a live checkout client before staging.
A failed launch command can still leave a client alive, so enumerate before
relaunching. `find_checkout_java_processes` and `GRADLE_HELPER_MARKERS` in
`tools/src/rtx_tools/benchmark/runner.py` define the current preflight filter.
A client belongs to a checkout when its command line names that checkout's
resolved Prism instance or another client directory such as `.gradle/`,
`build/`, or `run/`, so a worktree nested in the checkout does not block the
checkout's own launch.

The same commands refuse to start while the selected Prism profile is running,
such as the window `prism signin` opened: that Prism could take the launch over
without the command's settings. Close it and run the command again. A separate
portable Prism executable does not block a launch.

Identify a client by its executable path and full command line, which names
this checkout's resolved Prism instance, then record its PID. A Gradle wrapper,
daemon, or worker can also reference the checkout without being Minecraft, and
so can the Prism process that started the game. Stop only the client you own.
The Windows Application event log names a fast-failing process by PID, which
matters when it leaves no crash report.

**Do not run a benchmark beside somebody else's Java process.** The harness
calls `taskkill /f /im` for all `java.exe`, `javaw.exe`, and `capture.exe` on the
machine before launches and during cleanup. Bypassing the live-client preflight
does not disable this. Interactive `launch` and `world launch` do not run that
cleanup. [Manual profiling](PROFILING.md#manual-capture-beside-another-client)
avoids it, but concurrent clients still compete for CPU and GPU resources.

An unfocused initialized client uses the persisted `framerate.unfocused`
setting. `UnfocusedFramerateLimit` owns its default (currently 10 FPS) and the
vanilla Unlimited endpoint; change **Background Max Framerate** in **RTX Remix
Settings** for side-by-side work, or pin it through `render.modSettings`.
The CLI writes `pauseOnLostFocus:false` to the game directory's `options.txt`
on every launch. Keep the benchmark focused.

For scripted input, target the verified PID's window, not global `SendKeys`.
Window messages (`WM_KEYDOWN`, `WM_CHAR`, `WM_KEYUP`) still require a state in
which the client accepts them: a chat command sent while another screen is open
can disappear. Confirm the expected `[CHAT]` feedback in `latest.log`, not merely
that the input API returned. Preserve the prior log before launch if attribution
matters; log rotation happens when the new JVM starts. `diag hang --pid N` has
fallback selection if the PID disappeared, and `diag focus-torture` selects by
title, so neither fallback is safe evidence of checkout ownership.

## Worktrees

The CLI anchors paths to its own checkout, not the shell's current directory.
Invoke the intended worktree's `tools\rtx.bat`; SDK snapshots, result
directories, fixture pins, and the checkout-specific Prism instance belong to
that checkout. Installed Prism shares its normal sign-in; a portable Prism
profile under `run-prism/` signs in separately. An explicit local SDK `--path`
avoids the sibling-path assumption in a worktree layout.

Git-ignored assets are not supplied by Git. Copy complete world and resource-pack
fixture directories into the corresponding `tools/assets/` locations, import
your own save, or fetch assets you have access to. Tracy tools are separately
downloaded. After a checkout changes `packman/remix-sdk.packman.xml`, explicitly
restore the pin with `tools\rtx.bat sdk packman`; Gradle fetches missing runtimes
but does not replace existing ones. Use `sdk status` before trusting a capture.
Launches go through the CLI, which stages the assets and options each launch
needs.

## Runtime Telemetry

Telemetry is owned by the native runtime, not by the Python launcher. Do not
assume a development or benchmark launch is telemetry-free. For the exact
runtime build, consult `src/util/util_sentry.cpp`, `util_sentry.h`,
`util_local_data.cpp`, and `src/dxvk/imgui/dxvk_imgui_first_use_guide.cpp` in
the corresponding dxvk-remix source revision. Build configuration decides
whether Sentry is compiled in; actual transport depends on both consent and
the runtime's transaction-handling implementation.

`RTX_SENTRY_TEST_DSN`, when supported by that runtime, redirects the transport
to a test DSN. An unreachable endpoint caches envelopes under
`rtx-remix/logs/.sentry-native`; this is not deletion or a consent reset.
The CLI copies the parent process environment through `gradle_env()`, and
Prism passes it on to the game, so set test transport only in the launching
process, not persistently at User or Machine scope. The launcher does not
currently install a telemetry opt-out.

`RTX_AUTOMATION_DISABLE_BLOCKING_DIALOG_BOXES=1` is not a telemetry-off switch:
in the Sentry integration it enables automation consent as well as hiding
dialogs. For supported runtimes, the conf option
`rtx.automation.disableBlockingDialogBoxes = True` suppresses the UI without
setting that environment-mode flag. Verify both behaviors against the selected
runtime before unattended use. Application/environment tags are runtime-owned;
a mod checkout cannot promise to relabel them.

## Related

- [Benchmarking](BENCHMARKING.md) for what a benchmark run does once the
  launcher hands off, and for the `rtx.bat` command reference.
