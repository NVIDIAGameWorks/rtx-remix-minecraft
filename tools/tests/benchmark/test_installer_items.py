# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""The checklist where a player running packaging/installer.ps1 ticks what to install, under Windows PowerShell 5.1
as packaging/install.bat runs it: RTX Remix Renderer (the mod), the RTX Remix Materials pack, and the RTX Remix
Showcase World, each from the download the installer came in.

A player who already has the mod can tick only the pack or the world. Those go into the game folder an earlier install
made, because only the mod's steps prepare a launcher."""

import json
import os
import re
import subprocess
import zipfile
from collections.abc import Callable
from pathlib import Path

import pytest

from rtx_tools.benchmark import util

pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows PowerShell runs the installer")

INSTALLER = util.PROJECT_DIR / "packaging" / "installer.ps1"
JAR = "rtx-remix-renderer-1.0.0.jar"
PACK = "RTX-Remix-Materials-0.58.0.zip"
PACK_BYTES = b"PK\x05\x06" + bytes(18)
WORLD = "NVIDIA RTX Demo"
WORLD_ZIP = "RTX-Remix-Showcase-World-v0.11.1.zip"
INSTANCE = "NVIDIA RTX Remix for Minecraft"
TITLE = "  Choose what to install. Enter ticks or unticks an item."
MICROSOFT_NOTE = "also changes the look of your normal Minecraft"
PLAY_THE_WORLD = "Open Singleplayer and choose NVIDIA RTX Demo."
COMPLETE = "RTX Remix for Minecraft install complete"
PROBLEMS = "RTX Remix for Minecraft installed with problems"
ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")
STEP = re.compile(r"^\[(\d+)/(\d+)\] \S+ (.+)$")
# Loads the installer's constants and functions without its entry point, as test_installer_art_pack.py does, points
# it at -Dist as the folder it was extracted to, and keeps %APPDATA% and %LOCALAPPDATA% inside -UserDir. Each menu
# prints its title, the item its cursor starts on, and its items as "Item: <label> | <hint> | disabled=<bool>", and
# answers with the next of -Picks, or as Esc does once they run out. Action runs one step: Select runs the checklist,
# ContentOnly finds the game folder a run without the mod installs into, Run installs as Install does, and Uninstall
# uninstalls with every question answered yes. The steps that prepare a launcher and install the mod only print that
# they were called. The last line is what the step returned, as JSON, or what it threw.
HARNESS = r"""
param([string]$Installer, [string]$Loaded, [string]$Dist, [string]$UserDir, [string]$Action, [string]$Launcher,
      [switch]$Unattended, [string]$Picks, [string]$Mod, [string]$ArtPack, [string]$World, [string]$PrismDataDir)
$env:APPDATA = Join-Path $UserDir 'Roaming'
$env:LOCALAPPDATA = Join-Path $UserDir 'Local'
$ast = [System.Management.Automation.Language.Parser]::ParseFile($Installer, [ref]$null, [ref]$null)
$entryPoint = $ast.EndBlock.Statements[-1]
if ($entryPoint -isnot [System.Management.Automation.Language.TryStatementAst]) {
    throw 'installer.ps1 no longer ends with its entry point'
}
$start = $ast.ParamBlock.Extent.EndOffset
$body = $ast.Extent.Text.Substring($start, $entryPoint.Extent.StartOffset - $start)
[IO.File]::WriteAllText($Loaded, $body, [Text.UTF8Encoding]::new($true))
. $Loaded
$script:SCRIPT_DIR = $Dist
$script:answers = @($Picks -split ',' | Where-Object { $_ })
$script:answered = 0
function Invoke-Menu {
    param([string]$Title, [array]$Items, [string]$Footer, [string]$Selected)
    Write-Host "Title: $Title"
    Write-Host "Selected: $Selected"
    foreach ($item in $Items) {
        $disabled = [bool]($item.PSObject.Properties['Disabled'] -and $item.Disabled)
        Write-Host "Item: $($item.Label) | $($item.Hint) | disabled=$disabled"
    }
    if ($script:answered -ge $script:answers.Count) { return $null }
    $script:answered++
    return $script:answers[$script:answered - 1]
}
function Confirm-YesNo { param([string]$Prompt, [switch]$DefaultNo) return $true }
function Test-NotAdmin { return $true }
function Test-HostIsArm64 { return $false }
function Get-DetectedState {
    $state = Get-LauncherState
    $mods = @{}
    if ($state.PrismDataDir) {
        $mcDir = Get-McDirForLauncher -Launcher 'Prism' -PrismDataDir $state.PrismDataDir
        $version = Get-InstalledModVersion -McDir $mcDir
        if ($version) { $mods['Prism'] = $version }
    }
    [pscustomobject]@{
        HasPrism = $state.HasPrism; HasMs = $state.HasMs; PrismDataDir = $state.PrismDataDir; ModInstalled = $mods
    }
}
function Test-PathLengthBudget { param([string]$PrismDataDir) }
function Initialize-Prism {
    param([pscustomobject]$State, [switch]$Unattended)
    Write-Host 'Called: Initialize-Prism'
    $instance = Join-Path $State.PrismDataDir "instances\$($script:PRISM_INSTANCE_NAME)"
    $null = New-Item -ItemType Directory -Force -Path (Join-Path $instance '.minecraft')
    return $instance
}
function Wait-MinecraftLauncherClosed { Write-Host 'Called: Wait-MinecraftLauncherClosed' }
function Resolve-JavaForFabricInstaller { Write-Host 'Called: Resolve-JavaForFabricInstaller' }
function Initialize-MicrosoftLauncher {
    param([string]$JavaExe, [switch]$Unattended)
    Write-Host 'Called: Initialize-MicrosoftLauncher'
}
function Install-ModFiles { param([string]$McDir) Write-Host 'Called: Install-ModFiles' }
function Install-FabricApi { param([string]$McDir, [string]$Launcher) Write-Host 'Called: Install-FabricApi' }
function Start-InstalledLauncher {
    param([string]$Launcher, [string]$PrismDataDir)
    Write-Host 'Called: Start-InstalledLauncher'
}
try {
    $returned = @(switch ($Action) {
        'Select' {
            Select-InstallItems -Launcher $Launcher -Unattended:$Unattended `
                -ModChoice $Mod -ArtPackChoice $ArtPack -WorldChoice $World
        }
        'ContentOnly' { Get-ContentOnlyMcDir -Launcher $Launcher -PrismDataDir $PrismDataDir }
        'Run' { Invoke-InstallOrUpgrade -PreferredLauncher $Launcher -Unattended:$Unattended -Mode 'Install' }
        'Uninstall' { Invoke-Uninstall -PreferredLauncher $Launcher }
        default { throw "Unknown action: $Action" }
    })
    Write-Host ('Returned: ' + (ConvertTo-Json -InputObject $returned -Compress))
} catch {
    Write-Host "Threw: $($_.Exception.Message)"
}
"""
RETURNED = "Returned: "
THREW = "Threw: "

