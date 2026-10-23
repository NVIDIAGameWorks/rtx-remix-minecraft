# Resource Packs

This guide is for people who make texture packs. It describes what this
renderer reads out of a pack beyond the base colour texture, and what it does
with each channel.

The short version: the mod reads the vanilla textures from whatever packs you
have enabled, looks beside each one for LabPBR sidecar images, and converts
them once into the material channels a path tracer needs. It also reads a small
set of JSON rules that attach real lights to blocks. There is no per-texture
material file: a texture's material comes from its sidecars and from the
defaults for the atlas it belongs to.

Sidecars follow the [LabPBR standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard),
with one addition of the mod's own, `_sss`.

## Quick Start

1. Enable your own pack in Options, Resource Packs above Fabric's `fabric`
   pack. The mod's built-in baseline fills in textures you have not covered
   yet; yours wins for anything you replace.
2. Pick one block that should glow, say `glowstone`, and add
   `assets/minecraft/textures/block/glowstone_s.png` beside its base colour
   texture. Paint the alpha channel below 255 wherever it should emit.
3. Press `F3+T` in game. The pack reloads, the atlas is rebuilt, and the block
   lights are re-read without restarting.
4. Check `logs/latest.log` for the atlas and rule reload summaries from
   `BlockTextureManager` and `BlockLightRuleManager`. Replacing an existing
   resource can leave the totals unchanged; check the block's appearance to
   confirm your change.

## The Built-In Baseline

The baseline LabPBR textures ship inside the mod JAR as part of Fabric's
`fabric` pack, below user packs. No separate pack needs enabling. It carries
the minimum set of sidecars the renderer needs: `_s` for the blocks that are
meant to glow, and `_sss` for foliage so leaves and grass scatter light instead
of looking like painted cardboard. It also includes sky and particle base
textures. Your own pack wins for any of those you replace in turn.

Surface emission comes only from `_s` alpha, and the block atlas deliberately
passes an emission fallback of zero (`LabPBRConverter.blitEmissive`), so a
block's vanilla light level never becomes surface glow on its own. For
example, without an `_s` for glowstone, the block renders dark.

The textures live in `src/main/resources/assets/` in this repository and are
covered by the [LICENSE](../LICENSE), so copy them and build on them. A glow
map for part of a texture, such as a torch flame, lines up with the vanilla
texture, so a pack that redraws that texture should ship its own `_s` too.

## Channel Reference

Sidecars sit beside the texture they describe and take its name plus a suffix:
`glowstone.png` is described by `glowstone_n.png`, `glowstone_s.png`, and
`glowstone_sss.png`. All three are optional and independent.

| File | Channel | What the mod does with it |
|---|---|---|
| `_n` | R, G | Normal X and Y. Z is reconstructed from them |
| `_n` | B | Ambient occlusion, multiplied into the base colour |
| `_n` | A | Height, used for parallax with a displacement of 0.25 blocks on terrain and 0.02 on particles |
| `_s` | R | Perceptual smoothness, converted to roughness as `(1 - R/255)` squared |
| `_s` | G | Reflectance. `230` and above is metal, below it is not. There is no coloured reflectance and no metal index |
| `_s` | B | Subsurface scattering. `65` and above enables it and sets the thickness, rescaled so 65 is none and 255 is full. The porosity range below 65 is ignored |
| `_s` | A | Emission. Below 255 the surface emits at `A/254` of its base colour. 255 means no emission |
| `_sss` | R, G, B | The colour light takes on as it scatters through the surface. This suffix is the mod's own, not part of LabPBR |

The conversions are in `LabPBRConverter`, one named function per row.

Without a sidecar a surface gets a flat normal, a height of 255, no metal, no
scattering, no emission, and a matte finish (`BlockTextureManager`,
`ParticleAtlasManager`).

A sidecar does not have to match the resolution of the texture it describes. It
is sampled with a wrap, so a 16x16 sidecar tiles across a 32x32 base colour.
That makes a plain 16x16 `_s` a workable way to say "this whole block is
metal".

