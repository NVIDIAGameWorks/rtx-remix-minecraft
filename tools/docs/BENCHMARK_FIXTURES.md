# Benchmark Fixtures

A fixture is one benchmark world: its save, camera paths, resource packs, and
configuration. Fixture payloads live under the git-ignored
`tools/assets/worlds/`; they are not committed to this repository. You can
import your own save without a package service. Fetching a published fixture
needs its configured remote and concrete version.

For running benchmarks, see [Benchmarking](BENCHMARKING.md).

## Layout

```text
tools/assets/worlds/<world>_<version>/   e.g. MyWorld_v0.1.0
  world/          Minecraft save copied into saves/rtx-benchmark in the game directory
  cameras/        camera paths as version 1 JSON
  resourcepacks/  optional per-world packs, folders or .zip
  config.json     optional per-world overrides
```

The game directory is this checkout's Prism instance (`<gameDir>` below).
Portable Prism keeps it at
`run-prism/instances/rtx-remix-dev/.minecraft`.

The version is part of the directory name, so several versions can sit on disk
at once. Drop a raw save directly under `<world>_<version>/` and the CLI
normalizes it into `world/` on the next launch, list, or import. A bare
unversioned `<world>/` directory works for ad-hoc runs but cannot be pinned.

Do not put resource packs under `world/`: that directory is the mutable save
payload, and `world update` replaces it wholesale from
`<gameDir>/saves/rtx-benchmark`.

## The Pin

The shipped configuration pins the demo world. To select your own fixture
locally, set its `name`, concrete `version`, and `camera` in ignored
`tools/config.local.json`:

```json
{
    "world": {
        "name": "MyWorld",
        "version": "v0.1.0",
        "camera": "benchmark"
    }
}
```

This is an example, not a downloadable fixture. Import or stage that
version locally before running it. `runner.ensure_world_staged` trusts an
existing local save with no package marker; if the directory is missing, or a
marker identifies another version, it requires configured `world.remote`
before fetching. The remote comes from the mod's
`packman/config.packman.xml`.

Version is `vMAJOR.MINOR.PATCH`: minor for world content, patch for
camera or config-only updates. Each published version is a complete independent
snapshot; versions are not incremental.

`run` and `world launch` fetch the pinned world lazily when it is missing or its
local marker no longer matches. Run a non-pinned on-disk version with
`--version vMAJOR.MINOR.PATCH`, which `--ci` ignores so unattended runs stay on the pin.

World and art-pack downloads require Packman's `.<package>@<version>` install
marker in the destination. If Packman reports success without installing the
fixture, the tooling waits a few seconds and retries the same pull once. A
second missing marker reports the destination and the most likely cause, a
failed final rename, instead of continuing to a layout error. Retry the fetch
or exclude that folder from antivirus, indexing, or sync scanning on the drive.

A non-pinned `--world` uses a local unversioned or staged versioned directory.
If no local save exists, import one or pass `--version` with a concrete version
available from the configured remote. `--ci` rejects non-pinned worlds.

Camera precedence, highest first:

1. `--camera`
2. the fixture's own `config.json` `camera`, `defaultCamera`, or `cameraPath`
3. the configured `world.camera` (selected pinned world only)
4. the built-in fallback name `simple`

This selects a name only. The matching version 1 JSON must exist; there is no
generated fallback path.

### Two places declare a default camera

| | declares | serves |
|---|---|---|
| fixture `config.json` `defaultCamera` | what the world says about itself | local and pinned world launches |
| merged configuration's `world.camera` | the fallback when the fixture has no camera choice | pinned runs, reviewable without fetching a fixture |

The fixture's camera choice takes precedence over `world.camera`. A new
fixture version can therefore change its default camera without requiring a
second camera-pin edit. An explicit `--camera` still wins over both.

- **Choose a different camera for one run:** pass `--camera`.
- **Change the fallback:** edit `world.camera` in `tools/config.local.json`.
- **Change what the world defaults to:** edit the fixture `config.json`,
  create a new fixture version, then select that version.

