# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import json
import os
import re
import shutil
import subprocess
from pathlib import Path

import pytest
from conftest import recommended_heap_mib, write_jdk

from rtx_tools import client, loader_registry, prism
from rtx_tools.benchmark import util

PROJECT_STATE = loader_registry.load_registry()


def msa(name: str, *, owns: bool, active: bool, token: str = "SECRET") -> dict:
    """An accounts.json entry shaped like Prism's, with token fields the tools must never read."""
    return {
        "type": "MSA",
        "active": active,
        "entitlement": {"canPlayMinecraft": owns, "ownsMinecraft": owns},
        "profile": {"id": "0123", "name": name, "capes": [], "skin": {"id": "4567", "url": "https://skin.invalid"}},
        "msa": {"token": token, "refresh_token": token},
        "ygg": {"token": token, "iat": 1},
    }


def write_accounts(data_dir: Path, accounts: list, *, format_version: int = 3) -> None:
    (data_dir / "accounts.json").write_text(
        json.dumps({"formatVersion": format_version, "accounts": accounts}), encoding="utf-8"
    )


def test_read_accounts_reads_only_identity_fields(tmp_path):
    write_accounts(tmp_path, [msa("Alex", owns=True, active=True, token="SECRET")])
    accounts = prism.read_accounts(tmp_path)
    assert accounts == [prism.PrismAccount(kind="MSA", name="Alex", owns_minecraft=True, active=True)]
    assert "SECRET" not in repr(accounts)


def test_read_accounts_tolerates_entries_without_identity_fields(tmp_path):
    write_accounts(tmp_path, [{"type": "Offline"}, 7, {"type": "MSA", "profile": "x", "entitlement": []}])
    assert prism.read_accounts(tmp_path) == [
        prism.PrismAccount(kind="Offline", name="", owns_minecraft=False, active=False),
        prism.PrismAccount(kind="MSA", name="", owns_minecraft=False, active=False),
    ]


def test_read_accounts_missing_file_is_empty(tmp_path):
    assert prism.read_accounts(tmp_path) == []


@pytest.mark.parametrize(
    "text",
    [
        "{",
        "[]",
        '{"formatVersion": 2, "accounts": []}',
        '{"formatVersion": 3, "accounts": 5}',
        '{"accounts": []}',
    ],
)
def test_read_accounts_rejects_unreadable_files(tmp_path, text):
    (tmp_path / "accounts.json").write_text(text, encoding="utf-8")
    with pytest.raises(client.LaunchBlocked, match="prism signin"):
        prism.read_accounts(tmp_path)


def test_read_accounts_error_does_not_echo_the_file(tmp_path):
    write_accounts(tmp_path, [msa("Alex", owns=True, active=True, token="SECRET")], format_version=2)
    with pytest.raises(client.LaunchBlocked) as blocked:
        prism.read_accounts(tmp_path)
    assert "SECRET" not in str(blocked.value)


def test_select_account_order():
    a = prism.PrismAccount("MSA", "A", True, False)
    b = prism.PrismAccount("MSA", "B", True, True)
    assert prism.select_account([a, b], "A") is a
    assert prism.select_account([a, b], None) is b
    assert prism.select_account([a], None) is a


def test_select_account_is_ambiguous_without_an_active_or_preferred_account():
    a = prism.PrismAccount("MSA", "A", True, False)
    b = prism.PrismAccount("MSA", "B", True, False)
    with pytest.raises(client.LaunchBlocked, match="Several Microsoft accounts"):
        prism.select_account([a, b], None)


@pytest.mark.parametrize(
    "accounts",
    [
        [],
        [prism.PrismAccount("Offline", "Bob", False, True)],
        [prism.PrismAccount("MSA", "Demo", False, True)],
    ],
)
def test_select_account_requires_an_owning_microsoft_account(accounts):
    with pytest.raises(client.LaunchBlocked, match="Add Microsoft"):
        prism.select_account(accounts, None)


@pytest.mark.parametrize(
    "accounts",
    [
        # Would otherwise fall back to the active account and play as a profile the user did not name.
        [prism.PrismAccount("MSA", "Alex", True, True), prism.PrismAccount("MSA", "Bob", True, False)],
        # Would otherwise ask the user to set prism.account, which they already have.
        [prism.PrismAccount("MSA", "Alex", True, False), prism.PrismAccount("MSA", "Bob", True, False)],
    ],
)
def test_select_account_blocks_when_the_preferred_profile_is_not_signed_in(accounts):
    with pytest.raises(client.LaunchBlocked, match="prism.account") as blocked:
        prism.select_account(accounts, "Alx")
    message = str(blocked.value)
    assert "'Alx'" in message and "'Alex'" in message and "'Bob'" in message
    assert "Several Microsoft accounts" not in message
    assert message.endswith(prism.SIGN_IN_STEPS)


def test_select_account_names_a_preferred_account_that_does_not_qualify():
    demo = prism.PrismAccount("MSA", "Demo", False, False)
    owner = prism.PrismAccount("MSA", "Owner", True, True)
    with pytest.raises(client.LaunchBlocked, match="'Demo'"):
        prism.select_account([demo, owner], "Demo")


OFFLINE_ALEX = prism.PrismAccount("Offline", "Alex", False, False)
OWNING_ALEX = prism.PrismAccount("MSA", "Alex", True, True)