Run = Callable[..., subprocess.CompletedProcess[str]]


def write_zip(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries.items():
            archive.writestr(name, data)


def last_line(output: str, prefix: str) -> str | None:
    lines = [line for line in output.splitlines() if line.startswith((RETURNED, THREW))]
    assert len(lines) == 1, output
    return lines[0][len(prefix) :] if lines[0].startswith(prefix) else None


def returned(output: str) -> list:
    value = last_line(output, RETURNED)
    assert value is not None, output
    return json.loads(value)


def threw(output: str) -> str:
    value = last_line(output, THREW)
    assert value is not None, output
    return value


def chosen(output: str) -> tuple[bool, bool, bool]:
    [items] = returned(output)
    return items["Mod"], items["ArtPack"], items["World"]


def menus(output: str) -> list[list[str]]:
    """The lines each menu printed, from its title on."""
    shown: list[list[str]] = []
    for line in output.splitlines():
        if line.startswith("Title: "):
            shown.append([])
        if shown and line.startswith(("Title: ", "Selected: ", "Item: ")):
            shown[-1].append(line)
    return shown


def items(menu: list[str]) -> list[str]:
    return [line for line in menu if line.startswith("Item: ")]


def steps(output: str) -> list[tuple[int, int, str]]:
    found = []
    for line in ANSI.sub("", output).splitlines():
        match = STEP.match(line)
        if match:
            found.append((int(match[1]), int(match[2]), match[3]))
    return found


def final_box(output: str, headline: str) -> list[str]:
    """The five lines of the box the final instructions open with: its border, a blank line, the headline line, a
    blank line, and its border."""
    lines = output.splitlines()
    [row] = [i for i, line in enumerate(lines) if headline in line]
    return lines[row - 2 : row + 3]


def files(folder: Path) -> list[str]:
    return sorted(path.relative_to(folder).as_posix() for path in folder.rglob("*")) if folder.exists() else []


@pytest.fixture
def dist(tmp_path: Path) -> Path:
    path = tmp_path / "dist"
    for folder in ("mods", "resourcepacks", "saves"):
        (path / folder).mkdir(parents=True)
    (path / "mods" / JAR).write_bytes(b"jar")
    (path / "resourcepacks" / PACK).write_bytes(PACK_BYTES)
    write_zip(path / "saves" / WORLD_ZIP, {f"{WORLD}/level.dat": b"level"})
    return path


@pytest.fixture
def user_dir(tmp_path: Path) -> Path:
    path = tmp_path / "user"
    (path / "Roaming").mkdir(parents=True)
    (path / "Local").mkdir()
    return path


@pytest.fixture
def prism(tmp_path: Path) -> Path:
    """A Prism data folder without the RTX Remix instance."""
    path = tmp_path / "PrismLauncher"
    path.mkdir()
    return path


def instance(prism: Path) -> Path:
    """The game folder of the RTX Remix Prism instance, as installing the mod leaves it."""
    path = prism / "instances" / INSTANCE / ".minecraft"
    (path / "mods").mkdir(parents=True)
    (path / "mods" / JAR).write_bytes(b"jar")
    return path


@pytest.fixture
def run(tmp_path: Path, dist: Path, user_dir: Path, prism: Path) -> Run:
    harness = tmp_path / "harness.ps1"
    harness.write_text(HARNESS, encoding="ascii")

    def invoke(
        action: str = "Select",
        *,
        launcher: str = "Prism",
        unattended: bool = False,
        picks: str = "",
        mod: str = "",
        art_pack: str = "",
        world: str = "",
        prism_data_dir: Path | None = prism,
    ) -> subprocess.CompletedProcess[str]:
        command = [
            "powershell",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness),
            "-Installer",
            str(INSTALLER),
            "-Loaded",
            str(tmp_path / "installer-functions.ps1"),
            "-Dist",
            str(dist),
            "-UserDir",
            str(user_dir),
            "-Action",
            action,
            "-Launcher",
            launcher,
        ]
        if unattended:
            command.append("-Unattended")
        for name, value in (("-Picks", picks), ("-Mod", mod), ("-ArtPack", art_pack), ("-World", world)):
            if value:
                command += [name, value]
        if prism_data_dir:
            command += ["-PrismDataDir", str(prism_data_dir)]
        result = subprocess.run(command, capture_output=True, encoding="utf-8", errors="replace", check=False)
        assert result.returncode == 0, result.stdout + result.stderr
        return result

    return invoke