`doctor` and `run --dry-run` report the resolved camera name. Reproducibility
comes from a concrete world version and its immutable camera/config payload,
plus any recorded CLI override.

## Per-World Config

`tools/assets/worlds/<world>_<version>/config.json` deep-merges into the
active config after the world is selected, above shared, benchmark, and local
defaults. `world` is ignored here, so a fixture cannot redirect the benchmark
to a different world. CLI resource-pack and preset flags are applied afterwards
and win over overlapping fixture keys. A layer that names a different world or
art pack drops the pin keys it would inherit (`version`, `camera` and `package`
for `world`; `version` and `package` for `resourcePack`), while other keys such
as `remote`, `mode` and `stageName` still merge.

```json
{
    "defaultCamera": "flythrough",
    "resourcePacks": ["world-demo-pbr"],
    "screenshots": { "count": 5, "frames": [120, 360] },
    "worldSanitizer": { "stripEntitiesOnStage": true }
}
```

A fixture cannot choose the player: the game plays as the Microsoft account
selected in Prism's profile.

`resourcePacks` declares the enabled order of the world's own packs in
`options.txt`, using pack names without `file/` (zip entries may omit `.zip`).
The runner copies these packs from the fixture's `resourcepacks/` directory
and enables them after `vanilla` and Fabric's `fabric` mod pack, which supplies
the built-in baseline textures, and before the optional art pack. Omitting
`resourcePacks` enables no world packs. Prefer unpacked folders for clean diffs.

Older fixtures may still list `rtx-remix` for the retired baseline. The runner
logs to stderr and drops that entry unless a real world-local folder or ZIP has
that name, keeping `doctor --json` stdout machine-readable. Staging removes only
the old generated baseline folder, not unrelated user packs. World-local folders
named `rtx-remix` receive a `.rtx-remix-world-pack` marker in the staged copy so
later Gradle setup and main-menu staging preserve them even if they retain
baseline metadata. The source fixture is unchanged.

`worldSanitizer.stripEntitiesOnStage` deletes `<gameDir>/saves/rtx-benchmark/entities`
from the staged copy, leaving the source fixture untouched. It removes saved
mobs, dropped items, item frames, boats, and minecarts, but not terrain chunks
or block entities. Use it per-world for worlds with corrupt entity-region files;
it is a stability workaround, not a substitute for testing entity rendering.
The one-off equivalent is `--strip-entities` on `run` or `world launch`.

## Authoring And Updating

Author in `tools/assets/worlds/<world>_<version>/`, naming the directory for the
version you intend to keep. A local workflow needs no remote world package:

```batch
tools\rtx.bat world import <save-directory> --name MyWorld_v0.1.0
tools\rtx.bat world launch MyWorld --version v0.1.0 --resource-pack none
```

A relative save directory, here or in `world update --source`, starts at the
repository root, not the directory you run the command from.

In game, make the world changes and record the `benchmark` camera:

```text
/rtx camera record benchmark
```

Press `R` to start recording, fly the route, then press `R` again to save.
The managed session writes the recording into the fixture's `cameras/` as well
as `<gameDir>/camera-paths/<fixture-directory>/`. See
[Camera Paths](../../docs/CAMERA_PATHS.md) for the controls and strict version 1
schema. Set stable gamerules where needed:

```text
/gamerule doDaylightCycle false
/gamerule doWeatherCycle false
/gamerule doMobSpawning false
/time set 6000
```

Close the client before copying the staged save back:

```batch
tools\rtx.bat world update MyWorld_v0.1.0
tools\rtx.bat run --world MyWorld --version v0.1.0 --camera benchmark --resource-pack none --runs 1
```

`world update` takes the name of an existing fixture directory, including any
version suffix, and stops if there is no such directory; `world import` creates
one. It replaces only `world/` from `<gameDir>/saves/rtx-benchmark`;
it does not recover a version from the last launch. Cameras and fixture config
remain beside that directory. Confirm that the staged save is the one you mean
before updating. For automated authoring, write strict version 1 camera JSON
directly into `cameras/`: `/rtx camera record` only arms player input.

