# Multi-Loader And Version Architecture

This project is one canonical renderer reached through symmetric loader
adapters. The root `project-state.json` declares every supported Minecraft
target and its loader catalog. Minecraft 1.21.1 currently contains Fabric,
Forge, and NeoForge.

## Ownership

There are four boundaries:

1. `renderer-core/` owns Remix scene policy, resources, frame ordering, queues,
   and budgets. It does not depend on Minecraft or a loader.
2. `versions/<version>/minecraft/` owns behavior shared by that Minecraft
   version: capture, lifecycle, menus, networking, and translation into
   renderer inputs.
3. Each `versions/<version>/<loader>/` module owns only registration, metadata,
   hook-signature translation, and a narrow provider for genuine loader
   differences.
4. The host graphics provider owns window, graphics-API, framebuffer-transfer,
   and presentation details. The current Minecraft host is Windows/OpenGL; that
   is not a property of the canonical scene renderer.

Equivalent policy in multiple loader modules belongs in a shared layer.
Conversely, Fabric API setup, Forge JarJar/remapping, and NeoForge's ordinary
module JAR are real provider differences and should not be hidden behind
generic conditionals. The same rule applies below APIs: Fabric and Forge select
the shared vanilla-shaped section-compiler mixin, while NeoForge's patched
compiler signature has a thin mixin that delegates to the same canonical
section-capture implementation.

## Project State And Loader Catalog

`project-state.json` is the machine-readable state of the whole checkout. Its
schema-versioned contents identify the mod version, default Minecraft target,
and a keyed catalog of Minecraft versions. Each version declares its support
tier, shared integration module, default loader, Java target, and loader map.
A loader entry supplies the Gradle module and tasks, artifact and metadata
information, Prism component, companion dependencies, and provider identity
needed by build, tooling, CI, and installation.

Consumers enumerate the catalog; they do not carry a second closed loader list.
Unknown schemas, duplicate IDs, missing providers, or disagreement between a
declaration and its module fail clearly. Adding a loader therefore means adding
one declaration, one module, and only the provider behavior that is truly
different. It does not mean copying the renderer.

Minecraft 1.21.1 and Fabric remain the defaults for compatibility. Fabric,
Forge, and NeoForge are the loaders declared by that target, not a permanent
closed list.

## Build, Tools, And Releases

The neutral Gradle root applies no loader plugin. Shared conventions own common
sources, tests, licenses, runtime payload, and package contracts; each loader
module keeps its plugin and remapping verification local. Gradle selects one
Minecraft target with `-PmcVersion` and can narrow configuration to one loader
with `-Ploader`. The resulting JAR contains only that target's sources.

```powershell
.\gradlew.bat build "-PmcVersion=1.21.1" "-Ploader=fabric"
.\gradlew.bat package "-PmcVersion=1.21.1" "-Ploader=forge"
tools\rtx.bat launch --minecraft-version 1.21.1 --loader neoforge
```

CI validates the checked-out manifest, derives its target matrix, then builds,
tests, and packages every supported Minecraft-version/loader pair.

Published loader JARs include the loader ID, Minecraft target, and mod version;
the installer bundle includes the Minecraft target and mod version. Release
staging contains one JAR per declared loader plus the target's other release
content, so asset count is derived rather than fixed.

## Version Folders

Every supported version is self-describing and buildable in the same checkout:

```text
versions/
  1.21.1/
    minecraft/
    fabric/
    forge/
    neoforge/
```

A port adds a catalog entry, a version-specific Minecraft integration, and the
loader adapters that target supports. It does not copy `renderer-core/` or use
source-level version switches. `tools\rtx.bat versions` reports the catalog;
`tools\rtx.bat target-status` summarizes proportionate automated-contract,
build, artifact, and automated-live-run evidence for a selected target. These
commands report state; they do not rewrite source or treat a log as proof of
visual parity.

## Semantic Parity

Source equality is not the goal across Minecraft versions or loader providers.
Semantic parity is: the same scene decisions, ownership invariants, and visible
result for equivalent game state. Deterministic world, camera, and shot fixtures
provide reusable evidence across loaders and versions. Automated tests
cover contracts and artifacts; recorded fixture results and an actual launch
cover behavior that compilation cannot prove.

## Why There Is No Preprocessor

The current split already isolates stable renderer policy, version-specific
Minecraft integration, and small loader providers. Gradle performs
configuration-time module selection, producing a lean target without
preprocessor directives in Java sources. Stonecutter or another source
preprocessor would add conditional source paths before repeated port work
demonstrates that they reduce maintenance. Revisit that choice only with
measured, recurring duplication that the existing boundaries cannot absorb.
