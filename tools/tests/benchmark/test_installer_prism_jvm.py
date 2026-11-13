# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""The launcher JVM configuration packaging/installer.ps1 reconciles, run under Windows PowerShell 5.1 as
packaging/install.bat runs it.

Before every launch, Prism refuses an instance whose JvmArgs hold a heap option such as -Xmx or -Xms: it shows a modal
warning and fails the launch (JavaCommon::checkJVMArgs in Prism's launcher/JavaCommon.cpp, called from
LaunchController::executeTask in launcher/LaunchController.cpp). It gives the game its heap by appending
-Xms<MinMemAlloc>m -Xmx<MaxMemAlloc>m from the instance's memory settings after JvmArgs
(MinecraftInstance::javaArguments in launcher/minecraft/MinecraftInstance.cpp). Prism reads instance.cfg through
QSettings, where only keys before the first section header or under [General] are the instance's settings
(INIFile::loadFile in launcher/settings/INIFile.cpp). The Microsoft launcher accepts -Xmx in a profile's javaArgs."""

import json
import os
import re
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest

from rtx_tools import loader_registry
from rtx_tools.benchmark import util

pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows PowerShell runs the installer")

INSTALLER = util.PROJECT_DIR / "packaging" / "installer.ps1"
PROJECT_STATE = loader_registry.load_registry()
# Prism's global MinMemAlloc, and its global MaxMemAlloc on a machine with 6 GiB of RAM or more
# (Application::Application in launcher/Application.cpp, SysInfo::defaultMaxJvmMem in launcher/SysInfo.cpp).
PRISM_DEFAULT_MIN_MIB = 512
PRISM_DEFAULT_MAX_MIB = 4096
# Loads the installer's constants and functions without its entry point, answers its yes/no prompt with -Answer after
# printing PROMPTED, and runs one step: Sync and SyncMicrosoft reconcile the Prism instance's or the Microsoft launcher
# profile's JVM configuration, JavaPin pins the managed JDK as on ARM64, and the other actions print what an installer
# function returns for the JVM args in PRISM_TEST_JVM_ARGS. That is an environment variable because PowerShell would
# read a leading '-' in a -File argument as a parameter name. SyncMicrosoft finds launcher_profiles.json under APPDATA.
# With PRISM_TEST_NEXT_JVM_ARGS set, the installer is the next one: RECOMMENDED_JVM_ARGS is that value, and the current
# one has joined PREVIOUS_RECOMMENDED_JVM_ARGS, as docs/TUNING.md asks of whoever changes it, unless
# PRISM_TEST_NEXT_UNLISTED is set. ExportCopy copies the instance holding -Cfg to -Dest as Export stages it, and
# RemoveInstance removes the instance from the Prism data directory -Dest as Uninstall does.
HARNESS = r"""
param([string]$Installer, [string]$Loaded, [string]$Cfg, [string]$Answer, [switch]$Unattended,
      [string]$Action, [string]$ManagedJdk, [string]$Dest, [string]$Loader)
$ast = [System.Management.Automation.Language.Parser]::ParseFile($Installer, [ref]$null, [ref]$null)
$entryPoint = $ast.EndBlock.Statements[-1]
if ($entryPoint -isnot [System.Management.Automation.Language.TryStatementAst]) {
    throw 'installer.ps1 no longer ends with its entry point'
}
$start = $ast.ParamBlock.Extent.EndOffset
$body = $ast.Extent.Text.Substring($start, $entryPoint.Extent.StartOffset - $start)
[IO.File]::WriteAllText($Loaded, $body, [Text.UTF8Encoding]::new($true))
. $Loaded
function Confirm-YesNo {
    param([string]$Prompt, [switch]$DefaultNo)
    Write-Host "Confirm-YesNo: $Prompt"
    return $Answer -eq 'yes'
}
if ($env:PRISM_TEST_NEXT_JVM_ARGS) {
    if (-not $env:PRISM_TEST_NEXT_UNLISTED) { $script:PREVIOUS_RECOMMENDED_JVM_ARGS += $script:RECOMMENDED_JVM_ARGS }
    $script:RECOMMENDED_JVM_ARGS = $env:PRISM_TEST_NEXT_JVM_ARGS
}
switch ($Action) {
    'Sync' { Sync-JvmArgs -For Prism -PrismCfg $Cfg -Unattended:$Unattended }
    'SyncMicrosoft' {
        Sync-JvmArgs -For Microsoft -ModLoader $(if ($Loader) { $Loader } else { 'Fabric' }) `
            -Unattended:$Unattended
    }
    'JavaPin' {
        function Test-HostIsArm64 { return $true }
        $script:MANAGED_JDK_DIR = $ManagedJdk
        Set-PrismJavaPin -InstanceCfg $Cfg
    }
    'MemorySettings' {
        ConvertTo-Json -InputObject (Get-PrismMemorySettings -JvmArgs $env:PRISM_TEST_JVM_ARGS) -Compress
    }
    'PrismJvmArgs' { Get-PrismJvmArgs -JvmArgs $env:PRISM_TEST_JVM_ARGS }
    'RefusedJvmArgs' {
        # PRISM_TEST_JVM_ARGS holds a JSON list of JVM args here. Keys differing only in case stay apart.
        $refused = [System.Collections.Specialized.OrderedDictionary]::new([System.StringComparer]::Ordinal)
        foreach ($jvmArgs in (ConvertFrom-Json $env:PRISM_TEST_JVM_ARGS)) {
            $refused[$jvmArgs] = @(Get-PrismRefusedJvmArgs -JvmArgs $jvmArgs)
        }
        ConvertTo-Json -InputObject $refused -Compress
    }
    'PreviousRecommended' { ConvertTo-Json -InputObject @($script:PREVIOUS_RECOMMENDED_JVM_ARGS) -Compress }
    'ExportCopy' { Copy-InstanceForExport -Source (Split-Path -Parent $Cfg) -Dest $Dest }
    'RemoveInstance' { Remove-PrismInstance -DataDir $Dest }
    default { throw "Unknown action: $Action" }
}
"""
PROMPTED = "Confirm-YesNo:"
INSTANCE_NAME = "NVIDIA RTX Remix for Minecraft"
# instance.cfg as Initialize-PrismInstance creates it.
NEW_INSTANCE = f"InstanceType=OneSix\r\nname={INSTANCE_NAME}\r\nJavaVersion=21"
USER_MEMORY = "OverrideMemory=true\r\nMinMemAlloc=1024\r\nMaxMemAlloc=8192\r\n"
# What Prism saves when the instance's Java settings are applied with Memory unticked: Prism's global memory, and no
# MinMemAlloc or MaxMemAlloc of the instance's own (JavaSettingsWidget::saveSettings in
# launcher/ui/widgets/JavaSettingsWidget.cpp).
GLOBAL_MEMORY = "OverrideMemory=false\r\n"
MEMORY_KEYS = ("OverrideMemory", "MinMemAlloc", "MaxMemAlloc")
# The installer's record, beside instance.cfg, of the JvmArgs and memory it wrote to the instance.
RECORD = "rtx-remix-installer.json"
# The JVM's two spellings of each heap size. Prism takes the heap only from its memory settings.
HEAP_OPTIONS = ("-Xmx", "-XX:MaxHeapSize=", "-Xms", "-XX:InitialHeapSize=")

