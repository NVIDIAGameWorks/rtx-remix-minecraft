# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import json
import re
import subprocess
import unittest
import xml.etree.ElementTree as ElementTree

from rtx_tools import install
from rtx_tools.benchmark import util

REPO_ROOT = util.PROJECT_DIR


class BuildConfigurationTests(unittest.TestCase):
    def test_build_tool_versions_are_reproducible(self):
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        wrapper = (REPO_ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text(encoding="utf-8")

        loom = re.search(r"id 'fabric-loom' version '([^']+)'", build)
        if loom is None:
            self.fail("Fabric Loom version declaration is missing")
        self.assertNotIn("SNAPSHOT", loom.group(1).upper())

        checksum = re.search(r"(?m)^distributionSha256Sum=([0-9a-f]{64})$", wrapper)
        self.assertIsNotNone(checksum)

    def test_packaging_uses_an_explicit_distributable_archive_task(self):
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")

        self.assertNotIn("startParameter.taskNames", build)
        self.assertIn("tasks.register('packageJar', Zip)", build)
        self.assertIn("tasks.register('verifyPackageJar')", build)
        self.assertIn("destinationDirectory.set(layout.buildDirectory.dir('distributions'))", build)

    def test_release_staging_uses_only_the_verified_package_archive(self):
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        staging = re.search(
            r"(?ms)^def stageReleaseArchiveTask = tasks.register\('stageReleaseArchive', Sync\) \{(.+?)^\}", build
        )
        self.assertIsNotNone(staging, "release staging must remove stale files through Sync")
        assert staging is not None
        body = staging.group(1)
        self.assertIn("dependsOn 'package'", body)
        self.assertIn("into layout.buildDirectory.dir('release')", body)
        self.assertIn("from(packageJarArchive)", body)
        self.assertIn("into 'mods'", body)
        # The JAR carries the runtime textures; a second copy beside it would only grow the archive.
        self.assertNotIn("runtimeTexturesDir", body)
        self.assertNotIn("textures", body)
        for name in ("install.bat", "installer.ps1", "migrate_resource_packs.ps1", "INSTALLER_REFERENCE.md"):
            self.assertIn(f"'packaging/{name}'", body)
        self.assertIn("from('packaging/INSTALL.md')", body)
        self.assertIn("rename { 'README.md' }", body)
        self.assertIn("from 'LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md'", body)
        self.assertIn("from('packaging/licenses')", body)
        self.assertIn("into 'packaging/licenses'", body)
        # The only resource pack is the zipped art pack; the retired baseline pack must not come back.
        self.assertRegex(body, r"from\(artPackArchiveTask\) \{\s*into 'resourcepacks'\s*\}")
        self.assertEqual(body.count("resourcepacks"), 1)
        self.assertNotIn("LabPBR", body)
        self.assertNotIn("*.jar", body)

    def test_release_art_pack_is_the_pack_development_launches_stage(self):
        # The installer offers the art pack the archive carries; development launches stage the one tools/config.json
        # pins. Players and developers must see the same pack.
        manifest = ElementTree.parse(REPO_ROOT / "packman" / "art-pack.packman.xml").getroot()
        packages = manifest.findall("./dependency/package")
        self.assertEqual(len(packages), 1)
        dependency = manifest.find("./dependency")
        assert dependency is not None
        self.assertEqual(dependency.get("linkPath"), "../remix-runtime/art-pack")
        tools_config = json.loads((REPO_ROOT / "tools" / "config.json").read_text(encoding="utf-8"))
        self.assertEqual(packages[0].get("name"), tools_config["resourcePack"]["package"])
        self.assertEqual(packages[0].get("version"), tools_config["resourcePack"]["version"])

    def test_release_art_pack_is_a_reproducible_zip_named_for_the_installer(self):
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        archive = re.search(r"(?ms)^def artPackArchiveTask = tasks.register\('artPackArchive', Zip\) \{(.+?)^\}", build)
        assert archive is not None
        body = archive.group(1)
        # installer.ps1's ART_PACK_PATTERN finds the pack by this name, in the archive and in the game folder.
        self.assertIn('archiveFileName.set("rtx-remix-art-pack-${artPackVersion}.zip")', body)
        installer = (REPO_ROOT / "packaging" / "installer.ps1").read_text(encoding="utf-8")
        self.assertIn(r"$script:ART_PACK_PATTERN = '^rtx-remix-art-pack-\d+\.\d+\.\d+\.zip$'", installer)
        self.assertIn("exclude '.packman*'", body)
        self.assertIn("preserveFileTimestamps = false", body)
        self.assertIn("reproducibleFileOrder = true", body)

    def test_distributable_jar_carries_the_runtime_textures(self):
        # A launcher such as CurseForge installs the JAR alone, with no installer to copy textures beside it, so the
        # JAR must carry every runtime texture for NativeExtractor to write into the game directory.
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        package = re.search(r"(?ms)^def packageJarTask = tasks.register\('packageJar', Zip\) \{(.+?)^\}", build)
        verify = re.search(r"(?ms)^def verifyPackageJarTask = tasks.register\('verifyPackageJar'\) \{(.+?)^\}", build)
        assert package is not None and verify is not None
        self.assertIn("dependsOn fetchRuntimeTexturesTask", package.group(1))
        self.assertRegex(
            package.group(1),
            r'from\(runtimeTexturesDir\) \{\s*into\("native/textures"\)\s*include runtimeTextureFiles\s*\}',
        )
        self.assertIn('required.add("native/textures/${name}")', verify.group(1))
        self.assertNotIn("must not contain packaging textures", build)

    def test_release_archive_zips_the_staged_installable_tree(self):
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        archive = re.search(r"(?ms)^tasks.register\('releaseArchive', Zip\) \{(.+?)^\}", build)
        self.assertIsNotNone(archive)
        assert archive is not None
        body = archive.group(1)
        self.assertIn("group = 'build'", body)
        self.assertIn("from(stageReleaseArchiveTask)", body)
        self.assertIn("archiveVersion.set(resolvedBuildVersion)", body)
        self.assertIn("destinationDirectory.set(layout.buildDirectory.dir('distributions'))", body)
        self.assertIn("preserveFileTimestamps = false", body)
        self.assertIn("reproducibleFileOrder = true", body)

    def test_python_guard_pattern_rejects_python_payloads_only(self):
        # verifyPackageJar's pattern is the whole defence against a runtime pin
        # that brings the interpreter or USD bindings back, so its behaviour is
        # pinned here: the nv-usd layouts that shipped, nested and renamed
        # interpreters, and nothing legitimate. java.util.regex and re agree on
        # every construct the pattern uses.
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        declaration = re.search(r"(?m)^\s*def pythonEntry = ~/(.+)/\s*$", build)
        if declaration is None:
            self.fail("verifyPackageJar's pythonEntry pattern is missing")
        pattern = re.compile(declaration.group(1))

        rejected = [
            "native/remix-sdk-win64/python310.dll",
            "native/remix-sdk-arm64/python311.dll",
            "native/remix-sdk-win64/usd_python.dll",
            "native/remix-sdk-win64/python/",
            "native/remix-sdk-win64/python/pxr/Ar/_ar.pyd",
            "native/remix-sdk-win64/python/pxr/Ar/__pycache__/__init__.cpython-311.pyc",
            "native/remix-sdk-win64/lib/python310.dll",
            "native/remix-sdk-win64/libpython3.11.dll",
            "native/remix-sdk-win64/python311_d.dll",
            "native/remix-sdk-win64/boost_python310-vc142-mt-x64-1_76.dll",
            "native/remix-sdk-win64/python3.11/os.py",
            "native/remix-sdk-win64/lib/python3.11/site-packages/foo.pyc",
            "native/remix-sdk-win64/PYTHON311.DLL",
        ]
        accepted = [
            "native/remix-sdk-win64/d3d9.dll",
            "native/remix-sdk-win64/usd_usd.dll",
            "native/remix-sdk-win64/tbb12.dll",
            "native/remix-sdk-win64/usd/plugins/RemixParticleSystem.dll",
            "native/remix-sdk-win64/usd/usd/resources/codegenTemplates/__init__.py",
            "native/remix-sdk-win64/usd/usd/resources/plugInfo.json",
            "native/rtx.conf",
            "python311.dll",
        ]
        for name in rejected:
            with self.subTest(name=name):
                self.assertIsNotNone(pattern.fullmatch(name), "the Python guard must reject this entry")
        for name in accepted:
            with self.subTest(name=name):
                self.assertIsNone(pattern.fullmatch(name), "the Python guard must accept this entry")

    def test_runtime_textures_link_on_a_path_git_has_never_tracked(self):
        # A checkout of a commit that tracks files under a Packman junction writes and deletes them in the shared
        # package cache, as packaging/textures/ did for every commit before the textures moved to Packman.
        manifest = ElementTree.parse(REPO_ROOT / "packman" / "textures.packman.xml")
        dependency = manifest.find("dependency")
        assert dependency is not None
        # The path as written, not resolved: where git meets the link matters, not which package it reaches.
        self.assertEqual(dependency.attrib["linkPath"], "../remix-runtime/textures")
        self.assertIn("/remix-runtime/", (REPO_ROOT / ".gitignore").read_text(encoding="utf-8").splitlines())
        history = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "log", "--all", "--format=%h", "-1", "--", "remix-runtime"],
            capture_output=True,
            text=True,
            check=True,
        )
        self.assertEqual(history.stdout.strip(), "", "remix-runtime/ must never hold tracked files")
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        self.assertIn("def runtimeTexturesDir = 'remix-runtime/textures'", build)
        self.assertNotRegex(build, r"from\(?\s*'packaging/textures'")

    def test_runtime_texture_check_names_the_files_the_mod_and_installer_load(self):
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        listed = re.search(r"(?m)^def runtimeTextureFiles = \[([^\]]*)\]", build)
        self.assertIsNotNone(listed, "build.gradle must list the runtime textures it verifies")
        assert listed is not None
        checked = set(re.findall(r"'([^']+)'", listed.group(1)))
        loaded = set()
        for source in (REPO_ROOT / "src" / "main" / "java").rglob("*.java"):
            loaded.update(re.findall(r'"textures/([^"/]+\.dds)"', source.read_text(encoding="utf-8")))
        installer = (REPO_ROOT / "packaging" / "installer.ps1").read_text(encoding="utf-8")
        installed = set(re.findall(r"'textures\\([^'\\]+\.dds)'", installer))
        self.assertTrue(checked)
        self.assertEqual(checked, loaded)
        self.assertEqual(checked, installed)
        self.assertEqual(checked, set(install.RUNTIME_TEXTURE_FILES))

    def test_runtime_staging_uses_incremental_copy_tasks(self):
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")

        self.assertIn("tasks.register('syncRuntimeSdk', Sync)", build)
        self.assertIn("tasks.register('syncRuntimeTextures', Sync)", build)
        self.assertNotIn("tasks.register('syncDevelopmentResourcePack', Sync)", build)

    def test_runtime_config_copy_declares_only_its_own_output(self):
        # A Copy into 'run' declares the whole run directory as its output, so Gradle
        # fingerprints every file under it and fails on any file a still-running
        # client holds open. The task must own exactly run/rtx.conf.
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")

        self.assertNotIn("tasks.register('copyRuntimeConfig', Copy)", build)
        self.assertIn("tasks.register('copyRuntimeConfig')", build)
        self.assertIn("outputs.file(target)", build)
        self.assertIn("file('run/rtx.conf')", build)

    def test_dev_instance_fabric_api_comes_from_the_pinned_dependency(self):
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        task = re.search(r"(?ms)^tasks.register\('stageFabricApi', Copy\) \{(.+?)^\}", build)
        self.assertIsNotNone(task)
        assert task is not None
        self.assertIn("into layout.buildDirectory.dir('dev')", task.group(1))
        self.assertIn("rename { 'fabric-api.jar' }", task.group(1))
        self.assertNotIn("The benchmark CLI passes", build)


if __name__ == "__main__":
    unittest.main()