## Animated Textures

An animated sprite's sidecars are re-applied on every animation frame, so
ambient occlusion and the animated material channels stay in phase with the
base colour (`AtlasAnimationForwarder`).

A sidecar for an animated texture takes one of two shapes:

- A strip that matches the base colour strip frame for frame, at the base
  colour's own frame resolution. `lava_still_s.png` is 16 by 320 beside a 16 by
  320 base colour, twenty frames of 16 by 16 each. A frame is stepped in base
  colour pixels, so a 16-wide strip beside `lava_flow.png`, which is 32 by 512,
  is read at the wrong offsets. Frame timing comes from the base colour's
  `.mcmeta`; the sidecar needs none, and interpolation blends the sidecar too.
- A single frame, which is tiled across every frame of the animation. Use this
  when the material does not change as the animation plays. The resolution
  freedom above applies to this shape only.

## Subsurface Scattering

Two paths, chosen by how the block is drawn (`BlockTextureManager`):

- **Solid blocks** get a diffusion profile, with the scattering colour taken
  from `_sss` when there is one and from the base colour otherwise.
- **Cutout blocks**, which is most foliage, get thickness-based scattering
  driven by `_s` blue, with `_sss` supplying the single-scattering colour.

Author `_sss` for thin plants: leaves, grass, vines, glow lichen. That is what
the baseline pack covers. A thick solid block rarely needs it.

## Two Ways A Block Gives Off Light

They are separate systems and a block can use either or both.

**An emissive surface** is a texture that glows, from `_s` alpha. Terrain
surfaces emit at a base intensity of 100
(`BlockTextureManager.BASE_TERRAIN_EMISSION_INTENSITY`), which the player can
scale with Materials, Block Glow Intensity. It lights the scene the way any
bright surface does. Lanterns and sea pickles glow through their textures
rather than analytic lights: hundreds of small lights across a map dilute the
light sampling. The mod turns the pack's authored lantern emission up 2.5x and
gives a waterlogged sea pickle a glowing sprout tip when the pack authors none;
an `_s` with emission for the pickle replaces that (`BuiltinEmission`).

**A block light** is an analytic light placed in the world by a rule, with a
shape, a position, a colour, and an intensity. The player can scale each kind
under Lighting, Block Lights (`BlockLightIntensityControls`).

The placed Copper Beacon instead has a dynamic light owned by its block
entity, not a rule-system light. Its item still gets a held light
(`LightManager`).

A block with neither is lit only by its neighbours, whatever its vanilla light
level says.

## Block Light Rules

A pack can add a rule for a block that has none, or replace a built-in one.
Rules are JSON, one file per rule, at:

```text
assets/<namespace>/rtx_remix/block_lights/<name>.json
```

The simplest rule names some blocks and one light:

```json
{
  "blocks": ["minecraft:shroomlight"],
  "light": {
    "type": "sphere",
    "position": { "type": "centroid" },
    "radius": 0.4,
    "color": [1.0, 0.5, 0.2]
  }
}
```

### Rule fields

| Field | Meaning |
|---|---|
| `blocks` | Required, non-empty array of block ids, or of `#namespace:tag` entries |
| `when.properties` | Optional map of blockstate property names to values. Every entry must match |
| `light` | One light object |
| `lights` | An array of light objects. A rule may use either or both, and must define at least one |

### Light fields

