# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import io
import os
import re
import shutil
import subprocess
import time
from dataclasses import dataclass, field, replace
from pathlib import Path

import pytest
from conftest import recommended_heap_mib, write_jdk

from rtx_tools import client, install, loader_registry, prism, runtime_compat, winproc
from rtx_tools import config as tools_config
from rtx_tools.benchmark import runner, util
from rtx_tools.client import LaunchBlocked, LaunchRequest
from rtx_tools.prism_launcher import PrismGame, PrismLauncher, _window_size

PROJECT_STATE = loader_registry.load_registry()

ALEX = prism.PrismAccount(kind="MSA", name="Alex", owns_minecraft=True, active=True)
BOB = prism.PrismAccount(kind="MSA", name="Bob", owns_minecraft=True, active=False)
# A Microsoft account without the game.
CAROL = prism.PrismAccount(kind="MSA", name="Carol", owns_minecraft=False, active=True)
STEVE = prism.PrismAccount(kind="Offline", name="Steve", owns_minecraft=False, active=True)


@dataclass
class Proc:
    """A fake process that records what is done to it in an event list shared with the other fakes."""

    label: str
    events: list[str]
    pid: int = 1
    # None while it runs.
    code: int | None = None

    def poll(self) -> int | None:
        return self.code

    def wait(self, timeout: float | None = None) -> int:
        self.events.append(f"{self.label} wait {timeout}")
        if self.code is None:
            raise subprocess.TimeoutExpired(self.label, timeout or 0)
        return self.code

    def terminate(self) -> None:
        self.events.append(f"{self.label} terminate")
        self.code = 1

    def kill(self) -> None:
        self.events.append(f"{self.label} kill")
        self.code = 1


class UnendingProc(Proc):
    """A process this user may not end."""

    def terminate(self) -> None:
        raise PermissionError(5, "Access is denied")

    def kill(self) -> None:
        self.terminate()


def refuse_unlink(monkeypatch: pytest.MonkeyPatch, locked: Path) -> None:
    """Make `locked` fail to delete, as a file another process holds open does on Windows."""
    unlink = Path.unlink

    def guarded(path: Path, missing_ok: bool = False) -> None:
        if path == locked:
            raise PermissionError(13, "The file is in use by another process", str(path))
        unlink(path, missing_ok=missing_ok)

    monkeypatch.setattr(Path, "unlink", guarded)


def game_jvm(instance: Path) -> str:
    """A game JVM's command line as Prism builds it (LauncherPartLaunch::executeTask): the instance's natives folder is
    the only path to the instance in it, and Qt writes it with forward slashes."""
    data_dir = instance.parent.parent
    return (
        f'"C:/jdk/bin/javaw.exe" -Xmx12G -Djava.library.path={instance.as_posix()}/natives '
        f"-cp {data_dir.as_posix()}/libraries/net/fabricmc/fabric-loader.jar org.prismlauncher.EntryPoint"
    )


@dataclass
class FakePrism:
    """Stands in for the Prism the launcher starts, the process listing it polls, and the JVM handle it opens."""

    java_lines: list[tuple[int, str]]
    # Listings served one per poll before java_lines.
    polls: list[list[tuple[int, str]]] = field(default_factory=list)
    exit_immediately: bool = False
    # No game JVM is listed while Prism runs; it appears once Prism is ended, as one Prism started while being ended.
    jvm_appears_when_prism_ends: bool = False
    # Files a game JVM writes as Prism starts it, such as the logs of one that exits at once.
    game_writes: list[Path] = field(default_factory=list)
    events: list[str] = field(default_factory=list)
    argv: list[str] = field(default_factory=list)
    kwargs: dict = field(default_factory=dict)
    process: Proc | None = None
    jvm: Proc | None = None

    def popen(self, argv, **kwargs) -> Proc:
        self.argv = list(argv)
        self.kwargs = kwargs
        self.process = Proc("prism", self.events, pid=4242, code=1 if self.exit_immediately else None)
        for path in self.game_writes:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("A fatal error has been detected by the Java Runtime Environment")
            # A real JVM writes seconds after Prism starts; Windows stamps files from a clock that can trail
            # time.time_ns() by a timer tick.
            second_later = time.time_ns() + 10**9
            os.utime(path, ns=(second_later, second_later))
        return self.process

    def java_process_command_lines(self, *, required: bool = False) -> list[tuple[int, str]]:
        if self.process is None:
            return []
        if self.jvm_appears_when_prism_ends:
            return [] if self.process.code is None else self.java_lines
        return self.polls.pop(0) if self.polls else self.java_lines

    def open_jvm(self, pid: int) -> Proc:
        self.jvm = Proc("jvm", self.events, pid=pid)
        return self.jvm


@dataclass
class FakeGradle:
    args: list[str] = field(default_factory=list)
    cwd: Path | None = None
    commands: list[tuple[list[str], Path | None]] = field(default_factory=list)
    installs: list[tuple[Path, Path | None, str | None, str, Path | None]] = field(default_factory=list)
    install_result: int = 0