@pytest.mark.parametrize("preferred", [None, "Alex"])
def test_select_account_blocks_when_prism_would_launch_another_account_of_that_name(preferred):
    """Prism's --profile plays as the first account with the name (AccountList::getAccountByProfileName), so an
    offline Alex listed before the signed-in Alex would launch offline."""
    with pytest.raises(
        client.LaunchBlocked, match="2 accounts in Prism's selected profile are named 'Alex'"
    ) as blocked:
        prism.select_account([OFFLINE_ALEX, OWNING_ALEX], preferred)
    assert isinstance(blocked.value, prism.AccountBlocked)
    assert blocked.value.summary == "several accounts named Alex"


@pytest.mark.parametrize("preferred", [None, "Alex"])
def test_select_account_allows_a_namesake_listed_after_the_chosen_account(preferred):
    assert prism.select_account([OWNING_ALEX, OFFLINE_ALEX], preferred) is OWNING_ALEX


def test_select_account_reports_namesakes_none_of_which_own_minecraft_as_not_owning():
    demo = prism.PrismAccount("MSA", "Alex", False, True)
    with pytest.raises(client.LaunchBlocked, match="is not a Microsoft account that owns Minecraft"):
        prism.select_account([demo, OFFLINE_ALEX], None)


def test_sign_in_steps_cover_prisms_first_run_login_page():
    """In a data folder with no valid account Prism opens its setup wizard on a login page rather than the main
    window (Application::createSetupWizard, launcher/Application.cpp)."""
    for steps in (prism.SIGN_IN_STEPS, prism.SIGN_IN_OPENED_STEPS):
        assert "sign in on the page Prism shows (or Accounts > Manage Accounts > Add Microsoft)" in steps


def test_launch_command_never_goes_offline(tmp_path):
    layout = prism.PrismLayout(Path("p.exe"), tmp_path, tmp_path / "instances", "rtx-remix-dev", "RTX Remix Dev", False)
    cmd = prism.launch_command(layout, profile="Alex", world="rtx-benchmark")
    assert cmd == [
        "p.exe",
        "--dir",
        str(tmp_path),
        "--launch",
        "rtx-remix-dev",
        "--profile",
        "Alex",
        "--world",
        "rtx-benchmark",
    ]
    assert "--offline" not in prism.launch_command(layout, profile="Alex", world=None)
    assert "--world" not in prism.launch_command(layout, profile="Alex", world=None)


def test_portable_prism_uses_the_checkouts_launcher_profile(tmp_path):
    exe = tmp_path / "portable" / "prismlauncher.exe"
    exe.parent.mkdir()
    exe.write_bytes(b"")
    (exe.parent / "portable.txt").write_text("")
    checkout = tmp_path / "source"
    data_dir = checkout / "run-prism"

    layout = prism.launcher_layout(exe, data_dir, checkout)

    assert layout.data_dir == data_dir
    assert layout.game_dir == data_dir / "instances" / "rtx-remix-dev" / ".minecraft"
    assert layout.instance_id == "rtx-remix-dev"
    assert not layout.uses_default_data_dir
    assert prism.prism_command(layout) == [str(exe), "--dir", str(data_dir)]


def test_installed_prism_uses_its_account_store_and_a_checkout_specific_instance(tmp_path, monkeypatch):
    exe = tmp_path / "installed" / "prismlauncher.exe"
    exe.parent.mkdir()
    exe.write_bytes(b"")
    app_data = tmp_path / "appdata"
    monkeypatch.delenv(prism.DATA_DIR_ENV, raising=False)
    monkeypatch.setenv("APPDATA", str(app_data))
    custom_instances = tmp_path / "my-instances"
    profile = app_data / "PrismLauncher"
    profile.mkdir(parents=True)
    (profile / "prismlauncher.cfg").write_text(f"[General]\nInstanceDir={custom_instances.as_posix()}\n")
    checkout = tmp_path / "source"

    layout = prism.launcher_layout(exe, checkout / "run-prism", checkout)
    sibling = prism.launcher_layout(exe, checkout / "worktree" / "run-prism", checkout / "worktree")

    assert layout.data_dir == app_data / "PrismLauncher"
    assert layout.instances_dir == custom_instances
    assert layout.game_dir == custom_instances / layout.instance_id / ".minecraft"
    assert layout.instance_id.startswith("rtx-remix-dev-source-")
    assert layout.instance_id != sibling.instance_id
    assert layout.uses_default_data_dir
    assert prism.prism_command(layout) == [str(exe)]


def test_installed_prism_needs_appdata(tmp_path, monkeypatch):
    monkeypatch.delenv(prism.DATA_DIR_ENV, raising=False)
    monkeypatch.delenv("APPDATA", raising=False)
    with pytest.raises(client.LaunchBlocked, match="APPDATA"):
        prism.launcher_layout(tmp_path / "prismlauncher.exe", tmp_path / "run-prism", tmp_path)


def test_installed_prism_honors_its_data_directory_environment(tmp_path, monkeypatch):
    data_dir = tmp_path / "custom-profile"
    monkeypatch.setenv(prism.DATA_DIR_ENV, str(data_dir))
    monkeypatch.delenv("APPDATA", raising=False)

    layout = prism.launcher_layout(tmp_path / "prismlauncher.exe", tmp_path / "run-prism", tmp_path)

    assert layout.data_dir == data_dir


def test_write_instance_pins_versions_and_quotes_spaced_args(tmp_path):
    game = prism.write_instance(tmp_path, java=Path("C:/jdk/bin/javaw.exe"), jvm_args=["-XX:+UseG1GC", "-Dx=C:/a b/c"])
    cfg = (tmp_path / "instances" / "rtx-remix-dev" / "instance.cfg").read_text(encoding="utf-8")
    assert 'JvmArgs=-XX:+UseG1GC "-Dx=C:/a b/c"' in cfg and "OverrideJavaArgs=true" in cfg
    pack = json.loads((tmp_path / "instances" / "rtx-remix-dev" / "mmc-pack.json").read_text(encoding="utf-8"))
    assert {c["uid"]: c["version"] for c in pack["components"]} == {
        "net.minecraft": PROJECT_STATE.minecraft_version,
        "net.fabricmc.fabric-loader": PROJECT_STATE.get("fabric").version,
    }
    assert game == tmp_path / "instances" / "rtx-remix-dev" / ".minecraft" and game.is_dir()