Sync = Callable[..., tuple[str, str]]
SyncMicrosoft = Callable[..., tuple[dict[str, Any], str]]
MemorySettings = Callable[[str], dict[str, str]]
PrismJvmArgs = Callable[[str], str]
RefusedJvmArgs = Callable[[list[str]], dict[str, list[str]]]
PreviousRecommended = Callable[[], list[str]]


def installer_literal(name: str) -> str:
    """The value of the installer's single-quoted `$script:<name>` constant."""
    match = re.search(rf"^\$script:{name}\s*=\s*'([^']+)'", INSTALLER.read_text(encoding="utf-8"), re.M)
    assert match is not None, name
    return match.group(1)


def prism_form(jvm_args: str) -> str:
    """`jvm_args` as a Prism instance's JvmArgs hold them: in their order, without the heap sizes."""
    return " ".join(arg for arg in jvm_args.split() if not arg.startswith(HEAP_OPTIONS))


RECOMMENDED = installer_literal("RECOMMENDED_JVM_ARGS")
RECOMMENDED_FOR_PRISM = prism_form(RECOMMENDED)
MS_PROFILE_KEY = f"fabric-loader-{PROJECT_STATE.minecraft_version}"


def heap_mib(jvm_args: list[str]) -> int:
    """The heap the JVM takes from `jvm_args`: the last -Xmx, in MiB."""
    size = [arg.removeprefix("-Xmx") for arg in jvm_args if arg.startswith("-Xmx")][-1]
    match = re.fullmatch(r"(\d+)([mMgG])", size)
    assert match is not None, size
    return int(match.group(1)) * {"m": 1, "g": 1024}[match.group(2).lower()]


RECOMMENDED_HEAP_MIB = heap_mib(RECOMMENDED.split())
INSTALLER_MEMORY = f"OverrideMemory=true\r\nMinMemAlloc=512\r\nMaxMemAlloc={RECOMMENDED_HEAP_MIB}\r\n"


def prism_refuses_jvm_args(jvm_args: str) -> bool:
    """Whether Prism refuses to launch with this JvmArgs value: a port of JavaCommon::checkJVMArgs
    (launcher/JavaCommon.cpp), which LaunchController::executeTask calls before every launch. It shows a modal warning
    and fails the launch. Qt matches these case-sensitively, and the "-XX-MaxHeapSize" spelling is Prism's own."""
    return any(
        (
            "-XX:PermSize=" in jvm_args,
            re.search("-Xm[sx]", jvm_args) is not None,
            "-XX-MaxHeapSize" in jvm_args,
            "-XX:InitialHeapSize" in jvm_args,
            re.search("-version:.*", jvm_args) is not None,
        )
    )


def ini_sections(text: str) -> dict[str, dict[str, str]]:
    """Each section's settings as QSettings reads them: keys before the first header belong to General."""
    sections: dict[str, dict[str, str]] = {"General": {}}
    section = sections["General"]
    for line in text.splitlines():
        header = re.fullmatch(r"\s*\[([^\]]*)\]\s*", line)
        if header:
            section = sections.setdefault(header.group(1), {})
            continue
        key, separator, value = line.partition("=")
        if separator:
            assert key.strip() not in section, f"{key} is set twice"
            section[key.strip()] = value.strip()
    return sections


def cfg_values(text: str) -> dict[str, str]:
    """The instance's own settings."""
    return ini_sections(text)["General"]


def prism_launch_args(text: str) -> list[str]:
    """The JVM arguments Prism launches the instance with, from its JvmArgs on. Prism first refuses JvmArgs that
    prism_refuses_jvm_args matches, then appends -Xms and -Xmx from the instance's memory settings, or from its global
    ones without OverrideMemory, the smaller first (MinecraftInstance::javaArguments)."""
    cfg = cfg_values(text)
    assert cfg["OverrideJavaArgs"] == "true"
    jvm_args = cfg["JvmArgs"].strip('"')
    assert not prism_refuses_jvm_args(jvm_args), f"Prism refuses to launch with JvmArgs={jvm_args}"
    if cfg.get("OverrideMemory") == "true":
        minimum, maximum = sorted((int(cfg["MinMemAlloc"]), int(cfg["MaxMemAlloc"])))
    else:
        minimum, maximum = PRISM_DEFAULT_MIN_MIB, PRISM_DEFAULT_MAX_MIB
    return [*jvm_args.split(), f"-Xms{minimum}m", f"-Xmx{maximum}m"]


def launch_heap_mib(text: str) -> int:
    """The heap Prism launches the instance with, once it accepts the instance's JvmArgs."""
    return heap_mib(prism_launch_args(text))


def saved_by_prism(jvm_args: str | None, memory: str = "", *, name: str = INSTANCE_NAME, java: str = "") -> str:
    """instance.cfg after Prism has rewritten it: a [General] section, and JvmArgs quoted for its '=' signs. A
    `jvm_args` of None leaves the key out."""
    jvm_line = "" if jvm_args is None else f'JvmArgs="{jvm_args}"\r\n'
    return (
        f"[General]\r\nConfigVersion=1.3\r\nInstanceType=OneSix\r\n{java}JavaVersion=21\r\n"
        f"{jvm_line}{memory}OverrideJavaArgs=true\r\nname={name}\r\n"
    )


