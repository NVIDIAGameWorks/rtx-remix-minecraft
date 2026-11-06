# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import subprocess
from contextlib import contextmanager, nullcontext
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, call

import pytest

from rtx_tools import cli, client, diagnostics
from rtx_tools._diag_win32 import ProcessInfo, Win32, WindowInfo
from rtx_tools.benchmark import util

pytestmark = pytest.mark.usefixtures("isolated_tools_config")


@pytest.mark.parametrize("command", ["hang", "focus-torture"])
def test_print_output_root_is_checkout_relative_without_creating_it(command, tmp_path, monkeypatch, capsys):
    run_dir = tmp_path / "checkout" / "run"
    monkeypatch.setattr(util, "RUN_DIR", run_dir)
    monkeypatch.setattr(client, "_active", SimpleNamespace(game_dir=run_dir))
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(diagnostics, "Win32", Mock(side_effect=AssertionError("must not inspect processes")))

    assert cli.main(["diag", command, "--print-output-root"]) == 0
    assert Path(capsys.readouterr().out.strip()) == run_dir / "hang-dumps"
    assert not run_dir.exists()


@pytest.fixture
def native():
    api = Mock(spec=Win32)
    api.visible_windows.return_value = []
    api.process_ids.return_value = []
    api.process.return_value = None
    api.shell_window.return_value = 99
    api.has_exited.return_value = False
    api.responding.return_value = True
    return api


def test_explicit_pid_wins_without_discovering_other_processes(native):
    process = ProcessInfo(42, Path("jdk/bin/java.exe"), 100)
    native.process.return_value = process

    assert diagnostics.select_process(native, 42) == process
    native.visible_windows.assert_not_called()
    native.process_ids.assert_not_called()


def test_window_title_is_case_sensitive_and_last_matching_window_wins(native):
    native.visible_windows.return_value = [
        WindowInfo(1, 10, "Minecraft 26.3"),
        WindowInfo(2, 20, "Minecraft* 26.3"),
        WindowInfo(3, 30, "minecraft 26.3"),
        WindowInfo(4, 40, "Minecraft Launcher"),
    ]

    assert diagnostics.find_game_window(native) == WindowInfo(2, 20, "Minecraft* 26.3")


def test_focus_window_requires_java_owner(native):
    native.visible_windows.return_value = [
        WindowInfo(1, 10, "Minecraft 26.3"),
        WindowInfo(2, 20, "Minecraft 26.3"),
        WindowInfo(3, 30, "Minecraft 26.3"),
    ]
    native.process.side_effect = [
        ProcessInfo(10, Path("javaw.exe"), 10),
        ProcessInfo(20, Path("not-java.exe"), 20),
        None,
    ]

    assert diagnostics.find_game_window(native, require_java=True) == WindowInfo(1, 10, "Minecraft 26.3")


def test_unavailable_explicit_pid_falls_back_to_minecraft_window(native):
    process = ProcessInfo(10, Path("java.exe"), 10)
    native.visible_windows.return_value = [WindowInfo(1, 10, "Minecraft* 26.3")]
    native.process.side_effect = [None, process]

    assert diagnostics.select_process(native, 99) == process
    native.process_ids.assert_not_called()


def test_no_window_falls_back_to_largest_java_working_set(native):
    small = ProcessInfo(10, Path("java.exe"), 10)
    large = ProcessInfo(20, Path("JAVAW.EXE"), 20)
    other = ProcessInfo(30, Path("other.exe"), 30)
    native.process_ids.return_value = [10, 20, 30, 40]
    native.process.side_effect = [small, large, other, None]

    assert diagnostics.select_process(native) == large


