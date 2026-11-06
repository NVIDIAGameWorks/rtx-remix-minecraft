# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Prism Launcher discovery, account selection, and the per-checkout data folder the tools launch Minecraft through.

Low-level pieces only: nothing here starts a game. Only an account's type, profile name, ownership, and active flag
are ever read from accounts.json; the tokens beside them stay in the file."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import struct
from dataclasses import dataclass
from pathlib import Path

from .benchmark import util
from .client import LaunchBlocked

INSTANCE_ID = "rtx-remix-dev"
INSTANCE_NAME = "RTX Remix Dev"
# Where packaging/installer.ps1 puts its managed portable Prism.
MANAGED_PORTABLE_DIR = Path(os.environ.get("LOCALAPPDATA", "")) / "RtxRemixMinecraft" / "PrismLauncher"
DATA_DIR_ENV = "PRISMLAUNCHER_DATA_DIR"
ACCOUNTS_FORMAT_VERSION = 3
# The JVM's two spellings of each heap limit, and the sizes it accepts for them.
MAX_HEAP_OPTIONS = ("-Xmx", "-XX:MaxHeapSize=")
MIN_HEAP_OPTIONS = ("-Xms", "-XX:InitialHeapSize=")
# What Prism refuses in JvmArgs: before every launch it shows a modal warning and fails the launch when one matches
# (JavaCommon::checkJVMArgs in launcher/JavaCommon.cpp, from LaunchController::executeTask in
# launcher/LaunchController.cpp). "-XX-MaxHeapSize" is Prism's own spelling.
PRISM_REFUSED_JVM_ARG = re.compile(r"-XX:PermSize=|-Xm[sx]|-XX-MaxHeapSize|-XX:InitialHeapSize|-version:")
HEAP_SIZE = re.compile(r"(\d+)([kKmMgGtT]?)")
HEAP_UNIT_BYTES = {"": 1, "k": 1 << 10, "m": 1 << 20, "g": 1 << 30, "t": 1 << 40}
# Prism's default MinMemAlloc (Application::Application in launcher/Application.cpp).
PRISM_MIN_HEAP_MIB = 512
# The only Java major Prism's metadata lists for Minecraft 26.3 (compatibleJavaMajors in
# https://meta.prismlauncher.org/v1/net.minecraft/26.3.json). Prism refuses any other, newer ones included, unless
# IgnoreJavaCompatibility is set (VerifyJavaInstall::executeTask in launcher/minecraft/launch/VerifyJavaInstall.cpp).
# packaging/installer.ps1 pins the same major as $script:GAME_JAVA_MAJOR.
GAME_JAVA_MAJOR = 25
PE_MACHINE_ARCH = {0x8664: "win64", 0xAA64: "arm64"}
JAVA_VERSION_LINE = re.compile(r'^JAVA_VERSION="(\d+(?:\.\d+)*)', re.MULTILINE)

# Prism opens on its setup wizard's login page while no account is signed in (Application::createSetupWizard in
# launcher/Application.cpp), and on its main window, with Accounts > Manage Accounts > Add Microsoft, once one is.
SIGN_IN_STEPS = (
    "Run tools\\rtx.bat prism signin, sign in on the page Prism shows (or Accounts > "
    "Manage Accounts > Add Microsoft) with the Microsoft account that owns Minecraft: Java Edition, close Prism, then "
    "run the command again."
)
# For prism signin, which has just opened Prism, so the steps start inside it.
SIGN_IN_OPENED_STEPS = (
    "In Prism, sign in on the page Prism shows (or Accounts > Manage Accounts > Add Microsoft) with "
    "the Microsoft account that owns Minecraft: Java Edition, close Prism, then run your launch command again."
)


@dataclass(frozen=True)
class PrismAccount:
    kind: str
    name: str
    owns_minecraft: bool
    active: bool


@dataclass(frozen=True)
class PrismLayout:
    """The data root and instance an executable must use.

    Installed Prism owns its normal account store, while a portable build can
    safely keep the whole launcher profile inside the checkout.
    """

    executable: Path
    data_dir: Path
    instances_dir: Path
    instance_id: str
    instance_name: str
    uses_default_data_dir: bool

    @property
    def game_dir(self) -> Path:
        return self.instances_dir / self.instance_id / ".minecraft"