def as_installers_wrote(jvm_args: str) -> str:
    """instance.cfg as earlier installers left a new instance with `jvm_args`: unquoted, with no section header. Prism
    refused to launch an instance whose args held -Xmx, so it may never have rewritten the file."""
    return f"{NEW_INSTANCE}\r\nOverrideJavaArgs=true\r\nJvmArgs={jvm_args}"


# The recommended JVM args earlier installers wrote, oldest first, by the date packaging/installer.ps1 changed them.
# Prism refuses the 2026-09-03 set for its -Xmx12G.
PREVIOUS_RECOMMENDED = {
    "2026-04-21": (
        "-XX:MaxRAMPercentage=100.0 -XX:+UseZGC -XX:+ZGenerational -Xss1M "
        "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump"
    ),
    "2026-05-12": (
        "-XX:MaxRAMPercentage=100.0 -XX:SoftMaxHeapSize=8G -XX:+UseZGC -XX:+ZGenerational -XX:ZUncommitDelay=30 -Xss1M "
        "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump"
    ),
    "2026-09-03": (
        "-Xmx12G -XX:SoftMaxHeapSize=8G -XX:+UseZGC -XX:+ZGenerational -XX:ZUncommitDelay=30 -Xss1M "
        "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump"
    ),
}
# Every set of recommended JVM args an installer has written, the current one last. Until this installer, each went into
# the Prism instance as it is, -Xmx included.
INSTALLER_SETS = {**PREVIOUS_RECOMMENDED, "current": RECOMMENDED}
# The forms those sets take in instance.cfg: as the installer wrote them, and after Prism rewrote the file.
INSTALLER_FORMS = {"as-written": as_installers_wrote, "saved-by-prism": saved_by_prism}
# The 2026-09-03 set as a user edited it, which no installer wrote.
USER_EDITED_PREVIOUS = PREVIOUS_RECOMMENDED["2026-09-03"].replace("-Xmx12G", "-Xmx16G")


def next_recommended(*, gc: bool, heap: bool) -> str:
    """A later RECOMMENDED_JVM_ARGS: the current one with another GC list, another -Xmx, or both."""
    args = RECOMMENDED.split()
    if heap:
        args = [f"-Xmx{RECOMMENDED_HEAP_MIB * 2}m" if arg.startswith("-Xmx") else arg for arg in args]
    if gc:
        args = [arg for arg in args if arg.startswith(("-Xmx", "-XX:HeapDumpPath="))]
        args[1:1] = ["-XX:+UseZGC", "-XX:+ZGenerational"]
    return " ".join(args)


NEXT_RECOMMENDED = {
    "gc-list": next_recommended(gc=True, heap=False),
    "heap-only": next_recommended(gc=False, heap=True),
    "gc-list-and-heap": next_recommended(gc=True, heap=True),
}


def own_memory(minimum: int, maximum: int) -> str:
    """Memory as Prism saves it when the user ticks Memory in the instance's Java settings and sets these sizes."""
    return f"OverrideMemory=true\r\nMinMemAlloc={minimum}\r\nMaxMemAlloc={maximum}\r\n"


def memory_of(text: str) -> dict[str, str | None]:
    cfg = cfg_values(text)
    return {key: cfg.get(key) for key in MEMORY_KEYS}


def recorded(folder: Path) -> dict[str, Any]:
    return json.loads((folder / RECORD).read_text(encoding="utf-8"))


def without_lines(text: str, keys: tuple[str, ...]) -> list[str]:
    return [line for line in text.splitlines(keepends=True) if line.partition("=")[0] not in keys]


def refusal_lines(output: str) -> list[str]:
    return [line for line in output.splitlines() if "Prism refuses to launch" in line]


def run_harness(tmp_path: Path, *arguments: str, env: dict[str, str] | None = None) -> str:
    harness = tmp_path / "harness.ps1"
    harness.write_text(HARNESS, encoding="ascii")
    command = [
        "powershell",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        str(harness),
        "-Installer",
        str(INSTALLER),
        "-Loaded",
        str(tmp_path / "installer-functions.ps1"),
        *arguments,
    ]
    result = subprocess.run(command, capture_output=True, encoding="utf-8", errors="replace", check=False, env=env)
    assert result.returncode == 0, result.stdout + result.stderr
    assert not result.stderr
    return result.stdout


@pytest.fixture
def sync(tmp_path: Path) -> Sync:
    cfg = tmp_path / "instance.cfg"

    def run(
        text: str,
        *,
        answer: str = "no",
        unattended: bool = False,
        action: str = "Sync",
        managed_jdk: Path | None = None,
        next_jvm_args: str | None = None,
        unlisted: bool = False,
    ) -> tuple[str, str]:
        cfg.write_bytes(text.encode("utf-8"))
        arguments = ["-Cfg", str(cfg), "-Answer", answer, "-Action", action]
        if managed_jdk is not None:
            arguments += ["-ManagedJdk", str(managed_jdk)]
        if unattended:
            arguments.append("-Unattended")
        env = {key: value for key, value in os.environ.items() if not key.startswith("PRISM_TEST_NEXT_")}
        if next_jvm_args is not None:
            env["PRISM_TEST_NEXT_JVM_ARGS"] = next_jvm_args
        if unlisted:
            env["PRISM_TEST_NEXT_UNLISTED"] = "1"
        output = run_harness(tmp_path, *arguments, env=env)
        written = cfg.read_bytes()
        assert not written.startswith(b"\xef\xbb\xbf"), "instance.cfg gained a BOM"
        return written.decode("utf-8"), output

    return run


@pytest.fixture
def sync_microsoft(tmp_path: Path) -> SyncMicrosoft:
    app_data = tmp_path / "AppData"
    profiles = app_data / ".minecraft" / "launcher_profiles.json"

    def run(
        java_args: str | None,
        *,
        answer: str = "no",
        unattended: bool = False,
        next_jvm_args: str | None = None,
        profile_name: str = "Fabric",
    ) -> tuple[dict[str, Any], str]:
        """The Fabric profile in launcher_profiles.json after the reconcile, from one whose javaArgs are `java_args`,
        or that has none when it is None."""
        profile = {"lastVersionId": MS_PROFILE_KEY, "name": profile_name, "type": "custom"}
        if java_args is not None:
            profile["javaArgs"] = java_args
        profiles.parent.mkdir(parents=True, exist_ok=True)
        profiles.write_text(json.dumps({"profiles": {MS_PROFILE_KEY: profile}, "version": 3}), encoding="utf-8")
        arguments = ["-Answer", answer, "-Action", "SyncMicrosoft"]
        if unattended:
            arguments.append("-Unattended")
        env = {key: value for key, value in os.environ.items() if key != "PRISM_TEST_NEXT_JVM_ARGS"}
        env["APPDATA"] = str(app_data)
        if next_jvm_args is not None:
            env["PRISM_TEST_NEXT_JVM_ARGS"] = next_jvm_args
        output = run_harness(tmp_path, *arguments, env=env)
        return json.loads(profiles.read_text(encoding="utf-8"))["profiles"][MS_PROFILE_KEY], output

    return run


