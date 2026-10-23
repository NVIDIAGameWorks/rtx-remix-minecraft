# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""packaging/install.bat, the launcher that runs packaging/installer.ps1 under Windows PowerShell 5.1.

Typed at a PowerShell 7 prompt, install.bat inherits PowerShell 7's PSModulePath, which lists PowerShell 7's own
Microsoft.PowerShell.Utility ahead of Windows PowerShell's. PowerShell 7 rebuilds the path only when it starts
powershell.exe itself, not through a batch file. Windows PowerShell then resolves Get-FileHash to the module it
cannot load, and the installer fails at its first hashed download."""

import os
import shutil
import subprocess
from pathlib import Path

import pytest

from rtx_tools.benchmark import util

pytestmark = pytest.mark.skipif(os.name != "nt", reason="install.bat runs under cmd and Windows PowerShell")

INSTALL_BAT = util.PROJECT_DIR / "packaging" / "install.bat"
# Stands in for PowerShell 7's Microsoft.PowerShell.Utility: it claims Get-FileHash but only loads in PowerShell 7.
SHADOW_MANIFEST = (
    "@{ ModuleVersion = '7.0.0.0'; CompatiblePSEditions = @('Core'); FunctionsToExport = @('Get-FileHash') }"
)
# Fails with exit code 1 if Get-FileHash does not resolve, so a distinct exit code shows it resolved and came back.
STUB_INSTALLER = "Get-Command Get-FileHash -ErrorAction Stop | Out-Null\nexit 3\n"


def test_a_powershell_7_module_path_does_not_reach_windows_powershell(tmp_path: Path):
    shadow = tmp_path / "shadow" / "Microsoft.PowerShell.Utility"
    shadow.mkdir(parents=True)
    (shadow / "Microsoft.PowerShell.Utility.psd1").write_text(SHADOW_MANIFEST, encoding="ascii")
    launcher = tmp_path / "launcher"
    launcher.mkdir()
    shutil.copy(INSTALL_BAT, launcher / "install.bat")
    (launcher / "installer.ps1").write_text(STUB_INSTALLER, encoding="ascii")
    env = os.environ.copy()
    env["PSMODULEPATH"] = os.pathsep.join([str(shadow.parent), os.environ.get("PSMODULEPATH", "")])

    result = subprocess.run(
        ["cmd", "/c", str(launcher / "install.bat")],
        capture_output=True,
        encoding="utf-8",
        errors="replace",
        env=env,
        check=False,
    )

    assert result.returncode == 3, result.stdout + result.stderr
