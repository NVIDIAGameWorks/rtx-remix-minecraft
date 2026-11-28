# Authoring RTX Remix Looks For Minecraft

This guide sits beside the profile files it describes, in
`<gameDir>/rtx-remix/mods/minecraft-logic/`. It is for map makers and lighting
artists: you edit text files and run commands in chat, and the mod fades your
looks in and out as the game changes around the player.

If you only remember four things:

1. `rtx.conf` in the game folder is the baseline look.
2. Each file under `confs/` is a sparse profile. Put in only the `rtx.*`
   options that situation should change.
3. The mod drives each profile's strength from 0 to 1, and the runtime blends
   it over the profiles below it, one option at a time.
4. After editing a file, run `/rtx conf reload`.

## Where A Setting Can Come From

Three things can decide what a rendering option is worth. When they disagree,
the later one in this list wins.

**Conf files**, which is everything in this guide. `rtx.conf` is the baseline
and the profiles under `confs/` fade over it by situation.

**The mod's settings screen**, under Options, then RTX Remix Settings. Most of
it never argues with a profile. The controls on the Image & Exposure page are
the exception: moving one, or loading a saved value for one, takes that
option away from every profile until the game restarts. If a player has decided
the picture is too dark, no map should darken it again a minute later.

**The Remix runtime's own overlay, under Alt+X.** The advanced menu. Anything
changed there wins over both of the above and is saved by Remix separately from
the mod's settings.

The player's side of this is in the settings guide with the project's source.
The Alt+X overlay's Option Layers view names the current owner of any option,
which is the answer when a profile has the strength you expect and no visible
effect.

## Quick Start: A Time-Of-Day Look

The recognised time profiles under `confs/time/` are `dawn.conf`, `day.conf`,
`dusk.conf`, and `night.conf`.

For a first edit that is easy to see, give all four the same option with
different values. These are deliberately exaggerated, not art direction:

```ini
# confs/time/dawn.conf
rtx.localtonemap.exposurePreferenceSigma = 3.5
# confs/time/day.conf
rtx.localtonemap.exposurePreferenceSigma = 4.0
# confs/time/dusk.conf
rtx.localtonemap.exposurePreferenceSigma = 3.0
# confs/time/night.conf
rtx.localtonemap.exposurePreferenceSigma = 2.5
```

Then reload and walk the clock:

```text
/rtx conf reload
/rtx camera time 500
/rtx conf status all
/rtx camera time 11500
/rtx camera time 12500
/rtx camera time 18000
```

`/rtx camera time` sets the world clock and needs no cheats. Right after tick
500, status should report `mc.time.dawn = 1.0` and `mc.time.day` near `0.5`;
the clock keeps running while you type, so the second number drifts. Those
strengths feed the blend and confirm the schedule even when the picture barely
moves, and a look lands on the next frame rather than creeping toward it.

## Quick Start: A Zone, And Shipping It In Your Map

A zone is two independent halves. The `.conf` file says what the place looks
like; a zone definition says where it applies and how it fades.

Create `zones/lighthouse.conf` under `confs/`:

```ini
rtx.volumetrics.enable = True
rtx.volumetrics.transmittanceMeasurementDistanceMeters = 80.0
rtx.volumetrics.singleScatteringAlbedo = 0.85, 0.90, 1.0
```

Stand where the lighthouse is and run:

```text
/rtx conf reload
/rtx zone define lighthouse --inner 10 --outer 30
/rtx zone status
```

The profile is at full strength within 10 blocks, fades to nothing between 10
and 30, and is off beyond 30. The strength eases toward that target with a
half-second half-life, so wait about five seconds after defining a zone, or
after moving, before judging it. In single-player the definition is saved into
the world and comes back the next time you open it.

**To ship the look with the map**, put the same file under
`<world>/rtx-remix/confs/zones/lighthouse.conf` inside the save instead. The
save folder is what already gets distributed: zip the world, send it, and the
looks travel with it, the same way `datapacks/` does.