def test_write_instance_writes_the_instance_settings_and_component_shape(tmp_path):
    prism.write_instance(tmp_path, java=Path("C:/jdk/bin/javaw.exe"), jvm_args=["-Xmx12G", "-XX:+UseG1GC"])
    instance = tmp_path / "instances" / "rtx-remix-dev"
    # CRLF like the installer's Prism files.
    assert (instance / "instance.cfg").read_bytes().decode("utf-8").split("\r\n") == [
        "InstanceType=OneSix",
        "name=RTX Remix Dev",
        "OverrideJavaLocation=true",
        "JavaPath=C:/jdk/bin/javaw.exe",
        "OverrideJavaArgs=true",
        "JvmArgs=-XX:+UseG1GC",
        "OverrideMemory=true",
        "MinMemAlloc=512",
        "MaxMemAlloc=12288",
        "LowMemWarning=false",
        "OverrideConsole=true",
        "ShowConsole=false",
        "ShowConsoleOnError=false",
        "",
    ]
    pack = json.loads((instance / "mmc-pack.json").read_text(encoding="utf-8"))
    assert pack["formatVersion"] == 1
    minecraft, loader = pack["components"]
    assert minecraft == {
        "cachedName": "Minecraft",
        "important": True,
        "uid": "net.minecraft",
        "version": PROJECT_STATE.minecraft_version,
    }
    assert loader == {
        "cachedName": "Fabric Loader",
        "uid": "net.fabricmc.fabric-loader",
        "version": PROJECT_STATE.get("fabric").version,
    }


@pytest.mark.parametrize("loader_id", PROJECT_STATE.ids)
def test_write_instance_selects_manifest_loader_for_the_same_minecraft_version(tmp_path, loader_id):
    declared = PROJECT_STATE.get(loader_id)
    prism.write_instance(
        tmp_path,
        java=Path("C:/jdk/bin/javaw.exe"),
        jvm_args=[],
        mod_loader=loader_id,
    )
    pack = json.loads((tmp_path / "instances" / "rtx-remix-dev" / "mmc-pack.json").read_text(encoding="utf-8"))
    minecraft, loader = pack["components"]
    assert minecraft["version"] == PROJECT_STATE.minecraft_version
    assert loader == {
        "cachedName": declared.prism.name,
        "uid": declared.prism.uid,
        "version": declared.version,
    }


def test_write_instance_pins_requested_game_window_size(tmp_path):
    prism.write_instance(
        tmp_path,
        java=Path("C:/jdk/bin/javaw.exe"),
        jvm_args=[],
        window_size=(1920, 1080),
    )
    cfg = (tmp_path / "instances" / "rtx-remix-dev" / "instance.cfg").read_text(encoding="utf-8")
    assert prism_ini_value(cfg, "OverrideWindow") == "true"
    assert prism_ini_value(cfg, "LaunchMaximized") == "false"
    assert prism_ini_value(cfg, "MinecraftWinWidth") == "1920"
    assert prism_ini_value(cfg, "MinecraftWinHeight") == "1080"


def test_write_instance_lets_prism_exit_after_a_failed_launch(tmp_path):
    """Prism exits once no launch runs and no window is open (Application::controllerFinished), and a failed launch
    opens the console window unless ShowConsoleOnError is off under OverrideConsole."""
    prism.write_instance(tmp_path, java=Path("C:/jdk/bin/javaw.exe"), jvm_args=["-Dx=1"])
    cfg = (tmp_path / "instances" / "rtx-remix-dev" / "instance.cfg").read_text(encoding="utf-8")
    assert [prism_ini_value(cfg, key) for key in ("OverrideConsole", "ShowConsole", "ShowConsoleOnError")] == [
        "true",
        "false",
        "false",
    ]


def test_write_instance_always_rewrites_both_files(tmp_path):
    java = Path("C:/jdk/bin/javaw.exe")
    prism.write_instance(tmp_path, java=java, jvm_args=["-Xmx1G", "-Dv=1"])
    instance = tmp_path / "instances" / "rtx-remix-dev"
    (instance / "mmc-pack.json").write_text('{"components": []}', encoding="utf-8")
    prism.write_instance(tmp_path, java=java, jvm_args=["-Xmx2G", "-Dv=2"])
    cfg = (instance / "instance.cfg").read_text(encoding="utf-8").splitlines()
    assert "JvmArgs=-Dv=2" in cfg and "MaxMemAlloc=2048" in cfg
    assert json.loads((instance / "mmc-pack.json").read_text(encoding="utf-8"))["components"]


# What Prism does with a JvmArgs line, so the tests can prove an argument survives it. These are ports of
# launcher/settings/INIFile.cpp (parseOldFileFormat, the reader for a file without ConfigVersion, which is how this
# module writes; the same in 9.4 and develop) and of launcher/Commandline.cpp splitArgs, which differs: develop's is
# prism_split_args and 9.4's is prism_split_args_9_4.
def prism_ini_value(text: str, key: str) -> str:
    def unescape(value: str) -> str:
        out, escaped = "", False
        for char in value:
            if escaped:
                out += {"n": "\n", "t": "\t"}.get(char, char)
                escaped = False
            elif char == "\\":
                escaped = True
            else:
                out += char
        return out

    for raw in text.split("\n"):
        line, found = raw, 0
        while (found := line.find("#", found + 1)) != -1:
            if found > 0 and line[found - 1] == "\\":
                continue
            line = line[: raw.find("#")].strip()
        name, equals, value = line.partition("=")
        if equals and name.strip() == key:
            value = unescape(value.strip())
            if any(c in value for c in ";=,") and len(value) >= 2 and value[0] == value[-1] == '"':
                value = value[1:-1]
            return value
    raise AssertionError(f"no {key} line")


