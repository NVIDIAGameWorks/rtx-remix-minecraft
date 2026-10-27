# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""The RTX art pack packaging/installer.ps1 installs, updates, and removes, run under Windows PowerShell 5.1 as
packaging/install.bat runs it.

Minecraft reads options.txt's resourcePacks as a JSON list, lowest priority first, and loads a zipped pack from
resourcepacks/ without unpacking it. A listed pack whose file is missing is dropped when the game starts."""

import os
import subprocess
from collections.abc import Callable
from pathlib import Path

import pytest

from rtx_tools.benchmark import util

pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows PowerShell runs the installer")

INSTALLER = util.PROJECT_DIR / "packaging" / "installer.ps1"
PACK = "rtx-remix-art-pack-0.58.0.zip"
OLD_PACK = "rtx-remix-art-pack-0.57.0.zip"
PACK_BYTES = b"PK\x05\x06" + bytes(18)
# Loads the installer's constants and functions without its entry point, as test_installer_prism_jvm.py does, points
# it at -Dist as the folder it was extracted to, answers its yes/no prompt with -Answer after printing PROMPTED, and
# runs one step: Install as Install, Upgrade, and Repair run it, or Uninstall as Remove-ModArtifacts runs it.
HARNESS = r"""
param([string]$Installer, [string]$Loaded, [string]$Dist, [string]$McDir, [string]$Answer, [string]$Choice,
      [string]$Launcher, [switch]$Unattended, [string]$Action)
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
    Write-Host "Confirm-YesNo: $Prompt"
    return $Answer -eq 'yes'
}
switch ($Action) {
    'Install' { Install-ArtPack -McDir $McDir -Launcher $Launcher -Choice $Choice -Unattended:$Unattended }
    'Uninstall' { Remove-ModArtifacts -McDir $McDir -Launcher $Launcher }
    default { throw "Unknown action: $Action" }
}
"""
PROMPTED = "Confirm-YesNo:"

Run = Callable[..., str]


def resource_packs(*names: str) -> str:
    return "resourcePacks:[" + ",".join(f'"{name}"' for name in names) + "]"


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
        action: str = "Install",
        *,
        choice: str = "",
        answer: str = "no",
        unattended: bool = False,
        launcher: str = "Prism",
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
            "-Answer",
            answer,
            "-Launcher",
            launcher,
            "-Action",
            action,
        ]
        if choice:
            command += ["-Choice", choice]
        if unattended:
            command.append("-Unattended")
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


@pytest.mark.parametrize(("choice", "answer", "unattended"), [("Install", "no", True), ("", "yes", False)])
def test_a_new_instance_gets_the_pack_turned_on_above_fabric(
    run: Run, mc_dir: Path, choice: str, answer: str, unattended: bool
):
    run(choice=choice, answer=answer, unattended=unattended)

    assert installed(mc_dir) == [PACK]
    assert (mc_dir / "resourcepacks" / PACK).read_bytes() == PACK_BYTES
    # The game reads a file at its own data version as current, and fills in every other option.
    assert options(mc_dir) == f"version:3955\r\n{resource_packs('vanilla', 'fabric', f'file/{PACK}')}\r\n"


def test_an_interactive_run_asks_first_and_a_no_adds_nothing(run: Run, mc_dir: Path):
    output = run(answer="no")

    assert PROMPTED in output
    assert installed(mc_dir) == []
    assert not (mc_dir / "options.txt").exists()


def test_an_unattended_run_adds_the_pack_only_when_asked(run: Run, mc_dir: Path):
    output = run(unattended=True)

    assert PROMPTED not in output
    assert "-ArtPack Install" in output
    assert installed(mc_dir) == []
    assert not (mc_dir / "options.txt").exists()


def test_the_microsoft_launcher_prompt_says_the_pack_is_on_in_normal_play(run: Run, mc_dir: Path):
    output = run(answer="yes", launcher="Microsoft")

    assert "also on" in output
    assert "when you play without the mod" in output
    assert installed(mc_dir) == [PACK]


@pytest.mark.parametrize("newline", ["\r\n", "\n"])
@pytest.mark.parametrize("bom", [b"", b"\xef\xbb\xbf"])
def test_the_pack_goes_below_the_players_own_packs_and_nothing_else_changes(
    run: Run, mc_dir: Path, newline: str, bom: bytes
):
    before = newline.join(
        ["version:3955", "ao:true", resource_packs("vanilla", "fabric", "file/Faithful.zip"), "fov:0.0"]
    )
    (mc_dir / "options.txt").write_bytes(bom + (before + newline).encode("utf-8"))

    run(choice="Install", unattended=True)

    after = newline.join(
        ["version:3955", "ao:true", resource_packs("vanilla", "fabric", f"file/{PACK}", "file/Faithful.zip"), "fov:0.0"]
    )
    assert (mc_dir / "options.txt").read_bytes() == bom + (after + newline).encode("utf-8")


def test_an_options_file_without_a_pack_list_gets_one_with_fabric_below_the_pack(run: Run, mc_dir: Path):
    (mc_dir / "options.txt").write_bytes(b"version:3955\nao:true")

    run(choice="Install", unattended=True)

    assert options(mc_dir) == f"version:3955\nao:true\n{resource_packs('vanilla', 'fabric', f'file/{PACK}')}\n"


@pytest.mark.parametrize(("choice", "unattended"), [("", False), ("", True), ("Install", True)])
def test_an_upgrade_replaces_an_earlier_pack_in_place_without_asking(
    run: Run, mc_dir: Path, choice: str, unattended: bool
):
    (mc_dir / "resourcepacks").mkdir()
    (mc_dir / "resourcepacks" / OLD_PACK).write_bytes(b"old")
    (mc_dir / "options.txt").write_bytes(
        (resource_packs("vanilla", "fabric", f"file/{OLD_PACK}", "file/Faithful.zip") + "\r\n").encode("utf-8")
    )

    output = run(choice=choice, unattended=unattended)

    assert PROMPTED not in output
    assert installed(mc_dir) == [PACK]
    assert options(mc_dir) == resource_packs("vanilla", "fabric", f"file/{PACK}", "file/Faithful.zip") + "\r\n"


def test_an_upgrade_keeps_a_pack_the_player_turned_off_off(run: Run, mc_dir: Path):
    (mc_dir / "resourcepacks").mkdir()
    (mc_dir / "resourcepacks" / OLD_PACK).write_bytes(b"old")
    before = resource_packs("vanilla", "fabric", "file/Faithful.zip") + "\r\n"
    (mc_dir / "options.txt").write_bytes(before.encode("utf-8"))

    run()

    assert installed(mc_dir) == [PACK]
    assert options(mc_dir) == before


def test_skip_leaves_an_installed_pack_and_options_alone(run: Run, mc_dir: Path):
    (mc_dir / "resourcepacks").mkdir()
    (mc_dir / "resourcepacks" / OLD_PACK).write_bytes(b"old")
    before = resource_packs("vanilla", "fabric", f"file/{OLD_PACK}") + "\r\n"
    (mc_dir / "options.txt").write_bytes(before.encode("utf-8"))

    output = run(choice="Skip", answer="yes")

    assert PROMPTED not in output
    assert installed(mc_dir) == [OLD_PACK]
    assert options(mc_dir) == before


def test_an_archive_without_a_pack_warns_and_changes_nothing(run: Run, dist: Path, mc_dir: Path):
    (dist / "resourcepacks" / PACK).unlink()

    output = run(choice="Install", unattended=True)

    assert "carries no RTX art pack" in output
    assert installed(mc_dir) == []
    assert not (mc_dir / "options.txt").exists()


def test_files_that_only_look_like_the_pack_are_the_players(run: Run, mc_dir: Path):
    (mc_dir / "resourcepacks").mkdir()
    lookalikes = ["my-rtx-remix-art-pack-0.1.0.zip", "rtx-remix-art-pack-custom.zip", "rtx-remix-art-pack-0.57.0"]
    for name in lookalikes:
        (mc_dir / "resourcepacks" / name).write_bytes(b"mine")
    before = resource_packs("vanilla", "fabric", *(f"file/{name}" for name in lookalikes)) + "\r\n"
    (mc_dir / "options.txt").write_bytes(before.encode("utf-8"))

    run(choice="Install", unattended=True)
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

    output = run(choice="Install", unattended=True)

    assert "not a JSON list" in output
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
