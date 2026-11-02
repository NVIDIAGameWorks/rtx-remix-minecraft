# Tuning

Developer knobs: the JVM system properties the mod reads, the Gradle
pass-throughs `build.gradle` understands, the JVM arguments a play install and
a development launch get, and the files the mod owns in the game directory.

None of this is needed to play. Everything a player should change is in the
in-game settings screen, described in [Settings](SETTINGS.md).

## How To Set A Property

Properties below are read at startup or first use; some are read again later.
Set them before launching the game, and restart after changing the launch
arguments.

In a launcher, add `-Dkey=value` to the profile's or instance's JVM arguments.
For a client the developer tools launch, add it to `launch.jvmArgs` in ignored
`tools/config.local.json`, as [JVM Arguments](#jvm-arguments) describes.

The named owner class defines each property's current default, accepted range,
and invalid-value behaviour. Sizes are in bytes and durations in nanoseconds
unless the key name says otherwise.

## Terrain Capture

Vanilla's chunk workers capture section geometry and hand it to the render
thread through a bounded queue. These bound how much work is in flight and how
long a worker may wait.

| Key | Effect | Owner |
|---|---|---|
| `rtxremix.chunk.sectionBuilders` | Concurrent vanilla section builds. Vanilla allocates one buffer per logical CPU, which floods the frame that a chunk-boundary crossing lands in | `SectionBuildConcurrency` |
| `rtxremix.blockDataQueue.maxPendingItems` | Section captures queued for the render thread | `BlockDataQueue` |
| `rtxremix.blockDataQueue.maxPendingBytes` | Bytes of queued captures | `BlockDataQueue` |
| `rtxremix.blockDataQueue.maxDrainItems` | Captures drained per render-thread pass | `BlockDataQueue` |
| `rtxremix.blockDataQueue.maxDrainBytes` | Bytes drained per pass | `BlockDataQueue` |
| `rtxremix.blockDataQueue.publishWaitBudgetNs` | Initial worker wait budget before attempting FIFO eviction. In-flight reservations can require additional waits; it is not a total-wait limit | `BlockDataQueue` |
| `rtxremix.chunk.localBatchPacing` | Pace the integrated server's chunk delivery while capture is on, spreading chunk-load bursts across frames. Set to false to restore vanilla's single batch per tick | `LocalChunkBatchPacing` |
| `rtxremix.chunk.localBatchBudgetMs` | Measured render-thread chunk-apply time, in milliseconds, the paced batch may fill per tick | `LocalChunkBatchPacing` |
| `rtxremix.chunk.localBacklogBudgetMs` | Ceiling, in milliseconds, the per-tick budget rises to while a backlog is waiting, so a join burst streams in rather than landing in one frame | `LocalChunkBatchPacing` |
| `rtxremix.chunk.localBatchBacklogDivisor` | Share of the backlog, one chunk per this many waiting, added to the per-tick budget | `LocalChunkBatchPacing` |
| `rtxremix.chunk.localBatchMaxChunks` | Cap on chunks in one paced batch, whichever of the time budget and this cap is smaller; keeps a row spread even where the apply is cheap, because the rebuilds a row triggers cost the frame more than its deserialization | `LocalChunkBatchPacing` |
| `rtxremix.chunk.localBacklogMaxChunks` | Ceiling that cap rises to while a backlog is waiting | `LocalChunkBatchPacing` |
| `rtxremix.chunk.localUnloadPacing` | Pace the integrated server's chunk unload packets alongside its chunk data, so a crossing's trailing row does not land in the same drain as the new row. Set to false to send unloads the tick a column leaves the filter; off whenever `rtxremix.chunk.localBatchPacing` is off | `LocalChunkBatchPacing` |
| `rtxremix.chunk.localUnloadBudgetMs` | Measured render-thread unload time, in milliseconds, the paced unloads may fill per tick | `LocalChunkBatchPacing` |
| `rtxremix.chunk.neighbourReflagFilter` | Drop vanilla's chunk-load and light-data neighbour rebuild flags when the neighbour's last build already saw the loading column. Set to false to restore those flags. Block updates and the loading column itself stay vanilla | `NeighbourReflagFilter` |
| `rtxremix.light.vanillaSnapshot` | Set to true to make the client light engine clone its section map every pass as vanilla does. With false, while capture is on, the live map is published as the snapshot, which rests on nothing but the render thread reading client light | `ClientLightSnapshotPolicy` |
| `rtxremix.light.vanillaSurfaceScan` | Set to true to start each column's sky-light surface walk at the top of the chunk as vanilla does. With false the walk is seeded at the packet's `WORLD_SURFACE` height, which skips the air-over-air pairs that were most of the render thread's chunk-apply cost; the remedy for a sender whose heightmaps do not match its blocks | `SkyLightSurfaceScanPolicy` |
| `rtxremix.vanillaVisibilityWalk` | Set to true to leave the chunk visibility walk to vanilla with RTX on instead of the mod's allocation-free port, which must produce the same visible set | `ChunkRenderingDataPreparerMixin` |
| `rtxremix.logVisibleSet` | Log the size and a position hash of every full visibility walk's result whenever it changes, to compare the port against vanilla over one camera | `ChunkRenderingDataPreparerMixin` |
| `rtxremix.joinHold` | Keep vanilla's terrain-download screen up until frame times settle after a world join, with a short bounded hold, so the first-sight mesh, pipeline, and BLAS costs land behind the screen. Set to false to disable the hold | `JoinBurstHold` |

`BlockDataQueue.acquireCapacity` is authoritative for waiting: a worker
initially waits up to the configured budget (500 ms by default, with a 50 ms
floor), then attempts FIFO eviction until its capture fits. Capacity held by
reservations between reserve and enqueue cannot be evicted, so the worker
rearms another 50 ms wait and retries while the capture remains current.
The initial budget is not a hard upper bound on total waiting. The render
thread's synchronous builds never wait because it is the sole consumer.

The defaults bound worker captures to 512 items and 128 MiB. An evicted capture
that still belongs to a resident section is re-flagged through
`BuiltChunk.scheduleRebuild`, up to 128 per frame, and contributes to
`Chunk.CaptureEvicted`. The queue and `ChunkManager` own these limits; consult
their current constants when interpreting a capture.

## Terrain Assembly And Publication

A low-concurrency executor merges section buffers into chunk-column pages; the
render thread publishes them and retires the pages they replace, both under
per-frame budgets.

| Key | Effect | Owner |
|---|---|---|
| `rtxremix.chunk.meshBuildThreads` | Threads in the column merge executor | `ChunkManager` |
| `rtxremix.chunk.maxPendingBuilds` | Merges outstanding at once | `ChunkManager` |
| `rtxremix.chunk.maxPendingMeshBytes` | Bytes of merged but not yet installed geometry | `ChunkManager` |
| `rtxremix.chunk.installBudgetNs` | Render-thread time per frame spent installing columns | `ChunkManager` |
| `rtxremix.chunk.maxInstalledColumnsPerFrame` | Columns published per frame | `ChunkManager` |
| `rtxremix.chunk.columnStreamSettleFrames` | Frames a column waits for its neighbours before publishing | `ChunkManager` |
| `rtxremix.chunk.columnStreamMaxWaitFrames` | Hard cap, in frames, on that wait | `ChunkManager` |
| `rtxremix.chunk.maxSectionRemovalsPerFrame` | Deferred section removals per frame | `ChunkManager` |
| `rtxremix.chunk.maxSectionRemovalValidationsPerFrame` | Removal checks per frame | `ChunkManager` |
| `rtxremix.chunk.maintenanceBudgetNs` | Render-thread time per frame for terrain maintenance | `ChunkManager` |
| `rtxremix.chunk.maxColumnUnloadsPerFrame` | Column unloads processed per frame | `ChunkManager` |
| `rtxremix.chunk.meshPageBytes` | Byte cap on one terrain mesh page | `BlockMeshBuilder` |
| `rtxremix.chunk.meshRetireBudgetNs` | Render-thread time per frame spent retiring replaced pages | `TerrainAssetRetirementQueue` |
| `rtxremix.chunk.meshRetireSoftCount` | Queued retirements before backpressure starts | `TerrainAssetRetirementQueue` |
| `rtxremix.chunk.meshRetireHardCount` | Queued retirements that force the queue to drain | `TerrainAssetRetirementQueue` |
| `rtxremix.chunk.meshRetireSoftBytes` | Byte equivalent of the soft count | `TerrainAssetRetirementQueue` |
| `rtxremix.chunk.meshRetireHardBytes` | Byte equivalent of the hard count | `TerrainAssetRetirementQueue` |

Retirement has two different gates in `TerrainAssetRetirementQueue`.
`retirementBackpressured()` is a hysteretic count-or-bytes limit for teardown
(column unloads, section removals, and zombie audits).
`retirementBytesBackpressured()` pauses installs only at the hard byte bound,
currently 512 MiB. Keeping the gates separate prevents retirement of terrain
behind the camera from starving publication ahead of it.

Do not add a clock read around each native call inside these wall-clock budgeted
loops. That overhead consumes the same budget as useful page work and can
reduce publication throughput without showing up as a sampling hotspot.
`ProfileScope` is the per-call timing instrument; `TerrainNativeCallStats`
keeps the frame tallies clock-free.

## Block Lights

Light creation and destruction are native calls like any other, so they run on
the render thread under their own budget. Whether a surviving light is drawn is
decided per frame: a connectivity flood from the camera's section marks which
sections are reachable, and a reach test drops lights too dim to matter at
their distance.

| Key | Effect | Owner |
|---|---|---|
| `rtxremix.chunk.maxLightOpsPerFrame` | Light create, update, and destroy calls per frame | `ChunkManager` |
| `rtxremix.chunk.lightBudgetNs` | Render-thread time per frame for those calls | `ChunkManager` |
| `rtxremix.chunk.maxPendingLightOps` | Queued light work before backpressure | `ChunkManager` |
| `rtxremix.light.floodSliceNs` | Render-thread time per frame a connectivity rebuild may take. The rebuild fills a second set across frames and swaps it in when complete, so a larger slice finishes sooner and a smaller one costs less per frame | `TerrainState` |

## Entities, Textures, And Particles

| Key | Effect | Owner |
|---|---|---|
| `rtxremix.entity.meshCache.maxEntries` | Pooled entity meshes kept for reuse | `RemixEntitySubmitter` |
| `rtxremix.dynamicTextureCache.maxEntries` | Entries in the dynamic texture cache that serves skins and glyph pages | `DynamicTextureCache` |
| `rtxremix.directBufferPool.maxBytes` | Bytes of idle direct buffers kept pooled. 0 disables pooling | `DirectBufferPool` |
| `rtxremix.waterRapids.spawnBudgetPerTick` | Foam and mist particles spawned per tick near fast water. 0 turns the effect off | `WaterRapidParticleSpawner` |
| `rtxremix.particles.fastAirCollision` | Take a fast path through particle collision when every swept cell is air. Set to false to disable the fast path | `ParticleStateMixin` |
| `rtxremix.particles.fastAirCollisionMaxBlocks` | Largest sweep, in cells, that the fast path handles | `ParticleStateMixin` |

## Profiling And Diagnostics

| Key | Effect | Owner |
|---|---|---|
| `rtxremix.benchmark.jfrPath` | Names the JFR dump the benchmark flushes before it writes its result. A non-empty value also turns Java phase events on | `ProfileScope`, `BenchmarkController` |
| `rtxremix.profile.javaPhases` | Enable Java phase events in JFR. A non-empty `jfrPath` forces them on | `ProfileScope` |
| `rtxremix.profile.tracyJavaZones` | Mirror the same phases as Tracy zones; follows `javaPhases` unless explicitly set. Set to true for a Tracy-only session with no JFR recording | `ProfileScope` |
| `rtxremix.profile.tracyJavaMaxDepth` | Nesting depth limit for those Tracy zones. -1 means unlimited | `ProfileScope` |
| `rtxremix.profile.clientTasks` | Enable per-client-task JFR events; only read when Java phase events are on | `ProfileScope` |
| `rtxremix.profile.clientTaskSlowThresholdNs` | A client task slower than this gets its own JFR event | `ProfileScope` |
| `rtxremix.debug.entityTypeSummary` | Enable a periodic per-entity-type capture summary in the log. It costs a map probe per entity per frame and a sort and format pass on the render thread, so it is a diagnostic, not a setting | `RemixEntityPass` |

Tracy zones only appear when the staged runtime was built with Tracy support.
JFR works with any runtime.

## The Vanilla Worker Pool

The mod sets vanilla's own `max.bg.threads` property at pre-launch, before
Minecraft creates its shared `Worker-Main` pool.

Section builds, chunk loads, lighting updates, and resource reloads share that
pool. With Remix capture inline in section builds, a burst of workers contends
with the render thread for CPU time, so the mod bounds the pool.

`WorkerPoolCap` chooses the smaller of 8 and logical CPUs minus one, with a
minimum of one. It must run at pre-launch because `net.minecraft.util.Util`
creates the pool in a static initializer. The section-builder cap alone does
not limit the other work sharing that pool.

The cap is only applied when the property is unset, so `-Dmax.bg.threads=N`
opts out and keeps your value. `WorkerPoolCap` logs which of the two happened.

## Gradle Pass-Throughs

`gradlew` accepts these `-P` properties. Architecture selection applies to any
task; the rest apply to `runClient`, Fabric's standard Gradle task, and not to
clients the developer tools launch.

| Property | Effect |
|---|---|
| `-Parch=win64` or `-Parch=arm64` | Build and package one architecture instead of both |
| `-PrtxPreset=<low|medium|high|ultra>` | Sets `rtxremix.preset`, which selects Remix's graphics tier for that session only |
| `-PmcQuickPlayServer=<host:port>` | Join a server on launch |
| `-PbenchmarkJfrPath=<file.jfr>` | Starts a JFR recording, sets `rtxremix.benchmark.jfrPath`, and turns Java phases on |
| `-PbenchmarkTracyJavaMaxDepth=<n>` | Sets `rtxremix.profile.tracyJavaMaxDepth`; only applies when a JFR path is given |
| `-PbenchmarkJfrThrowables=true` | Adds exception and error throw events to that recording |
| `-PbenchmarkQuickPlayWorld=<name>` | Loads a single-player world on launch |

`mod_version` lives in `gradle.properties`; the build appends a timestamp, the
short commit hash, and a dirty marker to it.

## JVM Arguments

A development client and a fresh install share the unconditional tuning
arguments in `loom.runs.client.vmArgs` in [build.gradle](../build.gradle) and
`RECOMMENDED_JVM_ARGS` in
[packaging/installer.ps1](../packaging/installer.ps1). The developer tools
read `RECOMMENDED_JVM_ARGS` for every launch. Keep those unconditional
arguments identical in the same commit. Opt-in benchmark and profiling
arguments, such as the recording flags a benchmark run adds, are excluded: do
not copy them into every installed client. The installer keeps differing
existing arguments unless the user accepts replacement, but replaces
without asking any set an earlier installer wrote, in each form it wrote it. It
also replaces Prism memory that its record, `rtx-remix-installer.json` in the
instance folder, shows it wrote, while the user has not changed it. When you
change `RECOMMENDED_JVM_ARGS`, append the outgoing value to
`PREVIOUS_RECOMMENDED_JVM_ARGS` beside it so that upgrades recognise it.
`tools/tests/benchmark/test_installer_prism_jvm.py` checks the list against the
git history of the installer.

Prism has no command-line option for JVM arguments, so the developer tools
write them into the instance's `instance.cfg` on every launch: the recommended
arguments, then `launch.jvmArgs` from the tools configuration, then that
launch's preset and benchmark recording arguments. Prism refuses to launch an
instance whose JVM arguments hold a heap option such as `-Xmx` or `-Xms`, so
the tools move the last `-Xmx` and `-Xms` in that list into the instance's
memory settings instead, and a `-Xmx` in `launch.jvmArgs` still sets the
game's heap. To add your own arguments, such as an
`rtxremix.*` switch from this guide, list them in ignored
`tools/config.local.json`:

```json
{
    "launch": {
        "jvmArgs": ["-Drtxremix.vanillaVisibilityWalk=true", "-Drtxremix.logVisibleSet=true"]
    }
}
```

They apply to every launch from that checkout, benchmark runs included, so
remove an experiment's arguments when it is done. `JAVA_TOOL_OPTIONS` does not
reach a game Prism launches, because Prism removes it from the game's
environment; the tools print a warning naming `launch.jvmArgs` when it is set.
Other environment variables, such as `RTXREMIX_*` and `DXVK_*`, do reach the
game.

The Microsoft launcher profile gets `RECOMMENDED_JVM_ARGS` as written. Prism
refuses to launch an instance whose JVM arguments hold a heap option such as
`-Xmx` or `-Xms`, and gives the game its heap from the instance's memory
settings instead. The installer therefore writes `RECOMMENDED_JVM_ARGS` to the
Prism instance without its heap sizes, and sets the instance's memory from those
sizes unless a memory choice was saved for the instance in Prism. Without it the
instance gets Prism's global maximum, by default 4096 MiB on a machine with
6 GiB of RAM or more.

Path tracing does not remove Minecraft's allocation rate, it adds a render
thread that must not be paused.

These are the Minecraft 1.21.1 launcher defaults for supported Windows
arches: a 2 GB heap with G1GC. Measured on N1X, they showed none of the large
frame-time spikes seen with generational ZGC. Reducing allocation volume stays
necessary regardless of collector, since concurrent collector work competes with
the render thread.

The Intel driver workaround must keep this exact spelling:

```
-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump
```

`-XX:HeapDumpPath` is not a heap-dump setting here. Nothing in the tree sets
`-XX:+HeapDumpOnOutOfMemoryError`, so no dump is ever written. The argument is
there for its value, which says what it is for: it puts the names `javaw.exe`
and `minecraft.exe` on the process command line, where Intel's graphics driver
looks for them before applying its Minecraft profile. Copy the string exactly.
Tidying the file name removes the behaviour and leaves nothing behind to
explain it.

## Files In The Game Directory

Paths are relative to the game directory, which is the checkout-specific Prism
instance for a client the developer tools launch and the instance or profile
directory for an installed client. Portable Prism keeps the former at
`run-prism/instances/rtx-remix-dev/.minecraft`.

| Path | Owner | Access | Deleting it |
|---|---|---|---|
| `remix-sdk/` | mod | Replaced on full extraction, including when the version or architecture changes | Restored on the next launch even with a matching marker; a missing `d3d9.dll` also triggers full extraction |
| `rtx.conf` | mod | Replaced on full extraction; with a matching marker and runtime present, copied only if missing | Restored on the next launch |
| `.rtx-remix-version` | mod | Marker recording the extracted version and architecture | Forces full re-extraction of the runtime and `rtx.conf`, preserving existing files under `rtx-remix/` |
| `remix-sdk.old-*/` | mod | Left behind when the previous runtime was locked at upgrade time | Safe, and cleaned up opportunistically |
| `rtx-remix/mods/minecraft-logic/` | map or profile author | Missing shipped files are added on launch even with a matching marker; existing files, including edited confs, are never overwritten | A deleted shipped conf returns to its default on the next launch |
| `textures/` | mod | The shipped runtime DDS textures, read by name and replaced on full extraction; other files there are left alone | Restored on the next launch even with a matching marker |
| `config/rtxremix.json` | settings screen | Written when a settings screen closes, and deleted when nothing differs from the defaults | Resets every setting |
| `camera-paths/<world-key>/` | you | Your recordings | Loses them, so keep this one |
| `benchmark/` | benchmark harness | Trigger file, status heartbeat, results, and screenshots | Safe |
| `saves/<world>/rtx-remix/confs/` | map author | Read when you join that world | Part of the map, not of the install |
| `saves/<world>/data/rtx_remix_zones.dat` | mod, per save | Zone definitions for that world | Loses the zones defined in that world |
| `logs/latest.log` | game | Where the mod reports what it did and why | Safe |

## Resource Pack Inputs

Textures, material maps, and block light rules come from resource packs, not
from properties. Their formats are in [Resource Packs](RESOURCE_PACKS.md).

### Visibility traversal on Minecraft 1.21.4

Visibility preparation retains the single low-priority worker, pooled graph info,
allocation-free far-section probe, and bounded incremental propagation. Vanilla's
native octree owns coarse frustum culling, ordered visible sections, and the nearby
section list. Camera-ring movement stores the new section position and schedules
vanilla's terrain rebuild; the old separate coarse index and move-log reconciliation
are not used.

This changes the performance profile. Benchmark the native octree path on Windows
with the same world, camera path, and view distance before comparing it with the
1.21.1 baseline. Passing static selectors or standalone ring tests does not establish
visual, visible-set, or performance parity. The `rtxremix.logVisibleSet` diagnostic
now reports the section keys admitted to the octree; compare it with
`rtxremix.vanillaVisibilityWalk` enabled and disabled, then check actual visible and
nearby lists and the rendered scene.
