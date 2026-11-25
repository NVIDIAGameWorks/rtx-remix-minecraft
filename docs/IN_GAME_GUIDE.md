# In-Game Guide

Everything the mod adds inside the game: the chat commands, the keys, the new
block, and the small habits that make a screenshot or a bug report worth
sending. If you are still installing, start with [Playing](PLAYING.md).

Commands are typed in chat and are client-side, so they change only what you
see. They do not need cheats, and they work in single-player and on a server.
`/rtx`, `/rtx on`, `/rtx off`, and `/rtx status` work even when the renderer is
not running. Renderer-dependent commands need it: if a command answers that the
renderer is not initialized, run `/rtx status` first.

## Toggles And Status

| Command | What it does |
|---|---|
| `/rtx` | Turn path tracing off, or back on |
| `/rtx on` | Turn path tracing on |
| `/rtx off` | Turn path tracing off, giving you Minecraft's own renderer |
| `/rtx status` | Report the renderer state, including `Renderer Initialized` and `SDK Available` |
| `/rtx world` | Toggle path-traced terrain |
| `/rtx entities` | Toggle path-traced mobs, players, and dropped items |
| `/rtx blockentities` | Toggle path-traced chests, signs, banners, and beds |
| `/rtx particles` | Toggle path-traced particles |
| `/rtx sky` | Toggle the path-traced sky |
| `/rtx heldlights` | Put out or relight all lights held by the local player, like Toggle Held Lights (default `G`) |
| `/rtx heldlights underwater` | Toggle whether held open flames stay lit when the holder's eyes are under water; they go out by default |

The capture toggles are diagnostic. Turning one off tells you which part of the
scene a problem belongs to, which is useful in a bug report.

## Looks

Most of these have a control in the settings screen as well. The menu is easier;
the commands are here because they report their current value and are quicker to
repeat.

| Command | What it does |
|---|---|
| `/rtx preset` | List Remix's graphics tiers |
| `/rtx preset <name>` | Set Remix's graphics tier for this session only; the Graphics Settings menu keeps its own saved choice |
| `/rtx viewdistance` | Describe the band over which distant terrain fades out |
| `/rtx viewdistance auto` | Toggle matching that band to Minecraft's render distance |
| `/rtx viewdistance auto on` | Turn the automatic match on |
| `/rtx viewdistance auto off` | Turn the automatic match off |
| `/rtx culldistance` | Show the light and entity cull distances, in chunks, and the light reach, in blocks |
| `/rtx culldistance light <chunks>` | Stop drawing lights past this many chunks. `0` follows the render distance |
| `/rtx culldistance entity <chunks>` | Stop drawing entities past this many chunks. `0` follows the render distance |
| `/rtx culldistance reach <blocks>` | How far from you, in blocks, a torch still casts light. Brighter lights reach further |
| `/rtx sky ambient` | Toggle the soft area light the sky casts on the world |
| `/rtx sky ambient day <strength>` | Strength of that soft light during the day |
| `/rtx sky ambient night <strength>` | Strength of that soft light at night |
| `/rtx sky sun <brightness>` | Sun brightness |
| `/rtx sky moon <brightness>` | Moon brightness |
| `/rtx sky minimumopacity <opacity>` | How much rain and thunder are allowed to hide the sun, moon, and stars, as a normalized opacity |
| `/rtx skyDomeMult <value>` | Brightness multiplier for the sky dome itself |
| `/rtx water` | Show the water surface settings |
| `/rtx water ior <ior>` | How strongly the water surface bends light. Real water is about `1.33` |
| `/rtx water distance <metres>` | How far you can see through water before it goes opaque |
| `/rtx water color <r> <g> <b>` | The colour water lets through |
| `/rtx sss` | Show the subsurface scattering settings for leaves, grass, and other cutout blocks |
| `/rtx sss cutout transmissionDistance <value>` | How deep light travels inside those blocks |
| `/rtx sss cutout anisotropy <anisotropy>` | Whether scattered light continues forwards or bounces back |
| `/rtx sss cutout albedo <r> <g> <b>` | The colour of the light that scatters through |
| `/rtx sss cutout albedoTexture on` | Take that colour from the block texture |
| `/rtx sss cutout albedoTexture off` | Use the fixed colour above instead |
| `/rtx sss cutout reset` | Put the subsurface settings back to their defaults |

