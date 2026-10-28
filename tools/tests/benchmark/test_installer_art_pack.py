# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""The RTX Remix Materials pack packaging/installer.ps1 installs, updates, and removes, run under Windows PowerShell 5.1
as packaging/install.bat runs it.

Minecraft reads options.txt's resourcePacks as a JSON list, lowest priority first, and loads a zipped pack from
resourcepacks/ without unpacking it. A listed pack whose file is missing is dropped when the game starts."""

import json
import os
import subprocess
from collections.abc import Callable
from pathlib import Path

import pytest

from rtx_tools.benchmark import util

pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows PowerShell runs the installer")

INSTALLER = util.PROJECT_DIR / "packaging" / "installer.ps1"
PACK = "RTX-Remix-Materials-0.58.0.zip"
OLD_PACK = "RTX-Remix-Materials-0.57.0.zip"
PACK_BYTES = b"PK\x05\x06" + bytes(18)
# Loads the installer's constants and functions without its entry point, as test_installer_prism_jvm.py does, points
# it at -Dist as the folder it was extracted to, and runs one step: Install as Install, Upgrade, and Repair run it once
# the player ticks the pack, or Uninstall as Remove-ModArtifacts runs it. A ticked pack is never asked about, so any
# yes/no prompt fails the run. -Hold keeps a file open without sharing, as a running game holds its enabled packs.
# -ScanWaits holds the copied pack open, as antivirus software scanning a new file does, from the step's first try at
# moving it into place until the step has waited that many times between tries. Start-Sleep only records each wait, so
# retries cost no time; the line before the last lists them. The last line is what the step returned, as JSON.
HARNESS = r"""
param([string]$Installer, [string]$Loaded, [string]$Dist, [string]$McDir, [string]$Launcher, [string]$Hold,
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
        $staged = Get-ChildItem -LiteralPath (Join-Path $McDir 'resourcepacks') -File -Filter '*.new'
        $script:scan = [IO.File]::Open($staged.FullName, 'Open', 'Read', 'None')
        try {
            & $script:moveWithRetry @args
        } finally {
            if ($script:scan) { $script:scan.Dispose(); $script:scan = $null }
        }
    }
}
$held = if ($Hold) { [IO.File]::Open($Hold, 'Open', 'Read', 'None') } else { $null }
try {
    $returned = @(switch ($Action) {
        'Install' { Install-ArtPack -McDir $McDir }
        'Uninstall' { Remove-ModArtifacts -McDir $McDir -Launcher $Launcher }
        default { throw "Unknown action: $Action" }
    })
} finally {
    if ($held) { $held.Dispose() }
}
Write-Host ('Waited: ' + (ConvertTo-Json -InputObject @($script:waits) -Compress))
Write-Host ('Returned: ' + (ConvertTo-Json -InputObject $returned -Compress))
"""
RETURNED = "Returned: "
WAITED = "Waited: "

Run = Callable[..., str]


def resource_packs(*names: str) -> str:
    return "resourcePacks:[" + ",".join(f'"{name}"' for name in names) + "]"


def returned(output: str) -> list[str]:
    lines = [line for line in output.splitlines() if line.startswith(RETURNED)]
    assert len(lines) == 1, output
    return json.loads(lines[0][len(RETURNED) :])


def waits(output: str) -> list[int]:
    """How long, in milliseconds, the step waited each time between tries."""
    lines = [line for line in output.splitlines() if line.startswith(WAITED)]
    assert len(lines) == 1, output
    return json.loads(lines[0][len(WAITED) :])


@pytest.fixture
def mc_dir(tmp_path: Path) -> Path:
    path = tmp_path / ".minecraft"
    path.mkdir()
    return path


@pytest.fixture
def dist(tmp_path: Path) -> Path:
    path = tmp_path / "dist"
    (path / "resourcepacks").mkdir(parents=True)
    (path / "resourcepacks" / PACK).write_bytes(PACK_BYTES)
    return path


@pytest.fixture
def run(tmp_path: Path, dist: Path, mc_dir: Path) -> Run:
    harness = tmp_path / "harness.ps1"
    harness.write_text(HARNESS, encoding="ascii")

    def invoke(
        action: str = "Install", *, launcher: str = "Prism", hold: Path | None = None, scan_waits: int = 0
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
        if hold:
            command += ["-Hold", str(hold)]
        if scan_waits:
            command += ["-ScanWaits", str(scan_waits)]
        result = subprocess.run(command, capture_output=True, encoding="utf-8", errors="replace", check=False)
        assert result.returncode == 0, result.stdout + result.stderr
        assert not result.stderr
        return result.stdout

    return invoke


def installed(mc_dir: Path) -> list[str]:
    folder = mc_dir / "resourcepacks"
    return sorted(path.name for path in folder.iterdir()) if folder.exists() else []


def options(mc_dir: Path) -> str:
    return (mc_dir / "options.txt").read_bytes().decode("utf-8")


def test_a_new_instance_gets_the_pack_turned_on_above_fabric(run: Run, mc_dir: Path):
    assert returned(run()) == ["Installed"]

    assert installed(mc_dir) == [PACK]
    assert (mc_dir / "resourcepacks" / PACK).read_bytes() == PACK_BYTES
    # The game reads a file at its own data version as current, and fills in every other option.
    assert options(mc_dir) == f"version:3955\r\n{resource_packs('vanilla', 'fabric', f'file/{PACK}')}\r\n"


@pytest.mark.parametrize("newline", ["\r\n", "\n"])
@pytest.mark.parametrize("bom", [b"", b"\xef\xbb\xbf"])
def test_the_pack_goes_below_the_players_own_packs_and_nothing_else_changes(
    run: Run, mc_dir: Path, newline: str, bom: bytes
):
    before = newline.join(
        ["version:3955", "ao:true", resource_packs("vanilla", "fabric", "file/Faithful.zip"), "fov:0.0"]
    )
    (mc_dir / "options.txt").write_bytes(bom + (before + newline).encode("utf-8"))

    assert returned(run()) == ["Installed"]

    after = newline.join(
        ["version:3955", "ao:true", resource_packs("vanilla", "fabric", f"file/{PACK}", "file/Faithful.zip"), "fov:0.0"]
    )
    assert (mc_dir / "options.txt").read_bytes() == bom + (after + newline).encode("utf-8")


def test_an_options_file_without_a_pack_list_gets_one_with_fabric_below_the_pack(run: Run, mc_dir: Path):
    (mc_dir / "options.txt").write_bytes(b"version:3955\nao:true")

    assert returned(run()) == ["Installed"]

    assert options(mc_dir) == f"version:3955\nao:true\n{resource_packs('vanilla', 'fabric', f'file/{PACK}')}\n"


def test_an_upgrade_replaces_an_earlier_pack_in_place(run: Run, mc_dir: Path):
    (mc_dir / "resourcepacks").mkdir()
    (mc_dir / "resourcepacks" / OLD_PACK).write_bytes(b"old")
    (mc_dir / "options.txt").write_bytes(
        (resource_packs("vanilla", "fabric", f"file/{OLD_PACK}", "file/Faithful.zip") + "\r\n").encode("utf-8")
    )

    assert returned(run()) == ["Updated"]

    assert installed(mc_dir) == [PACK]
    assert (mc_dir / "resourcepacks" / PACK).read_bytes() == PACK_BYTES
    assert options(mc_dir) == resource_packs("vanilla", "fabric", f"file/{PACK}", "file/Faithful.zip") + "\r\n"


def test_an_upgrade_keeps_a_pack_the_player_turned_off_off(run: Run, mc_dir: Path):
    (mc_dir / "resourcepacks").mkdir()
    (mc_dir / "resourcepacks" / OLD_PACK).write_bytes(b"old")
    before = resource_packs("vanilla", "fabric", "file/Faithful.zip") + "\r\n"
    (mc_dir / "options.txt").write_bytes(before.encode("utf-8"))

    assert returned(run()) == ["Updated"]

    assert installed(mc_dir) == [PACK]
    assert options(mc_dir) == before


def test_a_pack_the_game_holds_open_fails_and_changes_nothing(run: Run, mc_dir: Path):
    (mc_dir / "resourcepacks").mkdir()
    (mc_dir / "resourcepacks" / PACK).write_bytes(b"old")
    before = resource_packs("vanilla", "fabric", f"file/{PACK}") + "\r\n"
    (mc_dir / "options.txt").write_bytes(before.encode("utf-8"))

    output = run(hold=mc_dir / "resourcepacks" / PACK)

    assert returned(output) == ["Failed"]
    assert "Close Minecraft" in output
    # It tried for about three seconds, in case the hold was a scan's.
    assert waits(output) == [300] * 9
    # Nothing half-copied is left beside the pack.
    assert installed(mc_dir) == [PACK]
    assert (mc_dir / "resourcepacks" / PACK).read_bytes() == b"old"
    assert options(mc_dir) == before


def test_an_upgrade_while_the_game_holds_the_older_pack_fails_and_leaves_options_alone(run: Run, mc_dir: Path):
    (mc_dir / "resourcepacks").mkdir()
    (mc_dir / "resourcepacks" / OLD_PACK).write_bytes(b"old")
    before = resource_packs("vanilla", "fabric", f"file/{OLD_PACK}") + "\r\n"
    (mc_dir / "options.txt").write_bytes(before.encode("utf-8"))

    # The fixture also asserts nothing reached stderr.
    output = run(hold=mc_dir / "resourcepacks" / OLD_PACK)

    assert returned(output) == ["Failed"]
    assert "Close Minecraft" in output
    # options.txt still names a file that exists. Both zips match, so the next run replaces the older one.
    assert options(mc_dir) == before
    assert installed(mc_dir) == sorted([OLD_PACK, PACK])
    assert (mc_dir / "resourcepacks" / OLD_PACK).read_bytes() == b"old"


def test_a_new_pack_held_open_for_a_moment_is_moved_on_a_later_try(run: Run, mc_dir: Path):
    # Antivirus software and the search indexer open files just written, which blocks moving one for a moment.
    output = run(scan_waits=2)

    assert returned(output) == ["Installed"]
    assert len(waits(output)) == 2
    assert installed(mc_dir) == [PACK]
    assert (mc_dir / "resourcepacks" / PACK).read_bytes() == PACK_BYTES


def test_a_bundle_without_a_pack_warns_and_changes_nothing(run: Run, dist: Path, mc_dir: Path):
    (dist / "resourcepacks" / PACK).unlink()

    output = run()

    assert returned(output) == ["Missing"]
    assert "carries no RTX Remix Materials" in output
    assert installed(mc_dir) == []
    assert not (mc_dir / "options.txt").exists()


def test_files_that_only_look_like_the_pack_are_the_players(run: Run, mc_dir: Path):
    (mc_dir / "resourcepacks").mkdir()
    lookalikes = [
        "my-RTX-Remix-Materials-0.1.0.zip",
        "RTX-Remix-Materials-latest.zip",
        "RTX-Remix-Materials-0.57.0",
        "rtx-remix-materials-0.58.0.zip.bak",
    ]
    for name in lookalikes:
        (mc_dir / "resourcepacks" / name).write_bytes(b"mine")
    before = resource_packs("vanilla", "fabric", *(f"file/{name}" for name in lookalikes)) + "\r\n"
    (mc_dir / "options.txt").write_bytes(before.encode("utf-8"))

    assert returned(run()) == ["Installed"]
    assert installed(mc_dir) == sorted([*lookalikes, PACK])
    assert options(mc_dir) == (
        resource_packs("vanilla", "fabric", f"file/{PACK}", *(f"file/{name}" for name in lookalikes)) + "\r\n"
    )

    run("Uninstall")
    assert installed(mc_dir) == sorted(lookalikes)
    assert options(mc_dir) == before


def test_a_pack_list_that_is_not_json_is_left_alone(run: Run, mc_dir: Path):
    before = "resourcePacks:[vanilla,fabric\r\n"
    (mc_dir / "options.txt").write_bytes(before.encode("utf-8"))

    output = run()

    assert returned(output) == ["Installed"]
    assert "not a JSON list" in output
    assert "Turn RTX Remix Materials on or off" in output
    assert installed(mc_dir) == [PACK]
    assert options(mc_dir) == before


def test_uninstall_removes_the_pack_and_its_entry(run: Run, mc_dir: Path):
    (mc_dir / "resourcepacks").mkdir()
    (mc_dir / "resourcepacks" / PACK).write_bytes(PACK_BYTES)
    (mc_dir / "resourcepacks" / "Faithful.zip").write_bytes(b"mine")
    (mc_dir / "options.txt").write_bytes(
        ("ao:true\n" + resource_packs("vanilla", "fabric", f"file/{PACK}", "file/Faithful.zip") + "\n").encode("utf-8")
    )

    run("Uninstall")

    assert installed(mc_dir) == ["Faithful.zip"]
    assert options(mc_dir) == "ao:true\n" + resource_packs("vanilla", "fabric", "file/Faithful.zip") + "\n"


def test_uninstall_without_a_pack_leaves_options_byte_for_byte(run: Run, mc_dir: Path):
    # Spacing the installer would not write, so any rewrite would show.
    before = 'resourcePacks:["vanilla", "fabric", "file/Faithful.zip"]\r\n'
    (mc_dir / "options.txt").write_bytes(before.encode("utf-8"))

    run("Uninstall")

    assert options(mc_dir) == before
