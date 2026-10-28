# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""A Prism Launcher that packaging/installer.ps1 does not detect, such as a portable copy kept anywhere, run under
Windows PowerShell 5.1 as packaging/install.bat runs it.

Detection finds the installer's own portable copy, and a Prism on the PATH, in Prism's default install folders, or
behind a Start menu shortcut. Without a detected Prism, the launcher question offers a Prism the player already has and
asks for its folder. Once the instance is in that Prism, the installer records the data folder under LOCALAPPDATA so
that later runs detect that Prism too. Prism keeps its data beside prismlauncher.exe when portable.txt is there, and in
APPDATA otherwise (Application::Application in Prism's launcher/Application.cpp)."""

import os
import re
import subprocess
from pathlib import Path

import pytest

from rtx_tools.benchmark import util

pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows PowerShell runs the installer")

INSTALLER = util.PROJECT_DIR / "packaging" / "installer.ps1"
INSTANCE_NAME = "NVIDIA RTX Remix for Minecraft"
ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")
# Loads the installer's constants and functions without its entry point and runs one step. The launcher question prints
# each option as "Option: <label> | <hint>" and answers -Choice. The folder prompt reads its answers from
# PRISM_TEST_ANSWERS, separated by '|', which no path holds, and prints each as "Answered: <answer>"; they travel in the
# environment because an answer copied from Explorer is quoted. Ask runs the launcher question on a machine without
# Prism, with the Microsoft launcher when -Ms is given, and prints the pick and the Prism data folder it gave the run,
# or what it threw. Install answers the launcher question on a machine without any launcher, prints whether that left a
# record, and then sets up the instance as Invoke-InstallOrUpgrade does, printing what that threw. Detect prints the
# Prism data folder detection finds, Start prints what Start-InstalledLauncher would start for the Prism data folder
# -DataDir, and RemoveInstance removes the instance from -DataDir as Uninstall does.
HARNESS = r"""
param([string]$Installer, [string]$Loaded, [string]$Action, [string]$Choice, [string]$DataDir, [switch]$Ms)
$ast = [System.Management.Automation.Language.Parser]::ParseFile($Installer, [ref]$null, [ref]$null)
$entryPoint = $ast.EndBlock.Statements[-1]
if ($entryPoint -isnot [System.Management.Automation.Language.TryStatementAst]) {
    throw 'installer.ps1 no longer ends with its entry point'
}
$start = $ast.ParamBlock.Extent.EndOffset
$body = $ast.Extent.Text.Substring($start, $entryPoint.Extent.StartOffset - $start)
[IO.File]::WriteAllText($Loaded, $body, [Text.UTF8Encoding]::new($true))
. $Loaded
$script:answers = [Collections.Queue]::new([string[]]("$env:PRISM_TEST_ANSWERS" -split '\|'))
function Read-Host {
    $answer = $script:answers.Dequeue()
    Write-Host "Answered: $answer"
    return $answer
}
function Invoke-Menu {
    param([string]$Title, [array]$Items, [string]$Footer)
    foreach ($item in $Items) { Write-Host "Option: $($item.Label) | $($item.Hint)" }
    return $Choice
}
function Start-Process {
    param([string]$FilePath, [string]$ArgumentList, [string]$WorkingDirectory, [string]$RedirectStandardOutput,
          [string]$RedirectStandardError)
    Write-Host "Started: $FilePath $ArgumentList"
}
switch ($Action) {
    'Ask' {
        $state = [pscustomobject]@{ HasPrism = $false; HasMs = $Ms.IsPresent; PrismDataDir = $null }
        try {
            Write-Host "Picked: $(Select-Launcher -State $state)"
            Write-Host "Prism data: $($state.PrismDataDir)"
        } catch {
            Write-Host "Threw: $($_.Exception.Message)"
        }
    }
    'Install' {
        $state = [pscustomobject]@{ HasPrism = $false; HasMs = $false; PrismDataDir = $null }
        $null = Select-Launcher -State $state
        Write-Host "Recorded before the instance: $(Test-Path -LiteralPath $script:PRISM_DATA_DIR_RECORD)"
        try {
            $null = Initialize-Prism -State $state -Unattended
        } catch {
            Write-Host "Threw: $($_.Exception.Message)"
        }
    }
    'Detect' { Write-Host "Detected: $((Get-LauncherState).PrismDataDir)" }
    'Start' { Start-InstalledLauncher -Launcher Prism -PrismDataDir $DataDir }
    'RemoveInstance' { Remove-PrismInstance -DataDir $DataDir }
    default { throw "Unknown action: $Action" }
}
"""


def run_harness(tmp_path: Path, *arguments: str, answers: str = "", path: Path | None = None) -> list[str]:
    harness = tmp_path / "harness.ps1"
    harness.write_text(HARNESS, encoding="ascii")
    env = {
        **os.environ,
        "LOCALAPPDATA": str(tmp_path / "LocalAppData"),
        "APPDATA": str(tmp_path / "AppData"),
        "PRISM_TEST_ANSWERS": answers,
    }
    if path is not None:
        env["PATH"] = f"{path}{os.pathsep}{os.environ['PATH']}"
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
        *arguments,
    ]
    result = subprocess.run(command, capture_output=True, encoding="utf-8", errors="replace", check=False, env=env)
    assert result.returncode == 0, result.stdout + result.stderr
    assert not result.stderr
    return ANSI.sub("", result.stdout).splitlines()


