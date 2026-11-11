# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import subprocess
import sys
from dataclasses import replace
from pathlib import Path

import pytest
from conftest import FakeLauncher, real_client_launch

from rtx_tools import client
from rtx_tools.benchmark import util


def test_use_switches_the_game_directory(fake_launcher: FakeLauncher, tmp_path: Path):
    other = replace(fake_launcher, game_dir=tmp_path / "other")
    client.use(other)
    assert client.active() is other
    assert util.RUN_DIR == tmp_path / "other"


def test_default_game_directory_is_the_prism_instance():
    assert util.PRISM_DATA_DIR == util.PROJECT_DIR / "run-prism"
    assert util.RUN_DIR == util.PROJECT_DIR / "run-prism" / "instances" / "rtx-remix-dev" / ".minecraft"


def test_benchmark_jvm_args_mirror_build_gradle():
    args = client.benchmark_jvm_args(Path("C:/r/profile.jfr"), 6)
    assert args == [
        "-Drtxremix.benchmark.jfrPath=C:/r/profile.jfr",
        "-Drtxremix.profile.javaPhases=true",
        "-Drtxremix.profile.tracyJavaMaxDepth=6",
        "-XX:FlightRecorderOptions=stackdepth=256",
        "-XX:StartFlightRecording=name=rtx-benchmark,settings=profile,disk=true,dumponexit=false,maxsize=0",
    ]
    gradle = (util.PROJECT_DIR / "versions" / "1.21.1" / "fabric" / "build.gradle").read_text(encoding="utf-8")
    for literal in (
        "-Drtxremix.profile.javaPhases=true",
        "-XX:FlightRecorderOptions=stackdepth=256",
        "name=rtx-benchmark,settings=profile,disk=true,dumponexit=false,maxsize=0",
    ):
        assert literal in gradle


def test_preset_jvm_args():
    assert client.preset_jvm_args("ultra") == ["-Drtxremix.preset=ultra"]
    assert client.preset_jvm_args(None) == []


@pytest.mark.parametrize(
    "args",
    [
        [str(util.PROJECT_DIR / "gradlew.bat"), "runClient", "--console=plain"],
        ["gradlew", "-Parch=win64", "runClient"],
        r"C:\checkout\gradlew.bat runClient",
        [r"C:\Program Files\PrismLauncher\prismlauncher.exe", "--dir", "run-prism"],
        [Path("C:/prism/PrismLauncher.exe"), "--help"],
    ],
)
def test_guard_recognizes_a_real_client_launch(args):
    assert real_client_launch(args) is not None


@pytest.mark.parametrize(
    "args",
    [
        [str(util.PROJECT_DIR / "gradlew.bat"), "build", "stageFabricApi"],
        ["powershell", "-Command", "Get-CimInstance Win32_Process -Filter \"Name='prismlauncher.exe'\""],
        ["reg", "query", r"HKCU\Software\PrismLauncher"],
        [sys.executable, "-c", "print('runClient')"],
    ],
)
def test_guard_lets_other_commands_through(args):
    assert real_client_launch(args) is None


def test_guard_fails_a_test_that_starts_a_real_client():
    with pytest.raises(pytest.fail.Exception, match="Gradle runClient"):
        util.run([str(util.PROJECT_DIR / "gradlew.bat"), "runClient"])
    with pytest.raises(pytest.fail.Exception, match="Prism Launcher"):
        subprocess.Popen(["prismlauncher.exe", "--dir", "run-prism"])
    assert subprocess.run([sys.executable, "-c", "pass"], check=False).returncode == 0