def prism_split_args(args: str) -> list[str]:
    out, current, quote, escaped = [], "", "", False
    for i, char in enumerate(args):
        if escaped:
            current += char
            escaped = False
        elif quote:
            if char == "\\" and i + 1 < len(args) and args[i + 1] in (quote, "\\"):
                escaped = True
            elif char == quote:
                quote = ""
            else:
                current += char
        elif char.isspace():
            if current:
                out.append(current)
                current = ""
        elif char in "\"'":
            quote = char
        else:
            current += char
    if current:
        out.append(current)
    return out


def prism_split_args_9_4(args: str) -> list[str]:
    # Unlike develop: inside quotes a backslash escapes any character, and only a space separates arguments.
    out, current, quote, escaped = [], "", "", False
    for char in args:
        if escaped:
            current += char
            escaped = False
        elif quote:
            if char == "\\":
                escaped = True
            elif char == quote:
                quote = ""
            else:
                current += char
        elif char == " ":
            if current:
                out.append(current)
                current = ""
        elif char in "\"'":
            quote = char
        else:
            current += char
    if current:
        out.append(current)
    return out


@pytest.mark.parametrize("split", [prism_split_args, prism_split_args_9_4], ids=["develop", "9.4"])
@pytest.mark.parametrize(
    "args",
    [
        ["-XX:+UseG1GC", "-Dx=C:/a b/c"],
        # Quoted first and last: Prism would strip the outer pair of quotes from the whole value.
        ["-Dfirst=C:/a b", "-XX:Flag", "-Dlast=C:/c d"],
        ["-Dp=C:\\no\\space", "-Dq=C:\\has space\\trailing\\"],
        ["-Dtag=a#b c", "-Dlist=a,b;c"],
        ['-Dquote=say "hi" now'],
    ],
)
def test_write_instance_jvm_args_survive_prisms_parsing(tmp_path, args, split):
    prism.write_instance(tmp_path, java=Path("C:/jdk/bin/javaw.exe"), jvm_args=args)
    cfg = (tmp_path / "instances" / "rtx-remix-dev" / "instance.cfg").read_text(encoding="utf-8")
    assert split(prism_ini_value(cfg, "JvmArgs")) == args


def prism_heap_args(cfg: str) -> list[str]:
    """The heap arguments MinecraftInstance::javaArguments (launcher/minecraft/MinecraftInstance.cpp) appends after
    JvmArgs: MinMemAlloc and MaxMemAlloc when OverrideMemory is set, else Prism's defaults, 512 and, on a machine with
    6 GiB of RAM or more, 4096 (SysInfo::defaultMaxJvmMem). Prism swaps the two when the minimum is not below the
    maximum."""
    if "OverrideMemory=true" in cfg.splitlines():
        low, high = int(prism_ini_value(cfg, "MinMemAlloc")), int(prism_ini_value(cfg, "MaxMemAlloc"))
    else:
        low, high = 512, 4096
    return [f"-Xms{low}m", f"-Xmx{high}m"] if low < high else [f"-Xms{high}m", f"-Xmx{low}m"]


def launched_jvm_args(tmp_path: Path, jvm_args: list[str]) -> list[str]:
    """The JVM arguments Prism builds from the instance write_instance writes for `jvm_args`."""
    prism.write_instance(tmp_path, java=Path("C:/jdk/bin/javaw.exe"), jvm_args=jvm_args)
    cfg = (tmp_path / "instances" / "rtx-remix-dev" / "instance.cfg").read_text(encoding="utf-8")
    return prism_split_args(prism_ini_value(cfg, "JvmArgs")) + prism_heap_args(cfg)


def last_with_prefix(args: list[str], prefix: str) -> str:
    return next(arg for arg in reversed(args) if arg.startswith(prefix))


@pytest.mark.parametrize(
    ("jvm_args", "heap"),
    [
        (["-Xmx12G", "-XX:SoftMaxHeapSize=8G"], "-Xmx12288m"),
        # A developer's launch.jvmArgs come after the recommended arguments and win.
        (["-Xmx12G", "-Xmx16g"], "-Xmx16384m"),
        (["-Xmx8192m"], "-Xmx8192m"),
        (["-Xmx2097152k"], "-Xmx2048m"),
        # Prism stores whole MiB, so a size between two rounds up.
        (["-Xmx1500k"], "-Xmx2m"),
        (["-Xmx1073741824"], "-Xmx1024m"),
        (["-Xmx1t"], "-Xmx1048576m"),
        (["-Xmx12G", "-XX:MaxHeapSize=6g"], "-Xmx6144m"),
    ],
)
def test_the_launched_jvm_gets_the_last_heap_limit_in_the_jvm_args(tmp_path, jvm_args, heap):
    assert last_with_prefix(launched_jvm_args(tmp_path, jvm_args), "-Xmx") == heap


def test_the_launched_jvm_gets_the_recommended_heap(tmp_path):
    recommended = prism.recommended_jvm_args()
    launched = launched_jvm_args(tmp_path, recommended)
    assert last_with_prefix(launched, "-Xmx") == f"-Xmx{recommended_heap_mib()}m"
    assert {arg for arg in recommended if not arg.startswith(("-Xmx", "-Xms"))} <= set(launched)