def ask(tmp_path: Path, answers: str, *, ms: bool = False) -> list[str]:
    """The launcher question answered with the player's own Prism, then the folder prompt with `answers`."""
    return run_harness(tmp_path, "-Action", "Ask", "-Choice", "OwnPrism", *(["-Ms"] if ms else []), answers=answers)


def install(tmp_path: Path, folder: Path) -> list[str]:
    """The launcher question answered with the player's own Prism at `folder`, then the instance set up in it."""
    return run_harness(tmp_path, "-Action", "Install", "-Choice", "OwnPrism", answers=str(folder))


def portable_prism(folder: Path) -> Path:
    """A portable Prism as its Windows archive unpacks: the program with portable.txt beside it."""
    folder.mkdir(parents=True)
    (folder / "prismlauncher.exe").write_bytes(b"")
    (folder / "portable.txt").write_bytes(b"")
    return folder


def record_path(tmp_path: Path) -> Path:
    return tmp_path / "LocalAppData" / "RtxRemixMinecraft" / "prism-data-dir.txt"


@pytest.mark.parametrize("ms", [False, True])
def test_without_a_detected_prism_the_launcher_question_offers_one_the_player_has(tmp_path: Path, ms: bool):
    output = run_harness(tmp_path, "-Action", "Ask", "-Choice", "Prism", *(["-Ms"] if ms else []))
    assert any(line.startswith("Option: Use a Prism I already have |") for line in output)
    assert "Picked: Prism" in output
    assert not record_path(tmp_path).exists()


@pytest.mark.parametrize("answer", ["{folder}", '"{folder}\\prismlauncher.exe"'], ids=["folder", "copied-path"])
def test_the_prism_the_player_names_is_used_without_being_recorded(tmp_path: Path, answer: str):
    folder = portable_prism(tmp_path / "Games" / "Prism Launcher")
    output = ask(tmp_path, answer.format(folder=folder))
    assert "Picked: Prism" in output
    assert f"Prism data: {folder}" in output
    # A run can still be cancelled, or fail, before the instance is in that Prism.
    assert not record_path(tmp_path).exists()
    assert f"Detected: {folder}" not in run_harness(tmp_path, "-Action", "Detect")


def test_later_runs_detect_the_named_prism_once_the_instance_is_in_it(tmp_path: Path):
    folder = portable_prism(tmp_path / "Games" / "Prism Launcher")
    output = install(tmp_path, folder)
    assert "Recorded before the instance: False" in output
    assert not any(line.startswith("Threw: ") for line in output)
    assert (folder / "instances" / INSTANCE_NAME / "instance.cfg").is_file()
    assert record_path(tmp_path).read_text(encoding="utf-8") == str(folder)
    assert f"Detected: {folder}" in run_harness(tmp_path, "-Action", "Detect")


