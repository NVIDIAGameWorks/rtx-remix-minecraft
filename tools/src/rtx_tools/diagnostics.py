# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Capture Java and native hang evidence without terminating the client."""

from __future__ import annotations

import re
import subprocess
import time
from datetime import datetime
from pathlib import Path

from ._diag_win32 import ProcessInfo, Win32, WindowInfo
from .benchmark import util

# "Minecraft 26.3", or "Minecraft* 26.3" while a world is open; 1.x titles read the same way.
_GAME_TITLE = re.compile(r"^Minecraft\*? \d+\.\d")
# A full dump holds all of the game's memory, which includes the session token of the account it plays as.
DUMP_PRIVACY_NOTE = (
    "  native.dmp holds the game's memory, including your signed-in Minecraft session: do not share it publicly."
)


def output_root() -> Path:
    return util.RUN_DIR / "hang-dumps"


def capture_directory() -> Path:
    directory = output_root() / datetime.now().strftime("%Y%m%d_%H%M%S")
    directory.mkdir(parents=True, exist_ok=True)
    return directory.resolve()


def find_game_window(native: Win32, *, require_java: bool = False) -> WindowInfo | None:
    selected = None
    for window in native.visible_windows():
        if not _GAME_TITLE.match(window.title):
            continue
        if require_java:
            owner = native.process(window.pid)
            if owner is None or not owner.is_java:
                continue
        selected = window
    return selected


def select_process(native: Win32, pid: int = 0) -> ProcessInfo | None:
    if pid > 0 and (process := native.process(pid)) is not None:
        return process
    window = find_game_window(native)
    if window is not None and (process := native.process(window.pid)) is not None:
        return process
    processes = (native.process(candidate) for candidate in native.process_ids())
    return max(
        (process for process in processes if process is not None and process.is_java),
        key=lambda process: process.working_set,
        default=None,
    )


def capture_java(process: ProcessInfo, directory: Path) -> None:
    jstack = process.path.with_name("jstack.exe")
    if not jstack.is_file():
        print(f"  jstack not found at {jstack} - skipping Java dump")
        return
    result = subprocess.run(
        [str(jstack), "-l", str(process.pid)],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding=util.console_encoding(),
        errors="replace",
        check=False,
    )
    # Windows PowerShell's redirected native output is UTF-16 with a BOM.
    (directory / "jstack.txt").write_text(result.stdout, encoding="utf-16")
    print("  jstack.txt written")


def capture_native(pid: int, directory: Path, *, polls: int) -> int:
    dump = directory / "native.dmp"
    writer = subprocess.Popen(
        [
            r"C:\Windows\System32\rundll32.exe",
            r"C:\Windows\System32\comsvcs.dll,",
            "MiniDump",
            str(pid),
            str(dump),
            "full",
        ]
    )
    last_size = -1
    for _ in range(polls):
        time.sleep(2)
        writer.poll()
        if not dump.exists():
            continue
        size = dump.stat().st_size
        if size > 0 and size == last_size:
            break
        last_size = size
    return dump.stat().st_size if dump.exists() else 0


def hang(*, pid: int = 0, print_output_root: bool = False) -> int:
    if print_output_root:
        print(output_root())
        return 0
    native = Win32()
    process = select_process(native, pid)
    if process is None:
        print("No javaw/java process found - is the game still running?")
        return 1
    directory = capture_directory()
    print(f"Capturing PID {process.pid} ({round(process.working_set / 2**30, 1)} GB working set) -> {directory}")
    capture_java(process, directory)
    size = capture_native(process.pid, directory, polls=120)
    if size:
        print(f"  native.dmp written ({round(size / 2**20)} MB)")
        print(DUMP_PRIVACY_NOTE)
    else:
        print("  native.dmp not created or empty (try running as the same user as the game)")
    print("Done. You can force-kill the game now.")
    return 0


def capture_frozen(native: Win32, process: ProcessInfo) -> None:
    with native.suspended(process.pid) as suspended:
        if not suspended:
            print("OpenProcess failed; process already gone?")
            return
        print("Suspended. Dumping...")
        directory = capture_directory()
        size = capture_native(process.pid, directory, polls=180)
        print(f"Dump: {round(size / 2**20)} MB. Resuming...")
    time.sleep(1)
    capture_java(process, directory)
    print(f"Capture complete in {directory}")
    if size:
        print(DUMP_PRIVACY_NOTE)


def focus_torture(*, max_cycles: int = 80, dwell_seconds: int = 3, print_output_root: bool = False) -> int:
    if print_output_root:
        print(output_root())
        return 0
    native = Win32()
    window = None
    for _ in range(60):
        window = find_game_window(native, require_java=True)
        if window is not None:
            break
        time.sleep(5)
    if window is None:
        print("No Minecraft window found.")
        return 1
    process = native.process(window.pid)
    if process is None:
        raise RuntimeError(f"Process {window.pid} exited before focus cycling started.")
    print(f"Target: PID {window.pid} hwnd={window.hwnd} '{window.title}'")
    partner = native.shell_window()
    if not partner:
        print("No shell window; aborting.")
        return 1
    for cycle in range(1, max_cycles + 1):
        if native.has_exited(process.pid):
            print("Game exited.")
            break
        if cycle % 5 == 0:
            print(f"cycle {cycle} : minimize/restore")
            native.show_window(window.hwnd, 6)  # SW_MINIMIZE
            time.sleep(dwell_seconds)
            native.show_window(window.hwnd, 9)  # SW_RESTORE
        else:
            print(f"cycle {cycle} : focus away/back")
            native.foreground(partner)
            time.sleep(dwell_seconds)
        native.foreground(window.hwnd)
        time.sleep(dwell_seconds)
        if not native.responding(window.hwnd):
            print(f"HANG DETECTED at cycle {cycle} - suspending PID {process.pid}...")
            capture_frozen(native, process)
            return 0
    print(f"No hang after {max_cycles} cycles.")
    return 0