def select(run: Run, **arguments) -> str:
    result = run("Select", **arguments)
    assert not result.stderr
    return result.stdout


def test_all_three_start_ticked(run: Run):
    output = select(run, picks="Continue")

    assert chosen(output) == (True, True, True)
    [menu] = menus(output)
    assert menu[0] == f"Title: {TITLE}"
    # Every menu before this one teaches Enter to accept, so Enter here installs what is ticked rather than unticking
    # the mod.
    assert menu[1] == "Selected: Continue"
    assert items(menu) == [
        "Item: [x] RTX Remix Renderer | the mod | disabled=False",
        "Item: [x] RTX Remix Materials | texture pack | disabled=False",
        "Item: [x] RTX Remix Showcase World | the demo world | disabled=False",
        "Item: Continue | install the ticked items | disabled=False",
    ]


def test_picking_an_item_toggles_it(run: Run):
    output = select(run, picks="ArtPack,Continue")

    assert chosen(output) == (True, False, True)
    first, second = menus(output)
    assert "Item: [ ] RTX Remix Materials | texture pack | disabled=False" in items(second)
    assert "Item: [x] RTX Remix Renderer | the mod | disabled=False" in items(second)
    # The cursor stays on the item just toggled.
    assert "Selected: ArtPack" in second


def test_picking_an_item_twice_ticks_it_again(run: Run):
    output = select(run, picks="World,World,Continue")

    assert chosen(output) == (True, True, True)
    assert "Item: [ ] RTX Remix Showcase World | the demo world | disabled=False" in items(menus(output)[1])


