# Installer Reference

Everything the installer can do beyond double-clicking `install.bat`: the
modes, the flags, headless and CI use, and what it verifies before it writes
anything. For a normal install, `README.md` beside this file (`INSTALL.md` in
the source tree) is enough.

`install.bat` is a thin wrapper that starts `installer.ps1` with an execution
policy that lets an unsigned script run. Every flag below works on either
entry point.

## The Terminal Menu

Running `install.bat` with no arguments opens a full-screen menu. The
detected-state block identifies installed launchers and dependencies, missing
profiles, and whether the installed mod matches the archive.

The first item is chosen from what is already on disk. Nothing installed gives
**Install**; an installed build whose file name differs from the packaged one
gives **Upgrade**; an exact file-name match gives **Repair**. When the first
item is Upgrade or Repair, a plain **Install** is offered below it. With the
mod in only one launcher, Upgrade and Repair keep that launcher without asking;
Install asks, so it is how to install into the other launcher. The exception
is Prism with no Microsoft launcher, which Install, Upgrade, and Repair all use
without asking: to use the Microsoft launcher, install it and start it once, so
it creates its profile file, then run the installer again. `-Launcher` names
one in any mode.

A run chosen from the menu ends by waiting for a key, so the window a
double-clicked `install.bat` opens stays up to show the result or the error.
Quit closes at once, and a run given `-Mode` never waits.

## Modes

