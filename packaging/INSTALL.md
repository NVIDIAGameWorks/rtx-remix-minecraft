# RTX Remix for Minecraft

This archive installs a Fabric mod that renders Minecraft Java Edition 1.21.1
through NVIDIA RTX Remix. It sets up a launcher, Java,
Fabric, the mod, and the RTX Remix runtime for you.

## What You Need

- Windows 10 or 11, x64 or ARM64.
- Minecraft Java Edition 1.21.1, on a Microsoft account that owns the game.
- A GeForce RTX 30-series or newer GPU with NVIDIA driver 616.92 or newer.
- About 5 GB of free disk space while installing; about 3 GB stays in use.
- An internet connection while the installer runs.
- 16 GB of system memory is recommended.

## Install for **Prism Launcher** (Recommended)

1. (Optional) Install the [Prism Launcher (link)](https://prismlauncher.org/download/windows/)

2. Unzip the archive and **run** `install.bat`
    * Choose 'Install' -> 'Prism Launcher' (this will set up the mod automatically)

3. **Sign in** to your Microsoft account in Prism Launcher

4. Play
    * In Prism Launcher, double-click the 'NVIDIA RTX Remix for Minecraft' instance
    * *Note: if the instance is missing, press F5 to refresh*
    * *Note: on the very first launch, RTX Remix compiles its shaders before showing the world (one time only). See First Launch.*

## Install for **Microsoft Launcher** (Official)

1. Install the official [Microsoft's Minecraft Launcher (link)](https://www.minecraft.net/en-us/download)

2. Open the launcher, **sign in** to your Microsoft account

3. **Close** the launcher

4. Unzip the archive and **run** `install.bat`
    * Choose 'Install' -> 'Microsoft Launcher' (this will set up the mod automatically)

5. Play
    * In Minecraft Launcher, on the 'Minecraft: Java Edition' tab: **choose** 'NVIDIA RTX Remix for Minecraft' from the installation list, and hit 'Play'
    * *Note: on the very first launch, RTX Remix compiles its shaders before showing the world (one time only). See First Launch.*

---

## Notice

This is a beta. The mod began as a technology showcase and was tuned against
one showcase world, built for this project by BlockWorks
(<https://www.blockworks.uk/>), so the places that world visits are where it
looks and runs best. It ships with known issues, tracked at
<https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/issues>, and there are
bugs yet to be found. It has had limited testing alongside other
mods, it targets Minecraft 1.21.1 only, and blocks, mobs, dimensions, and
situations the showcase never reaches have had less care. When something
breaks, report it (see below); that is how it gets fixed.

## What Is In This Archive

| File or folder | What it is |
|---|---|
| `install.bat` | The installer. This is the one to run |
| `installer.ps1` | The installer itself. `install.bat` is a thin wrapper around it |
| `README.md` | This file |
| `INSTALLER_REFERENCE.md` | The installer modes, every flag, headless and CI use, and what the installer verifies before it downloads anything. |
| `mods\` | The mod, one JAR |
| `migrate_resource_packs.ps1` | Migrates legacy baseline packs and resource-pack ordering |
| `LICENSE`, `NOTICE` | The project's Apache License 2.0 and its attribution notices |
| `THIRD_PARTY_NOTICES.md`, `packaging\licenses\` | The third-party components the mod JAR carries, chiefly the RTX Remix runtime, and their license texts |

The mod file name ends in the version, a build timestamp, and a short commit
hash, which together identify the exact build. Keep the whole name for bug
reports. A file name ending in `-win64` or `-arm64` carries one architecture;
any other name carries both, and the matching runtime is unpacked on first
launch.

## Detailed installation

1. Extract the whole archive to a folder. Running the installer from inside the
   zip file does not work.
2. Double-click `install.bat`. The installer writes to your own user folders.
3. The installer opens a menu and preselects the right action for what is
   already on your machine: Install, Upgrade, or Repair. Press Enter to accept
   it. Upgrade and Repair keep the launcher that already has the mod and skip
   the launcher question; to use the other launcher, choose Install instead.
4. Answer the launcher question. With Prism and no Microsoft launcher on the
   machine, the installer uses Prism without asking. **Prism Launcher** is the
   recommended choice: the installer uses the Prism you already have, or
   downloads a portable copy if you have none, and creates a separate instance
   either way, so your existing game, worlds, and settings are untouched. The
   **Microsoft launcher** is the official one you already use, and the mod then
   shares the same game folder and the same worlds as your normal play.
5. The installer opens the launcher when it finishes. If it cannot, open it
   yourself. A Prism the installer downloaded has no Start menu entry: run
   `prismlauncher.exe` in `%LOCALAPPDATA%\RtxRemixMinecraft\PrismLauncher\`,
   the path the installer prints when it finishes. Sign in to your Microsoft
   account if you have not already. In Prism this is Accounts, then Manage
   Accounts, then Add Microsoft.
6. Start the game. Select the **NVIDIA RTX Remix for Minecraft** instance in
   Prism, or the profile of the same name in the Microsoft launcher.

`INSTALLER_REFERENCE.md` covers the rest: the modes, every flag, headless and
CI use, and what the installer verifies before it downloads anything. To add
the mod to a Fabric instance you already have instead, see
<https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/blob/main/docs/PLAYING.md#installing-without-the-installer>.

## First Launch

The first launch of a new version is slower than usual. The mod unpacks the
runtime into the game folder before the game window appears, which can look
like the game has frozen, especially on a hard drive. It happens once per
version.

The RTX Remix runtime also opens its First Use Guide over the title screen at
each launch, until you choose **Never Show This Again** in it. While the guide
is open, Minecraft receives no keyboard or mouse input, so the title screen
ignores clicks. Close the guide, or press Alt+X, to continue.

The guide also asks whether RTX Remix may send NVIDIA basic session information
and crash reports. Both boxes start checked, and closing the guide keeps them as
they are. The mod itself collects no data. You can change both later in the
Permissions tab of the RTX Remix Graphics Settings menu. What is sent is listed
at
<https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/blob/main/docs/PLAYING.md#crash-reports-and-privacy>.

Once you are in a world, the renderer compiles shaders and draws nothing until
it finishes. A dark, flat, or half-built scene right after is expected. Let it
settle before judging the picture.

The baseline PBR textures ship inside the mod JAR, so no separate resource
pack needs enabling. User packs override the built-in baseline.

## Check It Worked

Type `/rtx status` in chat. Check these two lines:

```text
  Renderer Initialized: true
  SDK Available: true
```

Both `true` means the renderer is initialized and the SDK was found. The same
status output reports `Enabled (master)`, which says whether RTX rendering is
enabled. For a direct comparison, type `/rtx off`, look around, then `/rtx on`.

## Updating

Download the new archive from
<https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/releases/latest>,
extract it, and run `install.bat` again. It
preselects Upgrade and replaces the mod and its supporting files in the
launcher that already has them. Launcher, Minecraft, Fabric, and other options
are preserved. To install into the other launcher instead, choose Install.

Install, Upgrade, and Repair remove an old generated `RtxRemix-LabPBR` pack
identified by its metadata and texture inventory, leaving other packs alone.
In an existing `options.txt`, they change only `resourcePacks`: vanilla first,
Fabric next, then user packs in their existing order, without the retired
baseline entry. Missing options files are not created.

The runtime under `remix-sdk\` and the baseline `rtx.conf` belong to the mod
and are replaced on the first launch after an upgrade, so edits to either do
not survive it. A missing `remix-sdk\` or `remix-sdk\d3d9.dll` triggers full
re-extraction on the next launch, even with a matching version marker.
A missing `rtx.conf` is also restored on the next launch.

The lighting profiles under `rtx-remix\mods\minecraft-logic\` belong to you.
An upgrade adds profiles you do not have and never writes over one you do, so
tuning survives. That also means a new shipped default cannot reach a file you
already have: delete that file and relaunch to take the shipped version.
Missing shipped files are restored even when `.rtx-remix-version` matches.
Deleting the marker forces full re-extraction of the runtime and `rtx.conf`,
but still never overwrites existing profiles.

## Uninstall

Run `install.bat` and choose **Uninstall**. It removes the mod files and then
offers to remove the Java, the Prism portable, and the Prism instance it
installed for you, listing only the ones that are actually on disk.

Everything is under the game folder, which is:

- Prism, when the installer downloaded a portable copy:
  `%LOCALAPPDATA%\RtxRemixMinecraft\PrismLauncher\instances\NVIDIA RTX Remix for Minecraft\.minecraft\`
- Prism, when you already had it: the same
  `instances\NVIDIA RTX Remix for Minecraft\.minecraft\` path under your own
  Prism data folder, usually `%APPDATA%\PrismLauncher\`
- Microsoft launcher: `%APPDATA%\.minecraft\`

The automated uninstall removes these:

- `mods\rtx-remix-renderer-*.jar`
- `mods\fabric-api-*.jar`, all of them in a Prism instance, and only the
  version the mod pins in the Microsoft launcher's shared `mods\` folder
- `remix-sdk\`
- `rtx.conf`
- `.rtx-remix-version`
- `textures\water_normals_flipbook.dds` and
  `textures\overcast_thunderstorm_bc6u.dds`
- `resourcepacks\RtxRemix-LabPBR\`, if left by an earlier version

Basic uninstall leaves these behind, because they are yours or they are part
of a world. Delete them by hand if you want the folder clean:

- `rtx-remix\`, which holds the lighting profiles and anything you edited
- `config\rtxremix.json`, the settings you changed in the mod's settings screen
- `camera-paths\`, your camera recordings
- `benchmark\`, results from `/rtx benchmark`
- `remix-sdk.old-*\`, a previous runtime that was still locked when it was
  replaced. The mod deletes these itself on a later launch
- `saves\<world>\rtx-remix\` and `saves\<world>\data\rtx_remix_zones.dat`,
  which are lighting content belonging to that world

The optional Prism instance or portable cleanup deletes everything inside
those directories, including worlds and the files listed above.

If an earlier installer enabled `RtxRemix-LabPBR` in Prism, remove its entry
from the `resourcePacks:` line in `options.txt`, or delete the whole instance
if you are wiping it anyway.

## Troubleshooting The Installer

| Symptom | What to do |
|---|---|
| Windows SmartScreen warns about the Prism Launcher download | Prism Launcher is open-source and is not signed by Microsoft. Choose More info, then Run anyway. You can also install it yourself from <https://prismlauncher.org>, start it once, then re-run `install.bat`, which detects it |
| The Prism download fails | Check the firewall and retry. The installer asks GitHub for the newest Prism release, and a shared network address can hit GitHub's anonymous rate limit. If GitHub reports a rate limit, wait before retrying |
| `Download failed after 3 attempts from` followed by an Adoptium URL and the error details | The Adoptium API at `api.adoptium.net` has to be reachable. Check the firewall and re-run |
| `Failed to extract JDK 21 archive:` followed by the error details | Check free disk space and the permissions on your temporary folder, then re-run the installer to download and extract a fresh archive |
| The Fabric API download fails | `maven.fabricmc.net` has to be reachable. Check the firewall and re-run |
| The installer refuses to choose a launcher | Both Prism and the Microsoft launcher are installed and the run was headless. Pass `-Launcher Prism` or `-Launcher Microsoft` |

Problems inside the game rather than inside the installer, such as a black
screen or a flat-looking world, are covered by the player guide at
<https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/blob/main/docs/PLAYING.md#troubleshooting>.
One case worth checking first: with other mods installed, a crash,
a black screen, or wrong rendering is most often a conflict with a mod that
changes rendering or chunk loading. Remove the other mods and try again, and
say which mod was involved in your report.

## Reporting A Bug

Open an issue at <https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/issues>
for anything about the mod: what
is in the world and how it looks, the settings screen, `/rtx` commands and
keys, lighting profiles, the installer, and crashes. Denoiser, DLSS, or
frame-generation artifacts, Remix's own menus (Alt+X and RTX Remix Graphics
Settings), and anything that also happens in another RTX Remix game belong to
the runtime. Report those at
<https://github.com/NVIDIAGameWorks/rtx-remix/issues/new?template=runtime_bug_report.yml>,
say you are playing RTX Remix for Minecraft, and give the `DXVK_Remix:` line
from the top of `rtx-remix\logs\remix-dxvk.log` as the version. If you are not
sure which it is, report it on this project and it will be moved.

Rendering problems are hard to act on without specifics, so include all of
the following:

1. The mod file name, from the `mods\` folder. It identifies the exact build.
2. Which launcher you used, Prism or Microsoft.
3. Your GPU and driver version, from the NVIDIA app or `nvidia-smi`.
4. Whether a laptop display is driven by the built-in graphics chip, from
   Windows Settings, Display, Graphics.
5. Your Windows architecture. Run `systeminfo | findstr "System Type"`, which
   reports `x64-based PC` or `ARM64-based PC`.
6. Which resource packs are on, from Options, Resource Packs.
7. The output of `/rtx status`.
8. `logs\latest.log` and `rtx-remix\logs\remix-dxvk.log` from the game
   folder, attached rather than pasted.
9. For a crash, a zip of whatever the game folder has of `crash\`,
   `crash-reports\`, and `hs_err_pid*.log`.
10. A screenshot of the problem. `F2` saves one to the `screenshots\` folder.
11. For a visual bug, a matching pair: take the shot, type `/rtx off`, take a
    second shot from the same spot, then `/rtx on`.

The full guides, covering the settings screen, the commands, camera paths, and
how to give a map its own lighting, live with the project's source at
<https://github.com/NVIDIAGameWorks/rtx-remix-minecraft>.

## License

The mod is licensed under the Apache License, Version 2.0; see `LICENSE` and
`NOTICE`. The mod JAR also carries the RTX Remix runtime, whose third-party and
NVIDIA components are listed, with their license terms, in
`THIRD_PARTY_NOTICES.md`.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR
MICROSOFT. Minecraft is a trademark of Mojang Synergies AB.

NVIDIA, GeForce, and RTX are trademarks and/or registered trademarks of NVIDIA
Corporation in the U.S. and other countries.