Changing a water or subsurface value rebuilds the loaded terrain, so the world
blinks for a moment. Of these commands, only `/rtx sky minimumopacity` writes
your settings file. Change a value in the settings screen when you want it kept
across launches.

## Lighting Profiles And Zones

The mod ships lighting profiles that fade in and out with the time of day, the
weather, whether you are in water, and which biome you are in. A map author can
add more, including profiles that belong to one place in the world, called
zones. The authoring guide is
[Gameplay-driven profiles](../packaging/rtx-remix/mods/minecraft-logic/README.md);
these are the commands you use while editing.

| Command | What it does |
|---|---|
| `/rtx conf status` | Show which profiles are active and how strongly |
| `/rtx conf status all` | The same, including idle profiles and where each one came from |
| `/rtx conf reload` | Re-read every profile from disk without restarting the game |
| `/rtx zone define <name and options>` | Create or replace a zone, centred on you unless you pass a centre |
| `/rtx zone list` | List the zones and the profile each one uses |
| `/rtx zone remove <name>` | Remove a zone |
| `/rtx zone rescan` | Re-read zone marker command blocks in the loaded chunks |
| `/rtx zone status` | Show each zone's current strength where you stand |

In single-player, a zone is saved into the world, so it travels with the save.
In multiplayer it applies to your own client for that session only.

## Camera Paths

Record a route by walking it, then play it back for a video or for a
frame-accurate comparison between two builds. The full guide, including the file
format and the two playback clocks, is [Camera Paths](CAMERA_PATHS.md).

| Command | What it does |
|---|---|
| `/rtx camera` | Show the camera state: the loaded path, the position in it, and whether a recording or playback is running |
| `/rtx camera status` | Identical to `/rtx camera` |
| `/rtx camera record <name>` | Arm a recording. Press `R` to start and `R` again to save |
| `/rtx camera play <name>` | Play a path from the start, following real time |
| `/rtx camera play <name> time` | The same clock, named explicitly |
| `/rtx camera play <name> fixed-60` | Advance one fixed 60 Hz step per rendered frame, so playback slows down when the frame rate does |
| `/rtx camera seek <seconds>` | Jump to a point in the loaded path |
| `/rtx camera seek <name> <seconds>` | Load a path and stand at that point without playing |
| `/rtx camera pause` | Pause playback |
| `/rtx camera resume` | Resume playback |
| `/rtx camera stop` | Stop playback, or cancel a recording you have not saved |
| `/rtx camera time day` | Set the time of day to morning |
| `/rtx camera time noon` | Set the time of day to noon |
| `/rtx camera time sunset` | Set the time of day to sunset |
| `/rtx camera time night` | Set the time of day to night |
| `/rtx camera time midnight` | Set the time of day to midnight |
| `/rtx camera time <ticks>` | Set an exact time from `0` to `23999` |

Recordings are stored per world, so a path recorded in one save does not appear
in another.

## Still Shots

A still shot is a single pose kept for a screenshot: where you stand, where you
look, the time of day, the weather, and whether path tracing and procedural
clouds are on. Frame the picture, open chat, and mark it. Shots live in a set
named `docs`, stored beside the camera paths of the current world, and a tool
can later stand at each one and take the picture again.

| Command | What it does |
|---|---|
| `/rtx shot` | List the marked shots |
| `/rtx shot list` | Identical to `/rtx shot` |
| `/rtx shot mark <name>` | Mark the current pose and scene under that name, replacing a shot of the same name |
| `/rtx shot remove <name>` | Remove a shot |

## Diagnostics

| Command | What it does |
|---|---|
| `/rtx lights` | Count the block lights in the loaded terrain by kind, and how many of each were drawn this frame |
| `/rtx benchmark` | Run a fixed camera path and write a frame-time report |
| `/rtx benchmark status` | Show how far a running benchmark has got |
| `/rtx benchmark stop` | Abort a running benchmark |

