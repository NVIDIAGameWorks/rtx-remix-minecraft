# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import re
import struct
import subprocess
from dataclasses import dataclass, field
from pathlib import Path, PureWindowsPath

import pytest

from rtx_tools import client
from rtx_tools.benchmark import util
from rtx_tools.client import LaunchBlocked, LaunchRequest


@pytest.fixture(autouse=True)
def no_cached_launcher(monkeypatch: pytest.MonkeyPatch) -> None:
    """Every test starts without a cached client launcher, so none reaches a real one an earlier test built."""
    monkeypatch.setattr(client, "_active", None)
    monkeypatch.setattr(
        util,
        "RUN_DIR",
        util.PRISM_DATA_DIR / "instances" / "rtx-remix-dev" / ".minecraft",
    )


def real_client_launch(args: object) -> str | None:
    """What a command line would start that no test may: Gradle's runClient or a Prism Launcher."""
    words = [str(arg) for arg in args] if isinstance(args, list | tuple) else str(args).split()
    names = [PureWindowsPath(word.strip('"')).name.lower() for word in words]
    if any(name in ("gradlew", "gradlew.bat") for name in names) and "runClient" in words:
        return "Gradle runClient"
    if names and names[0] in ("prismlauncher", "prismlauncher.exe"):
        return "Prism Launcher"
    return None


@pytest.fixture(autouse=True)
def no_real_client(monkeypatch: pytest.MonkeyPatch) -> None:
    """Fail a test the moment it would start a real game through util.run or subprocess, so a launch that skipped the
    active launcher fails here instead of spawning a client. A test that fakes either replaces its guard."""

    def refuse(args: object) -> None:
        if launch := real_client_launch(args):
            pytest.fail(f"a test tried to start {launch}: {args!r}")

    run = util.run

    def guarded_run(args, *rest, **kwargs):
        args = list(args)
        refuse(args)
        return run(args, *rest, **kwargs)

    class GuardedPopen(subprocess.Popen):
        def __init__(self, args, *rest, **kwargs) -> None:
            refuse(args)
            super().__init__(args, *rest, **kwargs)

    monkeypatch.setattr(util, "run", guarded_run)
    monkeypatch.setattr(subprocess, "Popen", GuardedPopen)


@pytest.fixture
def isolated_tools_config(tmp_path_factory: pytest.TempPathFactory, monkeypatch: pytest.MonkeyPatch) -> Path:
    """Opt-in shipped defaults with no developer-local state."""
    tools_dir = tmp_path_factory.mktemp("tools-config")
    shared = tools_dir / "config.json"
    shared.write_bytes(util.SHARED_CONFIG_PATH.read_bytes())
    monkeypatch.setattr(util, "SHARED_CONFIG_PATH", shared)
    monkeypatch.setattr(util, "TOOLS_DIR", tools_dir)
    return shared


def recommended_heap_mib() -> int:
    """The -Xmx in packaging/installer.ps1's recommended JVM arguments, in MiB, read without prism.py's parser, so
    tests follow whatever heap the project recommends."""
    text = (util.PROJECT_DIR / "packaging" / "installer.ps1").read_text(encoding="utf-8")
    recommended = re.search(r"\$script:RECOMMENDED_JVM_ARGS\s*=\s*'([^']+)'", text)
    assert recommended is not None
    sizes = re.findall(r"(?<!\S)-Xmx(\d+)([kKmMgG]?)(?!\S)", recommended.group(1))
    assert sizes, recommended.group(1)
    number, unit = sizes[-1]
    unit_bytes = {"": 1, "k": 1024, "m": 1024**2, "g": 1024**3}[unit.lower()]
    return -(-int(number) * unit_bytes // 1024**2)


def write_jdk(home: Path, *, version: str = "25.0.1", machine: int = 0x8664) -> Path:
    """A JDK home as the launcher reads one: its release file and a bin/javaw.exe whose PE header names `machine`
    (0x8664 x64, 0xAA64 ARM64)."""
    (home / "bin").mkdir(parents=True, exist_ok=True)
    (home / "release").write_text(f'IMPLEMENTOR="Test"\nJAVA_VERSION="{version}"\n', encoding="utf-8")
    header = bytearray(0x40)
    header[:2] = b"MZ"
    header[0x3C:0x40] = struct.pack("<I", 0x40)
    (home / "bin" / "javaw.exe").write_bytes(bytes(header) + b"PE\0\0" + struct.pack("<H", machine))
    return home


class FakeProcess:
    """A game process that has already exited cleanly."""

    pid: int = 0

    def poll(self) -> int | None:
        return 0

    def wait(self, timeout: float | None = None) -> int:
        return 0

    def terminate(self) -> None:
        pass

    def kill(self) -> None:
        pass


@dataclass
class FakeLauncher:
    """A client launcher that records what it is asked to do and never starts a game."""

    game_dir: Path
    name: str = "fake"
    block: LaunchBlocked | None = None
    prepared: list[tuple[str, bool]] = field(default_factory=list)
    requests: list[LaunchRequest] = field(default_factory=list)
    extra_log: str = ""
    sign_in_opened: bool = False
    description: str = "Fake launcher"

    def describe(self) -> str:
        return self.description

    def check(self, *, interactive: bool = True) -> None:
        if self.block is not None:
            # As PrismLauncher does: an interactive check that blocks opens sign-in.
            if interactive:
                self.open_sign_in()
            raise self.block

    def open_sign_in(self) -> None:
        self.sign_in_opened = True

    def prepare(self, arch: str, *, build: bool) -> None:
        self.prepared.append((arch, build))

    def start(self, request: LaunchRequest) -> FakeProcess:
        self.requests.append(request)
        return FakeProcess()

    def log_files(self, since_ns: int) -> list[Path]:
        if not self.extra_log:
            return []
        log = self.game_dir / "logs" / "fake.log"
        log.parent.mkdir(parents=True, exist_ok=True)
        log.write_text(self.extra_log, encoding="utf-8")
        return [log]


@pytest.fixture
def fake_launcher(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> FakeLauncher:
    """Install a FakeLauncher as the active client launcher; the test's end restores the launcher and RUN_DIR."""
    fake = FakeLauncher(game_dir=tmp_path / "game")
    monkeypatch.setattr(client, "_active", None)
    monkeypatch.setattr(util, "RUN_DIR", util.RUN_DIR)
    client.use(fake)
    return fake