def _instances_dir(data_dir: Path) -> Path:
    """The instance root Prism's profile config selects, or its default."""
    try:
        text = (data_dir / "prismlauncher.cfg").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return data_dir / "instances"
    match = re.search(r"^InstanceDir=(.*)$", text, re.MULTILINE)
    if match is None or not (value := match.group(1).strip().strip('"')):
        return data_dir / "instances"
    path = Path(value)
    return path if path.is_absolute() else data_dir / path


def launcher_layout(executable: Path, checkout_data_dir: Path, project_dir: Path) -> PrismLayout:
    """Choose the profile and a collision-free instance for this Prism executable."""
    if (executable.parent / "portable.txt").is_file():
        return PrismLayout(
            executable,
            checkout_data_dir,
            _instances_dir(checkout_data_dir),
            INSTANCE_ID,
            INSTANCE_NAME,
            False,
        )
    configured_data_dir = os.environ.get(DATA_DIR_ENV)
    app_data = os.environ.get("APPDATA")
    if not configured_data_dir and not app_data:
        raise LaunchBlocked(
            f"Neither {DATA_DIR_ENV} nor APPDATA is set, so the tools cannot locate installed Prism Launcher's "
            "account store. Use an official portable Prism build or restore APPDATA, then run the command again."
        )
    identity = os.path.normcase(str(project_dir.resolve()))
    suffix = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:10]
    label = re.sub(r"[^a-z0-9]+", "-", project_dir.name.lower()).strip("-") or "checkout"
    instance_id = f"{INSTANCE_ID}-{label[:32]}-{suffix}"
    data_dir = Path(configured_data_dir) if configured_data_dir else Path(app_data or "") / "PrismLauncher"
    return PrismLayout(
        executable,
        data_dir,
        _instances_dir(data_dir),
        instance_id,
        f"{INSTANCE_NAME} ({project_dir.name}, {suffix})",
        True,
    )


def find_executable(config: dict) -> Path | None:
    """The configured Prism, which must exist, else the first that exists of the installer's managed portable copy,
    PATH, and a per-user install. A configured path is never passed over for another Prism, which could be a different
    version run on the same data folder."""
    prism_config = config.get("prism")
    configured = prism_config.get("executable") if isinstance(prism_config, dict) else None
    if isinstance(configured, str) and configured.strip():
        path = Path(configured)
        if path.is_file():
            return path
        raise LaunchBlocked(
            f"prism.executable is {path}, which is not a file. Point it at prismlauncher.exe, or remove it from "
            "tools/config.local.json to let the tools find Prism."
        )
    candidates = [MANAGED_PORTABLE_DIR / "prismlauncher.exe"]
    on_path = shutil.which("prismlauncher")
    if on_path:
        candidates.append(Path(on_path))
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        candidates.append(Path(local_app_data) / "Programs" / "PrismLauncher" / "prismlauncher.exe")
    return next((path for path in candidates if path.is_file()), None)


def _unreadable_accounts(path: Path) -> LaunchBlocked:
    return LaunchBlocked(f"Prism's account list {path} is unreadable or in an unknown format. {SIGN_IN_STEPS}")


def _section(entry: dict, key: str) -> dict:
    value = entry.get(key)
    return value if isinstance(value, dict) else {}


def read_accounts(data_dir: Path) -> list[PrismAccount]:
    """The accounts signed in to `data_dir`. An absent file means none; an unreadable one blocks with the sign-in
    steps. Errors never quote the file, which holds tokens."""
    path = data_dir / "accounts.json"
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return []
    except OSError, ValueError:
        # Not chained: a JSONDecodeError carries the whole document, tokens included.
        raise _unreadable_accounts(path) from None
    if not isinstance(data, dict) or data.get("formatVersion") != ACCOUNTS_FORMAT_VERSION:
        raise _unreadable_accounts(path)
    entries = data.get("accounts")
    if not isinstance(entries, list):
        raise _unreadable_accounts(path)
    accounts = []
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        kind = entry.get("type")
        name = _section(entry, "profile").get("name")
        accounts.append(
            PrismAccount(
                kind=kind if isinstance(kind, str) else "",
                name=name if isinstance(name, str) else "",
                owns_minecraft=_section(entry, "entitlement").get("ownsMinecraft") is True,
                active=entry.get("active") is True,
            )
        )
    return accounts