A benchmark replays a camera path, so it needs one first. It uses a path named
`simple` in the current world. Record one with `/rtx camera record simple`
before you start. Results are written under `benchmark\results\` in the game
folder. Camera commands are unavailable while a benchmark is running.

## Keys

| Key or action | What it does |
|---|---|
| Toggle RTX Rendering (default `F8`) | Turn path tracing off, or back on, without opening chat. The new state shows above the hotbar |
| `Alt+X` | Open the RTX Remix runtime's own developer overlay, or close whichever Remix overlay is open, including the Graphics Settings menu that the button on the settings screen opens; each menu's own close button closes it too. What you change in either overrides the mod's settings. While one is open, Minecraft receives no keyboard or mouse input at all, Escape included; close the menu or press `Alt+X` to return |
| Toggle Held Lights (default `G`) | Put out everything lit in your hands, a torch's flame, glow and light or a Copper Beacon's beam, and light it again |
| `R` | Start and stop a camera recording, once `/rtx camera record` has armed it |
| `=` and `-` | While recording, move the time of day forward or back in normal steps. Hold `Ctrl` for larger steps |
| `F1` | Hide the interface, including your own hand |
| `F2` | Save a screenshot to the `screenshots` folder |
| `F3` | Show coordinates and frame rate |

Set bindings for the named actions in Options, Controls, under RTX Remix.
Toggle Held Lights affects only your client: nothing is sent to the server, so
what other players hold stays lit whatever you press. By default, an open flame
goes out while any holder's head is under water and returns on surfacing.
`R` is only intercepted while a recording is armed or running, and `=` and `-`
only while one is running. None is intercepted while a menu is open.

## New Blocks And Effects

### Copper Beacon

A lamp in a brass housing that throws a warm, aimable beam, in the spirit of a
lighthouse. The lamp is a block light in its own right, and the beam itself is
a real light: it picks out walls, mist, and anything standing in it.

Craft one from a lantern in the middle, copper ingots in the four corners,
glass panes on either side and above, and redstone dust below; it sits in the
Redstone group of the recipe book and in the creative inventory. With cheats
on, use `/give @s rtx-remix-renderer:copper_beacon`. It mounts on whichever
face you click, floor, wall, or ceiling, and the lens faces you, so it lights
whatever you were looking away from. It drops as an item even when broken by
hand; no pickaxe is required.

Right-click a placed beacon to aim, animate, and redstone-control it. Its
settings are server-authoritative block state, saved with the world, so every
player sees the same motion.

Held in your hand, it works as an aimable torch and starts lit. Toggle Held
Lights extinguishes or relights it without putting it away. A placed beacon's
controls do not affect the held beam.

### Water Rapids

Foam and mist appear on flowing water. Rapids follow the particle setting, so
`/rtx particles` turns them off with other particles.

## Getting Around

To look at a build rather than play in it, use Spectator mode: `/gamemode
spectator` with cheats on. You fly, pass through blocks, and nothing can see
you. Scroll the mouse wheel to fly faster or slower, which is the easiest way to
line up a distant shot. `/gamemode creative` gives you flight instead, by
double-tapping the jump key, plus the full block palette.

## Time Of Day And Weather

Lighting profiles fade with the time of day and the weather, so the fastest way
to see them is to change both.

`/rtx camera time night` and its siblings set the time from any game mode and
need no cheats. Vanilla's `/time set` and `/weather thunder` do the same job with
cheats on, and `/weather` is the only way to bring in rain.

Diving underwater switches to a look of its own, where the water absorbs and
tints the light around you.

## A Clean Frame

Press `F1` to hide the interface, wait a few seconds, then press `F2`. The wait
matters: the picture keeps resolving for a moment after you stop moving, and a
shot taken mid-turn is noisier than what you were actually looking at. `F1` also
hides your hand, so put the item you want in shot on the ground or in a frame.

## An RTX On And Off Pair

For a bug report, one screenshot rarely shows what is wrong. A pair does.

1. Frame the problem and press `F2`.
2. Type `/rtx off` and wait for the scene to redraw.
3. Press `F2` again without moving.
4. Type `/rtx on`.

Both files are in the `screenshots` folder, one after the other. The second is
Minecraft's own renderer from the same spot, which tells whoever reads the report
whether the fault is in the world or in the path tracer. The rest of the
checklist is in [Reporting a Bug](PLAYING.md#reporting-a-bug).