@pytest.fixture
def memory_settings(tmp_path: Path) -> MemorySettings:
    def run(jvm_args: str) -> dict[str, str]:
        env = {**os.environ, "PRISM_TEST_JVM_ARGS": jvm_args}
        return json.loads(run_harness(tmp_path, "-Action", "MemorySettings", env=env))

    return run


@pytest.fixture
def prism_jvm_args(tmp_path: Path) -> PrismJvmArgs:
    def run(jvm_args: str) -> str:
        env = {**os.environ, "PRISM_TEST_JVM_ARGS": jvm_args}
        return run_harness(tmp_path, "-Action", "PrismJvmArgs", env=env).strip()

    return run


@pytest.fixture
def refused_jvm_args(tmp_path: Path) -> RefusedJvmArgs:
    def run(cases: list[str]) -> dict[str, list[str]]:
        env = {**os.environ, "PRISM_TEST_JVM_ARGS": json.dumps(cases)}
        return json.loads(run_harness(tmp_path, "-Action", "RefusedJvmArgs", env=env))

    return run


@pytest.fixture
def previous_recommended(tmp_path: Path) -> PreviousRecommended:
    def run() -> list[str]:
        return json.loads(run_harness(tmp_path, "-Action", "PreviousRecommended"))

    return run


@pytest.mark.parametrize("unattended", [True, False], ids=["unattended", "interactive"])
def test_new_instance_launches_with_the_recommended_heap(sync: Sync, unattended: bool):
    text, output = sync(NEW_INSTANCE, unattended=unattended)

    cfg = cfg_values(text)
    assert cfg["OverrideJavaArgs"] == "true"
    assert cfg["JvmArgs"] == RECOMMENDED_FOR_PRISM
    assert not prism_refuses_jvm_args(cfg["JvmArgs"])
    assert cfg["OverrideMemory"] == "true"
    assert cfg["MinMemAlloc"] == "512"
    assert cfg["MaxMemAlloc"] == str(RECOMMENDED_HEAP_MIB)
    assert launch_heap_mib(text) == RECOMMENDED_HEAP_MIB
    assert PROMPTED not in output


def test_matching_args_get_the_recommended_heap_on_upgrade(sync: Sync):
    text, _ = sync(saved_by_prism(RECOMMENDED_FOR_PRISM), unattended=True)

    assert f'JvmArgs="{RECOMMENDED_FOR_PRISM}"' in text.splitlines()
    cfg = cfg_values(text)
    assert cfg["MinMemAlloc"] == "512"
    assert cfg["MaxMemAlloc"] == str(RECOMMENDED_HEAP_MIB)
    assert launch_heap_mib(text) == RECOMMENDED_HEAP_MIB


def test_matching_args_and_memory_are_left_alone(sync: Sync):
    configured, _ = sync(NEW_INSTANCE, unattended=True)

    text, output = sync(configured)

    assert text == configured
    assert "memory already matches" in output


@pytest.mark.parametrize("unattended", [True, False], ids=["unattended", "interactive"])
@pytest.mark.parametrize("form", list(INSTALLER_FORMS))
@pytest.mark.parametrize("written", list(INSTALLER_SETS.values()), ids=list(INSTALLER_SETS))
def test_args_an_earlier_installer_wrote_are_replaced_without_asking(
    sync: Sync, tmp_path: Path, written: str, form: str, unattended: bool
):
    original = INSTALLER_FORMS[form](written)

    text, output = sync(original, unattended=unattended)

    assert PROMPTED not in output
    assert cfg_values(text)["JvmArgs"] == RECOMMENDED_FOR_PRISM
    assert launch_heap_mib(text) == RECOMMENDED_HEAP_MIB
    assert without_lines(text, ("JvmArgs", *MEMORY_KEYS)) == without_lines(original, ("JvmArgs",))
    # The log says what Prism refused in the replaced args, if anything, and what they were, and a backup keeps them.
    refused = " ".join(arg for arg in written.split() if prism_refuses_jvm_args(arg))
    reasons = [line[line.index("Prism refuses") :] for line in output.splitlines() if "Prism refuses" in line]
    assert reasons == ([f"Prism refuses to launch with their {refused}."] if refused else [])
    assert f"Previous JVM args, kept in instance.cfg.bak: {written}" in output
    assert (tmp_path / "instance.cfg.bak").read_bytes() == original.encode()


@pytest.mark.parametrize(
    ("memory", "heap"),
    [(USER_MEMORY, 8192), (GLOBAL_MEMORY, PRISM_DEFAULT_MAX_MIB)],
    ids=["own-memory", "global-memory"],
)
def test_replacing_args_an_earlier_installer_wrote_keeps_memory_chosen_in_prism(sync: Sync, memory: str, heap: int):
    original = saved_by_prism(RECOMMENDED, memory)

    text, output = sync(original, unattended=True)

    assert cfg_values(text)["JvmArgs"] == RECOMMENDED_FOR_PRISM
    assert without_lines(text, ("JvmArgs",)) == without_lines(original, ("JvmArgs",))
    assert launch_heap_mib(text) == heap
    assert "Keeping" in output


@pytest.mark.parametrize("unattended", [True, False], ids=["unattended", "declined"])
def test_args_a_user_edited_from_an_earlier_installers_are_the_users(sync: Sync, unattended: bool):
    original = saved_by_prism(USER_EDITED_PREVIOUS)

    text, output = sync(original, unattended=unattended)

    assert text == original
    assert (PROMPTED in output) is not unattended
    assert ("keeping existing (unattended)" in output) is unattended
    refusal = refusal_lines(output)
    assert refusal, output
    assert all("-Xmx16G" in line for line in refusal)