class AccountBlocked(LaunchBlocked):
    """No signed-in account the launch can play as. `summary` is the short form the dashboard's Launcher row shows."""

    def __init__(self, message: str, summary: str) -> None:
        super().__init__(message)
        self.summary = summary


def _owns_minecraft(account: PrismAccount) -> bool:
    return account.kind == "MSA" and account.owns_minecraft


def select_account(accounts: list[PrismAccount], preferred: str | None) -> PrismAccount:
    """The account to play as: the preferred name when one is set, else the active one, else the only owning Microsoft
    account. A preferred name that is not signed in blocks rather than playing as someone else, and so does anything
    that is not a Microsoft account owning Minecraft.

    The launch names the account by profile name, and Prism plays as the first account in its list with that name
    whatever its type (AccountList::getAccountByProfileName in launcher/minecraft/auth/AccountList.cpp), so an earlier
    account of the same name blocks too: an offline one would otherwise launch in place of the signed-in one."""
    owning = [a for a in accounts if _owns_minecraft(a)]
    if preferred:
        chosen = next((a for a in accounts if a.name == preferred), None)
        if chosen is None:
            signed_in = ", ".join(repr(a.name) for a in accounts if a.name) or "none"
            raise AccountBlocked(
                f"prism.account is {preferred!r}, but no account in Prism's selected profile has that profile "
                f"name (signed in: {signed_in}). {SIGN_IN_STEPS}",
                f"prism.account {preferred} not signed in",
            )
    else:
        chosen = next((a for a in accounts if a.active), None) or (owning[0] if len(owning) == 1 else None)
    if chosen is None and len(owning) > 1:
        raise AccountBlocked(
            "Several Microsoft accounts in Prism's selected profile own Minecraft. Set prism.account in "
            f"tools/config.local.json to the profile name to use. {SIGN_IN_STEPS}",
            "several Microsoft accounts; set prism.account",
        )
    if chosen is None:
        raise AccountBlocked(
            f"No Microsoft account is signed in to Prism's selected profile. {SIGN_IN_STEPS}",
            "no Minecraft account signed in",
        )
    namesakes = [a for a in accounts if a.name == chosen.name]
    if namesakes[0] is not chosen or (not _owns_minecraft(chosen) and any(map(_owns_minecraft, namesakes))):
        raise AccountBlocked(
            f"{len(namesakes)} accounts in Prism's selected profile are named {chosen.name!r}, and Prism plays as the "
            "first of them in its account list, whatever its type. In Prism's Accounts page, remove each "
            f"{chosen.name!r} account other than the Microsoft account that owns Minecraft, close Prism, then run the "
            "command again.",
            f"several accounts named {chosen.name}",
        )
    if not _owns_minecraft(chosen):
        if preferred:
            summary = f"prism.account {chosen.name} does not own Minecraft"
        elif len(owning) > 1:
            summary = "several Microsoft accounts; set prism.account"
        elif owning:
            # Prism's active account is chosen before the one owning account, and it is not that account.
            summary = f"active account{f' {chosen.name}' if chosen.name else ''} does not own Minecraft"
        else:
            summary = "no Minecraft account signed in"
        raise AccountBlocked(
            f"Prism account {chosen.name!r} is not a Microsoft account that owns Minecraft. {SIGN_IN_STEPS}", summary
        )
    return chosen


def java_version(home: Path) -> tuple[int, ...] | None:
    """The version a Java home's release file names, with Java 8's leading 1 dropped, or None when it names none."""
    try:
        text = (home / "release").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    match = JAVA_VERSION_LINE.search(text)
    if match is None:
        return None
    version = tuple(int(part) for part in match.group(1).split("."))
    return version[1:] if version[0] == 1 and len(version) > 1 else version


