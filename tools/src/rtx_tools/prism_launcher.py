# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""The Prism client launcher, signed in to a Microsoft account that owns Minecraft.

It uses an installed launcher's normal profile or a portable launcher's
checkout-local profile, builds and installs the mod into a checkout-specific
instance, writes the launch's JVM arguments, starts Prism, and follows the
game's JVM.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import time
from collections.abc import Callable
from pathlib import Path

from . import client, install, prism, runtime_compat, winproc
from . import config as tools_config
from .benchmark import runner, util
from .client import GameProcess, LaunchBlocked, LaunchRequest

INSTALL_URL = "https://prismlauncher.org"
# The first Prism Launcher release whose --help lists --world (launcher/Application.cpp); --profile is older.
MIN_PRISM_VERSION = "9.0"
# The least a first launch waits for the game, since Prism downloads Minecraft, its libraries, and its assets (about
# 1 GB) before it starts the JVM. It also bounds the wait when the startup-stall budget is zero, which turns that
# check off.
FIRST_LAUNCH_STARTUP_SECONDS = 600
STARTUP_POLL_SECONDS = 2.0
PRISM_EXIT_GRACE_SECONDS = 10
TOOL_OPTIONS_WARNING = (
    "JAVA_TOOL_OPTIONS does not reach a Prism-launched game; put JVM options in launch.jvmArgs in "
    "tools/config.local.json."
)
DETACHED_PROCESS = getattr(subprocess, "DETACHED_PROCESS", 0)
CREATE_NEW_PROCESS_GROUP = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)


def _end(process: GameProcess) -> None:
    """End a Prism this launcher started, if it is still running."""
    if process.poll() is None:
        process.kill()
        try:
            process.wait(timeout=PRISM_EXIT_GRACE_SECONDS)
        except subprocess.TimeoutExpired:
            pass


def _normalized(text: str) -> str:
    return text.lower().replace("/", "\\")


def _game_needles(instance_dir: Path) -> set[str]:
    """What names the instance in its game JVM's command line. That names the instance's natives folder
    (MinecraftInstance::getNativePath) but not the game directory beneath the instance, which Prism passes on the JVM's
    standard input, so the match is the instance directory and a separator. Prism gives the 8.3 short form of a path the
    system code page cannot hold (FS::getPathNameInLocal8bit), so that form matches too."""
    forms = [instance_dir, winproc.short_path(instance_dir)]
    return {_normalized(str(form)).rstrip("\\") + "\\" for form in forms if form is not None}


def _find_game(needles: set[str]) -> int | None:
    try:
        processes = runner.java_process_command_lines(required=True)
    except RuntimeError as exc:
        raise LaunchBlocked(
            "Cannot inspect running Java processes, so the tools cannot safely identify this instance's game. "
            "Check Windows Management Instrumentation and run the command again."
        ) from exc
    for pid, command_line in processes:
        if any(needle in _normalized(command_line) for needle in needles):
            return pid
    return None


def _preferred_account(config: dict) -> str | None:
    settings = config.get("prism")
    preferred = settings.get("account") if isinstance(settings, dict) else None
    return preferred.strip() if isinstance(preferred, str) and preferred.strip() else None


def _configured_jvm_args(config: dict) -> list[str]:
    launch = config.get("launch")
    if launch is None:
        return []
    if not isinstance(launch, dict):
        raise LaunchBlocked("launch in tools/config.local.json must be a JSON object.")
    args = launch.get("jvmArgs", [])
    if not isinstance(args, list) or not all(isinstance(arg, str) for arg in args):
        raise LaunchBlocked("launch.jvmArgs in tools/config.local.json must be a list of strings.")
    return args


def _confirm_incompatible_runtime() -> bool:
    if not sys.stdin.isatty() or not sys.stdout.isatty():
        return True
    try:
        answer = input("Continue launching without a working RTX Remix renderer? [y/N] ")
    except EOFError:
        return False
    return answer.strip().lower() in {"y", "yes"}