@pytest.mark.parametrize("unattended", [True, False], ids=["unattended", "interactive"])
@pytest.mark.parametrize("next_args", list(NEXT_RECOMMENDED.values()), ids=list(NEXT_RECOMMENDED))
def test_the_next_installer_updates_an_instance_this_one_set_up_without_asking(
    sync: Sync, tmp_path: Path, next_args: str, unattended: bool
):
    configured, _ = sync(NEW_INSTANCE, unattended=True)

    text, output = sync(configured, unattended=unattended, next_jvm_args=next_args)

    assert PROMPTED not in output
    cfg = cfg_values(text)
    assert cfg["JvmArgs"] == prism_form(next_args)
    next_heap_mib = heap_mib(next_args.split())
    assert {key: cfg[key] for key in MEMORY_KEYS} == {
        "OverrideMemory": "true",
        "MinMemAlloc": "512",
        "MaxMemAlloc": str(next_heap_mib),
    }
    assert launch_heap_mib(text) == next_heap_mib
    assert without_lines(text, ("JvmArgs", *MEMORY_KEYS)) == without_lines(configured, ("JvmArgs", *MEMORY_KEYS))
    assert (tmp_path / "instance.cfg.bak").read_bytes() == configured.encode()
    assert recorded(tmp_path) == {"formatVersion": 1, "JvmArgs": cfg["JvmArgs"], **memory_of(text)}


@pytest.mark.parametrize("memory", [USER_MEMORY, GLOBAL_MEMORY], ids=["own-memory", "global-memory"])
@pytest.mark.parametrize("next_args", list(NEXT_RECOMMENDED.values()), ids=list(NEXT_RECOMMENDED))
def test_the_next_installer_keeps_memory_chosen_in_prism(sync: Sync, next_args: str, memory: str):
    original = saved_by_prism(RECOMMENDED_FOR_PRISM, memory)

    text, output = sync(original, unattended=True, next_jvm_args=next_args)

    cfg = cfg_values(text)
    assert cfg["JvmArgs"].strip('"') == prism_form(next_args)
    original_cfg = cfg_values(original)
    assert {key: cfg.get(key) for key in MEMORY_KEYS} == {key: original_cfg.get(key) for key in MEMORY_KEYS}
    assert "Keeping" in output


def test_the_installer_records_what_it_writes_to_a_new_instance(sync: Sync, tmp_path: Path):
    text, _ = sync(NEW_INSTANCE, unattended=True)

    assert recorded(tmp_path) == {
        "formatVersion": 1,
        "JvmArgs": RECOMMENDED_FOR_PRISM,
        "OverrideMemory": "true",
        "MinMemAlloc": "512",
        "MaxMemAlloc": str(RECOMMENDED_HEAP_MIB),
    }
    assert memory_of(text) == {key: recorded(tmp_path)[key] for key in MEMORY_KEYS}


@pytest.mark.parametrize(
    ("minimum", "maximum"),
    [(512, 12288), (1024, 8192)],
    ids=["maximum-only-12-gb", "both-sizes"],
)
def test_memory_a_user_chose_after_the_installer_is_kept_on_every_run(sync: Sync, minimum: int, maximum: int):
    # Ticking Memory in Prism shows 512 MiB as the minimum, so setting only the maximum to 12 GB saves 512/12288: the
    # memory the 2026-09-03 set asks for, which no installer wrote.
    sync(NEW_INSTANCE, unattended=True)
    chosen = saved_by_prism(RECOMMENDED_FOR_PRISM, own_memory(minimum, maximum))

    first, output = sync(chosen, unattended=True)
    second, _ = sync(first, unattended=True)

    assert first == chosen
    assert second == chosen
    assert "Keeping the memory set on the Prism instance" in output


def test_a_user_who_goes_back_to_an_earlier_heap_keeps_it(sync: Sync):
    next_args = NEXT_RECOMMENDED["heap-only"]
    configured, _ = sync(NEW_INSTANCE, unattended=True)
    moved, _ = sync(configured, unattended=True, next_jvm_args=next_args)
    assert cfg_values(moved)["MaxMemAlloc"] == str(heap_mib(next_args.split()))
    # The user then picks the earlier heap again in Prism.
    chosen = saved_by_prism(prism_form(next_args), own_memory(512, RECOMMENDED_HEAP_MIB))

    first, output = sync(chosen, unattended=True, next_jvm_args=next_args)
    second, _ = sync(first, unattended=True, next_jvm_args=next_args)

    assert first == chosen
    assert second == chosen
    assert "Keeping the memory set on the Prism instance" in output


@pytest.mark.parametrize(
    ("memory", "next_args"),
    [(own_memory(512, 12288), None), (INSTALLER_MEMORY, NEXT_RECOMMENDED["heap-only"])],
    ids=["maximum-only-12-gb", "an-earlier-default"],
)
def test_memory_without_a_record_is_the_users(sync: Sync, memory: str, next_args: str | None):
    original = saved_by_prism(RECOMMENDED_FOR_PRISM, memory)

    text, output = sync(original, unattended=True, next_jvm_args=next_args)

    assert memory_of(text) == memory_of(original)
    assert "Keeping the memory set on the Prism instance" in output


# Records the installer cannot read. Apart from what makes each unreadable, they record the memory the installer writes.
UNREADABLE_RECORDS = {
    "not-json": "{JvmArgs=",
    "not-an-object": "[]",
    "not-strings": json.dumps(
        {"formatVersion": 1, "OverrideMemory": True, "MinMemAlloc": 512, "MaxMemAlloc": RECOMMENDED_HEAP_MIB}
    ),
    "format-version-2": json.dumps({"formatVersion": 2, **memory_of(INSTALLER_MEMORY)}),
    "no-format-version": json.dumps(memory_of(INSTALLER_MEMORY)),
}


@pytest.mark.parametrize("record", list(UNREADABLE_RECORDS.values()), ids=list(UNREADABLE_RECORDS))
def test_an_unreadable_record_leaves_memory_to_the_user(sync: Sync, tmp_path: Path, record: str):
    (tmp_path / RECORD).write_text(record, encoding="utf-8")
    original = saved_by_prism(RECOMMENDED_FOR_PRISM, INSTALLER_MEMORY)

    text, output = sync(original, unattended=True, next_jvm_args=NEXT_RECOMMENDED["heap-only"])

    assert memory_of(text) == memory_of(original)
    assert "Keeping the memory set on the Prism instance" in output
    assert output.count(f"Could not read {RECORD}") == 1