@pytest.mark.parametrize("launcher", ["Prism", "Microsoft"])
def test_the_microsoft_launcher_warns_the_pack_changes_normal_minecraft(run: Run, launcher: str):
    # The Microsoft launcher's game folder is the player's everyday one, so the pack shows in every profile.
    [menu] = menus(select(run, launcher=launcher, picks="Continue"))

    [pack] = [line for line in items(menu) if "RTX Remix Materials" in line]
    if launcher == "Microsoft":
        assert pack == f"Item: [x] RTX Remix Materials | texture pack; {MICROSOFT_NOTE} | disabled=False"
    else:
        assert MICROSOFT_NOTE not in pack


def test_continue_is_disabled_with_nothing_ticked(run: Run):
    output = select(run, picks="Mod,ArtPack,World")

    shown = menus(output)
    assert len(shown) == 4
    assert items(shown[-1]) == [
        "Item: [ ] RTX Remix Renderer | the mod | disabled=False",
        "Item: [ ] RTX Remix Materials | texture pack | disabled=False",
        "Item: [ ] RTX Remix Showcase World | the demo world | disabled=False",
        "Item: Continue | install the ticked items | disabled=True",
    ]
    # Out of picks, the menu answers as Esc does.
    assert threw(output) == "Cancelled by user."


@pytest.mark.parametrize(
    ("missing", "label", "position"),
    [
        (f"saves/{WORLD_ZIP}", "RTX Remix Showcase World", 2),
        (f"resourcepacks/{PACK}", "RTX Remix Materials", 1),
        (f"mods/{JAR}", "RTX Remix Renderer", 0),
    ],
)
def test_an_item_missing_from_the_bundle_is_disabled_and_unticked(
    run: Run, dist: Path, missing: str, label: str, position: int
):
    (dist / missing).unlink()

    output = select(run, picks="Continue")

    expected = [True, True, True]
    expected[position] = False
    assert chosen(output) == tuple(expected)
    [menu] = menus(output)
    assert items(menu)[position] == f"Item: [ ] {label} | not in this download | disabled=True"


def test_a_bundle_with_two_packs_still_offers_the_pack(run: Run, dist: Path):
    # The step itself reports a broken download, which the checklist cannot explain.
    (dist / "resourcepacks" / "RTX-Remix-Materials-0.57.0.zip").write_bytes(PACK_BYTES)

    output = select(run, picks="Continue")

    assert chosen(output) == (True, True, True)


@pytest.mark.parametrize(
    ("flag", "label", "position"),
    [
        ("mod", "RTX Remix Renderer", 0),
        ("art_pack", "RTX Remix Materials", 1),
        ("world", "RTX Remix Showcase World", 2),
    ],
)
def test_flags_preset_the_checklist(run: Run, flag: str, label: str, position: int):
    output = select(run, picks="Continue", **{flag: "Skip"})

    expected = [True, True, True]
    expected[position] = False
    assert chosen(output) == tuple(expected)
    [menu] = menus(output)
    assert items(menu)[position].startswith(f"Item: [ ] {label} |")


def test_install_flags_keep_the_checklist_ticked(run: Run):
    output = select(run, picks="Continue", mod="Install", art_pack="Install", world="Install")

    assert chosen(output) == (True, True, True)


