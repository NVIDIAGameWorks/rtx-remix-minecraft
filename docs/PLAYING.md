# Playing

This guide takes you from a downloaded archive to a path-traced world. It is
written for players. You do not need development tools, and you do not need to
install Java, Fabric, or the RTX Remix runtime yourself.

## What You Need

- Windows 10 or 11, x64 or ARM64.
- Minecraft Java Edition 1.21.1, on a Microsoft account that owns the game.
- A GeForce RTX 30-series or newer GPU with NVIDIA driver 616.92 or newer.
- About 5 GB of free disk space while installing, for the downloaded archive,
  the game, Java, and the unpacked runtime. About 3 GB stays in use afterwards.
- An internet connection while the installer runs.
- 16 GB of system memory is recommended.

Java 21, Fabric, and the RTX Remix runtime are installed for you.

## Before You Start

This is a beta. The mod began as a technology showcase and was tuned against
one showcase world, built for this project by
[BlockWorks](https://www.blockworks.uk/), so the places that world visits are
where it looks and runs best. It ships with
[known issues](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/issues),
and there are bugs yet to be found. Two things to know before a long session:

- **Other mods.** It has had limited testing alongside other mods, and mods
  that change rendering or chunk loading are likely to conflict with it.
- **Scope.** It targets Minecraft 1.21.1 only. Blocks, mobs, dimensions, and
  situations the showcase never reaches have had less care, and some will look
  wrong or run slowly.

When something breaks, [report it](#reporting-a-bug); that is how it gets
fixed.

## Download

Download the release archive from the
[Releases page](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/releases/latest)
and extract it.

## Which Launcher

The installer asks which Minecraft launcher to use, unless it finds Prism and
no Microsoft launcher, when it uses Prism without asking. Both work. The
difference is where the game lives.

**Prism Launcher** is a free launcher built for modded Minecraft. The installer
uses your existing Prism, or downloads its own portable copy if you have none,
and creates a separate instance for the mod, so your existing game, worlds, and
settings are untouched. Worlds you create in that instance are separate from
the worlds in your normal launcher.

**The Microsoft launcher** is the official launcher you already use. The
installer adds a profile to it, and the mod shares the same game folder and the
same worlds as your normal play.

Prism is the recommended choice. Either way, you sign in with the same
Microsoft account.

## Install

1. Extract the whole archive to a folder. Running the installer from inside the
   zip file does not work.
2. Double-click `install.bat`. Do not right-click and run it as administrator.
   The installer writes to your own user folders and refuses to run elevated.
3. The installer opens a menu and preselects the right action for what is
   already on your machine: Install, Upgrade, or Repair. Press Enter to accept
   it. Answer the launcher question described above. Upgrade and Repair keep
   the launcher that already has the mod and do not ask; to use the other
   launcher, choose Install instead.
4. Open the launcher. A Prism the installer downloaded has no Start menu
   entry: run `prismlauncher.exe` in
   `%LOCALAPPDATA%\RtxRemixMinecraft\PrismLauncher\`, the path the installer
   prints when it finishes. Sign in to your Microsoft account if you have not
   already. In Prism this is Accounts, then Manage Accounts, then Add
   Microsoft.
5. Start the game. In Prism, select the **NVIDIA RTX Remix for Minecraft**
   instance and press Launch. In the Microsoft launcher, select the **NVIDIA
   RTX Remix for Minecraft** profile and press Play.

### Installing Without The Installer

If you already run a Fabric instance for Minecraft 1.21.1 in a launcher of
your choice, you can install the mod by hand. The instance needs Java 21,
Fabric Loader 0.16.5 or newer, and Fabric API 0.116.10+1.21.1 or newer.

1. Copy the JAR from the archive's `mods\` folder into the instance's `mods\`
   folder. It carries the runtime and its textures and unpacks them into the
   game folder on first launch.
2. Optionally give the instance the JVM arguments the installer would set:
   a 2 GB heap with G1GC, listed as `RECOMMENDED_JVM_ARGS` in
   [`packaging/installer.ps1`](../packaging/installer.ps1).

Upgrade, Repair, and Uninstall do not know about a manual install. To update,
replace the JAR. To remove the mod, delete the JAR and the files it unpacked,
which [packaging/INSTALL.md](../packaging/INSTALL.md#uninstall) lists.

## First Launch

The first launch of a new version is slower than usual. The mod unpacks the
runtime into the game folder before the game window appears, which can look
like the game has frozen, especially on a hard drive. It happens once per
version.

Once you are in a world, the renderer compiles shaders and draws nothing until
it finishes. A dark, flat, or half-built scene right after is expected. Let it
settle before judging the picture.

The baseline PBR textures ship inside the mod JAR, so neither launcher needs
a separate baseline pack enabled. User packs override the built-in baseline.

## Check It Worked

Type `/rtx status` in chat. Check these two lines:

```text
  Renderer Initialized: true
  SDK Available: true
```

Both `true` means the renderer is initialized and the SDK was found. The same
status output reports `Enabled (master)`, which says whether RTX rendering is
enabled. For a direct comparison, press `F8` to toggle path tracing, or type
`/rtx off`, look around, then `/rtx on`.

## Settings

The mod's own settings screen is in **Options**, then **RTX Remix Settings...**.
Its pages cover distance and framerate, sky and clouds, lighting, and
materials. The renderer's quality tier lives in Remix's own Graphics Settings
menu, which the **RTX Remix Graphics Settings** button on that screen opens.
See [Settings](SETTINGS.md) for how personal changes and map lighting fit
together.

**Alt+X** opens something different: the RTX Remix runtime's own developer
menu, aimed at people tuning the renderer itself. Anything you change there is
saved by Remix and overrides the mod's settings until you undo it. The
**RTX Remix Graphics Settings** button opens the runtime's simpler menu the
same way. While either is open the game receives no keyboard or mouse input,
Escape included; close the menu with its own close button, or press Alt+X
again, to carry on playing. The runtime's First Use Guide opens at each launch
until you choose **Never Show This Again** in it. It holds input the same way,
so the title screen ignores clicks until you close the guide or press Alt+X.

## Multiplayer

Path tracing runs entirely on your own machine, and the `/rtx` commands and
keys change only what you see. The Copper Beacon is a real block, though: to
place one on a server, the server needs the mod installed as well. On a server:

- Zones you define with `/rtx zone` apply to your own client for that session
  only. In single-player they are saved into the world.
- The chunk pacing that keeps single-player frames steady while the world loads
  applies only to single-player, so frame times on a server can be less even.
- Multiplayer has had less testing than single-player.

## Crash Reports And Privacy

The mod itself collects no data. The RTX Remix runtime it carries can send
diagnostic data to NVIDIA, and its First Use Guide asks about it with two
checkboxes, both on by default:

- **Basic session and system information**, collected each time RTX Remix
  launches: the game name, the Remix version, your GPU model and driver, the
  Windows version, the amount of memory, and session configuration flags.
- **Technical crash information**, sent when RTX Remix crashes: the same system
  information plus the crash type and the GPU's diagnostic state. A crash report
  carries a memory snapshot of the crashed process and the runtime's
  `remix-dxvk.log`.

Closing the guide keeps the boxes as they are, so uncheck either one there to
turn it off. You can change both at any time in the Permissions tab of the
**RTX Remix Graphics Settings** menu.

Crash data waits under `rtx-remix\logs\.sentry-native\` in the game folder
until it is sent, and is deleted after a successful upload. NVIDIA uses the data
to find and fix problems and to decide what to work on, under the
[NVIDIA Privacy Policy](https://www.nvidia.com/en-us/about-nvidia/privacy-policy/).
The runtime's
[crash reporting documentation](https://github.com/NVIDIAGameWorks/dxvk-remix/blob/main/documentation/SentryCrashReporting.md)
has the details.

## Troubleshooting

| Symptom | What to do |
|---|---|
| The scene is dark, flat, or half-built on first entering a world | Wait for shader compilation to settle before judging the picture. |
| The first launch looks frozen before the window appears | The runtime may be unpacking after an install, update, or runtime restore. Hard drives take longer than SSDs. |
| Blocks look flat or plastic, like paper cutouts | Baseline PBR textures ship with the mod. Disable extra resource packs to check whether one overrides those textures or sidecars. |
| The game closes during startup, and `latest.log` says `NVIDIA driver is below the minimum supported by RTX Remix; closing Minecraft` | Update your NVIDIA driver from the NVIDIA app or <https://www.nvidia.com/en-us/drivers/>, then relaunch. The mod closes Minecraft when the driver is too old for the runtime. |
| `/rtx status` reports `Renderer Initialized: false` | Look for `Failed to initialize RTX Remix renderer` in `logs\latest.log` and read the error that follows. If it names a conf file, check that file; an existing file that fails to load is not automatically replaced. Include the logs when reporting the failure. |
| On a laptop: the game runs but looks like ordinary Minecraft, or is extremely slow | The display is being driven by the built-in graphics chip. In Windows Settings, Display, Graphics, add `javaw.exe` and set it to High performance, then relaunch. |
| Windows SmartScreen warns about the Prism Launcher download | Prism Launcher is open-source and is not signed by Microsoft. Choose More info, then Run anyway. You can also install it yourself from <https://prismlauncher.org>, start Prism once, then re-run `install.bat`, which detects it. |
| Very choppy at a high render distance | Lower Minecraft's render distance, or pick a lower tier in Options, RTX Remix Settings, RTX Remix Graphics Settings. |
| The game will not start at all | Launch the **NVIDIA RTX Remix for Minecraft** profile rather than a plain Minecraft one, and confirm the launcher is set to Java 21. |
| A crash, a black screen, or wrong rendering with other mods installed | Remove the other mods and try again. Mods that change rendering or chunk loading are the likely conflict. If one mod turns out to be the cause, say which in your report. |

If none of that helps, the log is the next thing to look at. It is at
`logs\latest.log` inside the game folder:

- Prism, when the installer downloaded a portable copy:
  `%LOCALAPPDATA%\RtxRemixMinecraft\PrismLauncher\instances\NVIDIA RTX Remix for Minecraft\.minecraft\logs\latest.log`
- Prism, when you already had it: under your own Prism data folder, usually
  `%APPDATA%\PrismLauncher\instances\NVIDIA RTX Remix for Minecraft\.minecraft\logs\latest.log`
- Microsoft launcher: `%APPDATA%\.minecraft\logs\latest.log`

Paste either path into the File Explorer address bar to open the folder.

## Reporting a Bug

Open an issue on the project's
[issue tracker](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/issues)
for anything about the mod: what
is in the world and how it looks, the settings screen, `/rtx` commands and
keys, lighting profiles, the installer, and crashes. Denoiser, DLSS, or
frame-generation artifacts, Remix's own menus (Alt+X and RTX Remix Graphics
Settings), and anything that also happens in another RTX Remix game belong to
the runtime. Report those on the
[RTX Remix tracker](https://github.com/NVIDIAGameWorks/rtx-remix/issues/new?template=runtime_bug_report.yml),
say you are playing RTX Remix for Minecraft, and give the `DXVK_Remix:` line
from the top of `rtx-remix\logs\remix-dxvk.log` as the version. If you are not
sure which it is, report it here and it will be moved.

Rendering problems are hard to act on without specifics, so include all of the
following:

| What to include | Where to find it |
|---|---|
| The mod file name | The `mods\` folder in the game directory. The name ends in a build stamp, which identifies the exact build. |
| Which launcher you used | Prism or Microsoft. |
| Your GPU and driver version | The NVIDIA app, or `nvidia-smi` in a Command Prompt. |
| Whether a laptop display is driven by the built-in graphics chip | Windows Settings, Display, Graphics. |
| Your Windows architecture | Run `systeminfo \| findstr "System Type"`. It reports `x64-based PC` or `ARM64-based PC`. |
| Which resource packs are on | Options, Resource Packs. Material bugs depend on this. |
| The output of `/rtx status` | Type it in chat and copy the lines. |
| `latest.log` and `remix-dxvk.log` | `latest.log` is at the paths above, and `remix-dxvk.log` is in `rtx-remix\logs\` in the same game folder. Attach the files rather than pasting part of them. |
| For a crash, the crash files | Zip whatever the game folder has of `crash\`, `crash-reports\`, and `hs_err_pid*.log`, and attach the zip. |
| A screenshot of the problem | F2 saves one to the `screenshots\` folder. |
| For a visual bug, a matching pair | Take the shot, type `/rtx off`, take a second shot from the same spot, then `/rtx on`. The pair shows what changed. |

## Updating

Download the new release archive, extract it, and run `install.bat` again. It
preselects Upgrade, which updates the launcher that already has the mod; choose
Install to use the other launcher. Your worlds, settings, and any lighting
profiles you have edited are kept, and profiles added by the new release are
placed beside them.
To restore a shipped profile's default, delete that profile file and relaunch.
Missing shipped files under `rtx-remix\` and a missing `rtx.conf` return on
the next launch even when `.rtx-remix-version` matches. Existing profiles,
including edited files, are never overwritten. Deleting `remix-sdk\` or its
`d3d9.dll` also triggers recovery on the next launch. Deleting
`.rtx-remix-version` forces full re-extraction of the runtime and `rtx.conf`,
but still preserves existing profiles.

## Uninstalling

Run `install.bat` and choose Uninstall. It removes the mod files, such as the
mod, Fabric API, the runtime, and `rtx.conf`, and then offers to remove the
Java, the Prism portable, and the Prism instance it installed for you. Basic
uninstall leaves your worlds alone, along with any lighting profiles you
authored under `rtx-remix\`. The optional Prism instance or portable cleanup
deletes everything inside those directories, including worlds and authored
profiles. The full manual file list is in
[packaging/INSTALL.md](../packaging/INSTALL.md).

## Going Further

| You want to | Read |
|---|---|
| Configure the renderer | [Settings](SETTINGS.md) |
| Look up a command, a key, or a mod-added block | [In-Game Guide](IN_GAME_GUIDE.md) |
| Record and replay a camera flythrough | [Camera Paths](CAMERA_PATHS.md) |
| Give your own map its own lighting | [Gameplay-driven profiles](../packaging/rtx-remix/mods/minecraft-logic/README.md) |
| Make a texture pack that this renderer understands | [Resource Packs](RESOURCE_PACKS.md) |
