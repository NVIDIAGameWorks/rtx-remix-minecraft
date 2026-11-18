# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Validation and artifact status for one selected Minecraft target."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path
from typing import Any

from .benchmark import util
from .loader_registry import LoaderRegistry, load_registry


def _git(project_dir: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=project_dir,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


def _run(command: list[str], project_dir: Path) -> dict[str, Any]:
    result = subprocess.run(command, cwd=project_dir, check=False)
    return {
        "state": "passed" if result.returncode == 0 else "failed",
        "command": command,
        "exitCode": result.returncode,
    }


def run_validation(project_dir: Path, registry: LoaderRegistry, level: str) -> dict[str, Any]:
    result: dict[str, Any] = {"automatedContracts": None, "build": {}}
    if level == "automated-contracts":
        command = [str(project_dir / "tools" / "rtx.bat"), "check", "--tests"]
        result["automatedContracts"] = _run(command, project_dir)
    elif level == "build":
        for loader in registry.loaders:
            command = [
                str(project_dir / "gradlew.bat"),
                loader.build_task,
                loader.test_task,
                *registry.gradle_arguments(loader.id),
                "--console=plain",
            ]
            result["build"][loader.id] = _run(command, project_dir)
    return result


def _benchmark_evidence(
    project_dir: Path, commit: str, minecraft_version: str, loader_id: str
) -> dict[str, Any] | None:
    results_dir = project_dir / "tools" / "results"
    if project_dir == util.PROJECT_DIR:
        results_dir = util.RESULTS_DIR
    candidates: list[tuple[str, dict[str, Any]]] = []
    if not results_dir.is_dir():
        return None
    for path in results_dir.glob("*/manifest.json"):
        try:
            value = json.loads(path.read_text(encoding="utf-8-sig"))
        except OSError, ValueError:
            continue
        if not isinstance(value, dict):
            continue
        git = value.get("git")
        config = value.get("config")
        outcome = value.get("outcome")
        if (
            isinstance(git, dict)
            and git.get("commit") == commit
            and git.get("dirty") is False
            and isinstance(config, dict)
            and str(config.get("minecraftVersion") or "") == minecraft_version
            and str(config.get("modLoader") or "").lower() == loader_id
            and isinstance(outcome, dict)
            and outcome.get("status") == "passed"
        ):
            candidates.append((str(value.get("id") or path.parent.name), value))
    if not candidates:
        return None
    run_id, manifest = max(candidates, key=lambda item: item[0])
    config = manifest["config"]
    return {
        "state": "passed",
        "kind": "automated-live-benchmark",
        "attestation": "execution-only",
        "visualReview": "not-attested",
        "run": run_id,
        "commit": commit,
        "minecraftVersion": minecraft_version,
        "loader": loader_id,
        "fixture": {
            "world": config.get("world"),
            "worldVersion": config.get("worldVersion"),
            "camera": config.get("camera"),
            "cameraPath": config.get("cameraPath"),
        },
    }


def report(
    project_dir: Path,
    validation: dict[str, Any] | None = None,
    minecraft_version: str | None = None,
) -> dict[str, Any]:
    registry = load_registry(project_dir, minecraft_version)
    branch_result = _git(project_dir, "branch", "--show-current")
    branch = branch_result.stdout.strip() or "(detached)"
    commit = _git(project_dir, "rev-parse", "HEAD").stdout.strip()
    diff_check = _git(project_dir, "diff", "--check")
    status = _git(project_dir, "status", "--porcelain=v1", "--untracked-files=all")
    checkout_dirty = bool(status.stdout.strip())
    validation = validation or {"automatedContracts": None, "build": {}}
    build_results = validation.get("build")
    build_results = build_results if isinstance(build_results, dict) else {}
    contracts_value = validation.get("automatedContracts")
    automated_contracts = (
        contracts_value
        if isinstance(contracts_value, dict)
        else {"state": "not-run", "required": True, "command": [r"tools\rtx.bat", "check", "--tests"]}
    )
    loaders = []
    for loader in registry.loaders:
        directory = loader.project_path(project_dir, loader.development_jar_dir)
        artifacts = sorted(
            (
                path
                for path in directory.glob(loader.development_jar_pattern)
                if path.is_file() and "-sources" not in path.name.lower()
            ),
            key=lambda path: (path.stat().st_mtime_ns, path.name),
        )
        newest = artifacts[-1] if artifacts else None
        loaders.append(
            {
                "id": loader.id,
                "displayName": loader.display_name,
                "module": loader.module,
                "artifact": str(newest.relative_to(project_dir)).replace("\\", "/") if newest else None,
                "artifactState": "present-unverified" if newest else "missing",
                "build": build_results.get(loader.id)
                or {
                    "state": "not-run",
                    "required": True,
                    "command": [
                        loader.build_task,
                        loader.test_task,
                        *registry.gradle_arguments(loader.id),
                    ],
                },
                "live": (
                    None
                    if checkout_dirty
                    else _benchmark_evidence(project_dir, commit, registry.minecraft_version, loader.id)
                )
                or {
                    "state": "required",
                    "required": True,
                    "kind": "automated-live-benchmark",
                    "attestation": "execution-only",
                    "visualReview": "not-attested",
                    "commit": commit,
                    "minecraftVersion": registry.minecraft_version,
                    "loader": loader.id,
                },
            }
        )
    return {
        "schema": "target-status/v1",
        "branch": branch,
        "commit": commit,
        "catalog": "valid",
        "target": {
            "minecraftVersion": registry.minecraft_version,
            "supportTier": registry.support_tier,
            "defaultLoader": registry.default_id,
        },
        "checkout": {"dirty": checkout_dirty},
        "hygiene": {
            "state": "passed" if diff_check.returncode == 0 else "failed",
            "check": "git diff --check",
            "detail": diff_check.stdout.strip() or diff_check.stderr.strip(),
        },
        "automatedContracts": automated_contracts,
        "loaders": loaders,
    }


def print_report(value: dict[str, Any]) -> None:
    print(f"Target status: Minecraft {value['target']['minecraftVersion']} ({value['branch']})")
    print(f"  catalog:  {value['catalog']}")
    print(f"  hygiene:  {value['hygiene']['state']}")
    print(f"  checkout: {'dirty' if value['checkout']['dirty'] else 'clean'}")
    print(f"  contracts: {value['automatedContracts']['state']}")
    for loader in value["loaders"]:
        artifact = loader["artifact"] or "no development artifact"
        print(
            f"  {loader['id']:<9} build {loader['build']['state']}; "
            f"artifact {loader['artifactState']} ({artifact}); live {loader['live']['state']}"
        )