@pytest.mark.parametrize("record", list(UNREADABLE_RECORDS.values()), ids=list(UNREADABLE_RECORDS))
def test_an_unreadable_record_still_lets_the_installer_set_memory_where_none_is_set(
    sync: Sync, tmp_path: Path, record: str
):
    (tmp_path / RECORD).write_text(record, encoding="utf-8")

    text, _ = sync(saved_by_prism(RECOMMENDED_FOR_PRISM), unattended=True)

    assert memory_of(text) == memory_of(INSTALLER_MEMORY)
    # The installer's write replaces the unreadable record.
    assert recorded(tmp_path) == {"formatVersion": 1, **memory_of(INSTALLER_MEMORY)}


@pytest.mark.parametrize("record", [True, False], ids=["recorded", "not-recorded"])
def test_the_record_marks_args_the_installer_wrote_that_the_list_misses(sync: Sync, tmp_path: Path, record: bool):
    next_args = NEXT_RECOMMENDED["gc-list"]
    configured, _ = sync(NEW_INSTANCE, unattended=True)
    if not record:
        (tmp_path / RECORD).unlink()

    text, output = sync(configured, next_jvm_args=next_args, unlisted=True)

    # Without its record, the next installer cannot tell its predecessor's args from the user's, so it asks.
    assert (PROMPTED in output) is not record
    assert cfg_values(text)["JvmArgs"] == (prism_form(next_args) if record else RECOMMENDED_FOR_PRISM)


def test_export_takes_the_record_with_instance_cfg_and_leaves_the_backup(tmp_path: Path):
    instance = tmp_path / "instances" / INSTANCE_NAME
    (instance / ".minecraft").mkdir(parents=True)
    for name in ("instance.cfg", "instance.cfg.bak", RECORD):
        (instance / name).write_text(name, encoding="utf-8")
    staged = tmp_path / "stage"

    run_harness(tmp_path, "-Cfg", str(instance / "instance.cfg"), "-Action", "ExportCopy", "-Dest", str(staged))

    assert sorted(path.name for path in staged.iterdir() if path.is_file()) == sorted(["instance.cfg", RECORD])


def test_removing_the_instance_removes_its_record(tmp_path: Path):
    data_dir = tmp_path / "PrismLauncher"
    instance = data_dir / "instances" / INSTANCE_NAME
    instance.mkdir(parents=True)
    for name in ("instance.cfg", RECORD):
        (instance / name).write_text(name, encoding="utf-8")

    run_harness(tmp_path, "-Action", "RemoveInstance", "-Dest", str(data_dir))

    assert not instance.exists()


def test_memory_set_in_prism_is_kept(sync: Sync):
    original = saved_by_prism(RECOMMENDED_FOR_PRISM, USER_MEMORY)

    text, output = sync(original, answer="yes")

    assert text == original
    assert "Keeping the memory set on the Prism instance" in output


def test_global_memory_chosen_in_prism_is_kept(sync: Sync):
    original = saved_by_prism(RECOMMENDED_FOR_PRISM, GLOBAL_MEMORY)

    text, output = sync(original, answer="yes")

    assert text == original
    assert "Keeping Prism's global memory" in output
    assert launch_heap_mib(text) == PRISM_DEFAULT_MAX_MIB


@pytest.mark.parametrize("unattended", [True, False], ids=["unattended", "interactive"])
@pytest.mark.parametrize("jvm_args", [None, ""], ids=["absent-args", "empty-args"])
@pytest.mark.parametrize("memory", [USER_MEMORY, GLOBAL_MEMORY], ids=["own-memory", "global-memory"])
def test_args_written_beside_memory_chosen_in_prism_keep_it(
    sync: Sync, memory: str, jvm_args: str | None, unattended: bool
):
    original = saved_by_prism(jvm_args, memory)

    text, output = sync(original, answer="yes", unattended=unattended)

    cfg = cfg_values(text)
    assert cfg["OverrideJavaArgs"] == "true"
    assert cfg["JvmArgs"] == RECOMMENDED_FOR_PRISM
    original_cfg = cfg_values(original)
    assert {key: cfg.get(key) for key in MEMORY_KEYS} == {key: original_cfg.get(key) for key in MEMORY_KEYS}
    assert "Keeping" in output


@pytest.mark.parametrize("unattended", [True, False], ids=["unattended", "declined"])
def test_differing_args_keep_their_memory(sync: Sync, unattended: bool):
    original = saved_by_prism("-Xmx6G -XX:+UseG1GC")

    text, _ = sync(original, unattended=unattended)

    assert text == original


@pytest.mark.parametrize("unattended", [True, False], ids=["unattended", "declined"])
@pytest.mark.parametrize("refused", ["-Xmx6G", "-XX:InitialHeapSize=1g"])
def test_kept_args_name_what_prism_refuses_to_launch_with(sync: Sync, refused: str, unattended: bool):
    original = saved_by_prism(f"-XX:+UseG1GC {refused} -Dnote=kept")

    text, output = sync(original, unattended=unattended)

    assert text == original
    assert (PROMPTED in output) is not unattended
    refusal = refusal_lines(output)
    assert refusal, output
    assert all(refused in line and "-XX:+UseG1GC" not in line and "-Dnote" not in line for line in refusal)


@pytest.mark.parametrize("unattended", [True, False], ids=["unattended", "declined"])
def test_kept_args_prism_accepts_are_not_called_refused(sync: Sync, unattended: bool):
    # Prism refuses -Xmx but not -XX:MaxHeapSize, which the -Xmx it appends after JvmArgs overrides.
    original = saved_by_prism("-XX:+UseZGC -XX:MaxHeapSize=6g -Xss1M")

    text, output = sync(original, unattended=unattended)

    assert text == original
    assert (PROMPTED in output) is not unattended
    assert not refusal_lines(output)


def test_prompt_offers_the_args_the_instance_would_get(sync: Sync):
    _, output = sync(saved_by_prism("-Xmx6G -XX:+UseG1GC"))

    assert PROMPTED in output
    assert RECOMMENDED_FOR_PRISM in output
    assert RECOMMENDED not in output


def test_replacing_differing_args_replaces_their_memory(sync: Sync):
    text, output = sync(saved_by_prism("-Xmx6G -XX:+UseG1GC", USER_MEMORY), answer="yes")

    assert cfg_values(text)["JvmArgs"] == RECOMMENDED_FOR_PRISM
    assert launch_heap_mib(text) == RECOMMENDED_HEAP_MIB
    assert f"{RECOMMENDED_HEAP_MIB} MiB" in output