A command block can define the geometry instead of chat, which keeps that half
in the map too. Put `rtx zone define lighthouse --inner 10 --outer 30` in an
Impulse, Unconditional, Needs Redstone block at the centre of the zone, leave
it unpowered, and turn Track Output off. The scanner reads the block's text as
marker data when the chunk loads; it does not run the command, although vanilla
still will if something powers the block.

Zones are single-player, or a LAN host's own client. Where A Zone Is Stored,
below, says what a client connected to a dedicated server can and cannot do.

A marker is discovered only when its chunk loads. Keep its outer radius within
the lowest chunk-load distance the map targets, at 16 blocks per chunk of
render distance; [Where A Zone Is Stored](#where-a-zone-is-stored) explains the
alternative for larger zones.

## Where Files Live

Profiles come from two roots.

| Root | Path | Use it for |
|---|---|---|
| Mod | `<gameDir>/rtx-remix/mods/minecraft-logic/confs/` | Archetypes that suit any world: a cave look, a biome, weather, time of day |
| Map | `<world>/rtx-remix/confs/` | Looks that only make sense in one map: a named ruin, a set piece |

**A map profile does not replace the mod archetype of the same name. It
resolves above it, one option at a time.** A map's `zones/cave.conf` can set
the two options it wants different and inherit the rest from the shared
archetype, because one zone strength drives both files and the runtime picks
per option by priority.

Two limits come with map profiles. They are read when you join a world and
dropped when you leave, so one map's look never bleeds into the next. And only
zone and biome profiles are read from a map: water, weather, time of day,
automatic view distance, and volumetric settling are global looks rather than
map content.

`/rtx conf reload` always reads the copy belonging to the Minecraft instance
that is running. In Prism, `<gameDir>` is the instance's own `.minecraft`
folder, not the shared `%APPDATA%\.minecraft`. An upgrade adds profiles you do
not have and updates a profile you never changed, but never overwrites one you
edited, so your tuning survives. To take a new shipped default for a profile
you edited, delete your copy; it returns on the next launch.

## Write A Profile

The syntax is the same as `rtx.conf`:

```ini
# Comments start with #
rtx.someFloat = 1.25
rtx.someBool = True
rtx.someColor = 0.6, 0.9, 0.89
```

**Where option names come from.** They belong to the RTX Remix runtime, not to
this mod. Four places to find one:

- `rtx.conf` in the game folder, the baseline and the largest worked example
  you have.
- The profiles that ship here, `confs/water.conf` and `confs/zones/cave.conf`
  in particular.
- The Alt+X menu. Find the control you want and its tooltip names the `rtx.*`
  option behind it. Copy that name into your file.
- The runtime itself, developed in the open at
  <https://github.com/NVIDIAGameWorks/dxvk-remix>, which is the last word on
  what an option means.

**Naming rules.** A zone file name is one word with no spaces and no leading
`--`; `zones/lighthouse.conf` creates the channel `mc.zone.lighthouse`. A biome
file name is the exact path part of a vanilla Minecraft 1.21.1 biome id, in
lowercase: `minecraft:desert` is `biomes/desert.conf`. A name the mod does not
recognise is skipped with a warning in `logs/latest.log` and never appears in
`/rtx conf status all`.

**Stay sparse.** Author only the options that situation owns. Copying all of
`rtx.conf` into a profile makes it participate in every unrelated setting. A
blank or comments-only file is valid and does nothing, which is what the
shipped empty profiles are. A misspelled option name changes nothing either,
even though the profile still loads and still reports a strength.

For a quick live sweep, use a profile that `/rtx conf status` shows at strength
`1.0`, such as `time/day.conf` in the daytime Overworld or `water.conf` while
submerged. Append an option, run `/rtx conf reload`, and judge the next update.
This avoids relaunching for every value. Initialization-only runtime options
still need a restart, and stronger layers still win. Edit the profile in the
game folder of the instance you are playing, or in that world's
`rtx-remix/confs/` for map-specific profiles. Those files are the saved source
of your lighting changes.

**Endpoints.** For a value that should animate between named states, put the
same option in every adjacent profile. Leaving it out of one does not create an
implicit endpoint; it exposes whatever is below instead.

## How Blending Resolves

A profile is not a snapshot of the renderer. For each option, the runtime looks
only at the active profiles that name it, and an option a profile leaves out
falls through to the ones below.

Priority sets the order, not the size of an effect. A numeric option in a
stronger profile at strength 0.25 blends 25 percent of that profile over
whatever the weaker profiles resolved to. At strength 1 it covers them
completely, for that option only.

```text
rtx.conf:      distance = 100
rain.conf:     distance = 60, strength = 0.50
zone profile:  distance = 20, strength = 0.25

after rain = lerp(100, 60, 0.50) = 80
after zone = lerp(80, 20, 0.25)  = 65
```

That is a nested blend, not addition and not an average of everything active.

| Option type | How it resolves |
|---|---|
| Float, and vectors of floats | Continuous nested interpolation by strength |
| Bool, integer, string, integer vector | The strongest profile that names it switches it on once its strength reaches that profile's threshold. Nothing interpolates |

The threshold comparison is inclusive, so a zone threshold of 0.01 switches a
bool very near the start of the fade rather than at its midpoint.

**Equal priority.** All zone profiles from one root share a priority, and so do
all biome profiles. When two of them name the same option, the alphabetically
earlier path wins, which in one folder means `a.conf` beats `z.conf`. They do
not average. Give overlapping looks different options, or drive one profile
from several places with a shared channel.

## Reference

### Profile Catalog

| Profile | What drives its strength | Priority | Discrete threshold | Timing |
|---|---|---:|---:|---|
| `confs/water.conf` | Camera underwater: 0 or 1 | 20000 | 0.50 | Immediate |
| Map `zones/<name>.conf` | Distance falloff: 0 to 1 | 11500 | 0.01 | Half-second half-life |
| `confs/zones/<name>.conf` | Distance falloff: 0 to 1 | 11000 | 0.01 | Half-second half-life |
| Map `biomes/<biome>.conf` | Camera is in that biome: 0 or 1 | 6500 | 0.01 | Half-second half-life |
| `confs/biomes/<biome>.conf` | Camera is in that biome: 0 or 1 | 6000 | 0.01 | Half-second half-life |
| `confs/weather/thunder.conf` | Vanilla thunder gradient: 0 to 1 | 5500 | 0.01 | Immediate; vanilla ramps the gradient over about five seconds |
| `confs/weather/rain.conf` | Vanilla rain gradient: 0 to 1 | 5000 | 0.01 | Immediate; vanilla ramps the gradient over about five seconds |
| `confs/time/night.conf` | Fixed Overworld schedule | 4003 | 0.50 | The clock curve below |
| `confs/time/dusk.conf` | Fixed Overworld schedule | 4002 | 0.50 | The clock curve below |
| `confs/time/day.conf` | Fixed Overworld schedule | 4001 | 0.50 | The clock curve below |
| `confs/time/dawn.conf` | Fixed Overworld schedule | 4000 | 0.50 | The clock curve below |
| `confs/volumetrics/settling.conf` | A weather gradient moving, or a smoothed profile still easing: 0 or 1 | 3100 | 0.01 | Immediate, held 0.75 s after the last movement |
| `confs/view_distance.conf` | Render distance, 2 to 32 chunks | 3000 | 0.01 | Immediate on each change |

A smoothed strength halves its remaining distance to the target every half
second and snaps when the gap becomes imperceptible, so a full swing looks
settled after about five seconds. Each map row sits directly above its mod
counterpart, which is what makes the per-option override work.

### Time Of Day

| Minecraft ticks | Result |
|---|---|
| `[23000, 24000)`, ending at tick 0 | Night fades into dawn across the day boundary |
| `[0, 1000)` | Dawn fades into day |
| `[1000, 11000)` | Day holds |
| `[11000, 12000)` | Day fades into dusk |
| `[12000, 13000)` | Dusk fades into night |
| `[13000, 23000)` | Night holds |

Landmarks are 0 sunrise, 6000 noon, 12000 sunset, 18000 midnight. The four file
names, the schedule, and the curve are fixed by the mod: files such as
`06000.conf` are not discovered.

Time profiles apply only in the Overworld. In the Nether, the End, and custom
dimensions all four strengths are held at zero, so biome, weather, zone, water,
and `rtx.conf` resolve normally.

The four files are one family. If any of them fails to load, the mod drops all
four and reports them unloaded. Fix the failing file, then reload.

Because night is the strongest of the four and sits at exactly 0.50 during the
wrap to dawn, a discrete option in a time profile switches on the tick after
the midpoint rather than exactly on it.

### Zone Geometry

```text
rtx zone define <name> [--inner r] [--outer r] [--center x y z]
                       [--channel mc.zone.<stem>]

rtx zone define <name> --box x1 y1 z1 x2 y2 z2 [--fade d]
                       [--channel mc.zone.<stem>]
```

- Inside `--inner` the strength is 1, between the radii it eases down, and at
  `--outer` and beyond it is 0.
- With neither radius the defaults are inner 10 and outer 30. A lone `--inner`
  implies an outer 20 blocks further out; a lone `--outer` implies an inner 20
  blocks in, never below 0.
- A box is full strength inside and fades over `--fade` blocks of straight-line
  distance outside it, defaulting to 20. Box corners are absolute, so
  `--center` cannot be used with `--box`.
- In chat the centre defaults to where you stand; in a command block, to the
  block itself.

### Names And Channels

| Term | What it means |
|---|---|
| Zone name | The identity of one sphere or box. Defining the same name again replaces it |
| Channel | The profile strength a zone drives. It defaults to `mc.zone.<zone name>` |
| Conf file name | `zones/<stem>.conf` in either root creates the channel `mc.zone.<stem>` |

Normally all three match and `/rtx zone define cave` is all you need. To give
several separate volumes one look, name each volume differently and point them
at one channel:

```text
/rtx zone define cave_north --channel mc.zone.cave --center 100 40 200 --inner 10 --outer 30
/rtx zone define cave_south --channel mc.zone.cave --center 180 35 260 --inner 15 --outer 40
```

Zones sharing a channel combine with the larger of their strengths, not the sum
or the average. A custom channel has to name a profile that exists. Zone names
share one namespace, so do not reuse one: the newest definition or scan
replaces the active entry.

### Where A Zone Is Stored

| Defined by | Single-player | On a server |
|---|---|---|
| `/rtx zone define` | Saved into `<world>/data/rtx_remix_zones.dat` and restored on join | Local to your client for that session, not saved and not shared |
| A marker command block | Read as chunks load, and on `/rtx zone rescan`. Stays in the block, not in the saved file | Unavailable |
| A map profile under `<world>/rtx-remix/confs/` | Loaded on join, dropped on leave | Unavailable |

`rtx_remix_zones.dat` is optional. A map whose zones are all command blocks
never creates one. `/rtx zone remove` deletes the saved definition in
single-player, but a marker block can register the same name again on a later
chunk load or rescan, so remove the block as well. Zone coordinates are not
scoped to a dimension: the same coordinates in another dimension are inside the
zone too. Opening a world to LAN does not send zones to guests.

A marker whose radius extends beyond its chunk's load distance is first
discovered from inside the zone. Its look then pops in on the first approach,
softened only by the half-second half-life smoothing, instead of fading from the outer
radius. Define a large zone with `/rtx zone define` instead: the saved world
definition loads on join independently of its marker chunk. Removing a marker
block does not immediately remove an already active zone; remove the zone or
reload the world too.

### Biomes

Biome profiles are opt-in. `plains.conf` is a commented example you can copy or
rename, one file per biome. The mod finds them at startup and on every reload.
The camera's biome drives its file to 1 while every other biome profile eases
to 0, so a biome with no file simply lets the lower profiles show through.

All biome profiles from one root share a priority, and during a transition the
outgoing and incoming strengths overlap. If both name the same option, path
order decides during that overlap, so it is not a guaranteed two-way crossfade.
Test neighbouring biomes that author the same option.

### Weather

Clear weather contributes nothing: there is no `clear.conf`, and rain and
thunder simply fall away to the time profiles below. Both follow the vanilla
gradient directly, which already ramps over about five seconds, so no further
smoothing is added; [volumetric settling](#volumetric-settling) keeps the fog
in step with the ramp. Thunder is stronger than rain.

### Water

`water.conf` is the strongest profile and switches the moment the camera enters
or leaves water. It is also the one profile every install must have: if it
cannot be loaded, the whole system reports itself inactive.

The water surface and the underwater fog are separate. `/rtx water color` and
`/rtx water distance` rebuild the surface material, while `water.conf` owns the
underwater volumetrics. Their distances are different values today, and keeping
them equal is an art decision rather than a rule.

The runtime has one global homogeneous volumetric medium, and this mod keys
it to the camera. When submerged, `water.conf` supplies it. Otherwise it comes
from `rtx.conf` and active profiles such as rain, thunder or the cave.
`volumetrics/settling.conf` only shortens accumulation; it does not attach
another medium to water.

Seen from above, a pool's interior is absorption through a translucent surface,
not a separately lit volume. A light can illuminate the seabed through colored
water while the water column itself stays dark and its reflected beam is
absent. This is a runtime limitation, not a broken conf. Neither an underwater
fog adjustment nor a brighter light creates that missing above-water volume.

### Automatic View Distance

`rtx.conf` holds the 32-chunk fade band and `confs/view_distance.conf` holds
the 2-chunk band, and the mod blends between them so the band is always 70 to
80 percent of the render distance in use.

It sits below every situational profile on purpose, so a zone, biome, weather,
or time profile can override the fade keys deliberately.

Editing one endpoint without the other breaks that mapping. `/rtx viewdistance`
reports what this layer intends, which is not necessarily what wins.

### Volumetric Settling

Remix accumulates fog and light shafts over many frames
(`rtx.volumetrics.maxAccumulationFrames`). That history keeps them stable, and
it is also why they lag: when a profile moves the medium, the sky and direct
light switch at once while the fog keeps the old medium.

`confs/volumetrics/settling.conf` is the mod's answer. Its strength is 1 while a
transition is in progress and returns to 0 once the scene has been still for
0.75 s. A transition is in progress while the vanilla rain or thunder gradient
changed since the previous frame, or while any smoothed profile that sets at
least one option is still easing toward its target. A profile that sets no
options is inert, so crossing into or out of a comments-only file never opens
the window.

Shorter history and less temporal reuse let volumetrics follow a change sooner,
at the cost of more noise.
[settling.conf](confs/volumetrics/settling.conf) contains the current values.
At strength 0 its keys fall through to `rtx.conf` and the runtime defaults.
The runtime stores froxel history age relative to the accumulation limit, so
switching the limit rescales the existing history rather than resetting it,
and neither edge flashes.

The layer sits below every art profile, so a weather, time, biome, or zone file
that sets either key wins while it is active. The strength is not smoothed; it
is already an envelope. `/rtx conf status` lists the layer as
`mc.volumetrics.settling`.

## Commands

These are the ones you use while authoring. The rest are in the in-game guide
with the project's source.

| Command | What it does |
|---|---|
| `/rtx conf reload` | Re-reads every profile in both roots and re-scans the `zones/` and `biomes/` folders. It does not scan command blocks |
| `/rtx conf status` | Shows the profiles with a nonzero strength, and anything that failed to load |
| `/rtx conf status all` | Everything, idle profiles included, naming each one's root. A map profile is listed as `world/<channel>` |
| `/rtx zone define <name> ...` | Creates or replaces a zone |
| `/rtx zone status` | Each zone's channel, its strength where you stand, and a hint when its profile is missing |
| `/rtx zone list` | The zone names and their channels |
| `/rtx zone remove <name>` | Removes a zone |
| `/rtx zone rescan` | Re-reads marker command blocks in the loaded chunks. It does not re-read conf files |

Status uses three words. **Active** means loaded with a nonzero strength, which
does not prove the file contains a valid option. **Idle** means loaded at zero.
**Unacquired** means there is no live layer: it failed to load, or it was
dropped as part of the time family or a water failure. A reload reports success
only when water loaded and the selected quality tier came back with it, so
check status after a reload while you are authoring.

## Troubleshooting

**Everything reports inactive.** A reload failed. `logs/latest.log` carries the
path and the error. If the mod could not find its payload at all, the renderer
does not start either, and the same log says so.

**The strength changes but the image does not.** Check, in order: that you
edited the copy belonging to the running instance; that you ran
`/rtx conf reload`; that the option name is exact and the value is the right
type; that the file is not comments-only; that no stronger profile, settings
screen override, or Alt+X value owns the same option; that you are in the
Overworld, for a time profile; and that every adjacent profile carries the
option, for a value meant to animate between endpoints.

`/rtx conf status` reports strength, not the resolved value or the winner; the
Option Layers view in the Alt+X menu names the winner.

**A player changed a setting and the map's look stopped applying.** The controls
on the Image & Exposure page take an option away from profiles for the rest of
the session. Reset to Defaults restores the baseline immediately, and one
restart hands the option back to the profiles.

**A zone has strength but no look.** The channel needs a matching file.
`mc.zone.lighthouse` needs `zones/lighthouse.conf` in the mod root, in the map
root, or both, then a reload. A zone definition on its own never invents a
profile.

**A zone profile exists but never gains strength.** The file creates the
profile, not the geometry. Use `/rtx zone define` or a marker block, then check
`/rtx zone status`.

**All four time profiles are unloaded.** One of them failed, so the family was
dropped together. Check all four, read the log, and reload.

**A light illuminates the seabed but not the water above it.** See
[Water](#water): the runtime medium follows the camera, not individual bodies
of water.

## Quality Presets

A preset is a different thing from every profile above. A profile reacts to the
game; a preset is one of Remix's own graphics tiers, `low`, `medium`, `high`,
or `ultra`, and there is nothing to author for it. A player picks the tier in
Remix's Graphics Settings menu, which the button on the RTX Remix Settings
landing page opens, or sets one for a single session with `/rtx preset <name>`
in game or `-Drtxremix.preset=<name>` at launch.

Selecting a preset writes `rtx.graphicsPreset` to Remix's User Settings layer
and nothing else: Minecraft's options and the mod's settings are untouched, and
the write is not saved, so a session preset lasts until the game exits. Remix
expands the tier into bounce counts, volumetric quality, the NRC tier, and the
DLSS profile in its Quality layer, which outranks every profile in the stack
above, so a profile's value for one of those keys can be masked by the tier.

Do not set `rtx.graphicsPreset` in a profile. When it is left on Auto, Remix
resolves it from the GPU and writes the result to User Settings at startup,
which outranks every conf layer.

To confirm a tier applied, inspect Option Layers in the Alt+X developer menu.
`rtx.graphicsPreset` in User Settings proves the request reached the runtime;
a non-empty Quality Presets layer containing the tier's dependent keys proves
it expanded. The Custom tier leaves those tier values unset. Runtime enum
values and expansion rules are owned by the matching dxvk-remix source, not by
the mod's settings file.

### Ray Reconstruction Preset Ownership

Quality Presets can contain more than the graphics tier's values. With Ray
Reconstruction active and `rtx.rayreconstruction.pathTracerPreset` on its
default, the runtime writes its Ray Reconstruction sampling settings into that
layer. Examples include `rtx.di.initialSampleCount`,
`rtx.di.enableBestLightSampling`, and spatial/disocclusion sample counts.
Option Layers identifies their owner; a gameplay profile cannot override a
Quality Presets value.

Setting `rtx.rayreconstruction.pathTracerPreset = 0` is not a per-key change.
It resets the whole preset's tuning, including ReSTIR GI, lobe probabilities,
NEE cache and filters, to runtime defaults. These option registrations and
preset contents belong to dxvk-remix and may change with a runtime update.

For a one-key experiment, edit it in the developer menu and accept **Unblock
Option** if prompted. This selects Custom and promotes that option to User
Settings, where the menu and `SetConfigVariable` can edit it but conf layers
still cannot. Custom also gives up the tier's automatic tuning, so inspect the
other quality values rather than assuming only that one option changed.
