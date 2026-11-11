# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import json
import unittest

from rtx_tools import loader_registry
from rtx_tools.benchmark import util

REPO_ROOT = util.PROJECT_DIR
PROJECT_STATE = loader_registry.load_registry()
FABRIC_MODULE = REPO_ROOT / PROJECT_STATE.get("fabric").module
FABRIC_BUILD = FABRIC_MODULE / "build.gradle"
FABRIC_RESOURCES = FABRIC_MODULE / "src" / "main" / "resources"
MINECRAFT_MODULE = (
    REPO_ROOT
    / json.loads((REPO_ROOT / "project-state.json").read_text(encoding="utf-8"))["versions"][
        PROJECT_STATE.minecraft_version
    ]["minecraft"]["module"]
)


class VersionConsistencyTests(unittest.TestCase):
    def test_gradle_consumes_the_project_manifest(self):
        settings = (REPO_ROOT / "settings.gradle").read_text(encoding="utf-8")
        shared = (REPO_ROOT / "gradle" / "loader-module.gradle").read_text(encoding="utf-8")
        forge_build = (REPO_ROOT / PROJECT_STATE.get("forge").module / "build.gradle").read_text(encoding="utf-8")
        self.assertIn("project-state.json", settings)
        self.assertIn("state.minecraft.version", shared)
        self.assertIn("loader.loader.version", shared)
        self.assertIn('"net.minecraftforge:forge:${minecraftVersion}-${forgeVersion}"', forge_build)

    def test_installer_consumes_the_project_manifest_for_loader_pins(self):
        installer = (REPO_ROOT / "packaging" / "installer.ps1").read_text(encoding="utf-8")
        self.assertIn("project-state.json", installer)
        self.assertIn("Get-ProjectStatePath", installer)
        self.assertIn("Get-LoaderCatalog", installer)
        for stale_constant in ("FABRIC_LOADER_VER", "FABRIC_API_VERSION", "FORGE_VERSION"):
            self.assertNotIn(f"$script:{stale_constant}", installer)

    def test_runtime_code_does_not_hardcode_mod_version(self):
        benchmark_controller = (
            MINECRAFT_MODULE
            / "main"
            / "java"
            / "com"
            / "nvidia"
            / "rtxremix"
            / "minecraft"
            / "benchmark"
            / "BenchmarkController.java"
        ).read_text(encoding="utf-8")
        mod_entrypoint = (
            FABRIC_MODULE / "src" / "main" / "java" / "com" / "nvidia" / "rtxremix" / "fabric" / "RtxRemixMod.java"
        ).read_text(encoding="utf-8")
        self.assertNotRegex(benchmark_controller, r'modVersion",\s*"\d+\.\d+\.\d+"')
        self.assertNotRegex(mod_entrypoint, r"Renderer v\d+\.\d+\.\d+")

    def test_fabric_metadata_matches_repository_contract(self):
        metadata = json.loads((FABRIC_RESOURCES / "fabric.mod.json").read_text(encoding="utf-8"))
        fabric_loader = PROJECT_STATE.get("fabric")
        assert fabric_loader.api_version is not None

        self.assertEqual("Apache-2.0", metadata["license"])
        self.assertEqual("${minecraftVersion}", metadata["depends"]["minecraft"])
        shared = (REPO_ROOT / "gradle" / "loader-module.gradle").read_text(encoding="utf-8")
        self.assertIn("minecraftVersion: minecraftVersion", shared)
        self.assertEqual("${loaderVersionRange}", metadata["depends"]["fabricloader"])
        self.assertEqual("${apiVersionRange}", metadata["depends"]["fabric-api"])
        self.assertIn("loaderVersionRange: loader.loader.versionRange", shared)
        self.assertIn("apiVersionRange: loader.api?.versionRange", shared)
        self.assertNotIn("fabric-api", metadata.get("suggests", {}))

        icon = metadata.get("icon")
        if icon:
            self.assertTrue((FABRIC_RESOURCES / icon).is_file())

        sources = metadata.get("contact", {}).get("sources", "")
        self.assertNotIn("rtxremix-java", sources)

    def test_readme_license_matches_repository_license(self):
        license_text = (REPO_ROOT / "LICENSE").read_text(encoding="utf-8")
        readme = (REPO_ROOT / "README.md").read_text(encoding="utf-8")

        self.assertIn("Apache License", license_text)
        self.assertIn("Apache-2.0", readme)
        self.assertNotRegex(readme, r"(?m)^MIT\s+[\u2014-]\s+See LICENSE file$")


if __name__ == "__main__":
    unittest.main()
