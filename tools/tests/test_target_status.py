# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import json
import subprocess
from pathlib import Path
from unittest import mock

from rtx_tools import target_status
from rtx_tools.loader_registry import load_registry


def version() -> dict:
    return {
        "supportTier": "primary",
        "defaultLoader": "fabric",
        "minecraft": {
            "version": "1.21.1",
            "module": "versions/1.21.1/minecraft",
            "versionRange": "[1.21.1,1.21.2)",
            "javaTarget": 21,
            "dataVersion": 3955,
            "hostGraphicsBackend": "opengl",
        },
        "loaders": {
            "fabric": {
                "displayName": "Fabric",
                "module": "versions/1.21.1/fabric",
                "projectPath": ":versions:1.21.1:fabric",
                "provider": "fabric",
                "supportTier": "active",
                "gradlePlugin": {"id": "fabric-loom", "version": "1.9.2"},
                "developmentArtifact": {
                    "directory": "versions/1.21.1/fabric/build/libs",
                    "pattern": "*.jar",
                },
                "installedJar": "rtx-remix-renderer-fabric.jar",
                "builtinResourcePack": "fabric",
                "gradle": {
                    "build": ":versions:1.21.1:fabric:build",
                    "test": ":versions:1.21.1:fabric:test",
                    "package": ":versions:1.21.1:fabric:package",
                },
                "prism": {
                    "uid": "net.fabricmc.fabric-loader",
                    "name": "Fabric Loader",
                    "version": "0.16.5",
                },
                "loader": {
                    "coordinate": "net.fabricmc:fabric-loader",
                    "version": "0.16.5",
                    "versionRange": ">=0.16.5",
                },
                "metadataFile": "fabric.mod.json",
                "archiveBaseName": "rtx-remix-renderer",
                "packageSourceTask": "remapJar",
            }
        },
    }


def manifest() -> dict:
    return {
        "schema": "multi-version/v1",
        "defaultMinecraftVersion": "1.21.1",
        "mod": {
            "id": "rtx-remix-renderer",
            "group": "com.nvidia.rtxremix",
            "version": "0.1.1",
            "displayName": "RTX Remix for Minecraft",
        },
        "versions": {"1.21.1": version()},
    }


def git(repo: Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=repo, check=True, capture_output=True)


def test_target_status_is_honest_about_unrun_and_unverified_states(tmp_path):
    (tmp_path / "project-state.json").write_text(json.dumps(manifest()), encoding="utf-8")
    artifact = tmp_path / "versions/1.21.1/fabric/build/libs/example.jar"
    artifact.parent.mkdir(parents=True)
    artifact.write_bytes(b"jar")
    git(tmp_path, "init", "-b", "main")
    git(tmp_path, "config", "user.name", "Test")
    git(tmp_path, "config", "user.email", "test@example.invalid")
    git(tmp_path, "add", "project-state.json")
    git(tmp_path, "commit", "-m", "manifest")

    value = target_status.report(tmp_path)

    assert value["branch"] == "main"
    assert value["target"]["minecraftVersion"] == "1.21.1"
    assert value["catalog"] == "valid"
    assert value["hygiene"]["state"] == "passed"
    assert value["checkout"]["dirty"] is True
    assert value["automatedContracts"]["state"] == "not-run"
    assert value["loaders"][0]["artifact"] == "versions/1.21.1/fabric/build/libs/example.jar"
    assert value["loaders"][0]["artifactState"] == "present-unverified"
    assert value["loaders"][0]["build"]["state"] == "not-run"
    assert value["loaders"][0]["live"]["state"] == "required"


def test_target_status_runs_catalog_tasks_for_build(tmp_path):
    (tmp_path / "project-state.json").write_text(json.dumps(manifest()), encoding="utf-8")
    registry = load_registry(tmp_path)

    with mock.patch.object(subprocess, "run") as run:
        run.return_value.returncode = 0
        value = target_status.run_validation(tmp_path, registry, "build")

    assert run.call_args.args[0] == [
        str(tmp_path / "gradlew.bat"),
        ":versions:1.21.1:fabric:build",
        ":versions:1.21.1:fabric:test",
        "-PmcVersion=1.21.1",
        "-Ploader=fabric",
        "--console=plain",
    ]
    assert run.call_args.kwargs == {"cwd": tmp_path, "check": False}
    assert value["build"]["fabric"]["state"] == "passed"
    assert value["automatedContracts"] is None


def test_target_status_reports_automated_contract_command_result(tmp_path):
    (tmp_path / "project-state.json").write_text(json.dumps(manifest()), encoding="utf-8")
    registry = load_registry(tmp_path)

    with mock.patch.object(subprocess, "run") as run:
        run.return_value.returncode = 7
        value = target_status.run_validation(tmp_path, registry, "automated-contracts")

    assert value["automatedContracts"] == {
        "state": "failed",
        "command": [str(tmp_path / "tools/rtx.bat"), "check", "--tests"],
        "exitCode": 7,
    }
    assert value["build"] == {}


def test_target_status_accepts_only_matching_clean_benchmark_evidence(tmp_path):
    (tmp_path / "project-state.json").write_text(json.dumps(manifest()), encoding="utf-8")
    git(tmp_path, "init", "-b", "main")
    git(tmp_path, "config", "user.name", "Test")
    git(tmp_path, "config", "user.email", "test@example.invalid")
    git(tmp_path, "add", "project-state.json")
    git(tmp_path, "commit", "-m", "manifest")
    commit = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=tmp_path, check=True, capture_output=True, text=True
    ).stdout.strip()
    (tmp_path / ".git/info/exclude").write_text("tools/results/\n", encoding="utf-8")
    result = tmp_path / "tools/results/20261107_010203/manifest.json"
    result.parent.mkdir(parents=True)
    result.write_text(
        json.dumps(
            {
                "id": "20261107_010203",
                "git": {"commit": commit, "dirty": False},
                "config": {
                    "minecraftVersion": "1.21.1",
                    "modLoader": "fabric",
                    "world": "RTXDemo",
                    "worldVersion": "v0.11.2",
                    "camera": "benchmark",
                    "cameraPath": "benchmark",
                },
                "outcome": {"status": "passed"},
            }
        ),
        encoding="utf-8",
    )

    live = target_status.report(tmp_path)["loaders"][0]["live"]

    assert live == {
        "state": "passed",
        "kind": "automated-live-benchmark",
        "attestation": "execution-only",
        "visualReview": "not-attested",
        "run": "20261107_010203",
        "commit": commit,
        "minecraftVersion": "1.21.1",
        "loader": "fabric",
        "fixture": {
            "world": "RTXDemo",
            "worldVersion": "v0.11.2",
            "camera": "benchmark",
            "cameraPath": "benchmark",
        },
    }