def prism_refuses_jvm_args(jvm_args: str) -> bool:
    """Whether Prism refuses to launch with this JvmArgs value: a port of JavaCommon::checkJVMArgs
    (launcher/JavaCommon.cpp), which LaunchController::executeTask calls before every launch. It shows a modal warning
    and fails the launch. The "-XX-MaxHeapSize" spelling is Prism's own."""
    return any(
        (
            "-XX:PermSize=" in jvm_args,
            re.search("-Xm[sx]", jvm_args) is not None,
            "-XX-MaxHeapSize" in jvm_args,
            "-XX:InitialHeapSize" in jvm_args,
            re.search("-version:.*", jvm_args) is not None,
        )
    )


@pytest.mark.parametrize(
    "jvm_args",
    [
        prism.recommended_jvm_args(),
        ["-Xms1g", "-Xmx4g", "-XX:MaxHeapSize=3g", "-XX:InitialHeapSize=1g", "-Dx=1"],
    ],
    ids=["recommended", "every-heap-spelling"],
)
def test_prism_accepts_the_instance_jvm_args_and_still_gets_their_heap(tmp_path, jvm_args):
    launched = launched_jvm_args(tmp_path, jvm_args)
    cfg = (tmp_path / "instances" / "rtx-remix-dev" / "instance.cfg").read_text(encoding="utf-8")
    assert not prism_refuses_jvm_args(prism_ini_value(cfg, "JvmArgs"))
    assert last_with_prefix(launched, "-Xmx") == f"-Xmx{prism._heap_mib(jvm_args, prism.MAX_HEAP_OPTIONS)}m"


@pytest.mark.parametrize("refused", ["-XX:PermSize=256m", "-version:21", "-Dnote=-Xmx"])
def test_write_instance_blocks_an_argument_prism_refuses_to_launch_with(tmp_path, refused):
    with pytest.raises(client.LaunchBlocked, match=re.escape(refused)):
        prism.write_instance(tmp_path, java=Path("C:/jdk/bin/javaw.exe"), jvm_args=["-Xmx2G", refused])
    assert not (tmp_path / "instances").exists()


@pytest.mark.parametrize("control", ["\0", "\n", "\r", "\t"])
def test_write_instance_blocks_control_characters_in_jvm_args(tmp_path, control):
    with pytest.raises(client.LaunchBlocked, match="control character"):
        prism.write_instance(tmp_path, java=Path("C:/jdk/bin/javaw.exe"), jvm_args=[f"-Dvalue=a{control}b"])
    assert not (tmp_path / "instances").exists()


def test_write_instance_blocks_an_initial_heap_without_a_maximum(tmp_path):
    """Prism sets both sizes or neither, so an initial heap alone has nowhere to go."""
    with pytest.raises(client.LaunchBlocked, match="-Xms1g"):
        prism.write_instance(tmp_path, java=Path("C:/jdk/bin/javaw.exe"), jvm_args=["-Xms1g"])
    assert not (tmp_path / "instances").exists()


@pytest.mark.parametrize(
    ("jvm_args", "initial", "maximum"),
    [
        # Prism's own default.
        (["-Xmx8g"], "-Xms512m", "-Xmx8192m"),
        (["-Xms2g", "-Xmx8g"], "-Xms2048m", "-Xmx8192m"),
        (["-Xmx8g", "-XX:InitialHeapSize=1g"], "-Xms1024m", "-Xmx8192m"),
        # Never above the maximum, which Prism would otherwise swap in as the maximum.
        (["-Xmx256m"], "-Xms256m", "-Xmx256m"),
        (["-Xms4g", "-Xmx2g"], "-Xms2048m", "-Xmx2048m"),
    ],
)
def test_the_launched_jvm_keeps_its_maximum_heap_whatever_the_initial_one(tmp_path, jvm_args, initial, maximum):
    launched = launched_jvm_args(tmp_path, jvm_args)
    assert last_with_prefix(launched, "-Xms") == initial
    assert last_with_prefix(launched, "-Xmx") == maximum


def test_write_instance_turns_off_prisms_low_memory_prompt_with_the_heap(tmp_path):
    prism.write_instance(tmp_path, java=Path("C:/jdk/bin/javaw.exe"), jvm_args=["-Xmx12G"])
    cfg = (tmp_path / "instances" / "rtx-remix-dev" / "instance.cfg").read_text(encoding="utf-8")
    assert prism_ini_value(cfg, "LowMemWarning") == "false"


def test_write_instance_leaves_prisms_memory_settings_alone_without_a_heap_limit(tmp_path):
    prism.write_instance(tmp_path, java=Path("C:/jdk/bin/javaw.exe"), jvm_args=["-Dx=1", "-Xss1M"])
    cfg = (tmp_path / "instances" / "rtx-remix-dev" / "instance.cfg").read_text(encoding="utf-8")
    for key in ("OverrideMemory", "MinMemAlloc", "MaxMemAlloc", "LowMemWarning"):
        assert f"{key}=" not in cfg


@pytest.mark.parametrize("bad", ["-Xmx12GB", "-Xmx", "-XX:MaxHeapSize=lots", "-Xms1.5g"])
def test_write_instance_blocks_on_a_heap_size_the_jvm_would_reject(tmp_path, bad):
    with pytest.raises(client.LaunchBlocked, match=re.escape(bad)):
        prism.write_instance(tmp_path, java=Path("C:/jdk/bin/javaw.exe"), jvm_args=["-Xmx12G", bad])
    assert not (tmp_path / "instances").exists()