To reuse an existing recording, copy its version 1 JSON into the destination
fixture's `cameras/` directory and pass its filename without `.json` as
`--camera`. `world import` copies the save only, not a source fixture's camera
or resource-pack directories. Copy those separately when importing a complete
fixture. `camera list MyWorld --version v0.1.0` inventories the files;
`run --dry-run` validates the selected camera.

### Player Record In level.dat

The host player's record in `level.dat` under `Data.Player` includes
`abilities.flySpeed` and `walkSpeed`. `runner.update_world` copies the save
without normalizing those values. A fixture saved after fast flight can
therefore change interactive play and camera recording speed for everyone,
increasing the chunk-streaming workload. Benchmark playback sets the camera
pose every frame and is a different case.

Before distributing a fixture intended for ordinary movement, inspect both
`level.dat` and the fallback `level.dat_old` with a type-preserving NBT viewer.
For Minecraft 1.21.1, the ordinary ability values are float `flySpeed: 0.05`
and `walkSpeed: 0.1`; preserve intentional alternatives as fixture metadata,
not accidental authoring state. A save that Minecraft 26.x has written keeps
the host player in `players/data/<uuid>.dat` instead, with the same
`abilities` compound; inspect that file in a fixture saved from 26.x.

### Zone Coverage

A successful flythrough does not prove zone or world-conf behavior was exercised.
Check for marker command blocks whose text begins `rtx zone define`,
`world/data/rtx-remix-renderer/zones.dat` (`world/data/rtx_remix_zones.dat` in a 1.21.1 save), and `world/rtx-remix/confs/`. The sources
`MarkerBlockScanner`, `ZonePersistentState`, `ZoneRegistry`, and
`ConfLayerDriver` define their separate roles. With none of these inputs, a
fixture can exercise only empty zone/world-layer paths.