def test_a_named_prism_the_instance_cannot_be_created_in_is_not_recorded(tmp_path: Path):
    folder = portable_prism(tmp_path / "Games" / "Prism Launcher")
    # A file where Prism keeps its instances.
    (folder / "instances").write_bytes(b"")
    install(tmp_path, folder)
    assert not record_path(tmp_path).exists()
    assert f"Detected: {folder}" not in run_harness(tmp_path, "-Action", "Detect")


@pytest.mark.parametrize(
    ("files", "appdata"),
    [
        (["prismlauncher.exe", "portable.txt"], False),
        (["prismlauncher.exe"], True),
        (["prismlauncher.cfg"], False),
    ],
    ids=["portable-copy", "installed-program", "data-folder"],
)
def test_the_data_folder_is_where_that_prism_keeps_its_data(tmp_path: Path, files: list[str], appdata: bool):
    folder = tmp_path / "Prism"
    folder.mkdir()
    for name in files:
        (folder / name).write_bytes(b"")
    data = tmp_path / "AppData" / "PrismLauncher" if appdata else folder
    assert f"Prism data: {data}" in ask(tmp_path, str(folder))


def test_a_folder_without_prism_is_refused_until_the_player_names_one(tmp_path: Path):
    empty = tmp_path / "Downloads"
    empty.mkdir()
    folder = portable_prism(tmp_path / "Games" / "Prism Launcher")
    # A character no path may hold must not surface as a PowerShell error.
    invalid = "C:\\Games\\<Prism>"
    output = ask(tmp_path, f"{empty}|{invalid}|{folder}")
    assert any(line.endswith(f"No prismlauncher.exe or prismlauncher.cfg in {empty}") for line in output)
    assert any(line.endswith(f"No prismlauncher.exe or prismlauncher.cfg in {invalid}") for line in output)
    assert f"Prism data: {folder}" in output


@pytest.mark.parametrize("ms", [False, True])
def test_an_empty_answer_cancels_without_recording_a_prism(tmp_path: Path, ms: bool):
    assert "Threw: Cancelled by user." in ask(tmp_path, "", ms=ms)
    assert not record_path(tmp_path).exists()


def test_a_recorded_prism_whose_folder_is_gone_is_not_detected(tmp_path: Path):
    gone = tmp_path / "Deleted Prism"
    record = record_path(tmp_path)
    record.parent.mkdir(parents=True)
    record.write_text(str(gone), encoding="utf-8")
    assert f"Detected: {gone}" not in run_harness(tmp_path, "-Action", "Detect")


def test_a_portable_prism_found_on_the_path_keeps_its_data_beside_it(tmp_path: Path):
    folder = portable_prism(tmp_path / "Tools" / "Prism Launcher")
    assert f"Detected: {folder}" in run_harness(tmp_path, "-Action", "Detect", path=folder)


def test_a_portable_prism_starts_from_its_own_folder_without_a_data_dir_argument(tmp_path: Path):
    folder = portable_prism(tmp_path / "Games" / "Prism Launcher")
    output = run_harness(tmp_path, "-Action", "Start", "-DataDir", str(folder))
    started = [line for line in output if line.startswith("Started: ")]
    assert started == [f'Started: explorer.exe "{folder / "prismlauncher.exe"}"']


@pytest.mark.parametrize("recorded", [True, False], ids=["recorded-prism", "other-prism"])
def test_removing_the_instance_forgets_the_prism_recorded_for_it(tmp_path: Path, recorded: bool):
    folder = portable_prism(tmp_path / "Games" / "Prism Launcher")
    other = portable_prism(tmp_path / "Other Prism")
    record = record_path(tmp_path)
    record.parent.mkdir(parents=True)
    record.write_text(str(folder), encoding="utf-8")
    target = folder if recorded else other
    instance = target / "instances" / INSTANCE_NAME
    instance.mkdir(parents=True)
    run_harness(tmp_path, "-Action", "RemoveInstance", "-DataDir", str(target))
    assert not instance.exists()
    assert record.exists() is not recorded
