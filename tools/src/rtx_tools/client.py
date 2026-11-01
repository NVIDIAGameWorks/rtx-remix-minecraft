# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""The client launcher the developer tools start Minecraft through, and the game directory it owns."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import IO, Protocol

from .benchmark import util


class LaunchBlocked(RuntimeError):
    """The client cannot start. The message says what to do about it and is shown to the user as is."""


class GameExitedEarly(LaunchBlocked):
    """The game started and exited before the launcher could follow it. `logs` are what it wrote, which say why, so a
    harness can treat the launch as a run that failed rather than one that never happened."""

    def __init__(self, message: str, *, logs: tuple[Path, ...]) -> None:
        super().__init__(message)
        self.logs = logs


class GameProcess(Protocol):
    """The part of subprocess.Popen the tools use to follow and stop a client."""

    @property
    def pid(self) -> int: ...

    def poll(self) -> int | None: ...

    def wait(self, timeout: float | None = None) -> int: ...

    def terminate(self) -> None: ...

    def kill(self) -> None: ...


@dataclass(frozen=True)
class LaunchRequest:
    arch: str
    env: Mapping[str, str]
    stdout: IO[str] | None = None
    stderr: IO[str] | None = None
    quick_play_world: str | None = None
    # None means Custom: the launch passes no preset.
    preset: str | None = None
    jfr_path: Path | None = None
    tracy_java_max_depth: int | None = None
    quiet: bool = False
    # How long a launcher may wait for the game to start, as a harness resolved it, environment overrides included.
    # None leaves it to the launcher's own reading of the tools config.
    startup_seconds: int | None = None


class ClientLauncher(Protocol):
    name: str
    game_dir: Path

    def describe(self) -> str:
        """One line for the dashboard: what this launcher is and which account it plays as."""
        ...

    def check(self, *, interactive: bool = True) -> None:
        """Raise LaunchBlocked unless a client can start. interactive=False must not open any window."""
        ...

    def prepare(self, arch: str, *, build: bool) -> None: ...

    def start(self, request: LaunchRequest) -> GameProcess: ...

    def log_files(self, since_ns: int) -> list[Path]:
        """Launcher-written logs modified since `since_ns`, for a failed launch's diagnostics."""
        ...


_active: ClientLauncher | None = None


def active() -> ClientLauncher:
    global _active
    if _active is None:
        # Imported here because the launcher module imports this one.
        from .prism_launcher import PrismLauncher

        _active = PrismLauncher()
    refresh_layout = getattr(_active, "refresh_layout", None)
    if refresh_layout is not None:
        refresh_layout()
        util.RUN_DIR = _active.game_dir
    return _active


def use(launcher: ClientLauncher) -> None:
    """Make `launcher` the active one and point the run directory at its game directory."""
    global _active
    _active = launcher
    util.RUN_DIR = launcher.game_dir


def benchmark_jvm_args(jfr_path: Path, tracy_java_max_depth: int) -> list[str]:
    """The JFR and Tracy Java-phase arguments build.gradle gives runClient for a benchmark recording."""
    jfr = str(jfr_path).replace("\\", "/")
    return [
        f"-Drtxremix.benchmark.jfrPath={jfr}",
        "-Drtxremix.profile.javaPhases=true",
        f"-Drtxremix.profile.tracyJavaMaxDepth={tracy_java_max_depth}",
        "-XX:FlightRecorderOptions=stackdepth=256",
        # maxsize=0 lifts the JDK's 250 MB default disk cap so a long recording keeps its earliest frames.
        "-XX:StartFlightRecording=name=rtx-benchmark,settings=profile,disk=true,dumponexit=false,maxsize=0",
    ]


def preset_jvm_args(preset: str | None) -> list[str]:
    """The argument that selects Remix's graphics preset for one launch. None is Custom and passes nothing."""
    return [] if preset is None else [f"-Drtxremix.preset={preset}"]