Author a zone with `/rtx zone define` or a marker command block, include the
world-owned conf if testing world overrides, and place the camera route through
its fade bounds. Follow the
[zone author guide](../../packaging/rtx-remix/mods/minecraft-logic/README.md#quick-start-a-zone-and-shipping-it-in-your-map);
do not assume any particular downloaded fixture contains this coverage.

### Editing A Fixture Outside The Game

Bulk edits to a curated save can be made outside the game under
`tools/assets/worlds/<world>_<version>/world/region/`. Back up the save first.
No managed client opens that fixture directly: `world launch` copies it into
`<gameDir>/saves/rtx-benchmark` again. Edit the fixture, launch to restage and inspect
it, then distribute a new minor version for block/content changes. Do not run
`world update` after such an edit unless the staged save was refreshed
after the edit, or it overwrites the edited `world/`. To edit the staged save
instead, first confirm no client holds its `session.lock`, then use
`world update` to copy it back.

An Anvil region has an 8 KiB header: a 4 KiB location table with a three-byte
sector offset and one-byte sector count per chunk, then a 4 KiB timestamp table.
At the allocated sector, the chunk record starts with a four-byte length and
one-byte compression scheme. Respect the actual scheme and external-payload
flags; do not assume every file is zlib or that a minimal reader supports every
Minecraft version.

For a custom editor:

- Preserve NBT scalar types and list element types. Prove the writer by
  byte-exact round trips of real decoded chunks before writing changes.
- Re-serialize only changed chunks. Copy untouched compressed payloads verbatim
  and confirm they remain byte-identical after rebuilding the region.
- After block edits, discard stale `Heightmaps` and set `isLightOn` to 0 so the
  game recomputes them. For the 1.21.1 section format, rebuild `block_states`
  palettes from used entries and repack `data` at
  `max(4, bitLength(paletteSize - 1))` bits per entry without entries straddling
  a long. A single-entry palette omits `data`. Wrong array lengths make the
  chunk unreadable.

Deleting a whole chunk regenerates a full 16x16 column through the world's
height and can erase hand terraforming or leave seams. Prefer block-level edits
for curated content. MCA Selector and Amulet provide chunk-level and block-level
GUI alternatives, respectively; check support for the save's format first.

## Fetching And Version Inventory

Local authoring needs no package service. To fetch published fixtures, set
`world.remote` or `resourcePack.remote` in `tools/config.local.json`, alongside
the fixture's name and concrete version. Optional `world.package` and
`resourcePack.package` override the derived package names for those pins.
These illustrative names must be replaced with packages you can download:

```json
{
    "world": {
        "name": "ExampleWorld",
        "version": "v1.0.0",
        "package": "example_world",
        "remote": "example-remote"
    },
    "resourcePack": {
        "mode": "pinned",
        "name": "ExampleArt",
        "version": "1.0.0",
        "package": "example_art",
        "remote": "example-remote"
    }
}
```

`world fetch` and `resourcepack fetch` download the configured pins using
remotes defined in the mod's `packman/config.packman.xml`. See
[Configuration](../README.md#configuration).

`world versions` and `resourcepack versions` report the configured pin and
locally staged versions only, including a pin that is not staged. They make no
remote listing request. Their JSON reports contain `name`, `pinned`, and
`versions`; each version entry contains `version`, `staged`, and `pinned`.

Both fetch commands use the configured pin by default or accept
`--version <version>` for a concrete version. An unpinned name requires that
explicit version. Fetching does not change the pin. Select an art-pack version
in `tools/config.local.json`, or pass `--resource-pack <name>@<version>` for
one launch or run.

```batch
tools\rtx.bat world versions
tools\rtx.bat world fetch
tools\rtx.bat resourcepack versions
tools\rtx.bat resourcepack fetch
```

Keep each distributed version immutable. A camera/config-only change merits a
new patch version; a world-content edit merits a minor version. A pin change
and the asset it names must reach consumers together.

## Runtime Camera Storage

The schema, the commands, and the two playback clocks are in
[Camera Paths](../../docs/CAMERA_PATHS.md). What follows is the fixture side:
where a path lands on disk and which copy wins.

The game stores camera paths per world under
`<gameDir>/camera-paths/<world-key>/<name>.json`. For an ordinary session the
key comes from the save folder name; for a managed session it is the
`cameraStorageKey` the trigger supplied, which is the fixture directory name
with its version suffix, so paths from two world versions cannot collide.

A managed target may also carry a canonical directory, the fixture's own
`cameras/`. A recording then writes both copies and the in-game result reports
both locations; without a canonical directory only the runtime copy is written.
See [Benchmarking](BENCHMARKING.md) for the trigger fields that carry these.

Loading resolves, in order: the runtime copy when it is newer than the
canonical one, then the canonical copy, then the runtime copy, then a
`fixture/` subdirectory of the runtime directory. That last slot exists so a
launcher can own and replace a staged read-only set of paths without deleting
recordings a developer made in the same world: an authored path in the parent
directory always wins over a staged path of the same name. Rename or remove the
parent copy to test a newly staged one.

After re-recording a distributed fixture camera, create a new version and update
the pin. Refresh every packaged or cached consumer that embeds the old fixture.

## Camera Schema Migration

Camera schema version 1 is a deliberate clean break. Any fixture camera still
containing per-waypoint `frames` is rejected by the game, even though
`camera list` will happily inventory the filename: that command does not parse
camera JSON.

`run`, `run --dry-run`, and `doctor` validate the selected camera before
fetching an SDK, building, or launching. Validate each camera before
distributing a fixture, not just the default path.

To migrate a fixture:

1. `tools\rtx.bat world launch <world> --version <version> --resource-pack none`
2. Re-record each camera with `/rtx camera record <name>`
3. Confirm root `version: 1` and waypoint `timeMs`, then `/rtx camera play <name>`
4. Run one benchmark with that path
5. Distribute a new patch version, update its pin, and refresh consumers

Treat a fixture version and all of its cameras as one release unit. Never
distribute or pin a fixture that mixes legacy and version 1 paths.