def executable_arch(exe: Path) -> str | None:
    """win64 or arm64 for a Windows executable built for that machine, read from its PE header, else None."""
    try:
        with exe.open("rb") as handle:
            if handle.read(2) != b"MZ":
                return None
            handle.seek(0x3C)
            (offset,) = struct.unpack("<I", handle.read(4))
            handle.seek(offset)
            header = handle.read(6)
    except OSError, struct.error:
        return None
    if len(header) != 6 or header[:4] != b"PE\0\0":
        return None
    return PE_MACHINE_ARCH.get(struct.unpack("<H", header[4:])[0])


def _is_game_jdk(home: Path, arch: str) -> bool:
    version = java_version(home)
    return version is not None and version[0] == GAME_JAVA_MAJOR and executable_arch(home / "bin" / "javaw.exe") == arch


def _installed_jdk_folders() -> list[list[Path]]:
    """Where JDKs are commonly installed, in the order to search them, each a list of candidate Java homes: Gradle's
    provisioned toolchains, which can hold the JDK one level inside a folder Gradle names, then the jdk-<major> folders
    of the Eclipse Adoptium, Microsoft, and Oracle installers."""
    gradle_home = os.environ.get("GRADLE_USER_HOME")
    gradle_jdks = (Path(gradle_home) if gradle_home else Path.home() / ".gradle") / "jdks"
    folders = [[*gradle_jdks.glob("*"), *gradle_jdks.glob("*/*")]]
    program_files = os.environ.get("ProgramFiles")
    if program_files:
        folders += [
            sorted((Path(program_files) / vendor).glob(f"jdk-{GAME_JAVA_MAJOR}*"))
            for vendor in ("Eclipse Adoptium", "Microsoft", "Java")
        ]
    return folders


def find_game_jdk(arch: str, java_home: Path | None) -> Path | None:
    """The Java home to run the game with: `java_home` when it is a JDK of GAME_JAVA_MAJOR for `arch`, else the newest
    such JDK in the first of _installed_jdk_folders() holding one, else None."""
    if java_home is not None and _is_game_jdk(java_home, arch):
        return java_home
    for candidates in _installed_jdk_folders():
        found = [home for home in candidates if _is_game_jdk(home, arch)]
        if found:
            return max(found, key=lambda home: (java_version(home) or (), home.name))
    return None


def installer_constant(name: str) -> str:
    """A `$script:NAME = '...'` string from packaging/installer.ps1, the one source of the pinned versions and the
    recommended JVM arguments."""
    path = util.PROJECT_DIR / "packaging" / "installer.ps1"
    pattern = rf"^[ \t]*\$script:{re.escape(name)}[ \t]*=[ \t]*'((?:[^']|'')*)'"
    match = re.search(pattern, path.read_text(encoding="utf-8"), re.MULTILINE)
    if match is None:
        raise RuntimeError(f"{path} has no $script:{name} string assignment")
    return match.group(1).replace("''", "'")


def _ini_value(value: str) -> str:
    """Escape a value for Prism's reader of config files that carry no ConfigVersion, which is how this module writes
    them (launcher/settings/INIFile.cpp, parseOldFileFormat on the develop branch): a backslash escapes, `#` starts a
    comment, and one pair of quotes is dropped from a value that starts and ends with one and holds `;`, `=` or `,`."""
    value = value.replace("\\", "\\\\").replace("#", "\\#")
    if len(value) >= 2 and value[0] == value[-1] == '"' and any(char in value for char in ";=,"):
        value = f'"{value}"'
    return value


def _write_ini(path: Path, entries: list[tuple[str, str]], *, header: str | None = None) -> None:
    lines = [] if header is None else [header]
    lines.extend(f"{key}={_ini_value(value)}" for key, value in entries)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\r\n")