def test_join_jvm_args_quotes_only_what_needs_it():
    assert prism.join_jvm_args([]) == ""
    assert prism.join_jvm_args(["-Xmx12G", "-XX:+UseZGC"]) == "-Xmx12G -XX:+UseZGC"
    assert prism.join_jvm_args(["-Dx=a b", "-Dt=a\tb"]) == '"-Dx=a b" "-Dt=a\tb"'
    # Prism reads a backslash outside quotes literally, so quoting one is a choice; inside quotes it is doubled because
    # Prism 9.4 reads a backslash there as escaping any character.
    assert prism.join_jvm_args(["-Dp=C:\\x"]) == '"-Dp=C:\\\\x"'


def test_seed_launcher_config_never_overwrites(tmp_path):
    (tmp_path / "prismlauncher.cfg").write_text("Language=de\n", encoding="utf-8")
    prism.seed_launcher_config(tmp_path, Path("C:/jdk/bin/javaw.exe"))
    assert (tmp_path / "prismlauncher.cfg").read_text(encoding="utf-8") == "Language=de\n"


def test_seed_launcher_config_skips_the_first_run_wizard_and_updates(tmp_path):
    data_dir = tmp_path / "run-prism"
    prism.seed_launcher_config(data_dir, Path("C:/jdk/bin/javaw.exe"))
    text = (data_dir / "prismlauncher.cfg").read_bytes().decode("utf-8")
    assert text.split("\r\n") == [
        "[General]",
        "Language=en_US",
        "JavaPath=C:/jdk/bin/javaw.exe",
        "IgnoreJavaWizard=true",
        "UserAskedAboutAutomaticJavaDownload=true",
        "ApplicationTheme=dark",
        "IconTheme=pe_colored",
        "AutoCheckUpdates=false",
        "AutoUpdate=false",
        "",
    ]