| Mode | What it does |
|---|---|
| Install | A fresh install, or a deliberate reinstall of the packaged files over an existing one. Installs the items ticked under Choosing What To Install: the mod with its launcher setup and Fabric API, RTX Remix Materials, and the RTX Remix Showcase World. Baseline PBR textures are built into the JAR, and the mod unpacks its runtime textures on first launch. Safe to re-run: it does not wipe an already-extracted runtime or your conf payload |
| Upgrade | Replaces the mod JAR and Fabric API, and, with RTX Remix Materials ticked, an installed pack. The new mod version refreshes `remix-sdk\`, `rtx.conf`, and the runtime textures on the next launch and adds conf profiles you do not have. Launcher config, JVM arguments, and options other than resource-pack ordering are preserved, and differing JVM arguments prompt before they are replaced. JVM arguments an earlier installer wrote are replaced without asking, in either launcher |
| Repair | Reinstalls the packaged files through the same path as Install and Upgrade. If the `.rtx-remix-version` marker matches, it does not re-extract the runtime or conf payload, but a missing `rtx.conf` is restored on launch. Delete the marker and relaunch to force full extraction |
| Uninstall | Removes the mod files listed in `README.md`, RTX Remix Materials among them, then offers to remove the installer-managed Java, the Prism portable, and the Prism instance, listing only those that exist. It keeps the RTX Remix Showcase World, as it keeps every world. With both launchers holding the mod it asks Prism, Microsoft, or Both |
| Export | Prism only. Zips the Prism instance into an archive another machine can drop into its own Prism `instances\` folder, stripping the paths listed under Privacy below |

An interactive Install, Upgrade, or Repair opens the launcher when it
finishes; `-Unattended` suppresses that. A scripted
`install.bat -Mode Install -Launcher Prism` without it opens a window. With the
Microsoft launcher, an interactive run first waits for you to close a running
launcher.

Installing the mod also migrates the retired generated `RtxRemix-LabPBR`
pack. It identifies the pack by metadata and texture inventory and leaves
other packs alone. In an existing `options.txt`, only `resourcePacks` changes:
vanilla, Fabric, then user packs in their existing order, without the retired
baseline entry. It does not create a missing options file for that.

## Choosing What To Install

Install, Upgrade, and Repair install three items, and each can be left out:

| Item | What the archive holds | Flag |
|---|---|---|
| RTX Remix Renderer | The mod, `mods\rtx-remix-renderer-<version>.jar`. Ticking it also sets up Java, the launcher, and Fabric API | `-Mod` |
| RTX Remix Materials | The texture pack, `resourcepacks\RTX-Remix-Materials-<version>.zip` | `-ArtPack` |
| RTX Remix Showcase World | The demo world, `saves\RTX-Remix-Showcase-World-<version>.zip` | `-World` |

Once the launcher is settled, an interactive run shows them as a checklist,
all three ticked and the highlight on Continue, so Enter installs them. Enter
on an item ticks or unticks it, Continue installs the ticked ones and needs at
least one, and Esc cancels without changing anything. A flag set to `Skip`
starts its item unticked. An item the archive does not carry, as in an older
archive, shows `not in this download`, starts unticked, and cannot be ticked.

An unattended run has no checklist. It installs the mod unless given
`-Mod Skip`, and the pack and the world only when given `-ArtPack Install` or
`-World Install`, so a script written for an earlier archive installs what it
did before. An unattended Upgrade therefore leaves an installed pack at its
old version unless it is given `-ArtPack Install`.

With the mod unticked, nothing sets up a launcher, so the pack and the world go
into the game folder an earlier install made for the launcher you pick: the
RTX Remix Prism instance's `.minecraft`, or `%APPDATA%\.minecraft` for the
Microsoft launcher. If that folder does not exist, the run installs nothing,
says to install RTX Remix Renderer first, and exits with code `1`.

A failure in the mod's steps stops the run with exit code `1`. The pack and
the world each run whatever the other returned, and one that fails makes the
run exit with code `1` once every step has finished. The closing summary then
says the install had problems and names each item that was not installed. An
item the archive does not carry, asked for with `-ArtPack Install` or
`-World Install`, only warns.

### RTX Remix Materials

The archive carries the pack as
`resourcepacks\RTX-Remix-Materials-<version>.zip`, a resource pack Minecraft
loads without unpacking. The installer copies it into the game folder's
`resourcepacks\` under the same name.

- **A pack already installed** is replaced with the archive's, and older
  versions are removed. Its entry in `resourcePacks` is renamed in place, so a
  pack you turned off stays off.
- **No pack installed:** the new pack is turned on just above Fabric's pack,
  which holds the mod's built-in baseline, and below your own packs, so they
  still override it. With no `options.txt` yet, as in a new Prism instance,
  the installer creates one holding only the game version and the pack list,
  and the game fills in every other option with its default.
- **With the Microsoft launcher**, the pack is also on when you play without
  the mod, because both share one game folder and one `options.txt`. The
  checklist says so beside the item.
- **Unticked, or `-ArtPack Skip`,** the pack and its entry are left alone.
- **Uninstall** removes the pack and its `resourcePacks` entry.

Only files named `RTX-Remix-Materials-<major>.<minor>.<patch>.zip` are the
installer's; any other pack in `resourcepacks\` is yours and stays untouched.
A `resourcePacks` line that is not a JSON list is left alone, with a warning.
A running game holds its packs open, so close Minecraft before running the
installer. If the new pack cannot be placed, or an older one cannot be
removed, the step warns and fails.

### RTX Remix Showcase World

The archive carries the world as
`saves\RTX-Remix-Showcase-World-<version>.zip`, holding one `NVIDIA RTX Demo`
folder. The installer unpacks it into the game folder's
`saves\NVIDIA RTX Demo\`, which Singleplayer lists as NVIDIA RTX Demo.

- **A world already there** under that name is never replaced or changed,
  since it may hold your progress or your edits. The installer says it kept
  it. For a fresh copy, rename or delete that folder and run the installer
  again with the world ticked.
- **A zip that cannot be unpacked**, because it is damaged or the drive is
  full, leaves nothing of the world in `saves\`, and the step warns and fails.
- **Uninstall** keeps the world, as it keeps every world, and says where it
  is. Removing the RTX Remix Prism instance, alone or with Scorched earth,
  deletes it with the instance's other worlds.

## JVM Arguments And Prism Memory

Prism refuses to launch an instance whose JVM arguments hold a heap size such
as `-Xmx` or `-Xms`: it shows a "JVM arguments warning" and stops. It gives the
game its heap from the instance's memory settings instead. The installer
therefore writes the recommended JVM arguments to the Prism instance without
their heap sizes, and sets the instance's memory to the heap they ask for: the
maximum from their `-Xmx`, and the minimum from their `-Xms`, or 512 MiB when
they set none. The Microsoft launcher profile gets the recommended arguments as
they are, `-Xmx` included.

JVM arguments an installer wrote are the installer's, not yours, so it replaces
them without asking, in the Prism instance and in the Microsoft launcher
profile. That covers every set of recommended arguments the installer has
shipped, in each form it wrote them: as they are, `-Xmx` included, or in a
Prism instance without their heap sizes. It also covers the arguments the
installer's record, described below, says it wrote to the Prism instance.
Prism refuses to launch with the sets that hold `-Xmx`. The installer prints
the arguments it replaces, and keeps the file it changes as `instance.cfg.bak`
in the Prism instance, or as `launcher_profiles.json.bak` for the Microsoft
launcher. Arguments that differ in any way, such as an earlier set you edited,
are yours and are kept unless you accept the prompt to replace them. When they
hold an option Prism refuses, the prompt, or the warning of an unattended run,
names it, because the instance cannot launch until that option is removed.

A memory choice saved in the Memory group of the instance's Java settings in
Prism is kept, whether that is the instance's own memory or, with Memory
unticked, Prism's global memory. That holds even when your choice equals what
some set of recommended arguments asks for, such as a 12 GB maximum, or 2 GB
after the recommended heap has grown. Memory stays the installer's only while
it is exactly what the installer's record says it wrote. Such memory follows
the recommended heap without asking, and `instance.cfg.bak` keeps the previous
file. Where the instance has no memory setting at all, because Memory was never
saved in Prism, the installer sets it. Accepting the prompt to replace
differing JVM arguments also sets the memory, as the prompt says. To give the
game more memory in Prism, raise the maximum in that Memory group, not with an
`-Xmx` in the instance's JVM arguments.

The installer's record is `rtx-remix-installer.json`, beside `instance.cfg` in
the instance folder. It holds the JVM arguments and memory the installer last
wrote to the instance, which is how a later run tells its own settings from
yours. No value alone can show that: Prism fills the Memory group with its
defaults, so the 512 MiB minimum the installer writes is also what you save
when you change only the maximum. Export copies the record with `instance.cfg`,
and removing the instance removes it. Without the record, or when it cannot be
read, the instance's memory counts as yours.

## Headless And CI Use

`-Unattended` turns off every prompt and returns an error where an interactive
run would have asked a question. The checklist is the exception: an unattended
run takes the defaults under Choosing What To Install. It also keeps the
installer from opening the launcher afterwards and from waiting for a running
Microsoft launcher to close.

```powershell
# Fresh install with the usual defaults: the mod only
install.bat -Mode Install -Unattended -Launcher Prism