class PrismGame:
    """A game started through Prism. pid, poll, and wait follow the game's JVM. Prism normally exits with its game, but
    the next launch needs it gone, so once the JVM has exited, wait gives Prism PRISM_EXIT_GRACE_SECONDS to exit and
    then ends it, and terminate and kill end the JVM and then Prism."""

    def __init__(self, game: GameProcess, prism_process: GameProcess) -> None:
        self.game = game
        self.prism = prism_process

    @property
    def pid(self) -> int:
        return self.game.pid

    def poll(self) -> int | None:
        return self.game.poll()

    def wait(self, timeout: float | None = None) -> int:
        code = self.game.wait(timeout)
        try:
            self.prism.wait(timeout=PRISM_EXIT_GRACE_SECONDS)
        except subprocess.TimeoutExpired:
            _end(self.prism)
        return code

    def terminate(self) -> None:
        try:
            self.game.terminate()
        finally:
            _end(self.prism)

    def kill(self) -> None:
        try:
            self.game.kill()
        finally:
            _end(self.prism)


class PrismLauncher:
    """Launches through the profile appropriate to the selected Prism executable.

    Without a config it reads the tools config at each use, so an edit to
    tools/config.local.json reaches the next launch; building one reads nothing.
    """

    name = "prism"

    def __init__(
        self,
        config: dict | None = None,
        *,
        sleep: Callable[[float], None] = time.sleep,
        clock: Callable[[], float] = time.monotonic,
        confirm_incompatible_runtime: Callable[[], bool] = _confirm_incompatible_runtime,
    ) -> None:
        self._config = config
        self.data_dir = util.PRISM_DATA_DIR
        self.game_dir = util.RUN_DIR
        self.instance_id = prism.INSTANCE_ID
        self.instance_name = prism.INSTANCE_NAME
        self.sleep = sleep
        self.clock = clock
        self.confirm_incompatible_runtime = confirm_incompatible_runtime
        self._runtime_probe: runtime_compat.ProbeResult | None = None
        # Whether a Prism build, by path and modification time, has the launch flags: its --help is slow to ask.
        self._launch_flags: dict[tuple[Path, int], bool] = {}

    @property
    def config(self) -> dict:
        return self._config if self._config is not None else tools_config.load_config()

    @config.setter
    def config(self, value: dict | None) -> None:
        self._config = value

    def describe(self) -> str:
        try:
            config = self.config
        except RuntimeError, OSError:
            return "Prism (cannot read the tools config)"
        try:
            exe = prism.find_executable(config)
        except LaunchBlocked:
            return "Prism (prism.executable not found)"
        if exe is None:
            return "Prism (not installed)"
        try:
            layout = self._layout(exe)
        except LaunchBlocked:
            return "Prism (cannot locate account profile)"
        try:
            supported = self._supports_launch_flags(exe)
        except LaunchBlocked:
            return "Prism (did not answer --help)"
        if not supported:
            return f"Prism (too old: needs {MIN_PRISM_VERSION} or newer)"
        try:
            accounts = prism.read_accounts(layout.data_dir)
        except LaunchBlocked:
            return "Prism (no Minecraft account signed in)"
        try:
            return f"Prism, signed in as {prism.select_account(accounts, _preferred_account(config)).name}"
        except prism.AccountBlocked as blocked:
            return f"Prism ({blocked.summary})"

    def check(self, *, interactive: bool = True) -> None:
        config = self.config
        exe = self._executable(config)
        layout = self._layout(exe)
        if not self._supports_launch_flags(exe):
            raise LaunchBlocked(
                f"{exe} is too old: the tools need Prism Launcher {MIN_PRISM_VERSION} or newer, whose --help lists "
                "--world and --profile. "
                f"Update it from {INSTALL_URL}, then run the command again."
            )
        self._refuse_a_running_prism(layout)
        self._refuse_a_running_game(_game_needles(self.game_dir.parent))
        # Opening sign-in seeds Prism's Java path, so the JDK comes first.
        self._java(runner.host_arch())
        try:
            self._account(config)
        except LaunchBlocked:
            if interactive:
                self.open_sign_in()
            raise

    def open_sign_in(self) -> None:
        """Open Prism with its window shown to sign in through its login page or Accounts > Manage Accounts."""
        exe = self._executable(self.config)
        layout = self._layout(exe)
        if not layout.uses_default_data_dir:
            prism.seed_launcher_config(layout.data_dir, self._java(runner.host_arch()))
        subprocess.Popen(
            prism.prism_command(layout),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP,
        )

    def prepare(self, arch: str, *, build: bool) -> None:
        self._layout(self._executable(self.config))
        project = util.PROJECT_DIR
        gradle = [str(project / "gradlew.bat")]
        if build:
            gradle += ["build", "-x", "test", "stageFabricApi", f"-Parch={arch}", "--console=plain", "--quiet"]
        else:
            gradle += ["stageFabricApi", "--quiet"]
        util.run(gradle, cwd=project, env=runner.gradle_env())
        self._runtime_probe = runtime_compat.warn_if_incompatible(project, arch)
        # Before the install, which warns about a mods folder without Fabric API.
        self._install_fabric_api(project / "build" / "dev" / "fabric-api.jar")
        if install.install_development_build(project, game_dir=self.game_dir, arch=arch) != 0:
            raise LaunchBlocked(f"Could not install the development build into {self.game_dir}; see the errors above.")

    def start(self, request: LaunchRequest) -> GameProcess:
        if (
            self._runtime_probe is not None
            and self._runtime_probe.incompatible
            and request.stdout is None
            and not self.confirm_incompatible_runtime()
        ):
            raise LaunchBlocked("Launch cancelled: stage a Remix runtime compatible with this checkout and try again.")
        config = self.config
        exe = self._executable(config)
        layout = self._layout(exe)
        account = self._account(config)
        java = self._java(request.arch)
        configured_jvm_args = _configured_jvm_args(config)
        # Again at launch: a Prism can still be closing after the previous run, or be reopened during prepare.
        self._refuse_a_running_prism(layout)
        needles = _game_needles(self.game_dir.parent)
        self._refuse_a_running_game(needles)
        jvm_args = [
            *prism.installer_constant("RECOMMENDED_JVM_ARGS").split(),
            *configured_jvm_args,
            *client.preset_jvm_args(request.preset),
        ]
        if request.jfr_path is not None:
            depth = request.tracy_java_max_depth
            jvm_args += client.benchmark_jvm_args(
                request.jfr_path, runner.tracy_java_depth(config) if depth is None else depth
            )
        if "JAVA_TOOL_OPTIONS" in request.env:
            print(TOOL_OPTIONS_WARNING)
        # The instance first: it refuses a heap size the JVM would reject before anything is written.
        prism.write_instance(
            layout.data_dir,
            instances_dir=layout.instances_dir,
            instance_id=layout.instance_id,
            instance_name=layout.instance_name,
            java=java,
            jvm_args=jvm_args,
        )
        if not layout.uses_default_data_dir:
            prism.seed_launcher_config(layout.data_dir, java)
        first_launch = self._first_launch()
        startup_seconds = (
            runner.benchmark_startup_stall_seconds(config)
            if request.startup_seconds is None
            else max(0, request.startup_seconds)
        )
        if first_launch or not startup_seconds:
            startup_seconds = max(FIRST_LAUNCH_STARTUP_SECONDS, startup_seconds)
        if not request.quiet:
            download = " (this first launch also downloads Minecraft)" if first_launch else ""
            print(f"Starting Minecraft through Prism as {account.name}{download}.")
        started_ns = time.time_ns()
        # Prism's console output is its debug log, which it also keeps under its data folder's logs. A harness passes
        # streams to keep it in the run log; an interactive launch passes none and discards it, rather than let it
        # flood the terminal.
        launch_env = dict(request.env)
        if layout.uses_default_data_dir and (configured_data_dir := os.environ.get(prism.DATA_DIR_ENV)):
            launch_env[prism.DATA_DIR_ENV] = configured_data_dir
        process = subprocess.Popen(
            prism.launch_command(layout, profile=account.name, world=request.quick_play_world),
            env=launch_env,
            stdout=subprocess.DEVNULL if request.stdout is None else request.stdout,
            stderr=subprocess.DEVNULL if request.stderr is None else request.stderr,
        )
        try:
            pid = self._wait_for_game(process, needles, started_ns, startup_seconds)
        except BaseException:
            # Prism may have started the JVM since the last poll, and ending Prism does not end its game. Prism goes
            # first, so it cannot start one after the look below.
            try:
                _end(process)
            finally:
                if (late := _find_game(needles)) is not None:
                    winproc.PidProcess(late).terminate()
            raise
        return PrismGame(winproc.PidProcess(pid), process)

    def log_files(self, since_ns: int) -> list[Path]:
        """The game's logs written since `since_ns`: its log, the Remix runtime's, Minecraft's client crash reports,
        and JVM crash logs, in that order so that a run log's tail shows a crash. Prism keeps the game's console
        output to itself, so these files are the only record of what the game printed there. Earlier files stay
        where they are: Minecraft archives its own latest.log when it starts, and an earlier crash log is evidence,
        but neither is this launch's."""
        candidates = [
            self.game_dir / "logs" / "latest.log",
            self.game_dir / "rtx-remix" / "logs" / "remix-dxvk.log",
            *sorted(self.game_dir.glob("crash-reports/crash-*-client.txt")),
            *sorted(self.game_dir.glob("hs_err_pid*.log")),
        ]
        files = []
        for path in candidates:
            try:
                if path.stat().st_mtime_ns > since_ns:
                    files.append(path)
            except OSError:
                continue
        return files

    def _executable(self, config: dict) -> Path:
        exe = prism.find_executable(config)
        if exe is None:
            raise LaunchBlocked(
                f"Prism Launcher is not installed. Install it from {INSTALL_URL}, or set prism.executable in "
                "tools/config.local.json to its prismlauncher.exe, then run the command again."
            )
        return exe

    def refresh_layout(self) -> None:
        """Resolve the game directory before commands use the active launcher."""
        exe = prism.find_executable(self.config)
        if exe is None:
            self.data_dir = util.PRISM_DATA_DIR
            self.game_dir = util.PRISM_DATA_DIR / "instances" / prism.INSTANCE_ID / ".minecraft"
            self.instance_id = prism.INSTANCE_ID
            self.instance_name = prism.INSTANCE_NAME
            return
        self._layout(exe)

    def _layout(self, exe: Path) -> prism.PrismLayout:
        layout = prism.launcher_layout(exe, util.PRISM_DATA_DIR, util.PROJECT_DIR)
        self.data_dir = layout.data_dir
        self.game_dir = layout.game_dir
        self.instance_id = layout.instance_id
        self.instance_name = layout.instance_name
        return layout

    def _supports_launch_flags(self, exe: Path) -> bool:
        try:
            key = (exe, exe.stat().st_mtime_ns)
        except OSError:
            return prism.supports_launch_flags(exe)
        if key not in self._launch_flags:
            self._launch_flags[key] = prism.supports_launch_flags(exe)
        return self._launch_flags[key]

    def _refuse_a_running_prism(self, layout: prism.PrismLayout) -> None:
        """A launch handed to a Prism already running on this data folder goes to that Prism, which knows nothing of
        this command's settings."""
        try:
            running = prism.running_prism_pids(
                layout.data_dir,
                executable=layout.executable,
                uses_default_data_dir=layout.uses_default_data_dir,
                required=True,
            )
        except RuntimeError as exc:
            raise LaunchBlocked(
                "Cannot inspect running Prism processes, so the tools cannot safely determine whether this checkout's "
                "launcher is already open. Check Windows Management Instrumentation and run the command again."
            ) from exc
        if running:
            pids = ", ".join(str(pid) for pid in running)
            raise LaunchBlocked(
                f"Prism is already running on this checkout's data folder {self.data_dir} (process {pids}). A launch "
                f"would go to it and lose this command's settings; close the {self.instance_name} Prism window for "
                "this checkout and run the command again."
            )

    def _refuse_a_running_game(self, needles: set[str]) -> None:
        """Do not mistake a game left running without Prism for the game this launch starts."""
        if (pid := _find_game(needles)) is not None:
            raise LaunchBlocked(
                f"Minecraft is already running from this checkout's {self.instance_name} instance (process {pid}). "
                "Close it and run the command again."
            )

    def _account(self, config: dict) -> prism.PrismAccount:
        return prism.select_account(prism.read_accounts(self.data_dir), _preferred_account(config))

    def _java(self, arch: str) -> Path:
        """javaw.exe of a JDK Prism accepts for the pinned Minecraft and the game's native libraries load into: the
        detected JDK when it is a JDK of the game's Java major for `arch`, else one installed where JDKs usually are."""
        detected = runner.detect_java_home()
        home = prism.find_game_jdk(arch, detected)
        if home is None:
            raise LaunchBlocked(
                f"Minecraft {prism.installer_constant('MC_VERSION')} in Prism needs a 64-bit JDK "
                f"{prism.GAME_JAVA_MAJOR} for {arch}. The detected JDK ({detected or 'none'}) is not one, and none is "
                "in Gradle's toolchains or the Eclipse Adoptium, Microsoft, or Java folders under Program Files. "
                f"Install a JDK {prism.GAME_JAVA_MAJOR} for {arch}, set JAVA_HOME to it, and run the command again."
            )
        return home / "bin" / "javaw.exe"

    def _install_fabric_api(self, jar: Path) -> None:
        if not jar.is_file():
            raise LaunchBlocked(
                f"{jar} is missing. Run gradlew.bat stageFabricApi in the checkout, which writes it, and fix any "
                "errors it reports, then run the command again."
            )
        mods = self.game_dir / "mods"
        target = mods / f"fabric-api-{prism.installer_constant('FABRIC_API_VERSION')}.jar"
        path = mods
        try:
            mods.mkdir(parents=True, exist_ok=True)
            for path in [other for other in mods.glob("fabric-api-*.jar") if other != target]:
                path.unlink()
            path = target
            shutil.copy2(jar, target)
        except OSError as exc:
            # shutil.copy2's CopyFile2 errors carry no strerror.
            raise LaunchBlocked(
                f"Cannot update {path} ({exc.strerror or exc}). Close any Minecraft still running from this checkout "
                "and run the command again."
            ) from exc

    def _first_launch(self) -> bool:
        """Whether Prism may still have Minecraft to download: until the game has run in this instance and Prism holds
        the pinned Minecraft's client jar (libraries/<group path>/<artifact>/<version>/, GradleSpecifier::toPath in
        launcher/minecraft/GradleSpecifier.h), a launch can first fetch the client, its libraries, and its assets."""
        version = prism.installer_constant("MC_VERSION")
        jar = self.data_dir / "libraries" / "com" / "mojang" / "minecraft" / version / f"minecraft-{version}-client.jar"
        return not (jar.is_file() and (self.game_dir / "logs" / "latest.log").is_file())

    def _wait_for_game(self, process: GameProcess, needles: set[str], started_ns: int, seconds: int) -> int:
        """The PID of the game's JVM once Prism starts it. A Prism that exits first, logs the game wrote since
        `started_ns` with no JVM left to follow, and `seconds` passing all block the launch."""
        deadline = self.clock() + seconds
        while True:
            # Read before the scan, so that a JVM which wrote them and is still running is in the scan.
            left_behind = self.log_files(started_ns)
            if (pid := _find_game(needles)) is not None:
                return pid
            if left_behind:
                logs = ", ".join(str(path) for path in left_behind)
                raise client.GameExitedEarly(f"The game exited as it started; see {logs}", logs=tuple(left_behind))
            if process.poll() is not None or self.clock() >= deadline:
                raise LaunchBlocked(f"Prism did not start the game; see {self.data_dir / 'logs'}")
            self.sleep(STARTUP_POLL_SECONDS)