def test_recommended_jvm_args_reads_a_single_quoted_assignment(tmp_path, monkeypatch):
    (tmp_path / "packaging").mkdir()
    (tmp_path / "packaging" / "installer.ps1").write_text(
        "# $script:RECOMMENDED_JVM_ARGS = '-Xmx1G'\n$script:RECOMMENDED_JVM_ARGS = '-Dmessage=it''s_here -Xmx12G'\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(util, "PROJECT_DIR", tmp_path)
    assert prism.recommended_jvm_args() == ["-Dmessage=it's_here", "-Xmx12G"]


def test_installer_owned_recommended_jvm_arguments_exist():
    assert any(arg.startswith("-Xmx") for arg in prism.recommended_jvm_args())


def test_running_prism_pids_matches_only_this_data_folder(monkeypatch, tmp_path):
    monkeypatch.setattr(
        prism,
        "_prism_command_lines",
        lambda: [
            (11, f'prismlauncher.exe --dir "{tmp_path}" --launch rtx-remix-dev'),
            (12, "prismlauncher.exe --dir D:\\other\\run-prism"),
        ],
    )
    assert prism.running_prism_pids(tmp_path) == [11]


def test_running_prism_pids_ignores_case_separators_and_sibling_folders(monkeypatch, tmp_path):
    data_dir = tmp_path / "run-prism"
    sibling = str(tmp_path / "run-prism-old")
    monkeypatch.setattr(
        prism,
        "_prism_command_lines",
        lambda: [
            (21, f"PRISMLAUNCHER.EXE --dir {str(data_dir).upper().replace(chr(92), '/')}"),
            (22, f"prismlauncher.exe --dir {sibling}"),
        ],
    )
    assert prism.running_prism_pids(data_dir) == [21]


def test_running_installed_prism_matches_its_executable_without_an_explicit_data_folder(monkeypatch, tmp_path):
    exe = tmp_path / "Prism Launcher" / "prismlauncher.exe"
    monkeypatch.setattr(
        prism,
        "_prism_command_lines",
        lambda: [
            (31, f'"{exe}"'),
            (32, f'"{exe}" --dir D:\\other'),
            (33, f'"{exe.with_name("prismlauncher-old.exe")}"'),
        ],
    )
    assert prism.running_prism_pids(
        tmp_path / "appdata" / "PrismLauncher",
        executable=exe,
        uses_default_data_dir=True,
    ) == [31]


def process_listing(monkeypatch: pytest.MonkeyPatch, *, stdout: str = "", returncode: int = 0) -> list[list[str]]:
    seen: list[list[str]] = []

    def fake_run(args, **_):
        seen.append(list(args))
        return subprocess.CompletedProcess(args, returncode, stdout=stdout, stderr="")

    monkeypatch.setattr(util, "run", fake_run)
    return seen


@pytest.mark.skipif(os.name != "nt", reason="queries Win32_Process")
def test_prism_command_lines_parses_one_or_many_processes(monkeypatch):
    seen = process_listing(monkeypatch, stdout='{"ProcessId":7,"CommandLine":"prismlauncher.exe --dir x"}')
    assert prism._prism_command_lines() == [(7, "prismlauncher.exe --dir x")]
    assert "Name='prismlauncher.exe'" in seen[0][-1]
    process_listing(
        monkeypatch,
        stdout='[{"ProcessId":7,"CommandLine":"a"},{"ProcessId":8,"CommandLine":null},{"ProcessId":9,"CommandLine":"b"}]',
    )
    assert prism._prism_command_lines() == [(7, "a"), (9, "b")]


@pytest.mark.skipif(os.name != "nt", reason="queries Win32_Process")
@pytest.mark.parametrize("kwargs", [{"stdout": ""}, {"stdout": "not json"}, {"stdout": "[]", "returncode": 1}])
def test_prism_command_lines_is_empty_when_the_query_fails(monkeypatch, kwargs):
    process_listing(monkeypatch, **kwargs)
    assert prism._prism_command_lines() == []


@pytest.mark.skipif(os.name != "nt", reason="queries Win32_Process")
@pytest.mark.parametrize("kwargs", [{"stdout": "not json"}, {"stdout": "[]", "returncode": 1}])
def test_required_prism_command_lines_reports_query_failures(monkeypatch, kwargs):
    process_listing(monkeypatch, **kwargs)
    with pytest.raises(RuntimeError, match="Prism process query"):
        prism._prism_command_lines(required=True)


@pytest.mark.skipif(os.name != "nt", reason="queries Win32_Process")
def test_required_prism_command_lines_reports_query_timeout(monkeypatch):
    def timed_out(*args, **kwargs):
        raise RuntimeError("timed out")

    monkeypatch.setattr(util, "run", timed_out)
    with pytest.raises(RuntimeError, match="timed out"):
        prism._prism_command_lines(required=True)


@pytest.mark.parametrize(
    ("release", "version"),
    [
        ('JAVA_VERSION="21.0.5"', (21, 0, 5)),
        ('IMPLEMENTOR="Eclipse Adoptium"\nJAVA_VERSION="17.0.9"\nOS_ARCH="x86_64"', (17, 0, 9)),
        ('JAVA_VERSION="21"', (21,)),
        # Java 8 still names itself 1.8.
        ('JAVA_VERSION="1.8.0_402"', (8, 0)),
        ('JAVA_VERSION="21-ea"', (21,)),
        ("JAVA_RUNTIME_VERSION=21", None),
        ("", None),
    ],
)
def test_java_version_reads_the_release_file(tmp_path, release, version):
    (tmp_path / "release").write_text(release, encoding="utf-8")
    assert prism.java_version(tmp_path) == version


def test_java_version_of_a_folder_without_a_release_file_is_none(tmp_path):
    assert prism.java_version(tmp_path) is None


def test_executable_arch_reads_the_pe_machine(tmp_path):
    x64 = write_jdk(tmp_path / "x64", machine=0x8664)
    arm64 = write_jdk(tmp_path / "arm64", machine=0xAA64)
    i386 = write_jdk(tmp_path / "i386", machine=0x014C)
    assert prism.executable_arch(x64 / "bin" / "javaw.exe") == "win64"
    assert prism.executable_arch(arm64 / "bin" / "javaw.exe") == "arm64"
    assert prism.executable_arch(i386 / "bin" / "javaw.exe") is None
    (tmp_path / "text.exe").write_text("not a program")
    assert prism.executable_arch(tmp_path / "text.exe") is None
    assert prism.executable_arch(tmp_path / "missing.exe") is None


@pytest.fixture
def jdk_places(tmp_path, monkeypatch) -> dict[str, Path]:
    """The folders find_game_jdk searches, in its order, under tmp_path."""
    gradle_home = tmp_path / "gradle-home"
    program_files = tmp_path / "Program Files"
    monkeypatch.setenv("GRADLE_USER_HOME", str(gradle_home))
    monkeypatch.setenv("ProgramFiles", str(program_files))
    return {
        "gradle": gradle_home / "jdks" / "eclipse_adoptium-21-amd64-windows.2",
        "adoptium": program_files / "Eclipse Adoptium" / "jdk-21.0.5.11-hotspot",
        "microsoft": program_files / "Microsoft" / "jdk-21.0.5.11-hotspot",
        "oracle": program_files / "Java" / "jdk-21",
    }


def test_find_game_jdk_uses_java_home_when_it_is_a_jdk_21_for_the_arch(tmp_path, jdk_places):
    java_home = write_jdk(tmp_path / "home")
    write_jdk(jdk_places["gradle"])
    assert prism.find_game_jdk("win64", java_home) == java_home


@pytest.mark.parametrize(
    ("version", "machine"),
    [
        # Prism refuses any major but 21 for Minecraft 1.21.1, newer ones included.
        ("17.0.9", 0x8664),
        ("25.0.1", 0x8664),
        # The bridge and runtime load only into a JVM of their own architecture.
        ("21.0.5", 0xAA64),
    ],
)
def test_find_game_jdk_looks_past_a_java_home_prism_cannot_use(tmp_path, jdk_places, version, machine):
    java_home = write_jdk(tmp_path / "home", version=version, machine=machine)
    found = write_jdk(jdk_places["adoptium"])
    assert prism.find_game_jdk("win64", java_home) == found


def test_find_game_jdk_search_order(jdk_places):
    for place in jdk_places.values():
        write_jdk(place)
    for name in ("gradle", "adoptium", "microsoft", "oracle"):
        assert prism.find_game_jdk("win64", None) == jdk_places[name]
        shutil.rmtree(jdk_places[name])
    assert prism.find_game_jdk("win64", None) is None


def test_find_game_jdk_skips_another_architecture_and_version(jdk_places):
    write_jdk(jdk_places["gradle"], machine=0xAA64)
    write_jdk(jdk_places["adoptium"], version="17.0.9")
    write_jdk(jdk_places["microsoft"])
    assert prism.find_game_jdk("win64", None) == jdk_places["microsoft"]
    assert prism.find_game_jdk("arm64", None) == jdk_places["gradle"]


def test_find_game_jdk_reads_gradles_nested_toolchain_folders(jdk_places):
    # Some Gradle versions unpack the JDK's own top folder inside the toolchain folder.
    nested = write_jdk(jdk_places["gradle"] / "jdk-21.0.5+11")
    assert prism.find_game_jdk("win64", None) == nested


def test_find_game_jdk_takes_gradles_default_home(tmp_path, jdk_places, monkeypatch):
    monkeypatch.delenv("GRADLE_USER_HOME")
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: tmp_path / "me"))
    found = write_jdk(tmp_path / "me" / ".gradle" / "jdks" / "jdk-21.0.5+11")
    write_jdk(jdk_places["adoptium"])
    assert prism.find_game_jdk("win64", None) == found