# The mod, RTX Remix Materials turned on, and the RTX Remix Showcase World
install.bat -Mode Install -Unattended -Launcher Prism -ArtPack Install -World Install

# Upgrade the mod and bring an installed RTX Remix Materials pack up to date
install.bat -Mode Upgrade -Unattended -Launcher Prism -ArtPack Install

# Add only the world to the RTX Remix Prism instance an earlier run made
install.bat -Mode Install -Unattended -Launcher Prism -Mod Skip -World Install

# Pin the Prism version, which avoids the GitHub "latest" lookup entirely
install.bat -Mode Install -Unattended -Launcher Prism -PrismVersion 9.1

# Uninstall. -AcknowledgeDataLoss is mandatory here
install.bat -Mode Uninstall -Unattended -Launcher Prism -AcknowledgeDataLoss

# Export an existing Prism instance
install.bat -Mode Export -Unattended -Launcher Prism -OutputPath C:\artifacts\rtx-remix.zip

# Point at a Prism data directory that is not the detected one
install.bat -Mode Install -Unattended -Launcher Prism -PrismDataDir D:\PrismData
```

`-Launcher` is required in unattended mode when both Prism and the Microsoft
launcher are present. The installer refuses to pick one silently, and the run
fails with exit code `1`.

Exit codes: `0` success, `1` the operation failed, `2` the arguments were
invalid. A `2` covers `-Unattended` without `-Mode`, an unattended uninstall
without `-AcknowledgeDataLoss`, an unattended export without `-OutputPath`, and
`-Mode Export` on the Microsoft launcher. Running with no `-Mode` when standard
input is redirected is also a `2`: the menu needs a real terminal.

An unattended run keeps existing JVM arguments that differ from the recommended
set, unless an installer wrote them, rather than overwriting them silently, and
still prints what it did so a CI log is readable. When Prism refuses to launch
with the arguments it keeps, it prints a warning naming the option and the run
still succeeds: the installer does not overwrite arguments it did not write,
and the instance could not launch before the run either.

## Flag Reference

| Flag | Values | Purpose |
|---|---|---|
| `-Mode` | `Install`, `Upgrade`, `Repair`, `Uninstall`, `Export` | The operation. Omit for the menu |
| `-Launcher` | `Prism`, `Microsoft` | Skip the launcher question. Required in `-Unattended` when both are present |
| `-Unattended` | switch | No prompts, and the launcher is not opened afterwards. Errors instead of questions |
| `-AcknowledgeDataLoss` | switch | Required alongside `-Unattended -Mode Uninstall` |
| `-OutputPath` | path ending in `.zip` | Target archive for `-Mode Export`. Required in an unattended export |
| `-PrismVersion` | `latest` (default), or a version such as `9.1` | Pin a Prism Launcher release and skip the GitHub lookup. `9.0` is rejected: its Windows portable asset is broken upstream |
| `-PrismDataDir` | path | Prism's data directory, the folder holding `instances\` and `prismlauncher.cfg`. Needed when Prism is on the PATH but keeps its data elsewhere. It has to exist already |
| `-SkipHashCheck` | switch | Do not verify the SHA-256 of downloads. Troubleshooting only: it turns off the check described under Supply-Chain Safety |
| `-GitHubToken` | token string | Authenticate GitHub API calls, which raises the rate limit well above the anonymous one. `GITHUB_TOKEN` in the environment is honoured too. Useful where many machines share one outbound address |
| `-DevServer` | `host` or `host:port` | Add one entry to the instance's saved server list. Omit it and `servers.dat` is not touched. An existing populated `servers.dat` is never merged into |
| `-Mod` | `Install`, `Skip` | Whether to install RTX Remix Renderer, the mod, with the Java, launcher setup, and Fabric API it needs. Omitted means Install. Skip puts only the pack or the world into the game folder an earlier install made. See Choosing What To Install |
| `-ArtPack` | `Install`, `Skip` | Whether to install RTX Remix Materials, or bring an installed pack up to the archive's version. Omit it and an interactive run starts with the pack ticked, while an unattended one leaves the pack alone, installed or not. See RTX Remix Materials |
| `-World` | `Install`, `Skip` | Whether to unpack the RTX Remix Showcase World into `saves\`. A world already there is kept. Omit it and an interactive run starts with the world ticked, while an unattended one leaves it out. See RTX Remix Showcase World |
| `-Verbose` | switch | Trace-level output. `install.bat` also honours `VERBOSE=1` in the environment |

## Calling installer.ps1 Directly

Useful when you want to redirect output or call it from another script:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\installer.ps1 `
  -Mode Install -Unattended -Launcher Prism -Verbose 2>&1 | Tee-Object installer.log