def test_hang_without_java_refuses_without_creating_output(native, tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(diagnostics, "Win32", lambda: native)
    monkeypatch.setattr(util, "RUN_DIR", tmp_path / "run")

    assert cli.main(["diag", "hang"]) == 1
    assert "No javaw/java process found" in capsys.readouterr().out
    assert not (tmp_path / "run").exists()


def test_output_directory_keeps_local_timestamp_format(tmp_path, monkeypatch):
    monkeypatch.setattr(util, "RUN_DIR", tmp_path / "run")
    clock = Mock()
    clock.now.return_value = datetime(2026, 10, 14, 12, 34, 56)
    monkeypatch.setattr(diagnostics, "datetime", clock)

    directory = diagnostics.capture_directory()
    assert directory == tmp_path / "run" / "hang-dumps" / "20261014_123456"
    assert directory.is_dir()


def test_jstack_is_beside_target_java_and_preserves_combined_utf16_output(tmp_path, monkeypatch):
    java = tmp_path / "jdk with spaces" / "bin" / "java.exe"
    java.parent.mkdir(parents=True)
    jstack = java.with_name("jstack.exe")
    jstack.touch()
    run = Mock(return_value=subprocess.CompletedProcess([], 1, stdout="thread dump\nattach diagnostic\n"))
    monkeypatch.setattr(diagnostics.subprocess, "run", run)

    diagnostics.capture_java(ProcessInfo(42, java, 100), tmp_path)

    assert (tmp_path / "jstack.txt").read_text(encoding="utf-16") == "thread dump\nattach diagnostic\n"
    assert run.call_args.args[0] == [str(jstack), "-l", "42"]
    assert run.call_args.kwargs["stderr"] == subprocess.STDOUT
    assert run.call_args.kwargs["check"] is False


def test_missing_jstack_is_skipped(tmp_path, capsys):
    diagnostics.capture_java(ProcessInfo(42, tmp_path / "java.exe", 100), tmp_path)

    assert not (tmp_path / "jstack.txt").exists()
    assert "jstack not found" in capsys.readouterr().out


def test_native_dump_waits_for_nonempty_stable_file(tmp_path, monkeypatch):
    dump = tmp_path / "native.dmp"
    sizes = iter([None, 0, 4, 8, 8])
    sleep = Mock()

    def grow_dump(_seconds):
        size = next(sizes)
        if size is not None:
            dump.write_bytes(b"x" * size)

    sleep.side_effect = grow_dump
    start = Mock()
    monkeypatch.setattr(diagnostics.time, "sleep", sleep)
    monkeypatch.setattr(diagnostics.subprocess, "Popen", start)

    assert diagnostics.capture_native(42, tmp_path, polls=120) == 8
    assert sleep.call_args_list == [call(2)] * 5
    assert start.call_args.args[0][-4:] == ["MiniDump", "42", str(dump), "full"]


def test_native_dump_timeout_does_not_kill_target_or_writer(tmp_path, monkeypatch):
    sleep = Mock()
    start = Mock()
    monkeypatch.setattr(diagnostics.time, "sleep", sleep)
    monkeypatch.setattr(diagnostics.subprocess, "Popen", start)

    assert diagnostics.capture_native(42, tmp_path, polls=120) == 0
    assert sleep.call_args_list == [call(2)] * 120
    start.return_value.kill.assert_not_called()
    start.return_value.terminate.assert_not_called()


def test_hang_captures_java_before_native_without_suspending(native, tmp_path, monkeypatch):
    native.process.return_value = ProcessInfo(42, Path("jdk/bin/java.exe"), 100)
    captures = Mock()
    monkeypatch.setattr(diagnostics, "Win32", lambda: native)
    monkeypatch.setattr(diagnostics, "capture_directory", lambda: tmp_path)
    monkeypatch.setattr(diagnostics, "capture_java", captures.java)
    monkeypatch.setattr(diagnostics, "capture_native", captures.native)
    captures.native.return_value = 1024

    assert cli.main(["diag", "hang", "--pid", "42"]) == 0
    assert captures.mock_calls == [
        call.java(native.process.return_value, tmp_path),
        call.native(42, tmp_path, polls=120),
    ]
    native.suspended.assert_not_called()


@pytest.mark.parametrize(("size", "warned"), [(1024, True), (0, False)])
def test_hang_says_a_written_dump_holds_the_minecraft_session(native, tmp_path, monkeypatch, capsys, size, warned):
    native.process.return_value = ProcessInfo(42, Path("jdk/bin/java.exe"), 100)
    monkeypatch.setattr(diagnostics, "Win32", lambda: native)
    monkeypatch.setattr(diagnostics, "capture_directory", lambda: tmp_path)
    monkeypatch.setattr(diagnostics, "capture_java", Mock())
    monkeypatch.setattr(diagnostics, "capture_native", Mock(return_value=size))

    assert cli.main(["diag", "hang", "--pid", "42"]) == 0
    out = capsys.readouterr().out
    assert ("do not share it publicly" in out) is warned
    assert ("signed-in Minecraft session" in out) is warned


@pytest.fixture
def sleep(monkeypatch):
    pause = Mock()
    monkeypatch.setattr(diagnostics.time, "sleep", pause)
    return pause


@pytest.fixture
def focus_client(native, monkeypatch, sleep):
    game = ProcessInfo(42, Path("jdk/bin/java.exe"), 100)
    native.process.return_value = game
    native.visible_windows.return_value = [WindowInfo(7, 42, "Minecraft* 26.3")]
    monkeypatch.setattr(diagnostics, "Win32", lambda: native)
    return game


def test_focus_cycles_minimize_every_fifth_cycle(native, focus_client, sleep):
    assert cli.main(["diag", "focus-torture", "--max-cycles", "5", "--dwell-seconds", "2"]) == 0

    assert native.foreground.call_args_list == [call(hwnd) for hwnd in [99, 7] * 4 + [7]]
    assert native.show_window.call_args_list == [call(7, 6), call(7, 9)]
    assert sleep.call_args_list == [call(2)] * 10
    native.suspended.assert_not_called()


def test_focus_defaults_remain_eighty_cycles_and_three_seconds(native, focus_client, sleep):
    assert cli.main(["diag", "focus-torture"]) == 0

    assert native.responding.call_count == 80
    assert sleep.call_args_list == [call(3)] * 160


def test_focus_does_not_cycle_after_game_exits(native, focus_client, capsys):
    native.has_exited.return_value = True

    assert cli.main(["diag", "focus-torture"]) == 0
    assert "Game exited." in capsys.readouterr().out
    native.foreground.assert_not_called()


def test_focus_refuses_without_desktop_shell(native, focus_client, capsys):
    native.shell_window.return_value = 0

    assert cli.main(["diag", "focus-torture"]) == 1
    assert "No shell window" in capsys.readouterr().out
    native.foreground.assert_not_called()


def test_focus_waits_five_minutes_for_game_window(native, monkeypatch, capsys):
    sleep = Mock()
    monkeypatch.setattr(diagnostics, "Win32", lambda: native)
    monkeypatch.setattr(diagnostics.time, "sleep", sleep)

    assert cli.main(["diag", "focus-torture"]) == 1
    assert sleep.call_args_list == [call(5)] * 60
    assert "No Minecraft window found." in capsys.readouterr().out


def test_focus_suspends_for_native_dump_then_resumes_before_jstack(native, focus_client, tmp_path, monkeypatch):
    events = []

    @contextmanager
    def suspended(_pid):
        events.append("suspend")
        try:
            yield True
        finally:
            events.append("resume")

    native.responding.return_value = False
    native.suspended.side_effect = suspended
    monkeypatch.setattr(diagnostics, "capture_directory", lambda: tmp_path)

    def capture_native(pid, directory, *, polls):
        assert (pid, directory, polls) == (42, tmp_path, 180)
        events.append("native")
        return 1024

    monkeypatch.setattr(diagnostics, "capture_native", capture_native)
    monkeypatch.setattr(diagnostics, "capture_java", lambda process, directory: events.append("java"))

    assert cli.main(["diag", "focus-torture"]) == 0
    assert events == ["suspend", "native", "resume", "java"]
    assert native.responding.call_count == 1


def test_focus_says_its_dump_holds_the_minecraft_session(native, focus_client, tmp_path, monkeypatch, capsys):
    native.responding.return_value = False
    native.suspended.side_effect = lambda pid: nullcontext(True)
    monkeypatch.setattr(diagnostics, "capture_directory", lambda: tmp_path)
    monkeypatch.setattr(diagnostics, "capture_native", Mock(return_value=1024))
    monkeypatch.setattr(diagnostics, "capture_java", Mock())

    assert cli.main(["diag", "focus-torture"]) == 0
    assert "do not share it publicly" in capsys.readouterr().out


def test_suspension_always_resumes_and_closes_handle_after_capture_failure():
    api = Win32.__new__(Win32)
    api.kernel32 = Mock()
    api.ntdll = Mock()
    api.kernel32.OpenProcess.return_value = 123
    api.ntdll.NtSuspendProcess.return_value = 0
    api.ntdll.NtResumeProcess.return_value = 0

    with pytest.raises(OSError, match="disk full"), api.suspended(42) as suspended:
        assert suspended
        raise OSError("disk full")

    api.ntdll.NtResumeProcess.assert_called_once_with(123)
    api.kernel32.CloseHandle.assert_called_once_with(123)