def test_find_game_jdk_takes_the_newest_in_a_folder(jdk_places):
    folder = jdk_places["adoptium"].parent
    write_jdk(folder / "jdk-21.0.9.10-hotspot", version="21.0.9")
    newest = write_jdk(folder / "jdk-21.0.10.7-hotspot", version="21.0.10")
    write_jdk(folder / "jdk-21.0.2.13-hotspot", version="21.0.2")
    assert prism.find_game_jdk("win64", None) == newest


def test_find_game_jdk_reads_only_jdk_21_folders_under_program_files(jdk_places):
    # A vendor folder holds other products; only its jdk-21 folders are JDK candidates.
    write_jdk(jdk_places["microsoft"].parent / "Edge" / "Application")
    write_jdk(jdk_places["microsoft"].parent / "Edge")
    assert prism.find_game_jdk("win64", None) is None


def test_find_executable_prefers_configured_path(tmp_path):
    exe = tmp_path / "prismlauncher.exe"
    exe.write_bytes(b"")
    assert prism.find_executable({"prism": {"executable": str(exe)}}) == exe


def test_find_executable_search_order(tmp_path, monkeypatch):
    def make(path: Path) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"")
        return path

    configured = make(tmp_path / "configured" / "prismlauncher.exe")
    managed = make(tmp_path / "managed" / "prismlauncher.exe")
    on_path = make(tmp_path / "path" / "prismlauncher.exe")
    local_app_data = tmp_path / "local"
    installed = make(local_app_data / "Programs" / "PrismLauncher" / "prismlauncher.exe")
    monkeypatch.setattr(prism, "MANAGED_PORTABLE_DIR", managed.parent)
    monkeypatch.setattr(prism.shutil, "which", lambda name: str(on_path))
    monkeypatch.setenv("LOCALAPPDATA", str(local_app_data))

    assert prism.find_executable({"prism": {"executable": str(configured)}}) == configured
    assert prism.find_executable({"prism": {"executable": " "}}) == managed
    managed.unlink()
    assert prism.find_executable({}) == on_path
    monkeypatch.setattr(prism.shutil, "which", lambda name: None)
    assert prism.find_executable({}) == installed
    installed.unlink()
    assert prism.find_executable({}) is None


def test_find_executable_never_looks_past_a_configured_path_that_is_not_a_file(tmp_path, monkeypatch):
    managed = tmp_path / "managed" / "prismlauncher.exe"
    managed.parent.mkdir()
    managed.write_bytes(b"")
    monkeypatch.setattr(prism, "MANAGED_PORTABLE_DIR", managed.parent)
    for configured in (tmp_path / "moved" / "prismlauncher.exe", tmp_path):
        with pytest.raises(client.LaunchBlocked, match="prism.executable") as blocked:
            prism.find_executable({"prism": {"executable": str(configured)}})
        assert str(configured) in str(blocked.value)


def test_supports_launch_flags_needs_world_and_profile(monkeypatch):
    process_listing(monkeypatch, stdout="  -a, --profile <profile>\n  -w, --world <world>\n")
    assert prism.supports_launch_flags(Path("p.exe"))
    process_listing(monkeypatch, stdout="  -a, --profile <profile>\n")
    assert not prism.supports_launch_flags(Path("p.exe"))


def test_supports_launch_flags_prints_help_rather_than_showing_a_dialog(monkeypatch):
    monkeypatch.delenv("QT_COMMAND_LINE_PARSER_NO_GUI_MESSAGE_BOXES", raising=False)
    monkeypatch.setenv("RTX_TEST_INHERITED", "kept")
    calls: list[dict] = []

    def fake_run(args, **kwargs):
        calls.append(kwargs)
        return subprocess.CompletedProcess(args, 0, stdout="--profile --world", stderr="")

    monkeypatch.setattr(util, "run", fake_run)
    assert prism.supports_launch_flags(Path("p.exe"))
    (kwargs,) = calls
    assert kwargs["capture"] is True and kwargs["timeout"]
    # util.run replaces the whole environment when given one, so the child keeps ours plus the switch.
    assert kwargs["env"]["QT_COMMAND_LINE_PARSER_NO_GUI_MESSAGE_BOXES"] == "1"
    assert kwargs["env"]["RTX_TEST_INHERITED"] == "kept"
    assert "QT_COMMAND_LINE_PARSER_NO_GUI_MESSAGE_BOXES" not in os.environ


def test_supports_launch_flags_blocks_when_help_times_out(monkeypatch):
    def timed_out(args, **_):
        raise RuntimeError("command timed out after 30s: p.exe --help")

    monkeypatch.setattr(util, "run", timed_out)
    # A Prism that does not answer says nothing about its age.
    with pytest.raises(client.LaunchBlocked, match=re.escape("Prism at p.exe did not answer --help")) as blocked:
        prism.supports_launch_flags(Path("p.exe"))
    assert "too old" not in str(blocked.value)


def test_supports_launch_flags_blocks_when_prism_cannot_run(tmp_path):
    missing = tmp_path / "missing.exe"
    with pytest.raises(client.LaunchBlocked, match=re.escape(f"Prism at {missing} did not answer --help")):
        prism.supports_launch_flags(missing)