def test_memory_goes_into_general_ahead_of_a_trailing_section(sync: Sync):
    original = saved_by_prism(RECOMMENDED_FOR_PRISM) + "[UI]\r\nColumns=name\r\n"

    text, _ = sync(original, unattended=True)

    sections = ini_sections(text)
    assert launch_heap_mib(text) == RECOMMENDED_HEAP_MIB
    assert sections["UI"] == ini_sections(original)["UI"]
    assert without_lines(text, MEMORY_KEYS) == original.splitlines(keepends=True)


def test_memory_keys_in_another_section_are_not_the_instances(sync: Sync):
    original = saved_by_prism(RECOMMENDED_FOR_PRISM) + "[UI]\r\n" + USER_MEMORY

    text, _ = sync(original, unattended=True)

    sections = ini_sections(text)
    assert launch_heap_mib(text) == RECOMMENDED_HEAP_MIB
    assert sections["UI"] == ini_sections(original)["UI"]


@pytest.mark.parametrize("newline", ["\r\n", "\n"], ids=["crlf", "lf"])
def test_upgrade_keeps_unicode_and_changes_only_the_memory_keys(sync: Sync, newline: str):
    original = saved_by_prism(
        RECOMMENDED_FOR_PRISM,
        name="RTX Remix für Zoë – 光",
        java="JavaPath=D:/Spiele/Zoë/RtxRemixMinecraft/jdk-21/bin/javaw.exe\r\nOverrideJavaLocation=true\r\n",
    ).replace("\r\n", newline)

    text, _ = sync(original, unattended=True)

    assert without_lines(text, MEMORY_KEYS) == original.splitlines(keepends=True)
    added = [line for line in text.splitlines(keepends=True) if line not in original.splitlines(keepends=True)]
    assert added == INSTALLER_MEMORY.replace("\r\n", newline).splitlines(keepends=True)
    assert launch_heap_mib(text) == RECOMMENDED_HEAP_MIB


def test_arm64_java_pin_keeps_unicode(sync: Sync, tmp_path: Path):
    managed_jdk = tmp_path / "Zoë" / "jdk-21"
    javaw = managed_jdk / "bin" / "javaw.exe"
    javaw.parent.mkdir(parents=True)
    javaw.touch()
    original = saved_by_prism(RECOMMENDED_FOR_PRISM, INSTALLER_MEMORY, name="RTX Remix für Zoë – 光")

    text, _ = sync(original, action="JavaPin", managed_jdk=managed_jdk)

    cfg = cfg_values(text)
    assert cfg["OverrideJavaLocation"] == "true"
    assert cfg["JavaPath"] == javaw.as_posix()
    assert without_lines(text, ("OverrideJavaLocation", "JavaPath")) == original.splitlines(keepends=True)


@pytest.mark.parametrize("unattended", [True, False], ids=["unattended", "interactive"])
def test_new_microsoft_launcher_profile_gets_the_full_recommended_args(sync_microsoft: SyncMicrosoft, unattended: bool):
    profile, output = sync_microsoft(None, unattended=unattended)

    assert profile["javaArgs"] == RECOMMENDED
    assert PROMPTED not in output


def test_microsoft_launcher_profile_with_the_full_recommended_args_is_left_alone(sync_microsoft: SyncMicrosoft):
    profile, output = sync_microsoft(RECOMMENDED)

    assert profile["javaArgs"] == RECOMMENDED
    assert "already match" in output
    assert PROMPTED not in output


def test_microsoft_forge_profile_gets_jvm_args_without_touching_fabric(tmp_path: Path):
    app_data = tmp_path / "AppData"
    profiles_path = app_data / ".minecraft" / "launcher_profiles.json"
    mc_version = PROJECT_STATE.minecraft_version
    forge_release = PROJECT_STATE.get("forge").version
    forge_key = "existing-custom-forge-profile"
    forge_version = f"{mc_version}-forge-{forge_release}"
    profiles = {
        "profiles": {
            MS_PROFILE_KEY: {
                "lastVersionId": (
                    f"fabric-loader-{PROJECT_STATE.get('fabric').version}-{PROJECT_STATE.minecraft_version}"
                ),
                "javaArgs": "-Dfabric=kept",
            },
            forge_key: {"lastVersionId": forge_version, "name": "Forge"},
        },
        "version": 3,
    }
    profiles_path.parent.mkdir(parents=True)
    profiles_path.write_text(json.dumps(profiles), encoding="utf-8")
    env = {**os.environ, "APPDATA": str(app_data)}

    run_harness(tmp_path, "-Action", "SyncMicrosoft", "-Loader", "Forge", "-Unattended", env=env)

    written = json.loads(profiles_path.read_text(encoding="utf-8"))["profiles"]
    assert written[forge_key]["javaArgs"] == RECOMMENDED
    assert written[MS_PROFILE_KEY]["javaArgs"] == "-Dfabric=kept"


def test_replacing_microsoft_launcher_args_gives_the_full_recommended_args(sync_microsoft: SyncMicrosoft):
    profile, output = sync_microsoft("-Xmx6G -XX:+UseG1GC", answer="yes")

    assert PROMPTED in output
    assert RECOMMENDED in output
    assert profile["javaArgs"] == RECOMMENDED
    assert not refusal_lines(output)


@pytest.mark.parametrize("unattended", [True, False], ids=["unattended", "interactive"])
@pytest.mark.parametrize("previous", list(PREVIOUS_RECOMMENDED.values()), ids=list(PREVIOUS_RECOMMENDED))
def test_microsoft_launcher_args_an_earlier_installer_wrote_are_replaced_without_asking(
    sync_microsoft: SyncMicrosoft, tmp_path: Path, previous: str, unattended: bool
):
    profile, output = sync_microsoft(previous, unattended=unattended)

    assert PROMPTED not in output
    assert profile["javaArgs"] == RECOMMENDED
    assert f"Previous JVM args, kept in launcher_profiles.json.bak: {previous}" in output
    backup = json.loads(
        (tmp_path / "AppData" / ".minecraft" / "launcher_profiles.json.bak").read_text(encoding="utf-8")
    )
    assert backup["profiles"][MS_PROFILE_KEY]["javaArgs"] == previous


