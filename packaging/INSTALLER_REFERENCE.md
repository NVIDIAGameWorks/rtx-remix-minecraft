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

The installer detects its own portable copy of Prism, and a Prism on the PATH,
in Prism's default install folders, or behind a Start menu shortcut. Without a
detected Prism, the launcher question also offers **Use a Prism I already
have**, for a portable copy kept elsewhere or any Prism detection misses. It
asks for the folder holding `prismlauncher.exe`, or a data folder holding
`prismlauncher.cfg`. A Prism keeps its data beside `prismlauncher.exe` when
`portable.txt` marks it portable, and in `%APPDATA%\PrismLauncher\` otherwise.
Once the RTX Remix Prism instance is set up in that Prism, the installer
records its data folder in
`%LOCALAPPDATA%\RtxRemixMinecraft\prism-data-dir.txt` so later runs detect that
Prism too. A run that is cancelled or fails before then records nothing, and
removing the RTX Remix Prism instance at uninstall removes the record.

A run chosen from the menu ends by waiting for a key, so the window a
double-clicked `install.bat` opens stays up to show the result or the error.
Quit closes at once, and a run given `-Mode` never waits.

## Modes

| Mode | What it does |
|---|---|
| Install | A fresh install, or a deliberate reinstall of the packaged files over an existing one. Sets up the launcher, the mod, and Fabric API. Baseline PBR textures are built into the JAR, and the mod unpacks its runtime textures on first launch. Safe to re-run: it does not wipe an already-extracted runtime or your conf payload |
| Upgrade | Replaces the mod JAR and Fabric API. The new mod version refreshes `remix-sdk\`, `rtx.conf`, and the runtime textures on the next launch and adds conf profiles you do not have. Launcher config, JVM arguments, and options other than resource-pack ordering are preserved, and differing JVM arguments prompt before they are replaced. JVM arguments an earlier installer wrote are replaced without asking, in either launcher |
| Repair | Reinstalls the packaged files through the same path as Install and Upgrade. If the `.rtx-remix-version` marker matches, it does not re-extract the runtime or conf payload, but a missing `rtx.conf` is restored on launch. Delete the marker and relaunch to force full extraction |
| Uninstall | Removes the mod files listed in `README.md`, then offers to remove the installer-managed Java, the Prism portable, and the Prism instance, listing only those that exist. With both launchers holding the mod it asks Prism, Microsoft, or Both |
| Export | Prism only. Zips the Prism instance into an archive another machine can drop into its own Prism `instances\` folder, stripping the paths listed under Privacy below |

An interactive Install, Upgrade, or Repair opens the launcher when it
finishes; `-Unattended` suppresses that. A scripted
`install.bat -Mode Install -Launcher Prism` without it opens a window. With the
Microsoft launcher, an interactive run first waits for you to close a running
launcher.

Install, Upgrade, and Repair migrate the retired generated `RtxRemix-LabPBR`
pack. They identify it by metadata and texture inventory and leave other packs
alone. In an existing `options.txt`, only `resourcePacks` changes: vanilla,
Fabric, then user packs in their existing order, without the retired baseline
entry. They do not create a missing options file.

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
run would have asked a question. It also keeps the installer from opening the
launcher afterwards and from waiting for a running Microsoft launcher to close.

```powershell
# Fresh install with the usual defaults
install.bat -Mode Install -Unattended -Launcher Prism

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
| `-PrismDataDir` | path | Prism's data directory, the folder holding `instances\` and `prismlauncher.cfg`. Takes precedence over detection, including a Prism recorded after an earlier launcher question. Needed when Prism is on the PATH but keeps its data elsewhere, or for a headless run on a Prism detection misses. It has to exist already |
| `-SkipHashCheck` | switch | Do not verify the SHA-256 of downloads. Troubleshooting only: it turns off the check described under Supply-Chain Safety |
| `-GitHubToken` | token string | Authenticate GitHub API calls, which raises the rate limit well above the anonymous one. `GITHUB_TOKEN` in the environment is honoured too. Useful where many machines share one outbound address |
| `-DevServer` | `host` or `host:port` | Add one entry to the instance's saved server list. Omit it and `servers.dat` is not touched. An existing populated `servers.dat` is never merged into |
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