| Field | Default | Meaning |
|---|---|---|
| `type` | `sphere` | `sphere`, `cylinder`, `rect`, or `disk` |
| `position` | the model's centroid | `{"type":"offset","x":,"y":,"z":}` in block-local space, `{"type":"facing_offset","base":[x,y,z],"property":"facing","scale":1.0}`, or `{"type":"centroid"}` |
| `color` | the block's derived colour | `[r, g, b]`, each at least 0. Not clamped to 1 |
| `intensity` | the block's light level times 5 | Radiance, greater than 0 |
| `radius` | `0.2` | Emitter radius, greater than 0. Used by `sphere` and `cylinder` |
| `axis` | `[0, 1, 0]` | Cylinder axis. Also accepts `"facing"`, `"facing_opposite"`, or `"facing_axis"` |
| `axisLength` | `1.0` | Cylinder length, greater than 0 |
| `xAxis`, `yAxis` | `[1, 0, 0]`, `[0, 1, 0]` | The two in-plane axes of a `rect` or `disk` |
| `xSize`, `ySize` | `1.0` | Half-extents of a `rect`, greater than 0 |
| `xRadius`, `yRadius` | `0.5` | Radii of a `disk`, greater than 0 |
| `direction` | `[0, 0, -1]` | The way a `rect` or `disk` faces. Also accepts the three `facing` keywords |
| `volumetricRadianceScale` | `0.5` | How much this light feeds the volumetric fog, at least 0 (`LightEntry`) |

The parser is `BlockLightRuleManager`. A file it cannot read is skipped with a
warning in `logs/latest.log` naming the pack and the reason, such as
`missing array blocks`, `rule must define light or lights`, or
`unknown light type`. The rest of the pack still loads.

### Replacing a built-in rule

The built-in rules are generated in Java by `BlockLightDefaults`, with
identifiers under the `rtxremix` namespace, such as
`rtxremix:rtx_remix/block_lights/torch.json`. Ship a file at the same path and
it replaces the built-in rule completely. There are no shipped JSON files to
copy.

Complete matters, because each built-in rule carries an authored intensity
baseline that is multiplied into whatever the rule produces
(`BlockLightDefaults`): 7.5x for torches and campfires and 5x for everything
else. Replacing a rule takes that baseline away with it, so your authored
`intensity` is used as written and you should expect to state one. The
player's own multiplier for that kind still applies on top, so a player can
still dim or boost your light.

You cannot delete a built-in light from a pack: a rule has to define a light.
Only the player's Off position removes one.

When two enabled packs ship the same rule path, the one higher in the resource
pack list wins.

### Three examples

Recolour the torch, keeping its shape and brightness, at
`assets/rtxremix/rtx_remix/block_lights/torch.json`:

```json
{
  "blocks": ["minecraft:torch"],
  "light": {
    "type": "sphere",
    "position": { "type": "offset", "x": 0.5, "y": 0.7, "z": 0.5 },
    "radius": 0.08,
    "intensity": 1575.0,
    "color": [0.2, 0.6, 1.0]
  }
}
```

That intensity is what the built-in rule works out to: light level 14, times 5
radiance per level, times 3 because the emitter is shrunk to 0.08 and has to
make up the surface area it gave away, times the 7.5x torch baseline. Write
your own number if you want it brighter or dimmer, but start from that one.

Give a redstone lamp a light, but only while it is lit, at
`assets/mypack/rtx_remix/block_lights/redstone_lamp.json`:

```json
{
  "blocks": ["minecraft:redstone_lamp"],
  "when": { "properties": { "lit": "true" } },
  "light": {
    "type": "sphere",
    "position": { "type": "centroid" },
    "radius": 0.4,
    "color": [1.0, 0.7, 0.4]
  }
}
```

Light everything in a tag at once:

```json
{
  "blocks": ["#minecraft:candles"],
  "when": { "properties": { "lit": "true" } },
  "light": {
    "type": "sphere",
    "position": { "type": "offset", "x": 0.5, "y": 0.6, "z": 0.5 },
    "radius": 0.05
  }
}
```

## Where A Block Light's Colour And Intensity Come From

Colour, first match wins (`BlockTextureManager.getLightColorForBlock`):

1. The rule's own `color`.
2. A curated colour for that vanilla block, from
   `BlockTextureManager.KNOWN_LIGHT_COLORS`.
3. Any block whose id contains `candle`, which takes the warm colour.
4. The brightness-weighted average colour of the block's own sprite. Dark
   pixels count for almost nothing, so a torch takes the colour of its flame
   rather than of its stick.
