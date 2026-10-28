# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Whether packaging/installer.ps1 goes on with administrator rights, run under Windows PowerShell 5.1 as
packaging/install.bat runs it.

The folders the installer writes to belong to the account it runs as, not to its rights. With User Account Control
off, in the built-in Administrator account, or when started from an elevated program, a PC gives the installer
administrator rights as the account signed in to the desktop, and it goes on. It refuses administrator rights as
another account, whose folders it would install into, and when it cannot tell whose desktop it is on."""

import os
import re
import subprocess
from pathlib import Path

import pytest

from rtx_tools.benchmark import util

pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows PowerShell runs the installer")

INSTALLER = util.PROJECT_DIR / "packaging" / "installer.ps1"
ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")
# LocalSystem, whose name Windows can look up, and a SID no account has.
NAMED_ACCOUNT = "S-1-5-18"
UNKNOWN_ACCOUNT = "S-1-5-21-1000000000-1000000000-1000000000-1001"
# Loads the installer's constants and functions without its entry point and runs one step. Check runs
# Test-ElevationAllowed with administrator rights when -Elevated is given, on a desktop signed in to -Desktop: Self for
# the account running the check, None when it cannot tell, or a SID. It prints each error and then what the check
# returned. Desktop prints the SID Get-DesktopUserSid finds and the running account's.
HARNESS = r"""
param([string]$Installer, [string]$Loaded, [string]$Action, [switch]$Elevated, [string]$Desktop)
$ast = [System.Management.Automation.Language.Parser]::ParseFile($Installer, [ref]$null, [ref]$null)
$entryPoint = $ast.EndBlock.Statements[-1]
if ($entryPoint -isnot [System.Management.Automation.Language.TryStatementAst]) {
    throw 'installer.ps1 no longer ends with its entry point'
}
$start = $ast.ParamBlock.Extent.EndOffset
$body = $ast.Extent.Text.Substring($start, $entryPoint.Extent.StartOffset - $start)
[IO.File]::WriteAllText($Loaded, $body, [Text.UTF8Encoding]::new($true))
. $Loaded
$self = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
switch ($Action) {
    'Check' {
        function Test-Elevated { return $Elevated.IsPresent }
        function Get-DesktopUserSid {
            switch ($Desktop) {
                'Self' { return $self }
                'None' { return $null }
                default { return $Desktop }
            }
        }
        function Write-Err { param([string]$Msg) Write-Host "Error: $Msg" }
        $allowed = Test-ElevationAllowed
        Write-Host "Allowed: $allowed"
    }
    'Desktop' {
        Write-Host "Desktop: $(Get-DesktopUserSid)"
        Write-Host "Self: $self"
    }
    default { throw "Unknown action: $Action" }
}
"""


def run_harness(tmp_path: Path, *arguments: str) -> list[str]:
    harness = tmp_path / "harness.ps1"
    harness.write_text(HARNESS, encoding="ascii")
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
    result = subprocess.run(command, capture_output=True, encoding="utf-8", errors="replace", check=False)
    assert result.returncode == 0, result.stdout + result.stderr
    assert not result.stderr
    return ANSI.sub("", result.stdout).splitlines()


def check(tmp_path: Path, desktop: str, *, elevated: bool = True) -> tuple[list[str], str]:
    """The errors Test-ElevationAllowed reports on a desktop signed in to `desktop`, and what it returns."""
    output = run_harness(tmp_path, "-Action", "Check", *(["-Elevated"] if elevated else []), "-Desktop", desktop)
    errors = [line.removeprefix("Error: ") for line in output if line.startswith("Error: ")]
    allowed = next(line.removeprefix("Allowed: ") for line in output if line.startswith("Allowed: "))
    return errors, allowed


@pytest.mark.parametrize("desktop", ["Self", "None", UNKNOWN_ACCOUNT])
def test_without_administrator_rights_the_installer_goes_on(tmp_path: Path, desktop: str):
    assert check(tmp_path, desktop, elevated=False) == ([], "True")


def test_administrator_rights_as_the_account_signed_in_to_the_desktop_are_allowed(tmp_path: Path):
    # As with User Account Control off, in the built-in Administrator account, or from an elevated program.
    assert check(tmp_path, "Self") == ([], "True")


@pytest.mark.parametrize(
    ("desktop", "first"),
    [
        (NAMED_ACCOUNT, r"The installer is running as .+, not as .+\\SYSTEM, the account signed in to this desktop\."),
        (UNKNOWN_ACCOUNT, r"The installer is running as .+, not as the account signed in to this desktop\."),
    ],
    ids=["named", "unknown-name"],
)
def test_administrator_rights_as_another_account_are_refused(tmp_path: Path, desktop: str, first: str):
    errors, allowed = check(tmp_path, desktop)
    assert allowed == "False"
    assert re.fullmatch(first, errors[0]), errors
    assert "start install.bat by double-clicking it" in errors[1]


def test_administrator_rights_are_refused_when_the_desktop_account_is_unknown(tmp_path: Path):
    errors, allowed = check(tmp_path, "None")
    assert allowed == "False"
    assert errors[0] == (
        "The installer is running as administrator and cannot tell which account is signed in to this desktop."
    )
    # The command for a PC that starts every program as administrator names the install.bat beside the installer.
    assert errors[-1] == f'  runas /trustlevel:0x20000 "{tmp_path / "install.bat"}"'


def test_the_desktop_account_is_the_owner_of_this_sessions_explorer(tmp_path: Path):
    output = run_harness(tmp_path, "-Action", "Desktop")
    desktop = output[0].removeprefix("Desktop: ")
    if not desktop:
        pytest.skip("no Explorer runs in this session, as on a CI runner")
    assert desktop == output[1].removeprefix("Self: ")