def test_an_unattended_run_installs_only_the_mod_unless_asked(run: Run):
    output = select(run, unattended=True)
    assert chosen(output) == (True, False, False)
    assert not menus(output)

    output = select(run, unattended=True, mod="Skip", art_pack="Install", world="Install")
    assert chosen(output) == (False, True, True)
    assert not menus(output)


def test_escape_cancels(run: Run):
    assert threw(select(run)) == "Cancelled by user."


def test_content_only_needs_an_existing_prism_instance(run: Run, prism: Path):
    result = run("ContentOnly")
    assert returned(result.stdout) in ([], [None])

    mc_dir = instance(prism)
    result = run("ContentOnly")
    assert returned(result.stdout) == [str(mc_dir)]
    assert not result.stderr


def test_content_only_without_prism_has_no_game_folder(run: Run):
    result = run("ContentOnly", prism_data_dir=None)

    assert returned(result.stdout) in ([], [None])
    assert not result.stderr


def test_content_only_on_the_microsoft_launcher_needs_its_game_folder(run: Run, user_dir: Path):
    result = run("ContentOnly", launcher="Microsoft")
    assert returned(result.stdout) in ([], [None])

    mc_dir = user_dir / "Roaming" / ".minecraft"
    mc_dir.mkdir()
    result = run("ContentOnly", launcher="Microsoft")
    assert returned(result.stdout) == [str(mc_dir)]
    assert not result.stderr


def test_an_unattended_run_installs_the_mod_and_nothing_else_by_default(run: Run, prism: Path):
    result = run("Run", unattended=True)

    assert not result.stderr
    assert returned(result.stdout) == [0]
    output = result.stdout
    for called in ("Initialize-Prism", "Install-ModFiles", "Install-FabricApi"):
        assert f"Called: {called}" in output
    assert [(n, total) for n, total, _ in steps(output)] == [(1, 5), (2, 5), (3, 5), (4, 5), (5, 5)]
    mc_dir = prism / "instances" / INSTANCE / ".minecraft"
    assert files(mc_dir / "resourcepacks") == []
    assert files(mc_dir / "saves") == []
    assert PLAY_THE_WORLD not in output


def test_a_run_without_the_mod_installs_the_pack_and_world_into_the_instance(run: Run, prism: Path):
    mc_dir = instance(prism)

    result = run("Run", unattended=True, mod="Skip", art_pack="Install", world="Install")

    assert not result.stderr
    assert returned(result.stdout) == [0]
    output = result.stdout
    assert "Called:" not in output
    assert [name for _, _, name in steps(output)] == [
        "Checking prerequisites",
        "Selecting launcher",
        "Installing RTX Remix Materials",
        "Installing RTX Remix Showcase World",
    ]
    assert {total for _, total, _ in steps(output)} == {4}
    assert files(mc_dir / "resourcepacks") == [PACK]
    assert files(mc_dir / "saves") == [WORLD, f"{WORLD}/level.dat"]
    assert PLAY_THE_WORLD in output


def test_a_run_without_the_mod_needs_the_instance_and_writes_nothing(run: Run, prism: Path, user_dir: Path):
    result = run("Run", unattended=True, mod="Skip", art_pack="Install", world="Install")

    assert returned(result.stdout) == [1]
    assert "Install RTX Remix Renderer first: Prism has no RTX Remix game folder yet." in result.stderr
    assert "Called:" not in result.stdout
    assert files(prism) == []
    assert files(user_dir) == ["Local", "Roaming"]