def seed_launcher_config(data_dir: Path, java: Path) -> None:
    """Write prismlauncher.cfg so a fresh data folder skips the language, Java, and theme pages of Prism's first-run
    wizard, and its update checks. An existing file is the user's and is left alone."""
    path = data_dir / "prismlauncher.cfg"
    if path.exists():
        return
    _write_ini(
        path,
        [
            ("Language", "en_US"),
            ("JavaPath", java.as_posix()),
            ("IgnoreJavaWizard", "true"),
            ("UserAskedAboutAutomaticJavaDownload", "true"),
            # The wizard's theme page appears unless both are IDs Prism has registered. "dark" is DarkTheme::id()
            # (launcher/ui/themes/DarkTheme.cpp:42) and "pe_colored" leads ThemeManager::s_builtinIcons
            # (launcher/ui/themes/ThemeManager.h:94), the icon theme Application::createSetupWizard falls back to
            # (launcher/Application.cpp:1281). "system" is not fixed: it is whatever Qt style name the host reports.
            ("ApplicationTheme", "dark"),
            ("IconTheme", "pe_colored"),
            # The keys Disable-PrismUpdateCheck writes in packaging/installer.ps1.
            ("AutoCheckUpdates", "false"),
            ("AutoUpdate", "false"),
        ],
        header="[General]",
    )


def join_jvm_args(args: list[str]) -> str:
    """One JvmArgs value that Prism splits back into `args` (Commandline::splitArgs, launcher/Commandline.cpp). Prism
    splits on spaces outside quotes and reads a backslash there literally. Inside quotes, 9.4 lets a backslash escape
    any character and develop only a quote or another backslash, so an argument holding whitespace or a quote is
    double-quoted with its backslashes and double quotes escaped, which both read back. An argument holding only a
    backslash is quoted the same way, though it would also survive unquoted."""
    return " ".join(
        '"' + arg.replace("\\", "\\\\").replace('"', '\\"') + '"'
        if any(char.isspace() or char in "\\\"'" for char in arg)
        else arg
        for arg in args
    )