def test_unattended_microsoft_migration_preserves_utf8_profile_metadata(sync_microsoft: SyncMicrosoft):
    name = "Minecraft プレイヤー"

    profile, _ = sync_microsoft(
        next(iter(PREVIOUS_RECOMMENDED.values())),
        unattended=True,
        profile_name=name,
    )

    assert profile["name"] == name
    assert profile["javaArgs"] == RECOMMENDED


@pytest.mark.parametrize("unattended", [True, False], ids=["unattended", "interactive"])
def test_the_next_installer_updates_the_microsoft_launcher_profile_without_asking(
    sync_microsoft: SyncMicrosoft, unattended: bool
):
    next_args = NEXT_RECOMMENDED["gc-list-and-heap"]

    profile, output = sync_microsoft(RECOMMENDED, unattended=unattended, next_jvm_args=next_args)

    assert PROMPTED not in output
    assert profile["javaArgs"] == next_args


@pytest.mark.parametrize("unattended", [True, False], ids=["unattended", "declined"])
def test_microsoft_launcher_args_a_user_edited_are_the_users(sync_microsoft: SyncMicrosoft, unattended: bool):
    profile, output = sync_microsoft(USER_EDITED_PREVIOUS, unattended=unattended)

    assert profile["javaArgs"] == USER_EDITED_PREVIOUS
    assert (PROMPTED in output) is not unattended
    assert ("keeping existing (unattended)" in output) is unattended


def test_prism_jvm_args_leave_out_only_the_heap_sizes(prism_jvm_args: PrismJvmArgs):
    jvm_args = (
        "-Xms1G -Dx=1 -Xmx4G -XX:+UseG1GC -XX:MaxHeapSize=6G -Xss1M -XX:InitialHeapSize=2G -XX:SoftMaxHeapSize=3G"
    )

    assert prism_jvm_args(jvm_args) == "-Dx=1 -XX:+UseG1GC -Xss1M -XX:SoftMaxHeapSize=3G"
    assert prism_jvm_args(RECOMMENDED) == RECOMMENDED_FOR_PRISM


def test_installer_finds_what_prism_refuses(refused_jvm_args: RefusedJvmArgs):
    cases = [
        RECOMMENDED,
        RECOMMENDED_FOR_PRISM,
        "-Xmx6G",
        "-Xms1G",
        "-XX:MaxHeapSize=6g",
        "-XX-MaxHeapSize=6g",
        "-XX:InitialHeapSize=1g",
        "-XX:PermSize=256m",
        "-version:21",
        "-Dnote=-Xms",
        "-xmx6g",
        "-XX:+UseG1GC -Xss1M -XX:SoftMaxHeapSize=3G",
        "-Xmx6G -XX:+UseG1GC -version:21",
    ]

    refused = refused_jvm_args(cases)

    # No pattern Prism checks spans whitespace, so it refuses exactly the args in which one matches on its own.
    assert all(
        any(prism_refuses_jvm_args(arg) for arg in case.split()) == prism_refuses_jvm_args(case) for case in cases
    )
    assert refused == {case: [arg for arg in case.split() if prism_refuses_jvm_args(arg)] for case in cases}


def recommended_jvm_args_in_git_history() -> list[str] | None:
    """Every value RECOMMENDED_JVM_ARGS has had in the git history of packaging/installer.ps1, or None when that history
    is not available: without git or a repository, or in a shallow clone, as CI checkouts usually are."""

    def git(*arguments: str) -> str | None:
        try:
            result = subprocess.run(["git", "-C", str(util.PROJECT_DIR), *arguments], capture_output=True, check=False)
        except OSError:
            return None
        return result.stdout.decode("utf-8") if result.returncode == 0 else None

    if (git("rev-parse", "--is-shallow-repository") or "").strip() != "false":
        return None
    commits = git("log", "--format=%H", "-G", "script:RECOMMENDED_JVM_ARGS *=", "--", "packaging/installer.ps1")
    if commits is None:
        return None
    values: list[str] = []
    for commit in commits.split():
        # "--" stops git from also trying the argument as a file name, which fails on a long path.
        text = git("show", f"{commit}:packaging/installer.ps1", "--")
        assert text is not None, f"git show {commit}:packaging/installer.ps1 failed"
        values += re.findall(r"^\$script:RECOMMENDED_JVM_ARGS\s*=\s*'([^']+)'", text, re.M)
    return values


def test_every_recommended_jvm_args_in_git_history_is_the_installers(previous_recommended: PreviousRecommended):
    history = recommended_jvm_args_in_git_history()
    if history is None:
        pytest.skip("the git history of packaging/installer.ps1 is not available, as in a shallow clone")
    assert history, "git log found no change to RECOMMENDED_JVM_ARGS"

    listed = previous_recommended()

    missing = [value for value in dict.fromkeys(history) if value != RECOMMENDED and value not in listed]
    assert not missing, "When RECOMMENDED_JVM_ARGS changes, add the outgoing value to PREVIOUS_RECOMMENDED_JVM_ARGS"


def test_recommended_args_give_the_developer_tools_memory(memory_settings: MemorySettings):
    # The developer tools' Prism instance writes the same memory for these arguments.
    assert memory_settings(RECOMMENDED) == {"OverrideMemory": "true", "MinMemAlloc": "512", "MaxMemAlloc": "2048"}


@pytest.mark.parametrize(
    ("jvm_args", "expected"),
    [
        ("-Xms1G -Xmx4G", ("1024", "4096")),
        ("-XX:InitialHeapSize=256m -XX:MaxHeapSize=3g", ("256", "3072")),
        ("-Xmx4G -XX:MaxHeapSize=6G -Xms1G -XX:InitialHeapSize=2G", ("2048", "6144")),
        ("-Xms8G -Xmx4G", ("4096", "4096")),
        ("-Xmx3000000", ("3", "3")),
    ],
    ids=["xms-xmx", "xx-sizes", "last-wins", "min-clamped", "bytes-rounded-up"],
)
def test_memory_follows_the_heap_the_jvm_args_set(
    memory_settings: MemorySettings, jvm_args: str, expected: tuple[str, str]
):
    minimum, maximum = expected
    assert memory_settings(jvm_args) == {"OverrideMemory": "true", "MinMemAlloc": minimum, "MaxMemAlloc": maximum}


def test_args_without_a_heap_leave_memory_to_prism(memory_settings: MemorySettings):
    assert memory_settings("-XX:+UseG1GC") == {}