@pytest.fixture
def launcher(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> PrismLauncher:
    data_dir = tmp_path / "run-prism"
    monkeypatch.setattr(util, "PRISM_DATA_DIR", data_dir)
    monkeypatch.setattr(util, "RUN_DIR", data_dir / "instances" / prism.INSTANCE_ID / ".minecraft")
    exe = tmp_path / "prism" / "prismlauncher.exe"
    exe.parent.mkdir()
    exe.write_bytes(b"")
    (exe.parent / "portable.txt").write_text("")
    monkeypatch.setattr(prism, "find_executable", lambda config: exe)
    monkeypatch.setattr(prism, "supports_launch_flags", lambda exe: True)
    monkeypatch.setattr(prism, "running_prism_pids", lambda data_dir, **kwargs: [])
    monkeypatch.setattr(prism, "read_accounts", lambda data_dir: [ALEX])
    monkeypatch.setattr(runner, "detect_java_home", lambda: write_jdk(tmp_path / "jdk"))
    monkeypatch.setattr(runner, "host_arch", lambda override=None: "win64")
    # No JDK on this machine stands in for the one JAVA_HOME names.
    monkeypatch.setenv("GRADLE_USER_HOME", str(tmp_path / "no-gradle"))
    monkeypatch.setenv("ProgramFiles", str(tmp_path / "no-program-files"))
    return PrismLauncher({}, sleep=lambda seconds: None)


@pytest.fixture
def fake_prism(launcher: PrismLauncher, monkeypatch: pytest.MonkeyPatch) -> FakePrism:
    fake = FakePrism(java_lines=[(9001, game_jvm(launcher.game_dir.parent))])
    monkeypatch.setattr(subprocess, "Popen", fake.popen)
    monkeypatch.setattr(runner, "java_process_command_lines", fake.java_process_command_lines)
    monkeypatch.setattr(winproc, "PidProcess", fake.open_jvm)
    # Whether tmp_path has an 8.3 form depends on the volume.
    monkeypatch.setattr(winproc, "short_path", lambda path: None)
    return fake


@pytest.fixture
def fake_gradle(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> FakeGradle:
    project = tmp_path / "project"
    (project / "packaging").mkdir(parents=True)
    shutil.copy2(util.PROJECT_DIR / "packaging" / "installer.ps1", project / "packaging" / "installer.ps1")
    fabric = PROJECT_STATE.get("fabric")
    jar = project / fabric.module / "build" / "dev" / "fabric-api.jar"
    jar.parent.mkdir(parents=True)
    jar.write_bytes(b"pinned fabric api")
    mod_jar = project / fabric.development_jar_dir / "rtx-remix-renderer-0.1.jar"
    mod_jar.parent.mkdir(parents=True)
    mod_jar.write_bytes(b"fabric mod")
    monkeypatch.setattr(util, "PROJECT_DIR", project)
    fake = FakeGradle()

    def run(args, **kwargs):
        fake.args = [str(arg) for arg in args]
        fake.cwd = kwargs.get("cwd")
        fake.commands.append((fake.args, fake.cwd))
        return subprocess.CompletedProcess(fake.args, 0)

    def install_development_build(project_dir, *, game_dir=None, arch=None, mod_loader="fabric", mod_jar=None):
        fake.installs.append((project_dir, game_dir, arch, mod_loader, mod_jar))
        return fake.install_result

    monkeypatch.setattr(util, "run", run)
    monkeypatch.setattr(install, "install_development_build", install_development_build)
    return fake


def instance_cfg(launcher: PrismLauncher) -> str:
    return (launcher.game_dir.parent / "instance.cfg").read_text(encoding="utf-8")


def test_the_default_launcher_is_prism_and_resolves_its_game_directory(monkeypatch):
    monkeypatch.setattr(client, "_active", None)
    reads = []
    monkeypatch.setattr(tools_config, "load_config", lambda: reads.append(True) or {})
    monkeypatch.setattr(prism, "find_executable", lambda config: None)
    launcher = client.active()
    assert isinstance(launcher, PrismLauncher) and launcher.name == "prism"
    assert launcher.game_dir == util.RUN_DIR and launcher.data_dir == util.PRISM_DATA_DIR
    assert reads == [True]


def test_the_active_installed_prism_rebinds_the_shared_game_directory(tmp_path, monkeypatch):
    exe = tmp_path / "installed" / "prismlauncher.exe"
    exe.parent.mkdir()
    exe.write_bytes(b"")
    monkeypatch.setenv("APPDATA", str(tmp_path / "appdata"))
    monkeypatch.setattr(client, "_active", None)
    monkeypatch.setattr(tools_config, "load_config", lambda: {})
    monkeypatch.setattr(prism, "find_executable", lambda config: exe)

    launcher = client.active()

    assert launcher.game_dir == util.RUN_DIR
    assert launcher.game_dir.parent.name.startswith("rtx-remix-dev-")


def test_a_launcher_without_a_config_reads_the_tools_config_at_each_use(monkeypatch):
    reads = []
    monkeypatch.setattr(tools_config, "load_config", lambda: reads.append(True) or {"prism": {"account": "Bob"}})
    launcher = PrismLauncher()
    assert launcher.config == {"prism": {"account": "Bob"}}
    assert launcher.config == {"prism": {"account": "Bob"}}
    assert reads == [True, True]
    launcher.config = {}
    assert launcher.config == {} and len(reads) == 2


def test_check_passes_when_prism_and_an_owning_account_are_ready(launcher):
    launcher.check()


def test_check_blocks_when_this_checkouts_prism_is_open(launcher, monkeypatch):
    monkeypatch.setattr(prism, "running_prism_pids", lambda d, **kwargs: [42])
    with pytest.raises(client.LaunchBlocked, match="close"):
        launcher.check()


def test_check_blocks_when_running_prism_processes_cannot_be_inspected(launcher, monkeypatch):
    def query_failed(*args, **kwargs):
        raise RuntimeError("query failed")

    monkeypatch.setattr(prism, "running_prism_pids", query_failed)
    with pytest.raises(client.LaunchBlocked, match="Cannot inspect running Prism processes"):
        launcher.check()


def test_check_and_start_block_on_a_game_left_running_without_prism(launcher, monkeypatch):
    monkeypatch.setattr(
        runner,
        "java_process_command_lines",
        lambda **kwargs: [(73, game_jvm(launcher.game_dir.parent))],
    )
    monkeypatch.setattr(subprocess, "Popen", lambda *args, **kwargs: pytest.fail("started Prism"))
    message = re.escape("Minecraft is already running from this checkout's RTX Remix Dev instance (process 73)")

    with pytest.raises(client.LaunchBlocked, match=message):
        launcher.check()
    with pytest.raises(client.LaunchBlocked, match=message):
        launcher.start(LaunchRequest(arch="win64", env={}))
    assert not launcher.data_dir.exists()


def test_check_blocks_when_running_java_processes_cannot_be_inspected(launcher, monkeypatch):
    def query_failed(*args, **kwargs):
        raise RuntimeError("query failed")

    monkeypatch.setattr(runner, "java_process_command_lines", query_failed)
    with pytest.raises(client.LaunchBlocked, match="Cannot inspect running Java processes"):
        launcher.check()


def test_check_opens_sign_in_when_no_owning_account(launcher, monkeypatch):
    opened = []
    monkeypatch.setattr(launcher, "open_sign_in", lambda: opened.append(True))
    monkeypatch.setattr(prism, "read_accounts", lambda d: [])
    with pytest.raises(client.LaunchBlocked, match="Add Microsoft"):
        launcher.check()
    assert opened == [True]


def test_check_never_opens_sign_in_when_not_interactive(launcher, monkeypatch):
    opened = []
    monkeypatch.setattr(launcher, "open_sign_in", lambda: opened.append(True))
    monkeypatch.setattr(prism, "read_accounts", lambda d: [])
    with pytest.raises(client.LaunchBlocked, match="Add Microsoft"):
        launcher.check(interactive=False)
    assert opened == []


def test_check_opens_sign_in_when_the_account_list_is_unreadable(launcher, monkeypatch):
    opened = []
    monkeypatch.setattr(launcher, "open_sign_in", lambda: opened.append(True))

    def unreadable(data_dir):
        raise LaunchBlocked(f"Prism's account list is unreadable. {prism.SIGN_IN_STEPS}")

    monkeypatch.setattr(prism, "read_accounts", unreadable)
    with pytest.raises(client.LaunchBlocked, match="unreadable"):
        launcher.check()
    assert opened == [True]


def test_check_without_prism_links_to_the_installer(launcher, monkeypatch):
    monkeypatch.setattr(prism, "find_executable", lambda c: None)
    with pytest.raises(client.LaunchBlocked, match="https://prismlauncher.org"):
        launcher.check()


def test_check_reports_blockers_in_order(launcher, monkeypatch):
    opened = []
    monkeypatch.setattr(launcher, "open_sign_in", lambda: opened.append(True))
    monkeypatch.setattr(prism, "supports_launch_flags", lambda exe: False)
    monkeypatch.setattr(prism, "running_prism_pids", lambda d, **kwargs: [42])
    monkeypatch.setattr(prism, "read_accounts", lambda d: [])
    with pytest.raises(
        client.LaunchBlocked, match="Prism Launcher 9.0 or newer, whose --help lists --world and --profile"
    ):
        launcher.check()
    # Updating Prism changes the executable, so the launcher asks it again.
    exe = prism.find_executable({})
    assert exe is not None
    os.utime(exe, ns=(time.time_ns(), exe.stat().st_mtime_ns + 10**9))
    monkeypatch.setattr(prism, "supports_launch_flags", lambda exe: True)
    with pytest.raises(client.LaunchBlocked, match="RTX Remix Dev Prism window"):
        launcher.check()
    monkeypatch.setattr(prism, "running_prism_pids", lambda d, **kwargs: [])
    assert opened == []
    with pytest.raises(client.LaunchBlocked, match="Add Microsoft"):
        launcher.check()
    assert opened == [True]


@pytest.mark.parametrize("java_home", [None, "17.0.9"])
def test_check_and_start_need_a_jdk_21(launcher, fake_prism, monkeypatch, tmp_path, java_home):
    home = None if java_home is None else write_jdk(tmp_path / "jdk-17", version=java_home)
    monkeypatch.setattr(runner, "detect_java_home", lambda: home)
    message = re.escape(
        f"Minecraft {PROJECT_STATE.minecraft_version} in Prism needs a 64-bit JDK {PROJECT_STATE.java_target} for win64"
    )
    with pytest.raises(client.LaunchBlocked, match=message) as at_check:
        launcher.check()
    assert "JAVA_HOME" in str(at_check.value)
    with pytest.raises(client.LaunchBlocked, match=message):
        launcher.start(LaunchRequest(arch="win64", env={}))
    assert fake_prism.process is None


def test_start_runs_the_game_on_a_jdk_21_for_the_launch_architecture(launcher, fake_prism, monkeypatch, tmp_path):
    arm64 = write_jdk(tmp_path / "Program Files" / "Microsoft" / "jdk-21.0.5.11-hotspot", machine=0xAA64)
    monkeypatch.setenv("ProgramFiles", str(tmp_path / "Program Files"))
    launcher.start(LaunchRequest(arch="arm64", env={}))
    assert f"JavaPath={(arm64 / 'bin' / 'javaw.exe').as_posix()}" in instance_cfg(launcher).splitlines()
    fake_prism.process = None
    launcher.start(LaunchRequest(arch="win64", env={}))
    assert f"JavaPath={(tmp_path / 'jdk' / 'bin' / 'javaw.exe').as_posix()}" in instance_cfg(launcher).splitlines()


def test_check_looks_for_a_jdk_for_the_host_architecture(launcher, fake_prism, monkeypatch):
    monkeypatch.setattr(runner, "host_arch", lambda override=None: "arm64")
    with pytest.raises(client.LaunchBlocked, match="JDK 21 for arm64"):
        launcher.check()


def test_open_sign_in_seeds_the_config_and_opens_prism_detached(launcher, fake_prism, tmp_path):
    launcher.open_sign_in()
    assert fake_prism.argv == [str(tmp_path / "prism" / "prismlauncher.exe"), "--dir", str(launcher.data_dir)]
    assert fake_prism.kwargs["creationflags"] == getattr(subprocess, "DETACHED_PROCESS", 0) | getattr(
        subprocess, "CREATE_NEW_PROCESS_GROUP", 0
    )
    assert fake_prism.kwargs["stdout"] == subprocess.DEVNULL
    assert "JavaPath=" in (launcher.data_dir / "prismlauncher.cfg").read_text(encoding="utf-8")


def test_installed_prism_sign_in_uses_its_normal_account_store(launcher, fake_prism, tmp_path, monkeypatch):
    exe = prism.find_executable({})
    assert exe is not None
    (exe.parent / "portable.txt").unlink()
    app_data = tmp_path / "appdata"
    monkeypatch.setenv("APPDATA", str(app_data))
    read_from = []
    monkeypatch.setattr(prism, "read_accounts", lambda data_dir: read_from.append(data_dir) or [ALEX])

    assert launcher.describe() == "Prism, signed in as Alex"
    launcher.open_sign_in()

    assert read_from == [app_data / "PrismLauncher"]
    assert launcher.data_dir == app_data / "PrismLauncher"
    assert launcher.instance_id.startswith("rtx-remix-dev-")
    assert fake_prism.argv == [str(exe)]
    assert not (launcher.data_dir / "prismlauncher.cfg").exists()


def test_installed_prism_launches_the_checkout_specific_instance(launcher, fake_prism, tmp_path, monkeypatch):
    exe = prism.find_executable({})
    assert exe is not None
    (exe.parent / "portable.txt").unlink()
    monkeypatch.setenv("APPDATA", str(tmp_path / "appdata"))
    fake_prism.java_lines = []
    fake_prism.exit_immediately = True

    with pytest.raises(LaunchBlocked, match="did not start"):
        launcher.start(LaunchRequest(arch="win64", env={}))

    assert fake_prism.argv[:2] == [str(exe), "--launch"]
    assert fake_prism.argv[2] == launcher.instance_id
    assert "--dir" not in fake_prism.argv
    assert launcher.game_dir.parent.name == launcher.instance_id
    assert f"name={launcher.instance_name}" in instance_cfg(launcher)


def recommended_instance_args() -> list[str]:
    """The recommended JVM arguments as the instance's JvmArgs holds them: without the heap limits, which Prism takes
    only through its memory settings."""
    return [arg for arg in prism.recommended_jvm_args() if not arg.startswith(("-Xmx", "-Xms"))]


def test_start_writes_jvm_args_and_launches_the_signed_in_profile(launcher, fake_prism):
    launcher.start(
        client.LaunchRequest(
            arch="win64",
            env={"JAVA_TOOL_OPTIONS": "-Dx"},
            preset="ultra",
            quick_play_world="rtx-benchmark",
            jfr_path=Path("C:/r/p.jfr"),
            tracy_java_max_depth=6,
        )
    )
    cfg = instance_cfg(launcher)
    assert prism.join_jvm_args(recommended_instance_args()) in cfg
    assert "-Drtxremix.preset=ultra" in cfg and "-Drtxremix.benchmark.jfrPath=C:/r/p.jfr" in cfg
    assert fake_prism.argv[-4:] == ["--profile", "Alex", "--world", "rtx-benchmark"]
    assert "--offline" not in fake_prism.argv


def test_start_orders_jvm_args_recommended_configured_preset_then_benchmark(launcher, fake_prism):
    launcher.config = {"launch": {"jvmArgs": ["-Dconfigured=1"]}}
    launcher.start(LaunchRequest(arch="win64", env={}, preset="ultra", jfr_path=Path("C:/r/p.jfr")))
    jvm_args = next(line for line in instance_cfg(launcher).splitlines() if line.startswith("JvmArgs="))
    order = [
        jvm_args.index(prism.join_jvm_args(recommended_instance_args())),
        jvm_args.index("-Dconfigured=1"),
        jvm_args.index("-Drtxremix.preset=ultra"),
        jvm_args.index("-Drtxremix.benchmark.jfrPath="),
    ]
    assert order == sorted(order)
    # Without an explicit depth the benchmark arguments use the configured one (6 by default).
    assert "-Drtxremix.profile.tracyJavaMaxDepth=6" in jvm_args


def test_start_without_a_preset_or_recording_adds_neither(launcher, fake_prism):
    launcher.start(LaunchRequest(arch="win64", env={}))
    cfg = instance_cfg(launcher)
    assert "-Drtxremix.preset=" not in cfg and "jfrPath" not in cfg
    assert "--world" not in fake_prism.argv and fake_prism.argv[-2:] == ["--profile", "Alex"]


@pytest.mark.parametrize("loader_id", ["forge", "neoforge"])
def test_start_writes_the_selected_loader_component(launcher, fake_prism, loader_id):
    launcher.config = {"modLoader": loader_id}

    launcher.start(LaunchRequest(arch="win64", env={}))

    declared = PROJECT_STATE.get(loader_id)
    pack = util.read_json(launcher.game_dir.parent / "mmc-pack.json")
    assert pack["components"][1] == {
        "cachedName": declared.prism.name,
        "uid": declared.prism.uid,
        "version": declared.prism.version,
    }


def test_loader_environment_override_wins_for_start(launcher, fake_prism, monkeypatch):
    launcher.config = {"modLoader": "fabric"}
    monkeypatch.setenv("RTXREMIX_MOD_LOADER", "forge")

    launcher.start(LaunchRequest(arch="win64", env={}))

    pack = util.read_json(launcher.game_dir.parent / "mmc-pack.json")
    assert pack["components"][1]["uid"] == "net.minecraftforge"


def test_prepare_cancels_before_installing_an_incompatible_runtime(launcher, fake_gradle, monkeypatch):
    monkeypatch.setattr(
        runtime_compat,
        "warn_if_incompatible",
        lambda project, arch: runtime_compat.ProbeResult(runtime_compat.INCOMPATIBLE_VERSION),
    )
    launcher.confirm_incompatible_runtime = lambda: False

    with pytest.raises(LaunchBlocked, match="Launch cancelled"):
        launcher.prepare("win64", build=False)

    assert fake_gradle.installs == []


def test_prepare_can_continue_with_an_incompatible_runtime(launcher, fake_gradle, monkeypatch):
    monkeypatch.setattr(
        runtime_compat,
        "warn_if_incompatible",
        lambda project, arch: runtime_compat.ProbeResult(runtime_compat.INCOMPATIBLE_VERSION),
    )
    launcher.confirm_incompatible_runtime = lambda: True

    launcher.prepare("win64", build=False)

    assert len(fake_gradle.installs) == 1


def test_start_does_not_repeat_the_prepare_runtime_prompt(launcher, fake_prism):
    launcher._runtime_probe = runtime_compat.ProbeResult(runtime_compat.INCOMPATIBLE_VERSION)
    launcher.confirm_incompatible_runtime = lambda: pytest.fail("prompted")

    launcher.start(LaunchRequest(arch="win64", env={}))

    assert fake_prism.process is not None


def test_start_appends_configured_jvm_args_and_warns_about_tool_options(launcher, fake_prism, capsys):
    launcher.config = {"launch": {"jvmArgs": ["-Drtxremix.vanillaVisibilityWalk=true"]}}
    launcher.start(client.LaunchRequest(arch="win64", env={"JAVA_TOOL_OPTIONS": "-Dx"}))
    assert "-Drtxremix.vanillaVisibilityWalk=true" in instance_cfg(launcher)
    assert capsys.readouterr().out.count("launch.jvmArgs") == 1


def test_start_does_not_warn_without_tool_options(launcher, fake_prism, capsys):
    launcher.start(LaunchRequest(arch="win64", env={"PATH": "x"}))
    assert "JAVA_TOOL_OPTIONS" not in capsys.readouterr().out


@pytest.mark.parametrize(
    ("launch", "message"),
    [
        ({"jvmArgs": "-Dx=1"}, "launch.jvmArgs in tools/config.local.json"),
        ({"jvmArgs": [1]}, "launch.jvmArgs in tools/config.local.json"),
        (["-Dx=1"], "launch in tools/config.local.json"),
        # Empty, but not an object.
        ("", "launch in tools/config.local.json"),
    ],
)
def test_start_rejects_configured_jvm_args_of_the_wrong_shape(launcher, fake_prism, launch, message):
    log = launcher.game_dir / "logs" / "latest.log"
    log.parent.mkdir(parents=True)
    log.write_text("previous session")
    launcher.config = {"launch": launch}
    with pytest.raises(client.LaunchBlocked, match=message):
        launcher.start(LaunchRequest(arch="win64", env={}))
    # Nothing is touched before the launch is known to go ahead.
    assert fake_prism.process is None and log.exists()


def test_start_gives_prism_the_heap_of_the_last_xmx(launcher, fake_prism):
    launcher.start(LaunchRequest(arch="win64", env={}))
    assert f"MaxMemAlloc={recommended_heap_mib()}" in instance_cfg(launcher).splitlines()
    fake_prism.process = None
    launcher.config = {"launch": {"jvmArgs": ["-Xmx16G"]}}
    launcher.start(LaunchRequest(arch="win64", env={}))
    assert "MaxMemAlloc=16384" in instance_cfg(launcher).splitlines()


def test_start_blocks_on_a_configured_heap_size_before_touching_prism(launcher, fake_prism):
    launcher.config = {"launch": {"jvmArgs": ["-Xmx16GB"]}}
    with pytest.raises(client.LaunchBlocked, match="-Xmx16GB"):
        launcher.start(LaunchRequest(arch="win64", env={}))
    assert fake_prism.process is None and not launcher.data_dir.exists()


def test_start_blocks_when_this_checkouts_prism_opened_after_the_check(launcher, fake_prism, monkeypatch):
    log = launcher.game_dir / "logs" / "latest.log"
    log.parent.mkdir(parents=True)
    log.write_text("previous session")
    monkeypatch.setattr(
        prism,
        "running_prism_pids",
        lambda d, **kwargs: [42] if d == launcher.data_dir else [],
    )
    with pytest.raises(client.LaunchBlocked) as at_check:
        launcher.check()
    with pytest.raises(client.LaunchBlocked) as at_start:
        launcher.start(LaunchRequest(arch="win64", env={}))
    assert str(at_start.value) == str(at_check.value)
    # The running Prism would take the launch, so nothing is touched or started.
    assert fake_prism.process is None and log.exists()


def test_start_reads_a_null_launch_section_as_absent(launcher, fake_prism):
    launcher.config = {"launch": None}
    launcher.start(LaunchRequest(arch="win64", env={}))
    jvm_args = next(line for line in instance_cfg(launcher).splitlines() if line.startswith("JvmArgs="))
    assert jvm_args == "JvmArgs=" + prism.join_jvm_args(recommended_instance_args())


def test_start_plays_as_the_configured_account(launcher, fake_prism, monkeypatch):
    monkeypatch.setattr(prism, "read_accounts", lambda d: [ALEX, BOB])
    launcher.config = {"prism": {"account": "Bob"}}
    launcher.start(LaunchRequest(arch="win64", env={}))
    assert fake_prism.argv[-2:] == ["--profile", "Bob"]


def test_an_interactive_start_keeps_prism_console_output_out_of_the_terminal(launcher, fake_prism):
    # Prism's debug log, first-run "Failed to copy files" lines included, stays in run-prism/logs.
    launcher.start(LaunchRequest(arch="win64", env={}))
    assert fake_prism.kwargs["stdout"] == subprocess.DEVNULL
    assert fake_prism.kwargs["stderr"] == subprocess.DEVNULL


def test_start_hands_prism_the_environment_and_log_handles(launcher, fake_prism):
    out, err = io.StringIO(), io.StringIO()
    launcher.start(LaunchRequest(arch="win64", env={"RTXREMIX_X": "1"}, stdout=out, stderr=err))
    assert fake_prism.kwargs["env"] == {"RTXREMIX_X": "1"}
    assert fake_prism.kwargs["stdout"] is out and fake_prism.kwargs["stderr"] is err


def write_old(path: Path, text: str = "A fatal error has been detected by the Java Runtime Environment") -> Path:
    """A file an earlier session left, modified an hour ago."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    hour_ago = time.time_ns() - 3600 * 10**9
    os.utime(path, ns=(hour_ago, hour_ago))
    return path


def client_jar(launcher: PrismLauncher) -> Path:
    version = PROJECT_STATE.minecraft_version
    return (
        launcher.data_dir / "libraries" / "com" / "mojang" / "minecraft" / version / f"minecraft-{version}-client.jar"
    )


def launched_before(launcher: PrismLauncher) -> None:
    """Leave what a launch that reached the game leaves behind: Prism's copy of Minecraft and the game's log."""
    write_old(client_jar(launcher), "jar")
    write_old(launcher.game_dir / "logs" / "latest.log", "previous session")


def test_start_keeps_the_previous_sessions_logs_and_never_reports_them(launcher, fake_prism):
    """Minecraft archives latest.log itself when it starts, and earlier crash logs are evidence; neither is this
    launch's, so a failed launch never reads them."""
    since = time.time_ns()
    old_log = write_old(launcher.game_dir / "logs" / "latest.log")
    old_crash = write_old(launcher.game_dir / "hs_err_pid77.log")
    launcher.start(LaunchRequest(arch="win64", env={}))
    assert old_log.exists() and old_crash.exists()
    assert launcher.log_files(since) == []


def fake_clock(launcher: PrismLauncher) -> list[float]:
    """Make the launcher's waits take no real time; the list holds the seconds waited so far."""
    now = [0.0]
    launcher.clock = lambda: now[0]
    launcher.sleep = lambda seconds: now.__setitem__(0, now[0] + seconds)
    return now


def test_start_blocks_naming_the_logs_of_a_game_that_exited_as_it_started(launcher, fake_prism):
    log = launcher.game_dir / "logs" / "latest.log"
    crash = launcher.game_dir / "hs_err_pid9001.log"
    fake_prism.game_writes = [log, crash]
    fake_prism.java_lines = []
    waited = fake_clock(launcher)
    with pytest.raises(client.GameExitedEarly, match="exited") as blocked:
        launcher.start(LaunchRequest(arch="win64", env={}))
    assert str(log) in str(blocked.value) and str(crash) in str(blocked.value)
    # A harness classifies the run from these rather than treating it as a launch it could not make.
    assert blocked.value.logs == (log, crash)
    # Prism is still up, so only the logs tell the game came and went; nothing waits for the budget.
    assert waited == [0.0] and fake_prism.events[0] == "prism kill"


def test_start_follows_a_game_whose_log_is_already_written(launcher, fake_prism):
    fake_prism.game_writes = [launcher.game_dir / "logs" / "latest.log"]
    assert launcher.start(LaunchRequest(arch="win64", env={})).pid == 9001


def test_start_waits_past_a_previous_sessions_logs(launcher, fake_prism):
    write_old(launcher.game_dir / "logs" / "latest.log")
    write_old(launcher.game_dir / "hs_err_pid77.log")
    fake_prism.polls = [[], []]
    assert launcher.start(LaunchRequest(arch="win64", env={})).pid == 9001


def test_start_follows_the_jvm_of_this_instance(launcher, fake_prism):
    instance = launcher.game_dir.parent
    sibling_checkout = Path("D:/other") / "run-prism" / "instances" / prism.INSTANCE_ID
    sibling_instance = instance.with_name(prism.INSTANCE_ID + "-old")
    fake_prism.polls = [[], [(1, game_jvm(sibling_checkout)), (2, game_jvm(sibling_instance))]]
    fake_prism.java_lines = [(2, game_jvm(sibling_instance)), (9001, game_jvm(instance).upper())]
    sleeps: list[float] = []
    launcher.sleep = sleeps.append
    game = launcher.start(LaunchRequest(arch="win64", env={}))
    assert game.pid == 9001 and fake_prism.jvm is not None and fake_prism.jvm.pid == 9001
    assert sleeps == [2.0, 2.0]


def test_start_follows_a_jvm_given_the_short_form_of_the_instance(launcher, fake_prism, monkeypatch):
    instance = launcher.game_dir.parent
    short = Path("C:/RUN-PR~1/INSTAN~1/RTX-RE~1")
    monkeypatch.setattr(winproc, "short_path", lambda path: short if path == instance else None)
    # The short form of rtx-remix-dev-old.
    sibling = short.with_name("RTX-RE~2")
    fake_prism.polls = [[(2, game_jvm(sibling))]]
    fake_prism.java_lines = [(2, game_jvm(sibling)), (9001, game_jvm(short))]
    assert launcher.start(LaunchRequest(arch="win64", env={})).pid == 9001


def test_start_raises_when_prism_exits_without_a_game(launcher, fake_prism):
    fake_prism.exit_immediately = True
    fake_prism.java_lines = []
    with pytest.raises(client.LaunchBlocked, match="did not start the game"):
        launcher.start(client.LaunchRequest(arch="win64", env={}))


STALL_900 = {"profiling": {"benchmarkStartupStallSeconds": 900}}
STALL_OFF = {"profiling": {"benchmarkStartupStallSeconds": 0}}


@pytest.mark.parametrize(
    ("first", "config", "budget"),
    [
        # A first launch downloads Minecraft before the JVM starts.
        (True, {}, 600),
        (True, STALL_900, 900),
        # Later launches wait only the startup-stall budget, 120 seconds by default.
        (False, {}, 120),
        (False, STALL_900, 900),
        # Zero turns the stall check off, but the wait for Prism still ends.
        (False, STALL_OFF, 600),
    ],
)
def test_start_gives_up_and_ends_prism_after_the_startup_budget(launcher, fake_prism, first, config, budget):
    if not first:
        launched_before(launcher)
    fake_prism.java_lines = []
    launcher.config = config
    now = fake_clock(launcher)
    with pytest.raises(client.LaunchBlocked, match="did not start the game") as blocked:
        launcher.start(LaunchRequest(arch="win64", env={}))
    assert budget <= now[0] < budget + 2.1
    assert str(launcher.data_dir / "logs") in str(blocked.value)
    assert fake_prism.events[0] == "prism kill" and fake_prism.jvm is None


@pytest.mark.parametrize(
    ("first", "requested", "budget"),
    [
        # A harness hands over the budget it resolved, with its environment overrides, and that wins over the
        # launcher's own reading of the tools config, longer or shorter.
        (False, 900, 900),
        (False, 60, 60),
        # A first launch still waits for the download, and zero still bounds the wait.
        (True, 60, 600),
        (False, 0, 600),
    ],
)
def test_start_waits_the_startup_budget_the_request_names(launcher, fake_prism, first, requested, budget):
    if not first:
        launched_before(launcher)
    fake_prism.java_lines = []
    launcher.config = STALL_900
    now = fake_clock(launcher)
    with pytest.raises(client.LaunchBlocked, match="did not start the game"):
        launcher.start(LaunchRequest(arch="win64", env={}, startup_seconds=requested))
    assert budget <= now[0] < budget + 2.1


@pytest.mark.parametrize("missing", ["client jar", "game log"])
def test_a_launch_is_a_first_launch_until_the_game_has_run_on_this_minecraft(launcher, fake_prism, missing, capsys):
    launched_before(launcher)
    (client_jar(launcher) if missing == "client jar" else launcher.game_dir / "logs" / "latest.log").unlink()
    fake_prism.java_lines = []
    now = fake_clock(launcher)
    with pytest.raises(client.LaunchBlocked):
        launcher.start(LaunchRequest(arch="win64", env={}))
    assert now[0] >= 600
    assert "downloads Minecraft" in capsys.readouterr().out


def test_a_later_launch_does_not_announce_a_download(launcher, fake_prism, capsys):
    launched_before(launcher)
    launcher.start(LaunchRequest(arch="win64", env={}))
    out = capsys.readouterr().out
    assert "Starting Minecraft through Prism as Alex" in out and "download" not in out


def test_start_ends_prism_when_interrupted_while_waiting(launcher, fake_prism):
    fake_prism.java_lines = []

    def interrupt(seconds):
        raise KeyboardInterrupt

    launcher.sleep = interrupt
    with pytest.raises(KeyboardInterrupt):
        launcher.start(LaunchRequest(arch="win64", env={}))
    assert fake_prism.events[0] == "prism kill" and fake_prism.jvm is None


def test_start_ends_a_game_prism_started_between_polls_when_interrupted(launcher, fake_prism):
    # The JVM appears after the last poll, as Ctrl+C arrives.
    fake_prism.polls = [[]]

    def interrupt(seconds):
        raise KeyboardInterrupt

    launcher.sleep = interrupt
    with pytest.raises(KeyboardInterrupt):
        launcher.start(LaunchRequest(arch="win64", env={}))
    assert fake_prism.jvm is not None and fake_prism.jvm.pid == 9001
    # Prism first, so that it cannot start a game after the last look for one.
    assert fake_prism.events[0] == "prism kill" and "jvm terminate" in fake_prism.events


def test_start_ends_a_game_prism_started_as_it_was_ended(launcher, fake_prism):
    """Prism can start the JVM between the cleanup's look for one and its own end; looking after ending Prism catches
    that game too."""
    fake_prism.jvm_appears_when_prism_ends = True

    def interrupt(seconds):
        raise KeyboardInterrupt

    launcher.sleep = interrupt
    with pytest.raises(KeyboardInterrupt):
        launcher.start(LaunchRequest(arch="win64", env={}))
    assert fake_prism.jvm is not None and fake_prism.jvm.pid == 9001
    assert fake_prism.events.index("prism kill") < fake_prism.events.index("jvm terminate")


def test_start_ends_prism_even_when_the_game_it_started_cannot_be_ended(launcher, fake_prism, monkeypatch):
    fake_prism.polls = [[]]
    monkeypatch.setattr(winproc, "PidProcess", lambda pid: UnendingProc("jvm", fake_prism.events, pid=pid))

    def interrupt(seconds):
        raise KeyboardInterrupt

    launcher.sleep = interrupt
    with pytest.raises(PermissionError):
        launcher.start(LaunchRequest(arch="win64", env={}))
    assert fake_prism.events[0] == "prism kill"


def test_prism_game_follows_the_jvm_and_ends_a_lingering_prism():
    events: list[str] = []
    jvm = Proc("jvm", events, pid=9001)
    game = PrismGame(jvm, Proc("prism", events))
    assert game.pid == 9001 and game.poll() is None
    with pytest.raises(subprocess.TimeoutExpired):
        game.wait(timeout=5)
    assert events == ["jvm wait 5"]
    jvm.code = 0
    assert game.poll() == 0 and game.wait() == 0
    assert events[1:] == ["jvm wait None", "prism wait 10", "prism kill", "prism wait 10"]


def test_prism_game_leaves_a_prism_that_exits_on_its_own():
    events: list[str] = []
    game = PrismGame(Proc("jvm", events, code=3), Proc("prism", events, code=0))
    assert game.wait() == 3
    assert "prism kill" not in events


@pytest.mark.parametrize("stop", ["terminate", "kill"])
def test_prism_game_ends_the_jvm_then_prism(stop):
    events: list[str] = []
    game = PrismGame(Proc("jvm", events), Proc("prism", events))
    getattr(game, stop)()
    assert events[:2] == [f"jvm {stop}", "prism kill"]


@pytest.mark.parametrize("stop", ["terminate", "kill"])
def test_prism_game_ends_prism_when_the_jvm_cannot_be_ended(stop):
    events: list[str] = []
    game = PrismGame(UnendingProc("jvm", events), Proc("prism", events))
    with pytest.raises(PermissionError):
        getattr(game, stop)()
    assert events[0] == "prism kill"


def test_prepare_installs_fabric_api_and_prunes_other_versions(launcher, fake_gradle, tmp_path):
    stale = launcher.game_dir / "mods" / "fabric-api-0.1.jar"
    stale.parent.mkdir(parents=True)
    stale.write_bytes(b"x")
    launcher.prepare("win64", build=False)
    assert fake_gradle.args[1:3] == ["setupRuntime", *PROJECT_STATE.get("fabric").prepare_tasks]
    assert not stale.exists()
    assert (launcher.game_dir / "mods" / f"fabric-api-{PROJECT_STATE.get('fabric').api_version}.jar").exists()


def test_window_size_uses_positive_render_overrides():
    assert _window_size({"render": {"overrideWidth": 1920, "overrideHeight": 1080}}) == (1920, 1080)
    assert _window_size({"render": {"overrideWidth": 0, "overrideHeight": 1080}}) is None


def test_prepare_builds_probes_then_installs_into_the_instance(launcher, fake_gradle, monkeypatch):
    probes = []
    monkeypatch.setattr(runtime_compat, "warn_if_incompatible", lambda project, arch: probes.append((project, arch)))
    monkeypatch.setattr(launcher, "_java", lambda arch: Path("C:/jdk-arm64/bin/javaw.exe"))
    launcher.prepare("arm64", build=True)
    project = util.PROJECT_DIR
    fabric = PROJECT_STATE.get("fabric")
    assert fake_gradle.args == [
        str(project / "gradlew.bat"),
        "setupRuntime",
        *fabric.prepare_tasks,
        fabric.build_task,
        "-x",
        fabric.test_task,
        *PROJECT_STATE.gradle_arguments("fabric"),
        "-Parch=arm64",
        "--console=plain",
        "--quiet",
    ]
    assert fake_gradle.cwd == project
    assert probes == [(project, "arm64")]
    assert fake_gradle.installs == [
        (
            project,
            launcher.game_dir,
            "arm64",
            "fabric",
            project / fabric.development_jar_dir / "rtx-remix-renderer-0.1.jar",
        )
    ]
    installed = launcher.game_dir / "mods" / f"fabric-api-{PROJECT_STATE.get('fabric').api_version}.jar"
    assert installed.read_bytes() == b"pinned fabric api"


@pytest.mark.parametrize("loader_id", ["forge", "neoforge"])
@pytest.mark.parametrize("build", [False, True])
def test_prepare_selects_non_fabric_build_and_removes_fabric_api(launcher, fake_gradle, build, loader_id):
    project = util.PROJECT_DIR
    declared = PROJECT_STATE.get(loader_id)
    mod_jar = project / declared.development_jar_dir / declared.development_jar_pattern.replace("*", "0.1")
    mod_jar.parent.mkdir(parents=True)
    mod_jar.write_bytes(f"{loader_id} mod".encode())
    fabric_api = launcher.game_dir / "mods/fabric-api-old.jar"
    fabric_api.parent.mkdir(parents=True)
    fabric_api.write_bytes(b"fabric api")
    launcher.config = {"modLoader": loader_id}

    launcher.prepare("win64", build=build)

    build_tasks = [declared.build_task, "-x", declared.test_task] if build else []
    expected = [
        (
            [
                str(project / "gradlew.bat"),
                "setupRuntime",
                *declared.prepare_tasks,
                *build_tasks,
                *PROJECT_STATE.gradle_arguments(loader_id),
                "-Parch=win64",
                "--console=plain",
                "--quiet",
            ],
            project,
        )
    ]
    assert fake_gradle.commands == expected
    assert fake_gradle.installs == [(project, launcher.game_dir, "win64", loader_id, mod_jar)]
    assert not fabric_api.exists()
    pack = util.read_json(launcher.game_dir.parent / "mmc-pack.json")
    assert pack["components"][1] == {
        "cachedName": declared.prism.name,
        "uid": declared.prism.uid,
        "version": declared.prism.version,
    }


@pytest.mark.parametrize("loader_id", ["forge", "neoforge"])
def test_prepare_non_fabric_without_a_built_jar_suggests_building(launcher, fake_gradle, loader_id):
    launcher.config = {"modLoader": loader_id}

    display_name = PROJECT_STATE.get(loader_id).display_name
    with pytest.raises(LaunchBlocked, match=rf"No {display_name} mod JAR exists.*without --no-build"):
        launcher.prepare("win64", build=False)

    assert fake_gradle.installs == []


def test_prepare_blocks_when_the_install_fails(launcher, fake_gradle):
    fake_gradle.install_result = 1
    with pytest.raises(client.LaunchBlocked):
        launcher.prepare("win64", build=False)


def test_prepare_without_the_staged_fabric_api_says_how_to_stage_it(launcher, fake_gradle):
    fabric = PROJECT_STATE.get("fabric")
    (util.PROJECT_DIR / fabric.module / "build" / "dev" / "fabric-api.jar").unlink()
    target_args = " ".join(PROJECT_STATE.gradle_arguments("fabric"))
    with pytest.raises(
        client.LaunchBlocked,
        match=rf"Run gradlew\.bat {re.escape(fabric.prepare_tasks[0])} {re.escape(target_args)}",
    ):
        launcher.prepare("win64", build=False)
    assert fake_gradle.installs == []


def test_prepare_blocks_naming_an_old_fabric_api_it_cannot_remove(launcher, fake_gradle, monkeypatch):
    stale = launcher.game_dir / "mods" / "fabric-api-0.1.jar"
    stale.parent.mkdir(parents=True)
    stale.write_bytes(b"x")
    replace = Path.replace

    def guarded(path, target):
        if path == stale:
            raise PermissionError("The file is in use")
        return replace(path, target)

    monkeypatch.setattr(Path, "replace", guarded)
    with pytest.raises(client.LaunchBlocked, match=re.escape(f"Cannot update {stale} (The file is in use")):
        launcher.prepare("win64", build=False)
    assert fake_gradle.installs == []


def test_prepare_blocks_naming_a_fabric_api_it_cannot_write(launcher, fake_gradle, monkeypatch):
    def fails(source, target):
        # As shutil's CopyFile2 path raises it: no strerror.
        raise OSError("CopyFile2 failed")

    monkeypatch.setattr(shutil, "copy2", fails)
    target = launcher.game_dir / "mods" / f"fabric-api-{PROJECT_STATE.get('fabric').api_version}.jar"
    with pytest.raises(client.LaunchBlocked, match=re.escape(f"Cannot update {target} (CopyFile2 failed)")):
        launcher.prepare("win64", build=False)
    assert fake_gradle.installs == []


def test_prepare_blocks_naming_a_mods_folder_it_cannot_make(launcher, fake_gradle):
    mods = launcher.game_dir / "mods"
    mods.parent.mkdir(parents=True)
    mods.write_text("not a folder")
    with pytest.raises(client.LaunchBlocked, match=re.escape(f"Cannot update {mods} (")):
        launcher.prepare("win64", build=False)
    assert fake_gradle.installs == []


def test_log_files_are_the_game_log_and_crash_logs_written_since(launcher):
    assert launcher.log_files(0) == []
    since = time.time_ns()
    latest = write_old(launcher.game_dir / "logs" / "latest.log")
    old = write_old(launcher.game_dir / "hs_err_pid1.log")
    assert launcher.log_files(since) == []
    new = launcher.game_dir / "hs_err_pid2.log"
    for path in (latest, new):
        path.write_text("this session")
        # Windows stamps files from a clock that can trail time.time_ns() by a timer tick.
        os.utime(path, ns=(since + 10**9, since + 10**9))
    assert launcher.log_files(since) == [latest, new]
    assert old.exists()


def test_log_files_add_the_runtime_log_and_the_client_crash_reports_written_since(launcher):
    """Prism keeps the game's console to itself, so the crash report Minecraft prints there and the runtime's log
    lines come from their files."""
    since = time.time_ns()
    game = launcher.game_dir
    write_old(game / "crash-reports" / "crash-2026-01-01_00.00.00-client.txt")
    write_old(game / "rtx-remix" / "logs" / "remix-dxvk.log")
    expected = [
        game / "logs" / "latest.log",
        game / "rtx-remix" / "logs" / "remix-dxvk.log",
        game / "crash-reports" / "crash-2026-10-16_12.00.00-client.txt",
        game / "hs_err_pid9.log",
    ]
    for path in [*expected, game / "crash-reports" / "crash-2026-10-16_12.00.00-server.txt"]:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("this session")
        os.utime(path, ns=(since + 10**9, since + 10**9))
    assert launcher.log_files(since) == expected


def test_describe_never_raises(launcher, monkeypatch):
    monkeypatch.setattr(prism, "find_executable", lambda c: None)
    assert launcher.describe() == "Prism (not installed)"


def test_describe_names_the_signed_in_profile(launcher):
    assert launcher.describe() == "Prism, signed in as Alex"


def blocked(*args):
    raise LaunchBlocked("blocked")


@pytest.mark.parametrize(
    ("attribute", "value", "description"),
    [
        ("find_executable", blocked, "Prism (prism.executable not found)"),
        ("supports_launch_flags", lambda exe: False, "Prism (too old: needs 9.0 or newer)"),
        ("supports_launch_flags", blocked, "Prism (did not answer --help)"),
        ("read_accounts", lambda d: [], "Prism (no Minecraft account signed in)"),
        ("read_accounts", blocked, "Prism (no Minecraft account signed in)"),
        ("read_accounts", lambda d: [ALEX, BOB], "Prism, signed in as Alex"),
    ],
)
def test_describe_says_why_it_cannot_launch(launcher, monkeypatch, attribute, value, description):
    monkeypatch.setattr(prism, attribute, value)
    assert launcher.describe() == description


@pytest.mark.parametrize(
    ("config", "accounts", "description"),
    [
        ({"prism": {"account": "Bob"}}, [ALEX, BOB], "Prism, signed in as Bob"),
        ({"prism": {"account": "Dana"}}, [ALEX], "Prism (prism.account Dana not signed in)"),
        ({"prism": {"account": "Carol"}}, [CAROL, BOB], "Prism (prism.account Carol does not own Minecraft)"),
        ({"prism": {"account": "Carol"}}, [CAROL], "Prism (prism.account Carol does not own Minecraft)"),
        ({"prism": {"account": "Steve"}}, [STEVE, BOB], "Prism (prism.account Steve does not own Minecraft)"),
        ({}, [replace(ALEX, active=False), BOB], "Prism (several Microsoft accounts; set prism.account)"),
        ({}, [CAROL, replace(ALEX, active=False), BOB], "Prism (several Microsoft accounts; set prism.account)"),
        # One account owns the game, but Prism's active account is chosen first.
        ({}, [STEVE, BOB], "Prism (active account Steve does not own Minecraft)"),
        ({}, [CAROL, BOB], "Prism (active account Carol does not own Minecraft)"),
        ({}, [replace(CAROL, name=""), BOB], "Prism (active account does not own Minecraft)"),
        # With no active account the one owning account is chosen.
        ({}, [replace(STEVE, active=False), BOB], "Prism, signed in as Bob"),
        ({}, [CAROL], "Prism (no Minecraft account signed in)"),
        ({}, [STEVE], "Prism (no Minecraft account signed in)"),
        ({}, [], "Prism (no Minecraft account signed in)"),
        # Prism plays as the first account with the name, which would be the offline one.
        ({}, [replace(STEVE, name="Alex", active=False), ALEX], "Prism (several accounts named Alex)"),
        (
            {"prism": {"account": "Alex"}},
            [replace(STEVE, name="Alex", active=False), ALEX],
            "Prism (several accounts named Alex)",
        ),
        ({}, [ALEX, replace(STEVE, name="Alex", active=False)], "Prism, signed in as Alex"),
    ],
)
def test_describe_says_which_account_state_the_launch_hits(launcher, monkeypatch, config, accounts, description):
    monkeypatch.setattr(prism, "read_accounts", lambda d: accounts)
    launcher.config = config
    assert launcher.describe() == description
    # The launch itself goes ahead exactly when describe names a profile.
    try:
        launcher.check(interactive=False)
    except LaunchBlocked:
        launches = False
    else:
        launches = True
    assert launches == description.startswith("Prism, signed in as ")


def test_describe_survives_an_unreadable_tools_config(launcher, monkeypatch):
    def unreadable():
        raise RuntimeError("Cannot read configuration")

    monkeypatch.setattr(tools_config, "load_config", unreadable)
    launcher.config = None
    assert launcher.describe() == "Prism (cannot read the tools config)"


def test_check_reports_a_prism_that_does_not_answer_rather_than_an_old_one(launcher, monkeypatch):
    probes = []

    def hung(exe):
        probes.append(exe)
        raise LaunchBlocked(f"Prism at {exe} did not answer --help (command timed out after 30s).")

    monkeypatch.setattr(prism, "supports_launch_flags", hung)
    for _ in range(2):
        with pytest.raises(LaunchBlocked, match="did not answer --help") as blocked:
            launcher.check()
        assert "too old" not in str(blocked.value)
    # A Prism that did not answer is asked again by the next command.
    assert len(probes) == 2


def test_describe_and_check_ask_one_prism_for_its_flags_once(launcher, monkeypatch):
    probes = []
    monkeypatch.setattr(prism, "supports_launch_flags", lambda exe: probes.append(exe) or True)
    launcher.describe()
    launcher.describe()
    launcher.check()
    assert len(probes) == 1
