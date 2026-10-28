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
        # The only resource pack is the zipped Materials pack; the retired baseline pack must not come back.
        self.assertRegex(body, r"from\(materialsArchiveTask\) \{\s*into 'resourcepacks'\s*\}")
        self.assertEqual(body.count("resourcepacks"), 1)
        self.assertNotIn("LabPBR", body)
        self.assertNotIn("*.jar", body)
        # The showcase world rides beside it, where a launcher's saves folder expects it. Both zips stay whole:
        # the installer decides at install time which of them to unpack.
        self.assertRegex(body, r"from\(worldArchiveTask\) \{\s*into 'saves'\s*\}")
        self.assertEqual(body.count("saves"), 1)

    def test_release_content_pins_come_from_tools_config(self):
        # The installer offers the pack and world the release carries; development launches stage the ones
        # tools/config.json pins. Players and developers must see the same content, so there is one pin file.
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        self.assertIn("new groovy.json.JsonSlurper().parse(releaseContentConfigFile)", build)
        self.assertIn("def releaseContentConfigFile = file('tools/config.json')", build)
        for key in ("resourcePack.package", "resourcePack.version", "world.name", "world.version"):
            self.assertIn(f"releaseContentConfig.{key}", build)
        self.assertFalse((REPO_ROOT / "packman" / "art-pack.packman.xml").exists())
        self.assertNotIn("art-pack.packman.xml", build)

    def test_release_content_pins_match_the_names_the_installer_looks_for(self):
        # A pin the installer's file name patterns do not match would build a zip the installer silently ignores,
        # so fetchReleaseContent refuses it. The pins in the repository must pass that check.
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        materials = re.search(r"materialsVersion ==~ /([^/]+)/", build)
        world = re.search(r"worldVersion ==~ /([^/]+)/", build)
        assert materials is not None and world is not None
        tools_config = json.loads((REPO_ROOT / "tools" / "config.json").read_text(encoding="utf-8"))
        self.assertRegex(tools_config["resourcePack"]["version"], f"^(?:{materials.group(1)})$")
        self.assertRegex(tools_config["world"]["version"], f"^(?:{world.group(1)})$")

    def test_release_content_zips_are_reproducible_and_named_for_the_installer(self):
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        for task, name in (
            ("materialsArchive", "RTX-Remix-Materials-${materialsVersion}.zip"),
            ("worldArchive", "RTX-Remix-Showcase-World-${worldVersion}.zip"),
        ):
            with self.subTest(task=task):
                archive = re.search(rf"(?ms)^def {task}Task = tasks.register\('{task}', Zip\) \{{(.+?)^\}}", build)
                self.assertIsNotNone(archive, f"{task} must be a Zip task")
                assert archive is not None
                body = archive.group(1)
                # installer.ps1 finds each zip by this name, in the bundle and in the game folder.
                self.assertIn(f'archiveFileName.set("{name}")', body)
                self.assertIn("destinationDirectory.set(layout.buildDirectory.dir('release-content'))", body)
                self.assertIn("dependsOn fetchReleaseContentTask", body)
                self.assertIn("duplicatesStrategy = DuplicatesStrategy.FAIL", body)
                self.assertIn("preserveFileTimestamps = false", body)
                self.assertIn("reproducibleFileOrder = true", body)

    def test_the_installer_looks_for_the_materials_zip_the_build_names(self):
        # installer.ps1 installs, replaces, and removes only resource packs with this name, so it must be
        # materialsArchive's RTX-Remix-Materials-${materialsVersion}.zip for every version fetchReleaseContent accepts.
        installer = (REPO_ROOT / "packaging" / "installer.ps1").read_text(encoding="utf-8")
        self.assertIn(r"$script:ART_PACK_PATTERN = '^RTX-Remix-Materials-\d+\.\d+\.\d+\.zip$'", installer.splitlines())
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        materials = re.search(r"materialsVersion ==~ /([^/]+)/", build)
        assert materials is not None
        self.assertEqual(materials.group(1), r"\d+\.\d+\.\d+")

    def test_materials_zip_is_a_pack_minecraft_lists(self):
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        archive = re.search(
            r"(?ms)^def materialsArchiveTask = tasks.register\('materialsArchive', Zip\) \{(.+?)^\}", build
        )
        assert archive is not None
        body = archive.group(1)
        self.assertIn("exclude '.packman*'", body)
        # The pack's own pack.mcmeta is replaced by the one materialsPackMeta writes.
        self.assertIn("exclude 'pack.mcmeta'", body)
        self.assertIn("from(materialsPackMetaTask)", body)
        # Six stray exports in the source pack that the release leaves out.
        for channel in ("AO", "C", "H", "M", "N", "R"):
            self.assertIn(f"exclude 'assets/minecraft/textures/block/item_frame_{channel}.png'", body)
        self.assertEqual(body.count("item_frame_"), 6)
        self.assertIn("from('packaging/content/materials/pack.png')", body)
        self.assertIn("from('packaging/content/materials/README.txt')", body)
        self.assertIn("filter(ReplaceTokens, tokens: [VERSION: materialsVersion])", body)
        self.assertIn("from('packaging/content/LICENSE-CC-BY-4.0.txt')", body)
        self.assertIn("rename { 'LICENSE.txt' }", body)
        meta = re.search(r"(?ms)^def materialsPackMetaTask = tasks.register\('materialsPackMeta'\) \{(.+?)^\}", build)
        assert meta is not None
        # Minecraft shows the description in its pack list; pack_format stays as the pack's authors set it.
        self.assertIn("def materialsDescription = 'LabPBR textures by NVIDIA for RTX Remix'", build)
        self.assertIn("meta.pack.description = materialsDescription", meta.group(1))
        self.assertNotIn("pack_format", meta.group(1))

    def test_world_zip_keeps_only_the_world_a_player_needs(self):
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        archive = re.search(r"(?ms)^def worldArchiveTask = tasks.register\('worldArchive', Zip\) \{(.+?)^\}", build)
        self.assertIsNotNone(archive)
        assert archive is not None
        body = archive.group(1)
        world = re.search(r'(?ms)from\("\$\{worldDir\}/world"\) \{(.+?)^    \}', body)
        self.assertIsNotNone(world, "the world must come from the Packman package's world/ folder")
        assert world is not None
        included = re.search(r"include (.+)", world.group(1))
        assert included is not None
        patterns = re.findall(r"'([^']+)'", included.group(1))
        # No session.lock, playerdata, advancements, stats, datapacks, level.dat_old or Paper's files: a launcher
        # opens the world fresh, as a player's first visit would.
        self.assertEqual(len(patterns), 8)
        self.assertEqual(
            set(patterns),
            {"DIM-1/**", "DIM1/**", "data/**", "entities/**", "poi/**", "region/**", "icon.png", "level.dat"},
        )
        self.assertIn("into 'NVIDIA RTX Demo'", world.group(1))
        # The README and license sit beside level.dat in the world folder, not at the zip's root.
        for source in ("packaging/content/world/README.txt", "packaging/content/LICENSE-CC-BY-4.0.txt"):
            beside = re.search(rf"(?ms)from\('{re.escape(source)}'\) \{{(.+?)^    \}}", body)
            self.assertIsNotNone(beside, source)
            assert beside is not None
            self.assertIn("into 'NVIDIA RTX Demo'", beside.group(1))
        self.assertIn("filter(ReplaceTokens, tokens: [VERSION: worldVersion])", body)
        self.assertIn("rename { 'LICENSE.txt' }", body)

    def test_release_content_links_live_under_remix_runtime(self):
        # A checkout of a commit that tracks files under a Packman junction writes and deletes them in the shared
        # package cache, so the pack's and world's links stay where git has never tracked a file.
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        self.assertIn("def artPackDir = 'remix-runtime/art-pack'", build)
        self.assertIn("def worldDir = 'remix-runtime/world'", build)
        fetch = re.search(
            r"(?ms)^def fetchReleaseContentTask = tasks.register\('fetchReleaseContent'\) \{(.+?)^\}", build
        )
        self.assertIsNotNone(fetch)
        assert fetch is not None
        body = fetch.group(1)
        # The generated manifest links each dependency at one of those two paths and nowhere else.
        self.assertEqual(re.findall(r"link: (\w+)", body), ["artPackDir", "worldDir"])
        self.assertIn("linkPath: file(pin.link).absolutePath.replace('\\\\', '/')", body)
        self.assertIn("pkg: releaseContentConfig.resourcePack.package", body)
        self.assertIn('pkg: "rtx_remix_minecraft_world_${releaseContentConfig.world.name}"', body)
        self.assertIn("'pull', temporaryManifest.absolutePath", body)
        self.assertIn("inputs.file(releaseContentConfigFile)", body)
        self.assertIn("/remix-runtime/", (REPO_ROOT / ".gitignore").read_text(encoding="utf-8").splitlines())

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
        archive = re.search(r"(?ms)^def releaseArchiveTask = tasks.register\('releaseArchive', Zip\) \{(.+?)^\}", build)
        self.assertIsNotNone(archive)
        assert archive is not None
        body = archive.group(1)
        self.assertIn("group = 'build'", body)
        self.assertIn("from(stageReleaseArchiveTask)", body)
        self.assertIn("archiveVersion.set(resolvedBuildVersion)", body)
        self.assertIn("destinationDirectory.set(layout.buildDirectory.dir('distributions'))", body)
        self.assertIn("preserveFileTimestamps = false", body)
        self.assertIn("reproducibleFileOrder = true", body)

    def test_release_archive_is_the_installer_bundle(self):
        # The release carries the JAR, the pack and the world as separate files, so the bundle that holds the
        # installer and both zips has to read as the installer, not as a second copy of the mod.
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        archive = re.search(r"(?ms)^def releaseArchiveTask = tasks.register\('releaseArchive', Zip\) \{(.+?)^\}", build)
        assert archive is not None
        body = archive.group(1)
        self.assertIn("archiveClassifier.set('installer')", body)
        # The installer's name stays apart from the JAR's, which has no classifier.
        jar = re.search(r"(?ms)^def packageJarTask = tasks.register\('packageJar', Zip\) \{(.+?)^\}", build)
        assert jar is not None
        self.assertNotIn("archiveClassifier", jar.group(1))

    def test_release_assets_are_the_four_release_files(self):
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        assets = re.search(r"(?ms)^tasks.register\('stageReleaseAssets', Sync\) \{(.+?)^\}", build)
        self.assertIsNotNone(assets, "release assets must be staged through Sync so a stale file cannot be uploaded")
        assert assets is not None
        body = assets.group(1)
        self.assertIn("into layout.buildDirectory.dir('release-assets')", body)
        self.assertIn("duplicatesStrategy = DuplicatesStrategy.FAIL", body)
        # The JAR, both content zips and the bundle, and nothing else: the workflow uploads every file here.
        self.assertEqual(
            re.findall(r"from\(([^)]*)\)", body),
            ["packageJarArchive", "materialsArchiveTask", "worldArchiveTask", "releaseArchiveTask"],
        )
        self.assertNotIn("include", body)
        self.assertNotIn("exclude", body)

    def test_ordinary_builds_do_not_fetch_release_content(self):
        # The world is hundreds of megabytes, so build, test and package must never pull it. Only release staging
        # may name the content tasks. The tools suite does not run Gradle and the check workflow has no Java, so
        # this reads build.gradle instead of running `gradlew build --dry-run`.
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        content = ("fetchReleaseContent", "materialsPackMeta", "materialsArchive", "worldArchive")
        release = ("stageReleaseArchive", "releaseArchive", "stageReleaseAssets")
        remaining = build
        # Cut each task's definition out of the script. What is left is every other task, and none may name them.
        for name in (*content, *release):
            definition = rf"(?ms)^(?:def \w+ = )?tasks\.register\('{name}'[^\n]*\{{\n.+?^\}}\n"
            self.assertEqual(len(re.findall(definition, remaining)), 1, f"{name} must be defined exactly once")
            remaining = re.sub(definition, "", remaining)
        for name in (*content, *release):
            with self.subTest(reference=name):
                # Not assertNotRegex: its failure message would print the whole script.
                reference = re.search(rf".*\b{name}(?:Task)?\b.*", remaining)
                self.assertIsNone(
                    reference,
                    f"{name} may be named only by the release tasks, so no ordinary build reaches it: "
                    f"{reference.group(0).strip() if reference else ''}",
                )

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
