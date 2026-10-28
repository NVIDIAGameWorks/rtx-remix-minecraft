# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""The RTX Remix Showcase World packaging/installer.ps1 unpacks into the game's saves folder, run under Windows
PowerShell 5.1 as packaging/install.bat runs it.

Minecraft lists every folder in saves/ that holds a level.dat as a world, named after the folder. A player may already
have NVIDIA RTX Demo, from CurseForge or an earlier install, with their own progress in it, and on the Microsoft
launcher it sits among their own worlds."""

import json
import os
import struct
import subprocess
import zipfile
from collections.abc import Callable
from pathlib import Path

import pytest

from rtx_tools.benchmark import util

pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows PowerShell runs the installer")

INSTALLER = util.PROJECT_DIR / "packaging" / "installer.ps1"
WORLD = "NVIDIA RTX Demo"
ZIP = "RTX-Remix-Showcase-World-v0.11.1.zip"
WORLD_FILES = {f"{WORLD}/level.dat": b"level", f"{WORLD}/region/r.0.0.mca": b"region"}
# Loads the installer's constants and functions without its entry point, as test_installer_art_pack.py does, points
# it at -Dist as the folder it was extracted to, and runs one step: Install as Install, Upgrade, and Repair run it once
# the player ticks the world, or Uninstall as Remove-ModArtifacts runs it. A ticked world is never asked about, so any
# yes/no prompt fails the run. -Appear creates that folder after the step found none there and before its own copy
# moves into place, as another program could: the step loads the zip library only once it has looked. -ScanWaits holds
# the unpacked level.dat open, as antivirus software scanning a new file does, from the step's first try at moving the
# world into place until the step has waited that many times between tries. Start-Sleep only records each wait, so
# retries cost no time; the line before the last lists them. The last line is what the step returned, as JSON.
HARNESS = r"""
param([string]$Installer, [string]$Loaded, [string]$Dist, [string]$McDir, [string]$Launcher, [string]$Appear,
      [string]$Action, [int]$ScanWaits)
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
function Confirm-YesNo {
    param([string]$Prompt, [switch]$DefaultNo)
    throw "Asked: $Prompt"
}
if ($Appear) {
    function Add-Type {
        Microsoft.PowerShell.Utility\Add-Type @args
        $null = New-Item -ItemType Directory -Path $Appear
        [IO.File]::WriteAllText((Join-Path $Appear 'level.dat'), 'theirs')
    }
}
$script:waits = @()
$script:scan = $null
function Start-Sleep {
    param([int]$Milliseconds)
    $script:waits += $Milliseconds
    if ($script:scan -and $script:waits.Count -ge $ScanWaits) { $script:scan.Dispose(); $script:scan = $null }
}
if ($ScanWaits) {
    $script:moveWithRetry = ${function:Invoke-MoveWithRetry}
    function Invoke-MoveWithRetry {
        $stage = Get-ChildItem -LiteralPath (Join-Path $McDir 'saves') -Directory -Filter '.rtx-remix-world-*'
        $level = Join-Path $stage.FullName "$($script:WORLD_FOLDER_NAME)\level.dat"
        $script:scan = [IO.File]::Open($level, 'Open', 'Read', 'None')
        try {
            & $script:moveWithRetry @args
        } finally {
            if ($script:scan) { $script:scan.Dispose(); $script:scan = $null }
        }
    }
}
$returned = @(switch ($Action) {
    'Install' { Install-ShowcaseWorld -McDir $McDir }
    'Uninstall' { Remove-ModArtifacts -McDir $McDir -Launcher $Launcher }
    default { throw "Unknown action: $Action" }
})
Write-Host ('Waited: ' + (ConvertTo-Json -InputObject @($script:waits) -Compress))
Write-Host ('Returned: ' + (ConvertTo-Json -InputObject $returned -Compress))
"""
RETURNED = "Returned: "
WAITED = "Waited: "

Run = Callable[..., str]


def returned(output: str) -> list[str]:
    lines = [line for line in output.splitlines() if line.startswith(RETURNED)]
    assert len(lines) == 1, output
    return json.loads(lines[0][len(RETURNED) :])


def waits(output: str) -> list[int]:
    """How long, in milliseconds, the step waited each time between tries."""
    lines = [line for line in output.splitlines() if line.startswith(WAITED)]
    assert len(lines) == 1, output
    return json.loads(lines[0][len(WAITED) :])


def write_zip(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries.items():
            archive.writestr(name, data)


def files(folder: Path) -> dict[str, bytes]:
    return {path.relative_to(folder).as_posix(): path.read_bytes() for path in folder.rglob("*") if path.is_file()}


def saves(mc_dir: Path) -> list[str]:
    folder = mc_dir / "saves"
    return sorted(path.name for path in folder.iterdir()) if folder.exists() else []


@pytest.fixture
def mc_dir(tmp_path: Path) -> Path:
    path = tmp_path / ".minecraft"
    path.mkdir()
    return path


@pytest.fixture
def players_world(mc_dir: Path) -> Path:
    # A world of the player's own, as the Microsoft launcher's saves folder holds.
    path = mc_dir / "saves" / "New World"
    path.mkdir(parents=True)
    (path / "level.dat").write_bytes(b"mine")
    return path


@pytest.fixture
def dist(tmp_path: Path) -> Path:
    path = tmp_path / "dist"
    (path / "saves").mkdir(parents=True)
    write_zip(path / "saves" / ZIP, WORLD_FILES)
    return path


@pytest.fixture
def run(tmp_path: Path, dist: Path, mc_dir: Path) -> Run:
    harness = tmp_path / "harness.ps1"
    harness.write_text(HARNESS, encoding="ascii")

    def invoke(
        action: str = "Install", *, launcher: str = "Prism", appear: Path | None = None, scan_waits: int = 0
    ) -> str:
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
            "-McDir",
            str(mc_dir),
            "-Launcher",
            launcher,
            "-Action",
            action,
        ]
        if appear:
            command += ["-Appear", str(appear)]
        if scan_waits:
            command += ["-ScanWaits", str(scan_waits)]
        result = subprocess.run(command, capture_output=True, encoding="utf-8", errors="replace", check=False)
        assert result.returncode == 0, result.stdout + result.stderr
        assert not result.stderr
        return result.stdout

    return invoke


def test_a_new_world_is_unpacked_into_saves(run: Run, mc_dir: Path):
    assert returned(run()) == ["Installed"]

    assert files(mc_dir / "saves" / WORLD) == {"level.dat": b"level", "region/r.0.0.mca": b"region"}
    # Nothing it unpacked into is left beside the world.
    assert saves(mc_dir) == [WORLD]


def test_the_players_own_worlds_are_left_alone(run: Run, mc_dir: Path, players_world: Path):
    assert returned(run(launcher="Microsoft")) == ["Installed"]

    assert saves(mc_dir) == sorted([WORLD, players_world.name])
    assert files(players_world) == {"level.dat": b"mine"}


def test_an_existing_world_is_kept_byte_for_byte(run: Run, mc_dir: Path):
    world = mc_dir / "saves" / WORLD
    (world / "region").mkdir(parents=True)
    (world / "level.dat").write_bytes(b"mine")
    (world / "region" / "r.0.0.mca").write_bytes(b"my region")
    (world / "notes.txt").write_bytes(b"my notes")
    before = files(world)

    output = run()

    assert returned(output) == ["Kept"]
    assert WORLD in output
    assert files(world) == before
    assert saves(mc_dir) == [WORLD]


def test_a_broken_zip_leaves_no_partial_world(run: Run, dist: Path, mc_dir: Path, players_world: Path):
    (dist / "saves" / ZIP).write_bytes(b"not a zip")

    output = run()

    assert returned(output) == ["Failed"]
    assert "download the installer again" in output
    assert "antivirus" not in output
    assert waits(output) == []
    assert not (mc_dir / "saves" / WORLD).exists()
    assert saves(mc_dir) == [players_world.name]


def test_a_zip_that_breaks_partway_leaves_no_partial_world(run: Run, dist: Path, mc_dir: Path, players_world: Path):
    # level.dat unpacks; the region file after it is not a deflate stream, as in a download cut short or a full disk.
    archive = dist / "saves" / ZIP
    with zipfile.ZipFile(archive) as zipped:
        region = zipped.getinfo(f"{WORLD}/region/r.0.0.mca")
    data = bytearray(archive.read_bytes())
    name_length, extra_length = struct.unpack_from("<HH", data, region.header_offset + 26)
    # A deflate block header of type 3, which no deflate stream uses.
    data[region.header_offset + 30 + name_length + extra_length] = 0xFF
    archive.write_bytes(bytes(data))

    assert returned(run()) == ["Failed"]

    assert not (mc_dir / "saves" / WORLD).exists()
    assert saves(mc_dir) == [players_world.name]


def test_a_zip_without_the_world_folder_is_refused(run: Run, dist: Path, mc_dir: Path):
    write_zip(dist / "saves" / ZIP, {"other/level.dat": b"level"})

    output = run()

    assert returned(output) == ["Failed"]
    assert WORLD in output
    assert saves(mc_dir) == []


def test_a_world_that_appears_meanwhile_is_not_merged_into(run: Run, mc_dir: Path):
    world = mc_dir / "saves" / WORLD

    output = run(appear=world)

    assert returned(output) == ["Failed"]
    # Trying again cannot help once the folder exists, and the unpacking went fine.
    assert waits(output) == []
    assert "free space" not in output
    assert "download the installer again" not in output
    # The world that appeared is untouched, with nothing moved inside it.
    assert files(world) == {"level.dat": b"theirs"}
    assert saves(mc_dir) == [WORLD]


def test_a_world_held_open_for_a_moment_is_moved_on_a_later_try(run: Run, mc_dir: Path):
    # Antivirus software and the search indexer open files just written, and Windows renames no folder with a file
    # open inside it.
    output = run(scan_waits=2)

    assert returned(output) == ["Installed"]
    assert len(waits(output)) == 2
    assert files(mc_dir / "saves" / WORLD) == {"level.dat": b"level", "region/r.0.0.mca": b"region"}
    assert saves(mc_dir) == [WORLD]


def test_a_world_held_open_throughout_fails_with_advice_for_the_hold(run: Run, mc_dir: Path, players_world: Path):
    output = run(scan_waits=99)

    assert returned(output) == ["Failed"]
    # About three seconds of tries, about 300 ms apart.
    assert waits(output) == [300] * 9
    assert "antivirus software is still scanning the new files. Run the installer again." in output
    # The world unpacked, so neither disk space nor the download is the cause.
    assert "free space" not in output
    assert "download the installer again" not in output
    assert not (mc_dir / "saves" / WORLD).exists()
    assert saves(mc_dir) == [players_world.name]


def test_a_bundle_without_a_world_reports_missing(run: Run, dist: Path, mc_dir: Path):
    # An earlier bundle has no saves folder at all.
    (dist / "saves" / ZIP).unlink()
    (dist / "saves").rmdir()

    output = run()

    assert returned(output) == ["Missing"]
    assert "carries no RTX Remix Showcase World" in output
    assert not (mc_dir / "saves").exists()


def test_a_bundle_with_two_worlds_fails_without_throwing(run: Run, dist: Path, mc_dir: Path):
    write_zip(dist / "saves" / "RTX-Remix-Showcase-World-v0.10.0.zip", WORLD_FILES)

    output = run()

    assert returned(output) == ["Failed"]
    assert "found 2" in output
    assert not (mc_dir / "saves").exists()


@pytest.mark.parametrize("launcher", ["Prism", "Microsoft"])
def test_uninstall_leaves_the_world(run: Run, mc_dir: Path, launcher: str):
    assert returned(run(launcher=launcher)) == ["Installed"]

    output = run("Uninstall", launcher=launcher)

    assert files(mc_dir / "saves" / WORLD) == {"level.dat": b"level", "region/r.0.0.mca": b"region"}
    # It says the world is kept and where.
    assert WORLD in output
    assert str(mc_dir / "saves") in output