5. A warm white fallback.

Intensity is the rule's `intensity` if it has one, otherwise the block's
vanilla light level times 5
(`BlockLightRuleManager.RADIANCE_PER_LIGHT_LEVEL`). That is multiplied by the
rule's authored baseline and then by the player's multiplier for that kind.

Replacing the base colour of a block that is in the curated list does not
change its light colour. Set `color` in a rule instead.

## Particles

Particle sidecars live beside the particle texture, at
`assets/<namespace>/textures/particle/<name>_s.png` and `_n.png`.
`ParticleAtlasManager` reads those two and not `_sss`.

Particles emit at an intensity of 40 rather than the terrain's 100, so the same
`_s` alpha reads dimmer on a particle than on a block. Their alpha test cuts at
10, and their parallax displacement is 0.02.

Two things worth knowing:

- Block-break and other terrain-sheet particles borrow the block atlas and its
  materials, so they follow your block textures with no particle sidecar at
  all.
- A placed torch reads as lit largely because of the flame particle it spawns,
  not because of the two bright texels in `torch.png`. `flame_s.png` and
  `soul_fire_flame_s.png` matter more than the torch texture does, and they
  also drive the flame the mod draws on a torch held in the hand
  (`HeldItemFlame`).

## Entities And Runtime Atlases

Entity textures take the same three sidecars, beside the texture under
`textures/entity/`. `RemixEntitySubmitter` reads `_n`, `_s`, and `_sss` for
each, and entity surfaces emit at an intensity of 100.

The atlases Minecraft stitches at runtime, for banner patterns, shield
patterns, and beds, are re-stitched with their sidecars, so a pattern with an
`_s` behaves the way the same texture would on a block.

Dropped and held block items borrow the block atlas and its materials, so they
need nothing of their own. Downloaded player skins have no sidecar path of
their own.

## Sky And Weather

The sun and moon are drawn from the pack's own
`textures/environment/sun.png` and `moon_phases.png`, placed on the procedural
sky as emissive quads at a radiance of 8.0 and 0.2 respectively
(`SkyRenderer`). A repainted disc changes the shape and colour of what you see
in the sky; those two constants set how bright it is.

`textures/environment/clouds.png` drives the classic cloud shape when
Cloud Style is Classic. `textures/environment/rain.png` is used by the
weather renderer.

## Troubleshooting

- **A block that should glow is dark.** It has no `_s`, or its `_s` alpha is
  255 everywhere. A vanilla light level alone produces no surface glow.
- **A light is white when it should be coloured.** The block is not in the
  curated colour list and its sprite is too dark to derive a colour from. Give
  the rule an explicit `color`.
- **A rule does nothing.** Look in `logs/latest.log` for
  `Skipping block light rule`; the message names the pack and the problem. Also
  check that the file path is exactly
  `assets/<namespace>/rtx_remix/block_lights/<name>.json` and that `blocks`
  names an id that exists.
- **A sidecar is ignored on an animated block.** The strip has to match the
  base colour strip frame for frame, or be a single frame. A strip of a
  different length is read at the wrong offsets.
- **A change does not show up.** Press `F3+T`. That reloads the packs, rebuilds
  the atlas, and re-reads the block light rules. Replacing an existing resource
  can leave the counts in `logs/latest.log` unchanged. Check the rendered
  result, the file's path, and pack order.
- **A light is too strong or too weak everywhere.** Check whether you replaced
  a built-in rule. Replacing one drops its authored baseline, which for a torch
  is 7.5x.

## Going Further

| You want to | Read |
|---|---|
| Understand every control in the settings screen | [Settings](SETTINGS.md) |
| Give your own map its own lighting | [Gameplay-driven profiles](../packaging/rtx-remix/mods/minecraft-logic/README.md) |
| Look up a command or a key | [In-Game Guide](IN_GAME_GUIDE.md) |
