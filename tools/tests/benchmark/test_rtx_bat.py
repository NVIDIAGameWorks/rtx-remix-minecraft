# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""tools/rtx.bat, the entry point that installs uv when it is missing and runs the developer CLI through it.

Typed at a PowerShell 7 prompt, rtx.bat inherits PowerShell 7's PSModulePath, as packaging/install.bat does, and every
Windows PowerShell it starts gets that path too: the one that installs uv, and the ones the CLI starts through uv.
PowerShell 7's modules shadow Windows PowerShell's own there, so the uv installer cannot load Get-ExecutionPolicy and
fails."""

import os
import shutil
import subprocess
from pathlib import Path

import pytest

from rtx_tools.benchmark import util

pytestmark = pytest.mark.skipif(os.name != "nt", reason="rtx.bat runs under cmd")

RTX_BAT = util.TOOLS_DIR / "rtx.bat"
# Stands in for uv: records the module path rtx.bat started it with, which the CLI and every Windows PowerShell it
# starts would inherit. rtx.bat hands over to it without call, so its exit code is rtx.bat's.
STUB_UV = b'@echo off\r\n>"%~dp0module-path.txt" echo [%PSModulePath%]\r\n'


def test_a_powershell_7_module_path_does_not_reach_what_rtx_bat_starts(tmp_path: Path):
    tools = tmp_path / "tools"
    tools.mkdir()
    shutil.copy(RTX_BAT, tools / "rtx.bat")
    stub = tmp_path / "bin"
    stub.mkdir()
    (stub / "uv.cmd").write_bytes(STUB_UV)
    env = os.environ.copy()
    env["PATH"] = os.pathsep.join([str(stub), os.environ["PATH"]])
    env["PSMODULEPATH"] = str(tmp_path / "PowerShell 7 modules")

    result = subprocess.run(
        ["cmd", "/c", str(tools / "rtx.bat"), "--help"],
        capture_output=True,
        encoding="utf-8",
        errors="replace",
        env=env,
        check=False,
    )

    assert result.returncode == 0, result.stdout + result.stderr
    assert (stub / "module-path.txt").read_text(encoding="ascii").strip() == "[]"