def test_a_failed_item_fails_the_run_after_every_step(run: Run, dist: Path, prism: Path):
    # Two packs in the download: finding the pack throws, and the run goes on to the world.
    (dist / "resourcepacks" / "RTX-Remix-Materials-0.57.0.zip").write_bytes(PACK_BYTES)
    mc_dir = instance(prism)

    result = run("Run", unattended=True, mod="Skip", art_pack="Install", world="Install")

    assert not result.stderr
    assert returned(result.stdout) == [1]
    output = ANSI.sub("", result.stdout)
    assert "RTX Remix Materials was not installed: Expected one RTX Remix Materials pack" in output
    assert files(mc_dir / "resourcepacks") == []
    assert files(mc_dir / "saves") == [WORLD, f"{WORLD}/level.dat"]
    # The final instructions still print, after the steps, and their headline says the install had problems, since the
    # warning above has usually scrolled away by then.
    assert COMPLETE not in output
    box = final_box(output, PROBLEMS)
    assert output.index("Installing RTX Remix Showcase World") < output.index(PROBLEMS)
    assert len({len(line) for line in box}) == 1, box
    after = output[output.index(PROBLEMS) :]
    assert "RTX Remix Materials was not installed. See the messages above." in after
    assert "RTX Remix Showcase World was not installed" not in after
    assert PLAY_THE_WORLD in output


def test_a_run_where_every_item_installs_says_it_is_complete(run: Run, prism: Path):
    instance(prism)

    result = run("Run", unattended=True, mod="Skip", art_pack="Install", world="Install")

    assert returned(result.stdout) == [0]
    output = ANSI.sub("", result.stdout)
    assert PROBLEMS not in output
    assert "was not installed" not in output
    box = final_box(output, COMPLETE)
    assert len({len(line) for line in box}) == 1, box


def test_an_item_missing_from_the_bundle_only_warns(run: Run, dist: Path, prism: Path):
    (dist / "saves" / WORLD_ZIP).unlink()
    mc_dir = instance(prism)

    result = run("Run", unattended=True, mod="Skip", world="Install")

    assert not result.stderr
    assert returned(result.stdout) == [0]
    assert "carries no RTX Remix Showcase World" in result.stdout
    assert files(mc_dir / "saves") == []
    assert PLAY_THE_WORLD not in result.stdout


def test_a_kept_world_is_still_the_one_to_open(run: Run, prism: Path):
    mc_dir = instance(prism)
    (mc_dir / "saves" / WORLD).mkdir(parents=True)
    (mc_dir / "saves" / WORLD / "level.dat").write_bytes(b"mine")

    result = run("Run", unattended=True, mod="Skip", world="Install")

    assert returned(result.stdout) == [0]
    assert (mc_dir / "saves" / WORLD / "level.dat").read_bytes() == b"mine"
    assert PLAY_THE_WORLD in result.stdout


def test_an_interactive_run_installs_what_the_player_ticks(run: Run, user_dir: Path):
    # The Microsoft launcher's own game folder; without the mod the run does not wait for the launcher to close,
    # because only the mod's steps change its profiles.
    mc_dir = user_dir / "Roaming" / ".minecraft"
    mc_dir.mkdir()
    (mc_dir / "launcher_profiles.json").write_text("{}", encoding="ascii")

    result = run("Run", launcher="Microsoft", picks="Mod,Continue")

    assert not result.stderr
    assert returned(result.stdout) == [0]
    output = result.stdout
    assert "Called: Start-InstalledLauncher" in output
    assert "Called: Wait-MinecraftLauncherClosed" not in output
    assert "Called: Install-ModFiles" not in output
    assert MICROSOFT_NOTE in output
    assert files(mc_dir / "resourcepacks") == [PACK]
    assert files(mc_dir / "saves") == [WORLD, f"{WORLD}/level.dat"]
    # The steps after the checklist count only what was ticked.
    assert steps(output)[-2:] == [
        (3, 4, "Installing RTX Remix Materials"),
        (4, 4, "Installing RTX Remix Showcase World"),
    ]


def test_the_prism_cleanup_choices_say_they_delete_the_instances_worlds(run: Run, prism: Path):
    # Uninstall keeps the world, but removing the whole instance takes its saves folder with it.
    instance(prism)

    result = run("Uninstall", picks="none")

    assert not result.stderr
    [menu] = menus(result.stdout)
    hints = {line.split(" | ")[0]: line.split(" | ")[1] for line in items(menu)}
    assert "worlds" in hints["Item: Remove the RTX Remix Prism instance only"]
    assert "worlds" in hints["Item: Scorched earth"]