```

The script carries comment-based help:

```powershell
Get-Help .\installer.ps1
Get-Help .\installer.ps1 -Full
Get-Help .\installer.ps1 -Examples
```

## Java Resolution Order

The installer needs a Java only to run the Fabric installer, and the game needs
one to run. Which it uses depends on the architecture and the launcher.

- **x64 with Prism.** No Java step at all. Prism downloads Mojang's own Java on
  the first launch of the instance.
- **x64 with the Microsoft launcher.** `java` on the PATH, then the managed JDK
  at `%LOCALAPPDATA%\RtxRemixMinecraft\jdk-21`, then a Java bundled with the
  Microsoft launcher, then a downloaded Adoptium Temurin 21. Any version 8 or
  newer runs the Fabric installer, so no architecture match is required. The
  game itself then runs on the launcher's own Java.
- **ARM64.** `java` on the PATH only when it is version 21 and natively ARM64,
  then the managed JDK under the same test, then a downloaded Adoptium Temurin
  21 for ARM64. Mojang publishes no ARM64 Windows Java, and an emulated x64
  Java breaks the native bridge, so the managed install is normally used. A new
  Prism instance on ARM64 is pinned to that JDK so Prism cannot substitute an
  x64 one.

## Supply-Chain Safety

Downloads are checked against a SHA-256 before use, using a checksum pinned by
the installer or published by the dependency provider. This covers the JDK,
Prism portable, Fabric installer, and Fabric API.

If neither source yields a hash, an unattended run refuses to continue. An
interactive run asks if an offered checksum file is unreachable or malformed;
with no checksum source it only warns and continues. A mismatch always aborts.
`-SkipHashCheck` overrides all of it and exists for troubleshooting. Transport
integrity is already covered by TLS; this check detects corruption or tampering
relative to the expected hash. A compromised publisher can replace both a
download and its published checksum; an independently trusted pin provides
stronger protection.

Transient network errors are retried with a growing delay. Rate-limit errors
and stale version pins are reported separately. Supply a GitHub token through
`-GitHubToken` or `GITHUB_TOKEN` to raise the API rate limit, or pin a release
with `-PrismVersion` to skip discovery.

If the latest Prism release cannot be resolved, the installer can select a
pinned fallback and warns when it does. Pin a version yourself when the exact
release matters.

The installer-managed Prism portable does not self-update: the installer owns
its version. A Prism you installed yourself is left alone.

## Privacy Note On Export

Export copies the whole Prism instance, skips reparse points, and removes these
paths:

- `.minecraft\servers.dat` and `servers.dat_old`, which hold server addresses
  and cached session data
- `.minecraft\realms_persistence.json`, `usercache.json`, `usernamecache.json`
- `.minecraft\logs`, `crash-reports`, `saves`, and `screenshots`
- `.minecraft\libraries`, `assets`, `natives`, and `.cache`, all re-downloaded
  on the first launch
- Prism's own transient files: `.mmc-auto.json`, `instance.cfg.bak`, and
  `mmc-pack.json.bak`

Everything else is copied, including launcher settings, installed mods,
resource packs, any config you added, the extracted `remix-sdk\`, the
`.rtx-remix-version` marker, `rtx.conf`, and your lighting profiles under
`rtx-remix\`. A path the mod does not own, such as `rtx-remix\logs\`, travels
too, so look inside the archive before sharing it. Because the runtime and its
marker both travel, the recipient keeps using them until a mod version change
triggers a fresh extraction.