def _heap_mib(jvm_args: list[str], options: tuple[str, ...]) -> int | None:
    """The heap size in whole MiB, rounded up, that the last of `options` in `jvm_args` sets, or None when none does.
    A size the JVM would reject blocks the launch."""
    for arg in reversed(jvm_args):
        option = next((option for option in options if arg.startswith(option)), None)
        if option is None:
            continue
        match = HEAP_SIZE.fullmatch(arg.removeprefix(option))
        if match is None:
            raise LaunchBlocked(
                f"{arg} is not a heap size the JVM accepts: give a whole number with an optional k, m, g, or t. "
                "Correct it in launch.jvmArgs in tools/config.local.json and run the command again."
            )
        size = int(match.group(1)) * HEAP_UNIT_BYTES[match.group(2).lower()]
        return -(-size // HEAP_UNIT_BYTES["m"])
    return None


def _memory_settings(jvm_args: list[str]) -> list[tuple[str, str]]:
    """Prism's instance memory settings for the heap `jvm_args` ask for. Prism refuses to launch with a heap option in
    JvmArgs (PRISM_REFUSED_JVM_ARG), so these are the only way to give the game its heap: Prism appends
    `-Xms<MinMemAlloc>m -Xmx<MaxMemAlloc>m` after JvmArgs, and without them its default maximum (4096 MiB on a machine
    with 6 GiB of RAM or more) would apply (MinecraftInstance::javaArguments in
    launcher/minecraft/MinecraftInstance.cpp, SysInfo::defaultMaxJvmMem in launcher/SysInfo.cpp). They take effect
    only with OverrideMemory set (MinecraftInstance::loadSpecificSettings), which sets both sizes, so an initial heap
    without a maximum blocks the launch.

    The minimum never exceeds the maximum, because Prism swaps the two when it does. LowMemWarning is off because,
    under the same gate, Prism asks in a modal dialog whether to launch when free RAM is below 70% of MaxMemAlloc
    (EnsureAvailableMemory::executeTask in launcher/minecraft/launch/EnsureAvailableMemory.cpp), which would hold a
    windowless launch."""
    maximum = _heap_mib(jvm_args, MAX_HEAP_OPTIONS)
    minimum = _heap_mib(jvm_args, MIN_HEAP_OPTIONS)
    if maximum is None:
        if minimum is not None:
            initial = next(arg for arg in reversed(jvm_args) if arg.startswith(MIN_HEAP_OPTIONS))
            raise LaunchBlocked(
                f"{initial} sets an initial heap without a maximum, and Prism sets the two together. Add an -Xmx to "
                "launch.jvmArgs in tools/config.local.json, or remove the initial heap, and run the command again."
            )
        return []
    return [
        ("OverrideMemory", "true"),
        ("MinMemAlloc", str(min(PRISM_MIN_HEAP_MIB if minimum is None else minimum, maximum))),
        ("MaxMemAlloc", str(maximum)),
        ("LowMemWarning", "false"),
    ]


def instance_jvm_args(jvm_args: list[str]) -> list[str]:
    """`jvm_args` as the instance's JvmArgs: without the heap limits, which go to Prism's memory settings instead
    (_memory_settings). Any other argument Prism would refuse to launch with blocks the launch."""
    for arg in jvm_args:
        if any(ord(char) < 32 for char in arg):
            raise LaunchBlocked(
                f"{arg!r} contains a control character that cannot be written safely to Prism's instance.cfg. "
                "Remove it from launch.jvmArgs in tools/config.local.json and run the command again."
            )
    kept = [arg for arg in jvm_args if not arg.startswith(MAX_HEAP_OPTIONS + MIN_HEAP_OPTIONS)]
    for arg in kept:
        if PRISM_REFUSED_JVM_ARG.search(arg):
            raise LaunchBlocked(
                f"Prism refuses to launch with {arg} in the game's JVM arguments. Remove it from launch.jvmArgs in "
                "tools/config.local.json and run the command again."
            )
    return kept


def write_instance(
    data_dir: Path,
    *,
    instances_dir: Path | None = None,
    instance_id: str = INSTANCE_ID,
    instance_name: str = INSTANCE_NAME,
    java: Path,
    jvm_args: list[str],
) -> Path:
    """Write the instance's two files, replacing what is there, and return its game directory. The JSON pins Minecraft
    and Fabric Loader to the versions packaging/installer.ps1 pins, and the game's heap is the last one `jvm_args`
    sets."""
    memory = _memory_settings(jvm_args)
    instance_args = instance_jvm_args(jvm_args)
    instance_dir = (data_dir / "instances" if instances_dir is None else instances_dir) / instance_id
    game_dir = instance_dir / ".minecraft"
    game_dir.mkdir(parents=True, exist_ok=True)
    _write_ini(
        instance_dir / "instance.cfg",
        [
            ("InstanceType", "OneSix"),
            ("name", instance_name),
            ("OverrideJavaLocation", "true"),
            ("JavaPath", java.as_posix()),
            ("OverrideJavaArgs", "true"),
            ("JvmArgs", join_jvm_args(instance_args)),
            *memory,
            # Prism exits once no launch is running and no window is open (Application::controllerFinished in
            # launcher/Application.cpp). A failed launch opens the console window while ShowConsoleOnError, true by
            # default, holds (LaunchController::onFailed in launcher/LaunchController.cpp), so without these a
            # windowless launch that fails would leave Prism running until the tools' wait ran out. Both take effect
            # only with OverrideConsole set (BaseInstance::BaseInstance in launcher/BaseInstance.cpp).
            ("OverrideConsole", "true"),
            ("ShowConsole", "false"),
            ("ShowConsoleOnError", "false"),
        ],
    )
    util.write_json(
        instance_dir / "mmc-pack.json",
        {
            "components": [
                {
                    "cachedName": "Minecraft",
                    "important": True,
                    "uid": "net.minecraft",
                    "version": installer_constant("MC_VERSION"),
                },
                {
                    "cachedName": "Fabric Loader",
                    "uid": "net.fabricmc.fabric-loader",
                    "version": installer_constant("FABRIC_LOADER_VER"),
                },
            ],
            "formatVersion": 1,
        },
    )
    return game_dir


def _prism_command_lines(*, required: bool = False) -> list[tuple[int, str]]:
    """Snapshot of running Prism command lines (Windows only), as runner.java_process_command_lines does for Java.

    With `required`, a failed or malformed process query raises instead of
    looking like a machine with no Prism processes.
    """
    if os.name != "nt":
        return []
    script = (
        "Get-CimInstance Win32_Process -Filter \"Name='prismlauncher.exe'\""
        " | Select-Object ProcessId,CommandLine | ConvertTo-Json -Compress"
    )
    try:
        result = util.run(
            ["powershell", "-NoProfile", "-Command", script],
            capture=True,
            check=False,
            timeout=30,
            encoding=util.console_encoding(),
            errors="replace",
        )
    except RuntimeError:
        if required:
            raise
        return []
    text = (result.stdout or "").strip()
    if result.returncode != 0:
        if required:
            raise RuntimeError(f"Prism process query failed with exit code {result.returncode}")
        return []
    if not text:
        return []
    try:
        data = json.loads(text)
    except ValueError as exc:
        if required:
            raise RuntimeError("Prism process query returned malformed JSON") from exc
        return []
    processes = []
    for entry in data if isinstance(data, list) else [data]:
        if not isinstance(entry, dict):
            continue
        pid = entry.get("ProcessId")
        command_line = entry.get("CommandLine")
        if isinstance(pid, int) and isinstance(command_line, str) and command_line:
            processes.append((pid, command_line))
    return processes


def running_prism_pids(
    data_dir: Path,
    *,
    executable: Path | None = None,
    uses_default_data_dir: bool = False,
    required: bool = False,
) -> list[int]:
    """Prism processes using this launcher profile.

    A portable process names its explicit data folder. An installed process
    using Prism's normal profile names no data folder, so its executable path
    identifies it without conflating another portable copy.
    """
    needle = str(data_dir).lower().replace("/", "\\").rstrip("\\")
    pattern = re.compile(re.escape(needle) + r"(?![\w.-])")
    processes = _prism_command_lines(required=True) if required else _prism_command_lines()
    if uses_default_data_dir:
        if executable is None:
            raise ValueError("executable is required for Prism's default data directory")
        executable_needle = str(executable).lower().replace("/", "\\")
        executable_pattern = re.compile(r'^"?' + re.escape(executable_needle) + r'"?(?=\s|$)')
        return [
            pid
            for pid, line in processes
            if executable_pattern.match(line.lower().replace("/", "\\").lstrip()) and "--dir" not in line.lower()
        ]
    return [pid for pid, line in processes if pattern.search(line.lower().replace("/", "\\"))]


def prism_command(layout: PrismLayout, *args: str) -> list[str]:
    """A command using the account store selected for this Prism build."""
    command = [str(layout.executable)]
    if not layout.uses_default_data_dir:
        command += ["--dir", str(layout.data_dir)]
    return [*command, *args]


def launch_command(layout: PrismLayout, *, profile: str, world: str | None) -> list[str]:
    """Start the instance as an online `profile`, joining `world` when given. There is never an offline flag."""
    command = prism_command(layout, "--launch", layout.instance_id, "--profile", profile)
    if world:
        command += ["--world", world]
    return command


def supports_launch_flags(exe: Path) -> bool:
    """Whether this Prism can join a world as a chosen profile: its help names both flags. Prism is a GUI program, so
    Qt shows its help in a modal dialog unless told to print it; the timeout bounds a Prism that waits anyway. A Prism
    that cannot be run or does not answer in time blocks the launch, since that says nothing about its age."""
    try:
        result = util.run(
            [str(exe), "--help"],
            capture=True,
            check=False,
            timeout=30,
            env={**os.environ, "QT_COMMAND_LINE_PARSER_NO_GUI_MESSAGE_BOXES": "1"},
            encoding=util.console_encoding(),
            errors="replace",
        )
    except (OSError, RuntimeError) as exc:
        raise LaunchBlocked(
            f"Prism at {exe} did not answer --help ({exc}). Check that it starts, or set prism.executable in "
            "tools/config.local.json to a prismlauncher.exe that does, then run the command again."
        ) from exc
    text = (result.stdout or "") + (result.stderr or "")
    return "--world" in text and "--profile" in text
