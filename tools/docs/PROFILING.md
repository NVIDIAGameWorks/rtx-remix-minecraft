# JFR Profiler Toolkit

Command-line tools for analyzing JDK Flight Recorder (JFR) recordings. Their
output is plain text sized for a terminal, or JSON with `--json`, so a person
or an AI assistant can drive the analysis from it; see
[Using An Assistant](#using-an-assistant).

## Prerequisites

- **Python 3.15 or newer**
- **uv 0.13 or newer**, using the shared project's lockfile
- **JDK 25** with the `jfr` CLI on `PATH` (or set `JAVA_HOME`)

Profiler and benchmark tools share `tools/pyproject.toml`, `tools/uv.lock`,
and the installed `rtx_tools` package in `tools/.venv/`. Dependencies are pinned
through `uv`. Run the commands in this guide from the repository root:

```bash
uv sync --project tools
```

Python virtual environments are not relocatable. If this repository or its
`tools/` directory moves and a generated console entry point reports an old
path, remove the ignored `tools/.venv/` and run `uv sync --project tools` again.

Most direct JFR inspection tools use only the Python standard library; the test
suite uses the pinned development dependencies.

Verify both are available:

```bash
uv run --project tools python --version   # 3.15+
jfr --version      # should print JDK version
```

### Text encodings

Profile JSON, Tracy CSV, and Python helper JSON use UTF-8 with strict decoding.
Invalid byte sequences are not replaced in structured inputs.
`jfr print --json` also emits UTF-8, as specified by the
[JDK 25 JSON printer](https://github.com/openjdk/jdk25u/blob/master/src/jdk.jfr/share/classes/jdk/jfr/internal/tool/Print.java).
Its stdout is decoded separately from diagnostic stderr. Human-readable
`jfr summary` output and JFR error diagnostics use the system locale encoding.
Replacement decoding is limited to these informational messages and capture logs.

## How to Enable JFR in Minecraft

Add these JVM flags to your Minecraft launcher profile:

```
-XX:StartFlightRecording=filename=profile.jfr,settings=profile,dumponexit=true
```

Use the unconditional JVM tuning in [Tuning](../../docs/TUNING.md), which
`build.gradle` and the installer keep aligned. For deeper stacks, add:

```text
-XX:FlightRecorderOptions=stackdepth=256
```

Recording and Java-phase switches are opt-in instrumentation, not part of the
unconditional player-launch JVM argument invariant.

The `settings=profile` preset enables low-overhead sampling of CPU, allocations,
GC, locks, I/O, and thread activity. The recording is dumped to `profile.jfr`
when the JVM exits (or you can dump on-demand with `jcmd <pid> JFR.dump`).

### Manual Capture Beside Another Client

The benchmark harness kills Java and Tracy capture processes machine-wide.
To profile an interactive client without that cleanup, stage a suitable runtime
and assets first, then use separate Command Prompt terminals from the repository
root. Fetch the pinned Tracy capture/export tools without launching a client:

```batch
tools\rtx.bat sdk tracy-tools
```

The tools download from GitHub. Create `build\captures` if needed;
`%CD%` makes each output path absolute.

```batch
if not exist build\captures mkdir build\captures
tools\tracy-tools\capture\capture.exe -o "%CD%\build\captures\session.tracy" -f -s 600
```

In the other terminal, launch the client with the recording a benchmark run
starts, listed in `launch.jvmArgs` in ignored `tools/config.local.json`:

```json
{
    "launch": {
        "jvmArgs": [
            "-Drtxremix.profile.javaPhases=true",
            "-Drtxremix.profile.tracyJavaMaxDepth=6",
            "-XX:FlightRecorderOptions=stackdepth=256",
            "-XX:StartFlightRecording=name=rtx-benchmark,settings=profile,disk=true,dumponexit=false,maxsize=0"
        ]
    }
}
```

```batch
tools\rtx.bat launch
```

Start Tracy first so it attaches before the runtime queues startup zones. Its
runtime must have matching Tracy instrumentation; omit the capture command for
JFR-only work. The duration above is an example capture limit, not a benchmark
default. Do not assume two Tracy-enabled clients on one machine can be
unambiguously selected by the default capture connection.

`launch.jvmArgs` applies to every launch from the checkout, benchmark runs
included, which add their own recording, so remove these arguments when the
capture is done. The recording above is named `rtx-benchmark`, with
`dumponexit=false`. A benchmark run's recording is dumped by the mod on
benchmark completion; an interactive session with no benchmark does not write
it on exit. While the verified client PID is alive:

```batch
jcmd <pid> JFR.dump name=rtx-benchmark "filename=%CD%\build\captures\session.jfr"
```

To attach to a client that was not launched with JFR, use its JDK's `jcmd`:

```batch
jcmd <pid> JFR.start name=manual settings=profile
jcmd <pid> JFR.dump name=manual "filename=%CD%\build\captures\manual.jfr"
```

Attaching a recording does not retroactively enable Java phase instrumentation,
whose properties are read at class initialization. For manual phase events,
start the JVM with `-Drtxremix.profile.javaPhases=true`, in `launch.jvmArgs`
for a developer-tools launch. Stop only your own client after dumping; see
[Development clients](LAUNCHER.md#development-clients).
Concurrent clients remain a performance confound even when process ownership
is safe.

## Quick Start

```bash
# 1. Play the game with JFR enabled, then close it
# 2. Run the summary dashboard
uv run --project tools python -m rtx_tools.profiler.jfr_summary profile.jfr

# 3. Drill down based on what the summary shows
uv run --project tools python -m rtx_tools.profiler.jfr_hotspots profile.jfr -t "^Render thread$"
uv run --project tools python -m rtx_tools.profiler.jfr_alloc profile.jfr --by size --stacks
uv run --project tools python -m rtx_tools.profiler.jfr_gc profile.jfr --heap --rss
```

For benchmark-integrated recordings, Java renderer phases are emitted as
`minecraft.JavaPhase` events. Most phases are mirrored to Tracy zones; spans
that would cross existing zones, and the enclosing frame spans, stay JFR-only. Use the phase
summary first, then drill into CPU/allocation samples inside a specific phase:

```bash
uv run --project tools python -m rtx_tools.profiler.jfr_phase_summary profile.jfr -t "^Render thread$|^main$"
uv run --project tools python -m rtx_tools.profiler.jfr_phase_hotspots profile.jfr --phase "Chunk.Gen" --mode both
```

To explain 1% low regressions, correlate the slowest benchmark frame timings
with their JFR phase events:

```bash
uv run --project tools python -m rtx_tools.profiler.jfr_worst_frames --benchmark tools/results/<run>/run_1/benchmark.json --jfr tools/results/<run>/run_1/profile.jfr
```

The same recordings can carry client executor events:

```bash
uv run --project tools python -m rtx_tools.profiler.jfr_events profile.jfr --type minecraft.ClientTaskDrain
uv run --project tools python -m rtx_tools.profiler.jfr_events profile.jfr --type minecraft.ClientTask
```

When investigating a benchmark run, pair the JFR with `run_N/profile.tracy` and
the capture logs next to it. Sparse Tracy CSV output or a tiny `.tracy` file
should be treated as a capture-health issue until `profile.tracy.out.log` is
checked for instrumentation failures. Java spans mirrored into Tracy must nest:
a transition-style phase can be a Tracy zone only when every scope opened inside
it closes before the phase does (the `MinecraftClient.render.*` phases and the
vanilla profiler mirrors qualify). Spans that cross existing zones, such as the
Fabric `WorldRender.vanilla.*` events, stay JFR-only and still show up in Tracy
as plot lanes through `ProfileScope.plotOnClose(...)`. The enclosing
`MinecraftClient.render` and `WorldRenderer.render` spans also stay JFR-only:
each enclosing zone pushes every Java zone one level deeper against
`tracyJavaMaxDepth`, and the runtime's frame mark at Present already delimits the frame.
Benchmark runs cap mirrored Java Tracy zones with
`tools/src/rtx_tools/benchmark/config.json`'s `profiling.tracyJavaMaxDepth` value,
currently `6`, so Tracy shows coarse main-thread buckets without the deepest
per-block/per-mesh noise. For a manual launch, set
`-Drtxremix.profile.tracyJavaMaxDepth=<n>` in `launch.jvmArgs` (`-1`, the
default when unset, means unlimited; `0` disables Java phase zones while leaving
JFR phases intact).

## Reading A Benchmark Recording

### Client Task Events

`MinecraftClient.render` drains the client executor in the
`MinecraftClient.render.runTasks` phase. `ProfileScope` emits one
`minecraft.ClientTaskDrain` event with frame index, task/slow-task counts,
drain and summed task time, initial/end/peak queue depth, and the slowest task's
name and duration. Tasks at or above
`rtxremix.profile.clientTaskSlowThresholdNs` also emit `minecraft.ClientTask`.
`ProfileScope` owns the current 500000 ns threshold. Task profiling follows
Java phase enablement unless `-Drtxremix.profile.clientTasks=false` disables it.

Task names identify runnable classes. A task event commits after execution, so
its stack identifies the drain site, not the task body. Inspect `taskName`,
then use `jfr_phase_hotspots --phase "MinecraftClient.render.runTasks"` for
CPU/allocation composition. A `NetworkThreadUtils` lambda can be a packet
handler forwarded from the network thread; a long packet-heavy drain can
precede section rebuild and capture work rather than coincide with it.

When correlating spikes, report both how many occur within N frames of a drain
over X ms and what fraction of all frames those windows cover. Otherwise a
wide window can make almost every hitch look correlated.
`generate_profile_data` summarizes events under `jfr.clientTasks` in
`profile-data.json`; `jfr_worst_frames` also reports frame-ledger `runTasks`
time, task counts, and the slowest task.

### Attributing Spikes

Start with `jfr_worst_frames` and inspect its mapping warning. It orders
`renderAndSubmitFrame` events inside the recording window, maps them onto
`benchmark.json`'s measured frames, then joins phases and frame-ledger events
by `frameIndex`. Missing or mismatched counts make that mapping incomplete.
`minecraft.Benchmark.SessionMarker` supplies `recording-start` and
`recording-end`; without a usable window, inspect timestamps yourself rather
than trusting an ordinal match to exclude warmup.

A manual-play capture has no benchmark recording markers. Read raw phases and
their `frameIndex`, `entityCount`, `meshCreates`, `budgetHit`, and related
fields:

```bash
uv run --project tools python -m rtx_tools.profiler.jfr_events profile.jfr --type minecraft.JavaPhase --json
```

`jfr_phase_summary` applies clock-time filters, not automatic session-marker
selection. Its maxima can include world join and warmup. Use `--after` and
`--before` when comparing a measured window. A sampled hot line may be waiting
on a referenced object or cache miss; inspect what it dereferences before
optimizing its arithmetic.

Compare intentional cold-path runs with
[`warmupLap`](BENCHMARKING.md#environment-overrides) runs. Costs that disappear
are candidates for first-visit work; surviving terrain publication or mesh
creation bursts need per-frame budgeting analysis. Similar long durations in
unrelated API-touching phases can indicate one shared native stall. Confirm the
timeline and native stack rather than blaming every phase that waited on it.

### GC And Hitches

Small stop-the-world pause totals do not rule out GC-related frame cost.
`jfr_gc`, `jfr_summary`, `jfr_timeline --gc`, and `jfr_compare --mode gc` use
pause/collection events, not a complete account of concurrent collector work.
The launch arguments select G1 with a 2 GB heap; see
[JVM Arguments](../../docs/TUNING.md#jvm-arguments). Between its young and
mixed collection pauses, G1 marks the heap and rebuilds remembered sets on its
own threads. List a recording's concurrent phases, then filter by a name the
listing shows:

```bash
uv run --project tools python -m rtx_tools.profiler.jfr_events profile.jfr --type jdk.GCPhaseConcurrent
uv run --project tools python -m rtx_tools.profiler.jfr_events profile.jfr --type jdk.GCPhaseConcurrent --field "name=Concurrent Mark From Roots"
```

On JDK 25 the phases include `Concurrent Mark From Roots` and
`Concurrent Rebuild Remembered Sets and Scrub Regions`; `--field` matches the
whole value. Each pause is a `jdk.GCPhasePause` event, named `GC Pause` for a
young or mixed collection and `Pause Remark` or `Pause Cleanup` within a
marking cycle. Each young or mixed collection is also a `jdk.GarbageCollection`
event named `G1New`, with `sumOfPauses` and `longestPause`, and a
`jdk.G1GarbageCollection` event with the same `gcId` whose `type` is `Normal`,
`Concurrent Start`, `Prepare Mixed`, or `Mixed`:

```bash
uv run --project tools python -m rtx_tools.profiler.jfr_events profile.jfr --type jdk.GarbageCollection --field name=G1New --json
uv run --project tools python -m rtx_tools.profiler.jfr_events profile.jfr --type jdk.G1GarbageCollection --field type=Mixed --json
```

Use `--json` to join the event intervals to frame timestamps. Compare hitch
frequency and severity inside versus outside concurrent phases and mixed
collections, normalized by how many frames each interval covers. Render-thread
code that writes references pays G1's write barriers, which cost more while
marking runs, and the collector's threads compete with the game's for CPU even
while recorded pauses stay short.

Heap tuning can alter cycle frequency and overlap; it is not proof that the
cost of an overlapping frame improved. Measure total allocation, render-thread
allocation/reference traversal, concurrent phase duration, and frame tails
together. Verify the actual launch flags before interpreting an unexpected
pause total. Neither a low pause total nor a larger heap is a hitch fix by
itself.

### Attributing Time Inside The Frame

JFR sees `RemixBridge.submit` as native self-time. It cannot distinguish runtime
CPU work from a Vulkan/interop wait inside that call. `Submit` in
`RemixRenderer` excludes the later present/blit path; use matching native Tracy
zones, GPU evidence, and thread stacks to divide it. A breakdown from another
GPU or architecture does not apply to a JFR-only run.

`AtmosphericSkyGenerator.consumeCompletedReadback` polls an old GL fence with
`glClientWaitSync(..., 0, 0L)`. A long
`Sky.ProceduralGeneration.Readback` span can therefore be driver synchronization
paid at that API call rather than sky computation. Check surrounding
`Interop.VKToGL`, `Interop.GLToVK`, `Remix.Present`, and
`MinecraftClient.render.swapBuffers` spans; moving a sync point can relabel
the same wait without removing it. `GlSurfaceMixin` defines the current
swap/present redirection. Turning the sky off also removes its dome light and
changes GPU lighting work, so it is not a CPU-only control.

Tracy's CPU-usage lane is machine-wide. For process contamination, inspect
JFR `jdk.CPULoad`: `machineTotal - (jvmUser + jvmSystem)` estimates CPU used by
other processes. The summary averages the whole recording, including launch,
load, and warmup. Window it to measurement:

```bash
uv run --project tools python -m rtx_tools.profiler.jfr_events profile.jfr --type minecraft.Benchmark.SessionMarker
uv run --project tools python -m rtx_tools.profiler.jfr_events profile.jfr --type jdk.CPULoad --after <start-time> --before <end-time>
```

Use the recording markers' timestamps for those arguments, and compare a quiet
run on the same machine. Run expensive JFR/Tracy analysis after the benchmark
exits, not while the next arm records.

### Memory Counters

GPU memory views intentionally count different things:

- The runtime developer menu's Video Memory Usage uses device-local heap
  budget/usage accounting. On Windows this can include Minecraft's OpenGL
  driver allocations as well as Remix resources.
- The runtime memory profiler tracks its own resource categories. For builds
  supporting `rtx.profiler.memory.enable = True`, enable it before launch and
  choose **Write to Log** in the memory profiler panel to emit the
  `GPU Memory Profiler Log` block; it is not automatically a periodic log.
- `GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX` (`0x9049`) is the GL view
  when `GL_NVX_gpu_memory_info` is available, not a portable total of all GPU
  allocations.

The selected runtime's `dxvk_imgui.cpp` and `dxvk_memory_tracker.cpp` are the
authorities for those counters and controls. Growth in the heap readout with
flat tracked Remix resources suggests allocations outside that profiler;
correlate it with GL work rather than declaring the owner from one graph.
On 1.21.1 a known GL buffer-orphaning mechanism behind such growth was
avoided by a buffer reuse policy; 26.x uploads through its own renderpearl
buffers, so growth that scales with draw calls or frame rate, especially with
the debug chart open, now points at vanilla's upload path or the interop.

Java RSS growth is a separate question. Use `jfr_gc --heap --rss` and a
controlled `jcmd <pid> GC.run` outside measured runs to compare the live set
with committed memory. RSS falling with heap after collection is consistent
with deferred collection, not proof that all native memory is healthy.
`build.gradle` and [Tuning](../../docs/TUNING.md) own heap limits; do not infer
a leak, or its absence, from RSS alone.

## Tracy Capture Health And Compatibility

`tools/src/rtx_tools/benchmark/tracy.py` pins `TRACY_VERSION`, currently 0.8
(protocol 55), and stages matching x64 `capture` and `csvexport` binaries
under `tools/tracy-tools/`; ARM64 hosts run those tools under emulation.
That pin must match the runtime's vendored client. Use a desktop profiler
that explicitly supports the capture's file version, ideally the same release.
Newer profilers can reject old files; their update utilities also have a
limited input-version range, so conversion may need intermediate releases.
Changing the runtime's client requires a coordinated tool-pin update.

The shipped configuration pins the demo world and art pack, but no graphics
preset. Choose a preset explicitly and review the world/pack inputs through
[configuration](../README.md#configuration) before a capture. A local
Tracy-enabled SDK needs no asset service. Fetching a Tracy runtime uses the
configured `sdk.tracyRemote` from the mod's `packman/config.packman.xml`,
imported by the shared bootstrap's pull manifest. Select it with
`--sdk-variant tracy`; without that option a benchmark uses the release runtime
and records JFR only.

Read `profile.tracy.out.log` before interpreting a tiny trace, startup-only
frames, or sparse CSV:

- `Invalid order of zone begin and end events` suggests mismatched scope nesting
  or capture attaching after queued startup events. Capture before launch and
  ensure mirrored Java scopes close inside their parents.
- Anonymous native `???` zones or dominant system-trace lanes warrant checking
  `TRACY_NO_SYSTEM_TRACING` in the runtime build. Elevated Windows capture can
  otherwise flood the trace with ETW system events.
- Anonymous Java names, `src_file` of `???`, exporter access violations, or
  one-off dynamic zone names suggest source-location exhaustion.
  `ProfileScope.begin` documents the vendored client's signed 16-bit index
  limit, currently 32767 distinct names. Keep `begin`, `beginTracyOnly`, and
  `markTracyZone` names in a small bounded set; put coordinates, counts, and
  durations in JFR fields, not zone names.

An exporter failure leaves no `profile.csv` and can make
`tracy.health.valid=false`/empty `tracy.zones` while frame sections, counters,
and JFR remain intact. Inspect `tracy.health` and its warnings before comparing
zone totals.

`runner.export_tracy_csv` requests aggregate tab-separated CPU-zone totals,
without timestamps. For per-event output, fetch the pinned tools first (an
up-to-date installation is reused):

```batch
tools\rtx.bat sdk tracy-tools
tools\tracy-tools\csvexport\csvexport.exe -u -s "\t" -f <zone-name> <capture.tracy>
```

Omit `-f` for all zones. `rtx_tools.profiler.tracy_analyze` accepts either form.
CPU CSV does not supply GPU timing; the normalized `tracy.gpu` array is empty
in this pipeline and says nothing about GPU load. Open `profile.tracy` to see
GPU zones when the selected runtime actually emits them.

## Tool Reference

The tables use short module names. Invoke each with
`uv run --project tools python -m rtx_tools.profiler.<module>` followed by its
arguments. From `tools/`, omit `--project tools`. Do not invoke source files
directly.

| Module | Purpose | Key Flags |
|------|---------|-----------|
| `jfr_summary` | Dashboard: metadata, events, CPU threads, top allocs, heap, GC, locks | `--threads`, `--json` |
| `jfr_hotspots` | CPU hot methods (self/total time), wall-clock blocked time | `-t`, `--mode self\|total`, `--wall`, `--group package` |
| `jfr_alloc` | Allocation sites by count or size, class grouping | `--by count\|size`, `--top-classes`, `--stacks`, `--class` |
| `jfr_stacks` | Call trees, collapsed stacks, bottom-up views | `--format tree\|collapsed\|bottom-up`, `--filter`, `--depth` |
| `jfr_timeline` | Time-bucketed CPU sample distribution with GC overlay | `--interval`, `--gc` |
| `jfr_window` | Drill into a specific clock-time range | `--after HH:MM`, `--before HH:MM` |
| `jfr_gc` | Heap timeline, RSS, GC pauses, retained objects, thread alloc ranking | `--heap`, `--rss`, `--gc`, `--retained`, `--thread-alloc` |
| `jfr_threads` | Thread inventory, CPU load, dark thread detection, park analysis | `--cpu-load`, `--detect-stuck`, `--parks` |
| `jfr_locks` | Lock contention, RWL detection, per-monitor ranking | `--min-duration`, `--stacks` |
| `jfr_io` | File and socket I/O by duration or bytes | `--type file\|socket`, `--by duration\|bytes`, `--slow` |
| `jfr_compare` | Diff two recordings: alloc/CPU/GC deltas | `--mode alloc\|cpu\|gc\|all` |
| `jfr_flame` | Export collapsed stacks for flamegraph tools | `--mode cpu\|alloc\|lock`, `--include-thread` |
| `jfr_events` | Raw event explorer: list types, dump any event | `--list-types`, `--type`, `--field` |
| `jfr_phase_summary` | Summarize RTX Remix Java phase events mirrored from Tracy | `--phase`, `-t`, `--json` |
| `jfr_phase_hotspots` | CPU/allocation hotspots inside a named Java phase window | `--phase`, `--mode cpu\|alloc\|both`, `--by size\|count` |
| `jfr_worst_frames` | Correlate benchmark worst frames with Java phase events | `--benchmark`, `--jfr`, `--phase-count` |
| `tracy_analyze` | Tracy CSV analysis (native-side profiling) | `--min-ms`, `--json` |
| `generate_profile_data` | Build per-run `profile-data.json`, including `jfr.clientTasks` | `--benchmark`, `--jfr`, `--tracy`, `--out` |
| `aggregate_profile_data` | Merge `run_N/profile-data.json` into `aggregate.json` | `--input-dir`, `--out` |

### Common Flags (JFR inspection tools)

| Flag | Description |
|------|-------------|
| `-t`, `--thread REGEX` | Filter events by thread name (regex, case-insensitive) |
| `-n`, `--top N` | Show top N results (default: 30) |
| `--after TIME` | Only events after this time (`HH:MM`, `HH:MM:SS`, or ISO-8601) |
| `--before TIME` | Only events before this time |
| `--fqn` | Use fully qualified class names |
| `--json` | Machine-readable JSON output |

## Which Tool Do I Use?

| Question | Module and arguments |
|----------|---------|
| What's in this recording? | `jfr_summary` |
| Why is my frame rate low? | `jfr_hotspots -t "^Render"`, then `jfr_stacks --format bottom-up` |
| What's allocating all this memory? | `jfr_alloc --by size --stacks` |
| Is there a memory leak? | `jfr_gc --heap --rss` |
| Are threads stuck? | `jfr_threads --detect-stuck` |
| What's contending on locks? | `jfr_locks --stacks` |
| Did my optimization help? | `jfr_compare before.jfr after.jfr` |
| What happened at 15:50? | `jfr_window --after 15:50 --before 15:52` |
| Where is CPU time going over time? | `jfr_timeline --interval 500 --gc` |
| I need a flamegraph | `jfr_flame > stacks.folded` then `flamegraph.pl` |
| What events exist? | `jfr_events --list-types` |
| Which Java frame phase is expensive? | `jfr_phase_summary -t "^Render thread$\|^main$"` |
| What Java methods allocate inside `Entities`? | `jfr_phase_hotspots --phase "Entities" --mode both` |
| Which phases caused my 1% lows? | `jfr_worst_frames --benchmark benchmark.json --jfr profile.jfr` |
| Is concurrent GC involved in hitches? | `jfr_events --type jdk.GCPhaseConcurrent --field "name=Concurrent Mark From Roots"` |
| Are mixed collections involved in hitches? | `jfr_events --type jdk.G1GarbageCollection --field type=Mixed --json` |
| Native-side bottleneck? | `tracy_analyze inclusive.csv selftime.csv` |

## Output Conventions

- All tools write **results to stdout** and **progress/errors to stderr**
- Output is columnar text sized for terminals and assistant context windows, not HTML or SVG
- `--json` produces structured output for piping between tools
- Exit codes: `0` = success, `1` = no data matching filters, `2` = error
- Time values use adaptive formatting: `123us`, `4.5ms`, `1.23s`, `2.1min`
- Byte values use adaptive formatting: `123 B`, `4.5 KB`, `1.2 MB`, `3.4 GB`

## Architecture

```
tools/
  pyproject.toml          # Shared project and development dependencies
  uv.lock                 # Shared dependency lock
  src/rtx_tools/profiler/
    __init__.py
    profile-schema.json   # Packaged profile-data and aggregate schema
    jfr_parse.py          # Core library: JFR CLI wrapper, event accessors,
                          # frame formatting, time parsing, thread filtering,
                          # common argparse setup. Caches parsed events.
    jfr_summary.py        # Entry point / dashboard
    jfr_hotspots.py       # CPU analysis
    jfr_alloc.py          # Allocation analysis
    jfr_stacks.py         # Stack trace analysis
    jfr_timeline.py       # Temporal analysis
    jfr_window.py         # Time-window drill-down
    jfr_gc.py             # GC + memory analysis
    jfr_threads.py        # Thread state analysis
    jfr_locks.py          # Lock contention
    jfr_io.py             # I/O analysis
    jfr_compare.py        # Recording comparison
    jfr_flame.py          # Flamegraph export
    jfr_events.py         # Raw event explorer
    jfr_phase_summary.py  # RTX Remix Java phase summary
    jfr_phase_hotspots.py # CPU/allocation hotspots inside a Java phase
    jfr_worst_frames.py   # Benchmark worst-frame to Java phase correlation
    tracy_analyze.py      # Tracy CSV analysis (native-side)
    generate_profile_data.py   # One normalized benchmark run
    aggregate_profile_data.py  # Cross-run aggregation
  tests/profiler/
    test_*.py
    fixtures/
  docs/PROFILING.md
  results/                # Benchmark sessions and profiling artifacts
```

JFR tools import shared helpers from `.jfr_parse` within the package and follow
the same conventions:
- Positional argument: `.jfr` file path
- Flags declared by each module's `main()` using `argparse.ArgumentParser`
- Results to stdout, progress to stderr
- `--json` for machine output

Read the bundled schema through `importlib.resources`, not a checkout-relative
path:

```python
import json
from importlib.resources import files

schema = json.loads(files("rtx_tools.profiler").joinpath("profile-schema.json").read_text())
```

Run focused profiler tests through the shared project:

```bash
uv run --project tools python -m pytest tools/tests/profiler
```

Before publishing a tooling change, run `tools\rtx.bat check --tests` from the
repository root for format, lint, type, and test checks.

## Using An Assistant

An AI assistant that can run commands in the checkout can drive these
modules itself. Each prompt below starts one on a recording.

### General Performance Analysis

```
Please analyze this JFR recording: <path/to/profile.jfr>

Use the rtx_tools.profiler modules from the shared tools environment:
1. Start with uv run --project tools python -m rtx_tools.profiler.jfr_summary to get an overview
2. Based on what you see, drill down with the appropriate tool
3. Provide actionable recommendations

Focus on: frame rate, allocation pressure, GC impact, lock contention.
```

### Before/After Comparison

```
I optimized <description>. Compare these recordings:
- Before: <path/to/before.jfr>
- After: <path/to/after.jfr>

Use uv run --project tools python -m rtx_tools.profiler.jfr_compare to diff them,
then drill into any regressions.
```

### Memory Leak Investigation

```
My application's memory keeps growing. Analyze <path/to/profile.jfr>:
Run these modules with uv run --project tools python -m:
1. rtx_tools.profiler.jfr_gc --heap --rss  (is RSS growing faster than heap?)
2. rtx_tools.profiler.jfr_gc --retained    (what objects are retained?)
3. rtx_tools.profiler.jfr_alloc --by size  (what allocates the most?)
4. rtx_tools.profiler.jfr_threads --detect-stuck  (any threads stuck in native alloc?)
```
