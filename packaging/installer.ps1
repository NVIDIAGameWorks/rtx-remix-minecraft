# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

<#
.SYNOPSIS
    RTX Remix for Minecraft installer: TUI + headless modes.

.DESCRIPTION
    Interactive terminal UI with Install / Upgrade / Repair / Uninstall /
    Export modes, plus a flag-driven headless mode for CI and automated
    deployment.

    Targets PowerShell 5.1 (the version shipped with every Windows 10/11 box)
    so no extra runtimes are needed on end-user machines.

.PARAMETER Mode
    Operation mode. Omit for the interactive main menu. One of:
      Install   Fresh install (or forced reinstall over an existing one).
      Upgrade   Non-destructive update of an existing install. Preserves
                launcher config, JVM args, and options except pack ordering,
                but brings the JVM args and Prism memory an earlier installer
                wrote up to the current defaults.
      Repair    Reinstall packaged files for the currently-installed version.
                Surfaced by the TUI when the package filename matches what's
                already installed. Does not force native re-extraction.
      Uninstall Remove mod files. Optional deeper cleanup menu offers
                the installer-managed Java / Prism portable / Prism
                instance when those exist on disk.
      Export    Zip a Prism instance into a portable archive (Prism only).

.PARAMETER Launcher
    Prism | Microsoft. Picks a launcher without prompting. Required in
    -Unattended mode if both launchers are present (otherwise we fail fast
    rather than silently choose one).

.PARAMETER ModLoader
    A loader declared by this release's project-state.json. Interactive
    Upgrade/Repair preserves an unambiguous installed loader; Install prompts
    so it can switch. Required for every -Unattended operation.

.PARAMETER MinecraftVersion
    A Minecraft version carried by this release. Interactive runs ask when
    more than one packaged version is available. Required for every
    -Unattended operation.

.PARAMETER Unattended
    Headless mode. No prompts; -MinecraftVersion and -ModLoader are required
    for deterministic target selection.
    Uninstall additionally requires -AcknowledgeDataLoss. Export requires
    -OutputPath. Ambiguous launcher detection errors instead of defaulting.

.PARAMETER AcknowledgeDataLoss
    Required alongside -Unattended -Mode Uninstall to avoid accidental wipes
    in CI / scripted contexts. (Replaces an earlier -Confirm flag which
    collided with PowerShell's reserved common parameter.)

.PARAMETER OutputPath
    Target path for -Mode Export. Must end in .zip. Parent dir must exist or
    be creatable. Absolute or relative; relative is resolved against the
    current working directory.

.PARAMETER PrismVersion
    Pin a specific Prism Launcher release version (e.g. "9.1"). Default is
    "latest" which hits api.github.com, useful to avoid rate limits on
    CI fleets behind shared NAT.

.PARAMETER PrismDataDir
    Override Prism Launcher's data directory (default: detected, or the
    managed portable install at %LOCALAPPDATA%\RtxRemixMinecraft\PrismLauncher).
    Needed when Prism is on PATH but uses a non-standard data dir, or for a
    headless run on a Prism detection cannot find, such as a portable copy
    kept anywhere. Interactively, the launcher question asks for that folder.

.PARAMETER SkipHashCheck
    Skip SHA-256 verification on downloaded artifacts. Default is to verify.
    Use only for troubleshooting: hash verification is a supply-chain
    integrity check, not just transport integrity (TLS covers that).

.PARAMETER GitHubToken
    Personal access token used to authenticate GitHub API calls (boosts
    anonymous 60/hr rate limit to 5000/hr). Useful on corporate networks
    where many clients share a NAT egress IP. Also respects the
    GITHUB_TOKEN environment variable.

.PARAMETER DevServer
    Pre-add a server entry to the instance's saved server list. Omit to
    leave servers.dat untouched.

.PARAMETER Mod
    Install | Skip. Whether Install, Upgrade, and Repair install RTX Remix
    Renderer, the mod, with the Java, launcher setup, and Fabric API it needs.
    Install is the default. Skip installs only the pack or the world, into
    the game folder an earlier install made: the RTX Remix Prism instance,
    or the Microsoft launcher's .minecraft. An interactive run starts its
    checklist with the mod ticked unless this is Skip.

.PARAMETER ArtPack
    Install | Skip. Whether Install, Upgrade, and Repair install the RTX
    Remix Materials pack the download carries, or bring an installed one up
    to its version. An unattended run installs it only with Install. An
    interactive run starts its checklist with the pack ticked unless this
    is Skip.

.PARAMETER World
    Install | Skip. Whether Install, Upgrade, and Repair unpack the RTX
    Remix Showcase World the download carries into the game's saves folder.
    A world already there is kept as it is. An unattended run installs it
    only with Install. An interactive run starts its checklist with the
    world ticked unless this is Skip.

.PARAMETER Verbose
    Show verbose diagnostic output. Also respects VERBOSE=1 env var.

.EXAMPLE
    .\installer.ps1
    Interactive TUI.

.EXAMPLE
    .\installer.ps1 -Mode Install -Unattended -Launcher Prism -MinecraftVersion 1.21.1 -ModLoader Fabric
    Headless install using Prism. For CI / automated deployment.

.EXAMPLE
    .\installer.ps1 -Mode Install -Unattended -Launcher Prism -MinecraftVersion 1.21.1 -ModLoader Fabric -ArtPack Install -World Install
    Headless install of the mod, the RTX Remix Materials pack, and the RTX
    Remix Showcase World.

.EXAMPLE
    .\installer.ps1 -Mode Export -Unattended -MinecraftVersion 1.21.1 -ModLoader Forge -OutputPath D:\artifacts\mc.zip
    Headless export of the current Prism instance to a portable zip.

.EXAMPLE
    .\installer.ps1 -Mode Uninstall -Unattended -AcknowledgeDataLoss -Launcher Prism -MinecraftVersion 1.21.1 -ModLoader Fabric
    Headless uninstall. -AcknowledgeDataLoss is mandatory to guard against accidents.

.NOTES
    If PowerShell refuses to run the script ("script is not digitally signed"
    or Mark-of-the-Web block), invoke via install.bat which uses
    -ExecutionPolicy Bypass, or manually run: PowerShell -ExecutionPolicy
    Bypass -File installer.ps1
#>
[CmdletBinding()]
param(
    [ValidateSet('Install', 'Upgrade', 'Repair', 'Uninstall', 'Export')]
    [string]$Mode,

    [ValidateSet('Prism', 'Microsoft', 'Both')]
    [string]$Launcher,

    [string]$ModLoader,

    [string]$MinecraftVersion,

    [switch]$Unattended,

    [switch]$AcknowledgeDataLoss,

    [string]$OutputPath,

    [string]$PrismVersion = 'latest',

    [string]$PrismDataDir,

    [switch]$SkipHashCheck,

    [string]$GitHubToken,

    [string]$DevServer,

    [ValidateSet('Install', 'Skip')]
    [string]$Mod,

    [ValidateSet('Install', 'Skip')]
    [string]$ArtPack,

    [ValidateSet('Install', 'Skip')]
    [string]$World
)

# Do NOT set $ErrorActionPreference = 'Stop' at the top level. Many probes
# (Test-Path / Get-ChildItem / Get-Command on missing things) rely on the
# default Continue so absent-but-expected state is a no-op. Per-call error
# handling is done via try/catch where needed.
$ErrorActionPreference = 'Continue'

# --- Constants and manifest-owned project state ---
$script:APP_VERSION          = $null
$script:PRISM_INSTANCE_NAME  = 'NVIDIA RTX Remix for Minecraft'
# Legacy instance names kept for backward detection only; prior installs
# used shorter / differently-worded names. Ordered newest-to-oldest.
$script:PRISM_INSTANCE_LEGACY_NAMES = @(
    'NVIDIA RTX Remix',
    'RTX Remix 1.21.1 (Fabric)'
)
$script:PRISM_PORTABLE_DIR   = Join-Path $env:LOCALAPPDATA 'RtxRemixMinecraft\PrismLauncher'
# The data folder of a Prism named at the launcher question because detection
# cannot find it: a portable copy can be kept anywhere. Initialize-Prism writes
# it once the instance is in that Prism, and Get-LauncherState reads it, so
# later runs detect that Prism too.
$script:PRISM_DATA_DIR_RECORD = Join-Path $env:LOCALAPPDATA 'RtxRemixMinecraft\prism-data-dir.txt'
$script:MANAGED_JDK_DIR      = $null
$script:MC_VERSION           = $null
$script:JAVA_TARGET          = $null
$script:FABRIC_INSTALLER_VER = '1.0.1'
# Fabric installer 1.0.x writes launcher_profiles.json using an MC-only
# profile key, but the underlying `versions/` directory name (and the
# profile's inner `lastVersionId` field) still include the loader version.
# Two distinct strings; keep them both as derived constants so detection
# logic can't accidentally use one where the other is expected.
# Fallback Prism version used when all "latest" lookups fail (GitHub API
# rate-limited AND Atom feed unreachable). Bump periodically as part of a
# release; pinned because guessing at runtime would defeat the purpose.
$script:PRISM_FALLBACK_VERSION = '9.1'

# Known-good SHA-256 hashes for Prism Launcher portable releases. Populated
# so that, when the GitHub REST API is rate-limited AND the CDN-hosted
# .sha256 sidecar is unreachable (e.g. flaky corp proxy), we can still
# verify the download against a trusted local pin rather than installing
# unverified bytes. Lookup key: "<version>|<arch-pattern>".
#
# Sources by era:
#   10.0.0+  : GitHub REST API .digest field (populated by GitHub mid-2025
#              onwards). Pulled via `gh api repos/PrismLauncher/
#              PrismLauncher/releases`.
#   9.x      : no API digest, no .sha256 sidecar published. Hashes computed
#              by downloading the .zip and running Get-FileHash SHA256.
#
# Update this map when Prism ships new releases. Keys unused by the
# installer are fine to leave in place (extra map entries are ignored).
$script:PRISM_KNOWN_HASHES = @{
    # 9.x (minimum-supported; hashes computed locally from downloaded zips)
    '9.1|MSVC-Portable'           = 'b2441ce28e959627b0d5f6361c7ff8fee4ef8fa54ecb922ec734c259d4fe95ff'
    '9.1|MSVC-arm64-Portable'     = '6c9487fbcc87ebcce57dbff6bced512ee2cda8aceaccdb86a5dbd6ec32d9110f'
    '9.2|MSVC-Portable'           = '82d485650049ff0ef834d08621292b0d1439ce433d43c130fd60686f9caad82b'
    '9.2|MSVC-arm64-Portable'     = '7f89eb7899a247efcc3f58aede04cdb02fc556d65dd41e144ce9d6b1f9905418'
    '9.3|MSVC-Portable'           = 'e04fc4f349610b83be14d083f9365e4a7974be8113ac7fa1530e6fa46cca528d'
    '9.3|MSVC-arm64-Portable'     = '3dc56a83cf168f3fb8d901c2a5a4e98d3c90fe6c202f148625a7277136ff4c1b'
    '9.4|MSVC-Portable'           = '29c5cf47bc454fa6220925b4b988e4590dc7a3fa3920831d7d4fd79118773724'
    '9.4|MSVC-arm64-Portable'     = 'c37514a0ae7f0051337119d80355389061ed8c2dfa98f8ff0688b965321c6477'

    # 10.x (GitHub API digest)
    '10.0.0|MSVC-Portable'        = 'b9606f5745bd6a85e2b077803006cc5db9f59c1833775b6e3528ee956cc09e48'
    '10.0.0|MSVC-arm64-Portable'  = '3e4e79abf1d3665bc9527b8d4112a17754c5395cec040fc8d48c76425b647558'
    '10.0.1|MSVC-Portable'        = 'f7958f7afb9a25883d128b628a9aeb1af93a53e09fe924c05f575370608f0aa1'
    '10.0.1|MSVC-arm64-Portable'  = '4e5a3cefc24ad36733acf173295298c684b0d68d4ebdd2b8b83efc251299051f'
    '10.0.2|MSVC-Portable'        = '9e30e1fe0615184766d2a14b9d27b60c1c6c482b0fcc6174cb326a5fe276f2bf'
    '10.0.2|MSVC-arm64-Portable'  = '53341fda065d0bc43daa0240e084b9be0f5988fa0d50affefd8107fcb8bf51d4'
    '10.0.5|MSVC-Portable'        = '2ef7b0da51c270ba8065dcb1900288df331ec8fd6a3521a786c10c76c70fcc06'
    '10.0.5|MSVC-arm64-Portable'  = 'd0f02a7eddc1db22aac5ebce3b43195b6670352205dcb93e64077553a359f279'

    # 11.x (GitHub API digest)
    '11.0.0|MSVC-Portable'        = 'a00bc7efb0a57800bf7dbca181657264edc91c4eecbb23d37214d5ccbcca7a0f'
    '11.0.0|MSVC-arm64-Portable'  = '00810a6aa582e1515ec5b4693100750bd7ac536944ed15d67fc3cc4984f687ee'
    '11.0.1|MSVC-Portable'        = '16bd4b074879f5029aefa8855be7929ec59fe69e2788054795e0b0d51595c67c'
    '11.0.1|MSVC-arm64-Portable'  = 'ec85cbb95119bf58ed6406cc21caf5de1c5ec2ee7d39aa54051dbbbcc7f3b04c'
    '11.0.2|MSVC-Portable'        = '2370410de1e631aa06da58d042fb0acfc69dedaf90e97b7dfa3dee1c48a77f71'
    '11.0.2|MSVC-arm64-Portable'  = 'afa21a7de02d3635c74a4fa408f7a6765535ef200eef47b1f44e648bbef7e6e2'
}

# Pinned SHA-256 hashes for artifacts whose publishers' checksum sidecars
# we don't want to rely on as the sole integrity signal. Left empty by
# default; the installer falls back to the publisher's sibling checksum
# (Maven .sha256, GitHub release asset digest, Adoptium asset metadata)
# and fails closed if that too is unavailable in -Unattended mode.
#
# To populate: compute against a fresh download with
#   (Get-FileHash -Algorithm SHA256 <file>).Hash.ToLower()
# and paste below. Update when bumping the VER constants above.
$script:FABRIC_INSTALLER_SHA256 = ''
$script:FABRIC_API_SHA256       = ''
$script:FORGE_INSTALLER_SHA256  = ''
$script:NEOFORGE_INSTALLER_SHA256 = ''

# Minimum Prism Launcher version we'll install as a fresh portable. Prism
# 9.0's official release notes tagged the Windows MSVC portable asset as
# broken and directed users to 9.1. Block 9.0 even if the user pins it.
$script:PRISM_MIN_PORTABLE_VERSION = '9.1'

# KEEP IN SYNC WITH build.gradle loom.runs.client.vmArgs. Canonical since
# install.bat became a thin launcher. The Microsoft launcher profile gets
# them as written. The Prism instance gets them without their heap sizes
# (Get-PrismJvmArgs), which reach the game through its memory settings
# (Get-PrismMemorySettings).
$script:RECOMMENDED_JVM_ARGS = '-Xmx2G -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M -XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump'

# Every earlier RECOMMENDED_JVM_ARGS, oldest first, exactly as written here:
# the sets from 2026-04-21, 2026-05-12 and 2026-09-03. Installers wrote each
# set to the Microsoft launcher profile as it is, and to the Prism instance
# as it is or, once installers left the heap out of Prism's JVM args,
# without its heap sizes. Sync-JvmArgs replaces any of those forms without
# asking (Test-InstallerJvmArgs). The memory an installer set on a Prism
# instance is known only from its record (PRISM_INSTALLER_RECORD). When
# RECOMMENDED_JVM_ARGS changes, append the outgoing value here;
# tools/tests/benchmark/test_installer_prism_jvm.py checks the git history.
$script:PREVIOUS_RECOMMENDED_JVM_ARGS = @(
    '-XX:MaxRAMPercentage=100.0 -XX:+UseZGC -XX:+ZGenerational -Xss1M -XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump'
    '-XX:MaxRAMPercentage=100.0 -XX:SoftMaxHeapSize=8G -XX:+UseZGC -XX:+ZGenerational -XX:ZUncommitDelay=30 -Xss1M -XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump'
    '-Xmx12G -XX:SoftMaxHeapSize=8G -XX:+UseZGC -XX:+ZGenerational -XX:ZUncommitDelay=30 -Xss1M -XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump'
)

# Prism's own MinMemAlloc default, the instance's -Xms when the JVM args set none.
$script:PRISM_MIN_MEM_MIB = 512

# The JVM's two spellings of the maximum and of the initial heap size.
$script:MAX_HEAP_OPTIONS = @('-Xmx', '-XX:MaxHeapSize=')
$script:MIN_HEAP_OPTIONS = @('-Xms', '-XX:InitialHeapSize=')

# What Prism refuses in an instance's JVM args. Before every launch it checks
# them against these, case-sensitively, and on a match shows a modal "JVM
# arguments warning" and fails the launch (JavaCommon::checkJVMArgs in
# Prism's launcher/JavaCommon.cpp, called from LaunchController::executeTask
# in launcher/LaunchController.cpp). "-XX-MaxHeapSize" is Prism's own
# spelling.
$script:PRISM_REFUSED_JVM_ARG = '-XX:PermSize=|-Xm[sx]|-XX-MaxHeapSize|-XX:InitialHeapSize|-version:'

# The installer's record of the JvmArgs and memory it last wrote to a Prism
# instance, a JSON file beside instance.cfg. It is how a later run tells
# what the installer wrote from what the user set in Prism, which no value
# alone can show: Prism fills the Memory group with its defaults, so a user
# who sets only the maximum saves the same 512 MiB minimum the installer
# writes. A file of its own, because Prism rewrites instance.cfg. Export
# copies it with instance.cfg, and removing the instance removes it.
$script:PRISM_INSTALLER_RECORD = 'rtx-remix-installer.json'
$script:PRISM_INSTALLER_RECORD_KEYS = @('JvmArgs', 'OverrideMemory', 'MinMemAlloc', 'MaxMemAlloc')

# Resolve the directory the script lives in (used to find the sibling mods/
# staged by the dist archive).
$script:SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path

# Loader-independent code reads the manifest-backed catalog. Providers contain
# only behavior that cannot be declarative (how to install a loader into the
# Microsoft launcher and how to manage its companion dependency).
$script:LOADER_PROVIDERS = [ordered]@{
    fabric = [ordered]@{
        MicrosoftKind         = 'Fabric'
        MicrosoftProfileName  = 'NVIDIA RTX Remix for Minecraft'
        InstallerMinimumJava  = 8
        CompanionKind         = 'FabricApi'
        VersionIdKind         = 'Fabric'
    }
    forge = [ordered]@{
        MicrosoftKind         = 'Installer'
        MicrosoftProfileName  = 'NVIDIA RTX Remix for Minecraft (Forge)'
        InstallerMinimumJava  = 21
        CompanionKind         = $null
        VersionIdKind         = 'Forge'
        InstallerRepository   = 'https://maven.minecraftforge.net'
        InstallerGroupPath    = 'net/minecraftforge/forge'
        InstallerArtifact     = 'forge'
        InstallerClientArg    = '--installClient'
        InstallerReleaseIncludesMinecraft = $true
        InstallerPinnedSha256 = $script:FORGE_INSTALLER_SHA256
    }
    neoforge = [ordered]@{
        MicrosoftKind         = 'Installer'
        MicrosoftProfileName  = 'NVIDIA RTX Remix for Minecraft (NeoForge)'
        InstallerMinimumJava  = 21
        CompanionKind         = $null
        VersionIdKind         = 'NeoForge'
        InstallerRepository   = 'https://maven.neoforged.net/releases'
        InstallerGroupPath    = 'net/neoforged/neoforge'
        InstallerArtifact     = 'neoforge'
        InstallerClientArg    = '--install-client'
        InstallerReleaseIncludesMinecraft = $false
        InstallerPinnedSha256 = $script:NEOFORGE_INSTALLER_SHA256
    }
}
$script:LOADER_CATALOG = $null
$script:DEFAULT_LOADER_ID = $null
$script:PROJECT_STATE_PATH = $null
$script:PROJECT_CATALOG = $null
$script:DEFAULT_MINECRAFT_VERSION = $null
$script:AVAILABLE_MINECRAFT_VERSIONS = @()

function Get-ProjectStatePath {
    # In a release bundle project-state.json is beside installer.ps1.  In a
    # source checkout it is one directory above packaging/.
    $candidates = @()
    if ($env:RTXREMIX_PROJECT_STATE) {
        $candidates += $env:RTXREMIX_PROJECT_STATE
    }
    $candidates += @(
        (Join-Path $script:SCRIPT_DIR 'project-state.json'),
        (Join-Path (Split-Path -Parent $script:SCRIPT_DIR) 'project-state.json')
    )
    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw "Missing project-state.json. A release bundle must stage it beside installer.ps1."
}

function Get-PackagedVersionRoot {
    param(
        [Parameter(Mandatory)] [string]$VersionId,
        [Parameter(Mandatory)] [pscustomobject]$VersionState,
        [int]$DeclaredVersionCount
    )
    $versionedRoot = Join-Path $script:SCRIPT_DIR "mods\$VersionId"
    if (Test-Path -LiteralPath $versionedRoot -PathType Container) {
        $hasLoaderSubtree = @($VersionState.loaders.PSObject.Properties | Where-Object {
            Test-Path -LiteralPath (Join-Path $versionedRoot $_.Name) -PathType Container
        }).Count -gt 0
        if ($hasLoaderSubtree) { return "mods\$VersionId" }
    }
    # Current single-target release archives use mods/<loader>. Keep that
    # layout compatible while reserving mods/<version>/<loader> for archives
    # that carry more than one Minecraft version.
    $directRoot = Join-Path $script:SCRIPT_DIR 'mods'
    $directLoaderDirectories = @($VersionState.loaders.PSObject.Properties | ForEach-Object {
        $path = Join-Path $directRoot $_.Name
        if (Test-Path -LiteralPath $path -PathType Container) { $path }
    })
    if ($DeclaredVersionCount -eq 1 -and $directLoaderDirectories.Count -gt 0) {
        return 'mods'
    }
    # A release workflow selects one version at a time while staging the full
    # multi-version manifest. Identify that direct-layout target from the
    # version-qualified package name; never guess from a bare loader folder.
    $versionPattern = "*-mc$VersionId-*.jar"
    $hasVersionedJar = @($directLoaderDirectories | Where-Object {
        @(Get-ChildItem -LiteralPath $_ -File -Filter $versionPattern -ErrorAction SilentlyContinue).Count -gt 0
    }).Count -gt 0
    if ($hasVersionedJar) {
        return 'mods'
    }
    return $null
}

function New-LoaderCatalog {
    param(
        [Parameter(Mandatory)] [string]$VersionId,
        [Parameter(Mandatory)] [pscustomobject]$VersionState,
        [Parameter(Mandatory)] [string]$ArtifactRoot
    )
    $minecraft = $VersionState.minecraft
    if ($minecraft -isnot [pscustomobject] -or
        [string]::IsNullOrWhiteSpace([string]$minecraft.version) -or
        [string]$minecraft.version -cne $VersionId -or
        $minecraft.javaTarget -isnot [int] -or
        $minecraft.javaTarget -lt 8 -or
        $minecraft.dataVersion -isnot [int] -or
        $minecraft.dataVersion -le 0) {
        throw "project-state.json versions.$VersionId must declare matching minecraft.version, javaTarget, and dataVersion."
    }
    if (-not $VersionState.PSObject.Properties['loaders'] -or
        $VersionState.loaders -isnot [pscustomobject] -or
        $VersionState.loaders.PSObject.Properties.Count -eq 0) {
        throw "project-state.json field 'versions.$VersionId.loaders' must be a non-empty object."
    }
    $defaultLoaderId = ([string]$VersionState.defaultLoader).Trim().ToLowerInvariant()
    if (-not $defaultLoaderId) {
        throw "project-state.json field 'versions.$VersionId.defaultLoader' must be a non-empty string."
    }

    $catalog = [ordered]@{}
    $usedProviders = @{}
    $installedJars = @{}
    foreach ($property in $VersionState.loaders.PSObject.Properties) {
        $id = [string]$property.Name
        $declaration = $property.Value
        $loaderPath = "versions.$VersionId.loaders.$id"
        if (-not $id -or $id -cne $id.ToLowerInvariant() -or $id -notmatch '^[a-z][a-z0-9-]*$') {
            throw "project-state.json loader key '$id' for Minecraft $VersionId must be a lowercase slug."
        }
        if ($declaration -isnot [pscustomobject]) {
            throw "project-state.json field '$loaderPath' must be an object."
        }
        $providerId = ([string]$declaration.provider).Trim().ToLowerInvariant()
        if (-not $providerId -or -not $script:LOADER_PROVIDERS.Contains($providerId)) {
            throw "project-state.json loader '$id' for Minecraft $VersionId names unsupported provider '$($declaration.provider)'."
        }
        if ($usedProviders.ContainsKey($providerId)) {
            throw "project-state.json loaders '$($usedProviders[$providerId])' and '$id' for Minecraft $VersionId both name provider '$providerId'; installer providers are one-to-one."
        }
        $usedProviders[$providerId] = $id
        $provider = $script:LOADER_PROVIDERS[$providerId]
        if ([int]$provider.InstallerMinimumJava -gt [int]$minecraft.javaTarget) {
            throw "Loader provider '$providerId' needs Java $($provider.InstallerMinimumJava), above Minecraft $VersionId's Java target $($minecraft.javaTarget)."
        }
        foreach ($field in @('displayName', 'module', 'installedJar', 'builtinResourcePack')) {
            if ([string]::IsNullOrWhiteSpace([string]$declaration.$field)) {
                throw "project-state.json field '$loaderPath.$field' must be a non-empty string."
            }
        }
        $expectedModule = "versions/$VersionId/$id"
        if ([string]$declaration.module -cne $expectedModule) {
            throw "project-state.json field '$loaderPath.module' must be '$expectedModule'."
        }
        $installedJar = [string]$declaration.installedJar
        if ($installedJar -ne [IO.Path]::GetFileName($installedJar) -or $installedJar -notmatch '\.jar$') {
            throw "project-state.json field '$loaderPath.installedJar' must be a JAR file name without a path."
        }
        if ($installedJars.ContainsKey($installedJar.ToLowerInvariant())) {
            throw "project-state.json loaders '$($installedJars[$installedJar.ToLowerInvariant()])' and '$id' for Minecraft $VersionId declare the same installedJar '$installedJar'."
        }
        $installedJars[$installedJar.ToLowerInvariant()] = $id
        foreach ($objectField in @('developmentArtifact', 'gradle', 'prism', 'loader')) {
            if (-not $declaration.PSObject.Properties[$objectField] -or
                $declaration.$objectField -isnot [pscustomobject]) {
                throw "project-state.json field '$loaderPath.$objectField' must be an object."
            }
        }
        foreach ($field in @('directory', 'pattern')) {
            if ([string]::IsNullOrWhiteSpace([string]$declaration.developmentArtifact.$field)) {
                throw "project-state.json field '$loaderPath.developmentArtifact.$field' must be a non-empty string."
            }
        }
        foreach ($field in @('build', 'test', 'package')) {
            if ([string]::IsNullOrWhiteSpace([string]$declaration.gradle.$field)) {
                throw "project-state.json field '$loaderPath.gradle.$field' must be a non-empty string."
            }
        }
        if ($declaration.gradle.PSObject.Properties['prepare'] -and
            $null -ne $declaration.gradle.prepare -and
            $declaration.gradle.prepare -isnot [array]) {
            throw "project-state.json field '$loaderPath.gradle.prepare' must be an array of strings."
        }
        foreach ($field in @('uid', 'name', 'version')) {
            if ([string]::IsNullOrWhiteSpace([string]$declaration.prism.$field)) {
                throw "project-state.json field '$loaderPath.prism.$field' must be a non-empty string."
            }
        }
        $loaderVersion = [string]$declaration.loader.version
        if ([string]::IsNullOrWhiteSpace($loaderVersion)) {
            throw "project-state.json field '$loaderPath.loader.version' must be a non-empty string."
        }
        if ([string]$declaration.prism.version -ne $loaderVersion) {
            throw "project-state.json loader '$id' for Minecraft $VersionId has prism.version that disagrees with loader.version."
        }
        $companion = $null
        if ($provider.CompanionKind) {
            if (-not $declaration.PSObject.Properties['api'] -or
                $declaration.api -isnot [pscustomobject] -or
                [string]::IsNullOrWhiteSpace([string]$declaration.api.version)) {
                throw "project-state.json loader '$id' for Minecraft $VersionId provider '$providerId' requires api.version."
            }
            $companion = [pscustomobject]@{
                Kind = $provider.CompanionKind
                DisplayName = 'Fabric API'
                Version = [string]$declaration.api.version
            }
        } elseif ($declaration.PSObject.Properties['api'] -and $null -ne $declaration.api) {
            throw "project-state.json loader '$id' for Minecraft $VersionId provider '$providerId' does not support an API companion."
        }
        $entry = [ordered]@{}
        foreach ($key in $provider.Keys) { $entry[$key] = $provider[$key] }
        $entry.Id = $id
        $entry.Provider = $providerId
        $entry.DisplayName = [string]$declaration.displayName
        $entry.Module = [string]$declaration.module
        $entry.DevelopmentArtifactDirectory = [string]$declaration.developmentArtifact.directory
        $entry.DevelopmentArtifactPattern = [string]$declaration.developmentArtifact.pattern
        $entry.InstalledJar = $installedJar
        $entry.BuiltInResourcePack = [string]$declaration.builtinResourcePack
        $entry.PrismUid = [string]$declaration.prism.uid
        $entry.PrismCachedName = [string]$declaration.prism.name
        $entry.LoaderVersion = $loaderVersion
        $entry.ArtifactDirectory = Join-Path $ArtifactRoot $id
        $entry.Companion = $companion
        $entry.VersionId = switch ($provider.VersionIdKind) {
            'Fabric' { "fabric-loader-$loaderVersion-$VersionId" }
            'Forge' { "$VersionId-forge-$loaderVersion" }
            'NeoForge' { "neoforge-$loaderVersion" }
            default { throw "Loader provider '$providerId' has unknown version ID kind '$($provider.VersionIdKind)'." }
        }
        $entry.MicrosoftProfileKey = if ($provider.MicrosoftKind -eq 'Fabric') {
            "fabric-loader-$VersionId"
        } else {
            "rtx-remix-$id-$VersionId"
        }
        $catalog[$id] = [pscustomobject]$entry
    }
    if (-not $catalog.Contains($defaultLoaderId)) {
        throw "project-state.json versions.$VersionId.defaultLoader '$defaultLoaderId' is not present in loaders."
    }
    return [pscustomobject]@{
        Id = $VersionId
        State = $VersionState
        Loaders = $catalog
        DefaultLoaderId = $defaultLoaderId
        ArtifactRoot = $ArtifactRoot
    }
}

function Set-MinecraftVersion {
    param([Parameter(Mandatory)] [string]$VersionId)
    if (-not $script:PROJECT_CATALOG.Contains($VersionId)) {
        throw "Unknown Minecraft version '$VersionId'. Supported versions: $($script:AVAILABLE_MINECRAFT_VERSIONS -join '|')."
    }
    if ($script:AVAILABLE_MINECRAFT_VERSIONS -cnotcontains $VersionId) {
        throw "Minecraft $VersionId is declared but this release has no staged mods subtree for it."
    }
    $selected = $script:PROJECT_CATALOG[$VersionId]
    $minecraft = $selected.State.minecraft
    $script:MC_VERSION = $VersionId
    $script:JAVA_TARGET = [int]$minecraft.javaTarget
    $script:MC_DATA_VERSION = [int]$minecraft.dataVersion
    $script:MANAGED_JDK_DIR = Join-Path $env:LOCALAPPDATA "RtxRemixMinecraft\jdk-$($minecraft.javaTarget)"
    $script:DEFAULT_LOADER_ID = $selected.DefaultLoaderId
    $script:LOADER_CATALOG = $selected.Loaders
}

function Select-MinecraftVersion {
    param([string]$Preferred, [switch]$Unattended)
    if ($Preferred) {
        Set-MinecraftVersion -VersionId $Preferred
        return $Preferred
    }
    if ($script:MC_VERSION) { return $script:MC_VERSION }
    if ($Unattended) {
        throw "-MinecraftVersion is required in -Unattended mode. Supported versions: $($script:AVAILABLE_MINECRAFT_VERSIONS -join '|')."
    }
    $items = @(foreach ($versionId in $script:AVAILABLE_MINECRAFT_VERSIONS) {
        $version = $script:PROJECT_CATALOG[$versionId]
        [pscustomobject]@{
            Label = "Minecraft $versionId"
            Hint = "$($version.Loaders.Count) loader$(if ($version.Loaders.Count -eq 1) { '' } else { 's' })"
            Value = $versionId
        }
    })
    $selected = Invoke-Menu -Title '  Which Minecraft version should this installation use?' `
        -Items $items -Selected $script:DEFAULT_MINECRAFT_VERSION
    if (-not $selected) { throw 'Cancelled by user.' }
    Set-MinecraftVersion -VersionId $selected
    return $selected
}

function Initialize-LoaderCatalog {
    param([string]$PreferredMinecraftVersion)
    $manifestPath = Get-ProjectStatePath
    try {
        $manifest = [IO.File]::ReadAllText($manifestPath, [Text.UTF8Encoding]::new($false)) |
            ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "Cannot read loader manifest ${manifestPath}: $($_.Exception.Message)"
    }
    if ([string]$manifest.schema -cne 'multi-version/v1') {
        throw "project-state.json schema must be 'multi-version/v1', got '$($manifest.schema)'."
    }
    if ($manifest.mod -isnot [pscustomobject] -or
        [string]::IsNullOrWhiteSpace([string]$manifest.mod.version)) {
        throw "project-state.json field 'mod.version' must be a non-empty string."
    }
    $script:APP_VERSION = [string]$manifest.mod.version
    if (-not $manifest.PSObject.Properties['versions'] -or
        $manifest.versions -isnot [pscustomobject] -or
        $manifest.versions.PSObject.Properties.Count -eq 0) {
        throw "project-state.json field 'versions' must be a non-empty object."
    }
    $defaultVersion = ([string]$manifest.defaultMinecraftVersion).Trim()
    if (-not $defaultVersion -or -not $manifest.versions.PSObject.Properties[$defaultVersion]) {
        throw "project-state.json defaultMinecraftVersion must name a declared version."
    }

    $catalog = [ordered]@{}
    $available = @()
    $versionProperties = @($manifest.versions.PSObject.Properties)
    $isReleaseBundle = (Split-Path -Parent $manifestPath) -eq $script:SCRIPT_DIR
    foreach ($property in $versionProperties) {
        $versionId = [string]$property.Name
        if ($versionId -notmatch '^\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.-]+)?$' -or
            $property.Value -isnot [pscustomobject]) {
            throw "Every project-state.json version key must be a Minecraft version string with an object value."
        }
        $artifactRoot = Get-PackagedVersionRoot -VersionId $versionId `
            -VersionState $property.Value -DeclaredVersionCount $versionProperties.Count
        if (-not $isReleaseBundle) {
            $artifactRoot = if ($versionProperties.Count -eq 1) { 'mods' } else { "mods\$versionId" }
        } elseif ($artifactRoot) {
            $available += $versionId
        }
        $catalogArtifactRoot = if ($artifactRoot) { $artifactRoot } else { "mods\$versionId" }
        $catalog[$versionId] = New-LoaderCatalog -VersionId $versionId `
            -VersionState $property.Value -ArtifactRoot $catalogArtifactRoot
    }
    if (-not $isReleaseBundle) {
        $available = @($versionProperties | ForEach-Object { $_.Name })
    }
    if ($available.Count -eq 0) {
        throw "This release has no staged mods subtree for any declared Minecraft version."
    }
    $script:PROJECT_STATE_PATH = $manifestPath
    $script:PROJECT_CATALOG = $catalog
    $script:DEFAULT_MINECRAFT_VERSION = $defaultVersion
    $script:AVAILABLE_MINECRAFT_VERSIONS = @($available)
    if ($PreferredMinecraftVersion) {
        Set-MinecraftVersion -VersionId $PreferredMinecraftVersion
    } elseif ($available.Count -eq 1) {
        Set-MinecraftVersion -VersionId $available[0]
    }
}

function Get-LoaderCatalog { return $script:LOADER_CATALOG }
function Get-LoaderProvider {
    param([Parameter(Mandatory)] [string]$ModLoader)
    $id = $ModLoader.ToLowerInvariant()
    if (-not $script:LOADER_CATALOG.Contains($id)) {
        throw "Unknown mod loader '$ModLoader'. Supported loaders: $(($script:LOADER_CATALOG.Values.DisplayName) -join ', ')."
    }
    return $script:LOADER_CATALOG[$id]
}
function Test-LoaderName {
    param([string]$ModLoader)
    return [bool]($ModLoader -and $script:LOADER_CATALOG.Contains($ModLoader.ToLowerInvariant()))
}
function Get-LoaderDisplayNames { return @($script:LOADER_CATALOG.Values | ForEach-Object DisplayName) }
function Get-ModArtifactLoader {
    param([string]$FileName)
    if (-not $FileName -or $FileName -match '-(sources|dev|javadoc)\.jar$') { return $null }
    foreach ($loader in $script:LOADER_CATALOG.Values) {
        if ($FileName -eq $loader.InstalledJar) { return $loader.DisplayName }
    }
    # Recognize pre-manifest versioned installs for upgrades and uninstall.
    # Loader-qualified artifacts must be checked before Fabric's historical
    # neutral name, which otherwise also matches Forge and NeoForge.
    foreach ($loader in $script:LOADER_CATALOG.Values | Where-Object Provider -ne 'fabric') {
        $qualified = '^rtx-remix-renderer-' + [regex]::Escape($loader.Id) + '-.+\.jar$'
        if ($FileName -match $qualified) { return $loader.DisplayName }
    }
    if ($FileName -match '^rtx-remix-renderer-.+\.jar$') {
        $fabric = @($script:LOADER_CATALOG.Values | Where-Object Provider -eq 'fabric')
        if ($fabric.Count -eq 1) { return $fabric[0].DisplayName }
    }
    return $null
}

# Initialize while loading the function body as well as from the entry point.
# Focused installer harnesses dot-source the body without executing the entry
# point, and must exercise the same catalog validation as a real run.
Initialize-LoaderCatalog -PreferredMinecraftVersion $MinecraftVersion
# What the detected state shows as the packaged mod when mods\ holds more than
# one, usually because a newer download was extracted over an older one.
$script:AMBIGUOUS_MOD_PACKAGE = '(more than one mod JAR)'

# The baseline textures ship inside the mod JAR. This resource pack held them
# before; an enabled copy above Fabric's mod pack masks the JAR's textures, so
# install and uninstall both remove it.
$script:LEGACY_RESOURCE_PACK_PATH = 'resourcepacks\RtxRemix-LabPBR'

# The RTX Remix Materials pack ships as
# resourcepacks\RTX-Remix-Materials-<version>.zip beside the installer and is
# installed under the same name. Only files matching this pattern are the
# installer's to replace or remove. build.gradle's materialsArchive names it.
$script:ART_PACK_PATTERN = '^RTX-Remix-Materials-\d+\.\d+\.\d+\.zip$'
# The target catalog supplies Minecraft's data version. A new options.txt
# carries it so the game reads the file as current.
# The RTX Remix Showcase World ships as
# saves\RTX-Remix-Showcase-World-<version>.zip beside the installer, and the
# zip's one top-level folder is the save, which keeps its name in saves\ and
# in Minecraft's world list. build.gradle's worldArchive names both.
$script:WORLD_ZIP_PATTERN = '^RTX-Remix-Showcase-World-v?\d+\.\d+\.\d+\.zip$'
$script:WORLD_FOLDER_NAME = 'NVIDIA RTX Demo'

# Per-run temp dir so concurrent installer invocations don't corrupt each
# other's downloads. Populated by Initialize-Session; torn down in finally.
$script:SessionTempDir = $null

# --- Terminal styling ---
# chcp 65001 so box-drawing chars render across locales.
[void](cmd /c 'chcp 65001 >nul 2>&1')

# VT100 processing is on by default on Win10 1511+; ESC sequences render as
# ANSI colors. On older shells they print literally.
$ESC = [char]27
$script:C_RESET   = "$ESC[0m"
$script:C_BOLD    = "$ESC[1m"
$script:C_DIM     = "$ESC[2m"
$script:C_RED     = "$ESC[31m"
$script:C_GREEN   = "$ESC[32m"
$script:C_YELLOW  = "$ESC[33m"
$script:C_GRAY    = "$ESC[90m"
$script:C_NVGREEN = "$ESC[38;2;118;185;0m"
$script:C_LIGHTGREEN  = "$ESC[38;2;200;255;200m"   # selected menu item
$script:C_LIGHTYELLOW = "$ESC[38;2;255;250;200m"   # unselected menu item

$script:ICON_OK   = "$($script:C_GREEN)$([char]0x2713)$($script:C_RESET)"
$script:ICON_ERR  = "$($script:C_RED)$([char]0x2717)$($script:C_RESET)"
$script:ICON_WARN = "$($script:C_YELLOW)$([char]0x26A0)$($script:C_RESET)"
$script:ICON_INFO = "$($script:C_NVGREEN)$([char]0x25B8)$($script:C_RESET)"

# Verbose propagation from env (matches install.bat's /verbose convention).
# Explicit '1' comparison: non-empty strings like "0" or "false" are truthy
# in PowerShell, so a naive `if ($env:VERBOSE)` would flip on for VERBOSE=0
# exactly opposite of user intent.
if ($env:VERBOSE -eq '1' -and -not $PSBoundParameters.ContainsKey('Verbose')) {
    $VerbosePreference = 'Continue'
}

# --- Logging helpers ---
function Write-Info    { param([string]$Msg) Write-Host "  $($script:ICON_OK) $Msg" }
function Write-Warn    { param([string]$Msg) Write-Host "  $($script:ICON_WARN) $($script:C_YELLOW)$Msg$($script:C_RESET)" }
function Write-Err     { param([string]$Msg) [Console]::Error.WriteLine("  $($script:ICON_ERR) $($script:C_RED)$Msg$($script:C_RESET)") }
function Write-Trace   { param([string]$Msg) Write-Verbose $Msg }

$script:StepTotal   = 0
$script:StepCurrent = 0
function Start-StepCounter { param([int]$Total) $script:StepTotal = $Total; $script:StepCurrent = 0 }
function Write-Step {
    param([string]$Msg)
    $script:StepCurrent++
    Write-Host ''
    Write-Host "$($script:C_NVGREEN)[$($script:StepCurrent)/$($script:StepTotal)]$($script:C_RESET) $($script:ICON_INFO) $($script:C_BOLD)$Msg$($script:C_RESET)"
}

function Write-Banner {
    param([string]$Title, [string]$Subtitle)
    # $Title.Length counts UTF-16 code units, not display cells. All current
    # titles are ASCII so centering is accurate; revisit if a title ever
    # contains CJK/emoji.
    $inner = 66
    $padTitle = [math]::Max(0, [math]::Floor(($inner - $Title.Length) / 2))
    $padTitleR = $inner - $Title.Length - $padTitle
    $titleLine = (' ' * $padTitle) + $Title + (' ' * $padTitleR)
    $sublineContent = if ($Subtitle) { $Subtitle } else { '' }
    $padSub = [math]::Max(0, [math]::Floor(($inner - $sublineContent.Length) / 2))
    $padSubR = $inner - $sublineContent.Length - $padSub
    $subLine = (' ' * $padSub) + "$($script:C_DIM)$sublineContent$($script:C_RESET)" + (' ' * $padSubR)

    $top    = "$($script:C_NVGREEN)$([char]0x2554)$([string][char]0x2550 * $inner)$([char]0x2557)$($script:C_RESET)"
    $bot    = "$($script:C_NVGREEN)$([char]0x255A)$([string][char]0x2550 * $inner)$([char]0x255D)$($script:C_RESET)"
    $bar    = "$($script:C_NVGREEN)$([char]0x2551)$($script:C_RESET)$(' ' * $inner)$($script:C_NVGREEN)$([char]0x2551)$($script:C_RESET)"
    Write-Host ''
    Write-Host $top
    Write-Host $bar
    Write-Host "$($script:C_NVGREEN)$([char]0x2551)$($script:C_RESET)$($script:C_BOLD)$titleLine$($script:C_RESET)$($script:C_NVGREEN)$([char]0x2551)$($script:C_RESET)"
    if ($Subtitle) {
        Write-Host "$($script:C_NVGREEN)$([char]0x2551)$($script:C_RESET)$subLine$($script:C_NVGREEN)$([char]0x2551)$($script:C_RESET)"
    }
    Write-Host $bar
    Write-Host $bot
    Write-Host ''
}

# --- Session / temp-dir lifecycle ---
function Initialize-Session {
    # Per-run temp dir under %TEMP%. All downloads / staging live under here
    # so concurrent installers don't race on fixed paths. Torn down in the
    # entry-point's finally.
    #
    # Also sweep any rtx-remix-installer-* dirs older than a day from prior
    # crashed runs: the entry-point finally doesn't fire on host crashes /
    # OOM / forced restarts, so orphans accumulate over time (up to ~200 MB
    # each if the crash happened mid-JDK-extract).
    try {
        Get-ChildItem -LiteralPath $env:TEMP -Directory -Filter 'rtx-remix-installer-*' -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-1) } |
            ForEach-Object {
                Write-Trace "Sweeping stale temp dir: $($_.FullName)"
                Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
            }
    } catch { Write-Trace "Stale-temp sweep failed (non-fatal): $($_.Exception.Message)" }

    $unique = [guid]::NewGuid().ToString('N').Substring(0, 8)
    $script:SessionTempDir = Join-Path $env:TEMP "rtx-remix-installer-$unique"
    New-Item -ItemType Directory -Path $script:SessionTempDir -Force | Out-Null
    Write-Trace "Session temp dir: $($script:SessionTempDir)"
}

function Remove-Session {
    if ($script:SessionTempDir -and (Test-Path -LiteralPath $script:SessionTempDir)) {
        Remove-Item -LiteralPath $script:SessionTempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function New-SessionTempPath {
    param([string]$Leaf)
    return Join-Path $script:SessionTempDir $Leaf
}

# --- Prerequisite checks ---
function Test-Elevated {
    # Whether this process holds administrator rights: run as administrator,
    # or any program with User Account Control off or in the built-in
    # Administrator account.
    $principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-DesktopUserSid {
    # The SID of the account signed in to this Windows session: the owner of
    # the session's explorer.exe. $null when no Explorer runs in the session
    # or Windows does not say who owns it.
    $sessionId = (Get-Process -Id $PID).SessionId
    try {
        $shell = Get-CimInstance -ClassName Win32_Process -Filter "Name = 'explorer.exe' AND SessionId = $sessionId" -ErrorAction Stop |
            Select-Object -First 1
        if ($shell) {
            $owner = Invoke-CimMethod -InputObject $shell -MethodName GetOwnerSid -ErrorAction Stop
            if ($owner.ReturnValue -eq 0) { return $owner.Sid }
        }
    } catch {
        Write-Trace "Could not tell who owns this session's Explorer: $($_.Exception.Message)"
    }
    return $null
}

function Test-ElevationAllowed {
    <#
      Administrator rights alone are harmless: the per-user folders the
      installer writes to (%APPDATA%, %LOCALAPPDATA%, the launchers' data)
      belong to the account it runs as, not to its rights. Some PCs give
      every program those rights without asking, with User Account Control
      off, in the built-in Administrator account, or when install.bat starts
      from a program that is elevated itself, and the installer then still
      runs as the account signed in to the desktop. It refuses administrator
      rights as another account, such as an administrator's password typed
      at a standard user's prompt, whose folders it would install into; and,
      to be safe, administrator rights when it cannot tell whose desktop it
      is on.
    #>
    if (-not (Test-Elevated)) { return $true }
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $desktopSid = Get-DesktopUserSid
    if ($desktopSid -eq $identity.User.Value) {
        Write-Trace "Running with administrator rights as $($identity.Name), the account signed in to this desktop."
        return $true
    }
    if ($desktopSid) {
        $desktopName = try { ([Security.Principal.SecurityIdentifier]$desktopSid).Translate([Security.Principal.NTAccount]).Value } catch { $null }
        $signedIn = if ($desktopName) { "$desktopName, the account signed in to this desktop" } else { 'the account signed in to this desktop' }
        Write-Err "The installer is running as $($identity.Name), not as $signedIn."
        Write-Err "It would install into that account's folders. Close this window and start install.bat by double-clicking it."
    } else {
        Write-Err 'The installer is running as administrator and cannot tell which account is signed in to this desktop.'
        Write-Err 'Close this window and start install.bat by double-clicking it. If Windows starts every program as administrator, run this in Command Prompt instead:'
        Write-Err "  runas /trustlevel:0x20000 `"$(Join-Path $script:SCRIPT_DIR 'install.bat')`""
    }
    return $false
}

function Test-InteractiveHost {
    # Detect CI / piped-stdin contexts where TUI prompts would deadlock.
    # PS 5.1 on .NET 4.5+ has [Console]::IsInputRedirected.
    try { return (-not [Console]::IsInputRedirected) }
    catch { return $true }
}

function Test-PathLengthBudget {
    <#
      Best-effort MAX_PATH-class check. Windows legacy API caps at 260
      characters unless long-path-aware mode is opted into (manifest +
      registry), which we don't assume. The worst-case path we build is
      the managed instance's mod JAR. If the user's
      data dir starts out long (deep personal path, substituted drive
      letter, OneDrive redirect, etc.) we could bust 260 and see opaque
      cryptic errors mid-install.
    #>
    param([string]$PrismDataDir)
    if (-not $PrismDataDir) { return }
    # Conservative sample path we know will be constructed.
    $probe = Join-Path (Get-PrismInstanceDir -DataDir $PrismDataDir) ".minecraft\mods\rtx-remix-renderer-$($script:APP_VERSION)-000000000000-0000000.jar"
    if ($probe.Length -gt 248) {
        Write-Warn "Prism data dir path is long ($($PrismDataDir.Length) chars). Some install paths will exceed Windows' legacy 260-char MAX_PATH limit:"
        Write-Warn "  Probe: $probe ($($probe.Length) chars)"
        Write-Warn 'Consider moving Prism to a shorter path (e.g. C:\Prism), or ensuring long-path support is enabled at the OS level (Group Policy: Computer Config -> Admin Templates -> System -> Filesystem -> Enable Win32 long paths).'
    }
}

# --- GitHub authentication helpers ---
function Get-GitHubHeaders {
    <#
      Compose HTTP headers for api.github.com calls. If a GitHub token is
      available (via -GitHubToken param or $env:GITHUB_TOKEN), the
      Authorization header lifts our rate limit from 60 req/hour (anonymous)
      to 5000/hr (authenticated). Corporate networks behind shared NAT hit
      the anon limit fast; the flag / env var lets a user plug in their
      own PAT to work around it.
    #>
    $h = @{ 'User-Agent' = 'RtxRemixMinecraft-Installer' }
    $tok = if ($GitHubToken) { $GitHubToken } elseif ($env:GITHUB_TOKEN) { $env:GITHUB_TOKEN } else { $null }
    if ($tok) {
        $h['Authorization'] = "Bearer $tok"
        $h['X-GitHub-Api-Version'] = '2022-11-28'
    }
    return $h
}

# --- Download helpers (per-run temp, hash verify, retries) ---
function Get-FileSha256 {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Invoke-VerifiedDownload {
    <#
      Download $Url -> $Destination, verifying SHA-256 against $ExpectedSha256
      (hex) OR against a checksum file at $ChecksumUrl. -SkipHashCheck on the
      script bypasses verification entirely. Retries up to 3x with exponential
      backoff. Maps 403 / 404 / 410 to actionable error messages.

      Integrity policy (fail-closed):
       - If $ExpectedSha256 is provided, it is authoritative. Mismatch throws.
       - Else if $ChecksumUrl is provided, we fetch it. If the fetch fails or
         the body isn't a valid SHA-256 hex, we refuse to proceed (in
         -Unattended mode) or prompt to continue (interactive).
         -SkipHashCheck on the script overrides everything and prints a loud
         warning.
       - Else (neither provided): unattended mode throws; interactive warns
         loudly. Callers who legitimately cannot verify must pass
         -SkipHashCheck (user opt-out) to proceed.
    #>
    param(
        [Parameter(Mandatory)] [string]$Url,
        [Parameter(Mandatory)] [string]$Destination,
        [string]$ExpectedSha256,
        [string]$ChecksumUrl,
        [string]$UserAgent = 'RtxRemixMinecraft-Installer',
        [int]$MaxRetries = 3
    )
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

    # Resolve expected hash from sidecar URL if literal wasn't passed.
    $sidecarResolved = $false
    if (-not $ExpectedSha256 -and $ChecksumUrl -and -not $script:SkipHashCheck) {
        # Same retry policy as the main download: transient network hiccups
        # (connection-closed mid-stream on a flaky corp proxy) shouldn't
        # downgrade us to unverified.
        $sidecarAttempt = 0
        while ($sidecarAttempt -lt $MaxRetries -and -not $sidecarResolved) {
            $sidecarAttempt++
            try {
                $raw = Invoke-WebRequest -Uri $ChecksumUrl -UseBasicParsing -Headers @{ 'User-Agent' = $UserAgent } -ErrorAction Stop
                # Normalize to string: PS 5.1 Invoke-WebRequest returns .Content
                # as byte[] when the server sends an octet-stream content type
                # (which Maven often does for .sha256 siblings). Decode if so.
                $content = $raw.Content
                if ($content -is [byte[]]) {
                    $content = [System.Text.Encoding]::UTF8.GetString($content)
                }
                # Checksum files vary: "<hex>", "<hex>  <name>", "<hex> *<name>".
                # Also tolerate CRLF and leading whitespace.
                $firstLine = (($content -split "[`r`n]+", 2)[0]).Trim()
                if ($firstLine -match '^([0-9a-fA-F]{64})\b') {
                    $ExpectedSha256 = $Matches[1].ToLowerInvariant()
                    $sidecarResolved = $true
                    Write-Trace "Expected SHA-256 from $ChecksumUrl = $ExpectedSha256"
                } else {
                    Write-Warn "Checksum file at $ChecksumUrl not in expected format (got '$firstLine')."
                    break
                }
            } catch {
                if ($sidecarAttempt -ge $MaxRetries) {
                    Write-Warn "Could not fetch checksum from $ChecksumUrl after $MaxRetries attempts: $($_.Exception.Message)."
                } else {
                    $backoff = [math]::Pow(2, $sidecarAttempt)
                    Write-Warn "Checksum fetch attempt $sidecarAttempt/$MaxRetries failed ($($_.Exception.Message)); retrying in $backoff s..."
                    Start-Sleep -Seconds $backoff
                }
            }
        }

        # Fail-closed: if a sidecar was offered but we couldn't resolve a hash,
        # refuse to proceed without verification in unattended mode. Interactive
        # mode prompts.
        if (-not $sidecarResolved) {
            if ($script:Unattended) {
                throw "Cannot verify $Url. Checksum sidecar $ChecksumUrl unreachable or malformed. Refusing to download unverified in -Unattended mode. Pass -SkipHashCheck to override (not recommended)."
            }
            Write-Warn "Integrity of $Url cannot be verified (sidecar failed)."
            if (-not (Confirm-YesNo -Prompt 'Proceed with UNVERIFIED download?' -DefaultNo)) {
                throw 'Aborted: refused to proceed without integrity verification.'
            }
            Write-Warn 'Proceeding without verification at user request.'
        }
    }

    $attempt = 0
    while ($true) {
        $attempt++
        try {
            if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Force }
            Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing -Headers @{ 'User-Agent' = $UserAgent } -ErrorAction Stop
            break
        } catch {
            $status = $null
            if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
            if ($status -eq 403) {
                # Scope the GitHub rate-limit hint to actual GitHub URLs.
                if ($Url -match 'github\.com|api\.github\.com') {
                    throw "Download denied (HTTP 403) from $Url. On GitHub this usually means the API rate limit was hit; wait ~60 min, use -PrismVersion <ver> to bypass the 'latest' lookup, or set a GITHUB_TOKEN env var for higher limits."
                }
                throw "Download denied (HTTP 403) from $Url : $($_.Exception.Message)"
            }
            if ($status -eq 404 -or $status -eq 410) {
                throw "Artifact not found (HTTP $status) at $Url. Check the pinned version is still published."
            }
            if ($attempt -ge $MaxRetries) {
                throw "Download failed after $MaxRetries attempts from $Url : $($_.Exception.Message)"
            }
            $backoff = [math]::Pow(2, $attempt)
            Write-Warn "Download attempt $attempt/$MaxRetries failed ($($_.Exception.Message)); retrying in $backoff s..."
            Start-Sleep -Seconds $backoff
        }
    }

    if ($script:SkipHashCheck) {
        Write-Warn "-SkipHashCheck was set; $Url was NOT integrity-verified."
        return
    }
    if ($ExpectedSha256) {
        $actual = Get-FileSha256 -Path $Destination
        if ($actual -ne $ExpectedSha256.ToLowerInvariant()) {
            Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
            throw "SHA-256 mismatch on $Url`n  expected: $ExpectedSha256`n  actual:   $actual`nAborting to avoid running untrusted code."
        }
        Write-Trace 'SHA-256 verified OK.'
        return
    }
    # Caller passed no hash and no sidecar. In unattended mode this is a
    # policy violation: throw. Interactive mode warns loudly but keeps the
    # downloaded artifact on disk.
    if ($script:Unattended) {
        Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
        throw "Refusing to accept $Url in -Unattended mode without integrity verification (no hash or checksum URL available). Pass -SkipHashCheck to override, or fix the caller to supply -ChecksumUrl / -ExpectedSha256."
    }
    Write-Warn "$Url was downloaded without integrity verification (no checksum source available)."
}

# --- PE Machine arch detection ---
# Returns IMAGE_FILE_HEADER.Machine of a Windows PE, or 0 on failure.
# 0x8664 = x64, 0xAA64 = ARM64. Used to confirm resolved Java matches host
# native arch: WoA emulation of x64 Java works for simple Minecraft but
# breaks JNI bridges like remixbridge.dll.
function Get-PeMachine {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return 0 }
    try {
        $fs = [System.IO.File]::OpenRead($Path)
        try {
            $br = [System.IO.BinaryReader]::new($fs)
            $fs.Seek(0x3C, 'Begin') | Out-Null
            $peOffset = $br.ReadInt32()
            $fs.Seek($peOffset + 4, 'Begin') | Out-Null
            return [int]$br.ReadUInt16()
        } finally {
            $fs.Dispose()
        }
    } catch {
        return 0
    }
}

function Get-HostArch {
    # PROCESSOR_ARCHITEW6432 is set when a 32-bit process runs on 64-bit
    # Windows; PROCESSOR_ARCHITECTURE is the PROCESS arch. Prefer ARCHITEW6432
    # so we always end up with the HOST arch.
    if ($env:PROCESSOR_ARCHITEW6432) { return $env:PROCESSOR_ARCHITEW6432 }
    return $env:PROCESSOR_ARCHITECTURE
}

function Get-ExpectedMachineForHost {
    switch (Get-HostArch) {
        'ARM64' { return 0xAA64 }
        'AMD64' { return 0x8664 }
        'IA64'  { return 0x0200 }
        default { return 0x8664 }
    }
}

function Test-HostIsArm64 {
    return (Get-HostArch) -eq 'ARM64'
}

# --- Java resolution ---
function Get-JavaMajor {
    # Match `openjdk version "N..."` or `java version "N..."` anywhere in
    # the output. We don't anchor to start-of-line because PowerShell wraps
    # native stderr through 2>&1 as NativeCommandError records that prefix
    # the text with things like "java.exe : openjdk version..."; an
    # anchor would refuse to match Mojang's own bundled JRE under that
    # wrapping. The `(openjdk|java) version` prefix is specific enough
    # that banners like "Runtime Environment Microsoft-1234" don't
    # false-match (they lack the literal 'version' word right after).
    param([string]$JavaExe)
    if (-not (Test-Path -LiteralPath $JavaExe)) { return 0 }
    try { $out = & $JavaExe -version 2>&1 | Out-String }
    catch { return 0 }
    if ($out -match '(?i)\b(?:openjdk|java)\s+version\s+"(\d+)') { return [int]$Matches[1] }
    return 0
}

function Test-JavaArchMatchesHost {
    param([string]$JavaExe)
    $machine = Get-PeMachine -Path $JavaExe
    return $machine -eq (Get-ExpectedMachineForHost)
}

function Resolve-GameJavaForArm {
    # ARM64: need the branch-pinned Java release AND native ARM64. Force
    # managed Adoptium aarch64
    # because Mojang publishes no ARM64 Windows JRE and x64 Java under WoA
    # emulation breaks JNI.
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath -and (Get-JavaMajor $onPath.Source) -eq $script:JAVA_TARGET -and (Test-JavaArchMatchesHost $onPath.Source)) {
        Write-Info "Java $($script:JAVA_TARGET) (ARM64 native) found on PATH: $($onPath.Source)"
        return $onPath.Source
    }
    $managed = Join-Path $script:MANAGED_JDK_DIR 'bin\java.exe'
    if ((Test-Path -LiteralPath $managed) -and (Get-JavaMajor $managed) -eq $script:JAVA_TARGET -and (Test-JavaArchMatchesHost $managed)) {
        Write-Info "Using managed ARM64 JDK $($script:JAVA_TARGET) at $($script:MANAGED_JDK_DIR)"
        return $managed
    }
    Install-ManagedJdk
    return (Join-Path $script:MANAGED_JDK_DIR 'bin\java.exe')
}

function Resolve-JavaForLoaderInstaller {
    param([Parameter(Mandatory)] [string]$ModLoader)
    # The provider controls the Java floor for its one-shot loader installer;
    # the game itself uses the branch-pinned Java target. Probe PATH -> managed JDK
    # -> MS launcher's bundled runtimes -> Adoptium fallback. Arch-match is
    # not required for this one-shot installer process.
    $provider = Get-LoaderProvider -ModLoader $ModLoader
    $minimumMajor = $provider.InstallerMinimumJava
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath -and (Get-JavaMajor $onPath.Source) -ge $minimumMajor) {
        Write-Info "Using system Java for $ModLoader installer: $($onPath.Source)"
        return $onPath.Source
    }
    $managed = Join-Path $script:MANAGED_JDK_DIR 'bin\java.exe'
    if ((Test-Path -LiteralPath $managed) -and (Get-JavaMajor $managed) -ge $minimumMajor) {
        Write-Info "Using previously-installed managed JDK for $ModLoader installer"
        return $managed
    }
    $runtimeRoots = @(
        (Join-Path $env:LOCALAPPDATA 'Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime'),
        'C:\Program Files (x86)\Minecraft Launcher\runtime',
        'C:\Program Files\Minecraft Launcher\runtime'
    )
    $components = @('java-runtime-delta', 'java-runtime-gamma', 'java-runtime-beta', 'java-runtime-alpha', 'jre-legacy')
    foreach ($root in $runtimeRoots) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        foreach ($comp in $components) {
            $candidate = Join-Path $root "$comp\windows-x64\$comp\bin\java.exe"
            if ((Test-Path -LiteralPath $candidate) -and (Get-JavaMajor $candidate) -ge $minimumMajor) {
                Write-Info "Using Microsoft launcher's bundled Java for $ModLoader installer: $candidate"
                return $candidate
            }
        }
    }
    Write-Trace 'No existing Java found; falling back to Adoptium download'
    Install-ManagedJdk
    return (Join-Path $script:MANAGED_JDK_DIR 'bin\java.exe')
}

function Install-ManagedJdk {
    # Adoptium v3 binary endpoint redirects; we pre-fetch the assets list
    # to get the SHA-256 for supply-chain verification.
    $jdkArch   = if (Test-HostIsArm64) { 'aarch64' } else { 'x64' }
    $assetUrl  = "https://api.adoptium.net/v3/assets/latest/$($script:JAVA_TARGET)/hotspot?architecture=$jdkArch&image_type=jdk&os=windows&vendor=eclipse"
    $binaryUrl = "https://api.adoptium.net/v3/binary/latest/$($script:JAVA_TARGET)/ga/windows/$jdkArch/jdk/hotspot/normal/eclipse"
    $zip       = New-SessionTempPath "adoptium-jdk-$($script:JAVA_TARGET).zip"
    $staging   = New-SessionTempPath "adoptium-jdk-$($script:JAVA_TARGET)-extract"

    Write-Warn "Java $($script:JAVA_TARGET) not found on this system."
    Write-Info "Auto-installing Adoptium Temurin JDK $($script:JAVA_TARGET) ($jdkArch, ~200 MB) to $($script:MANAGED_JDK_DIR)"
    Write-Info 'No admin rights needed; this is a user-level install only used by the installer and the mod launcher.'

    # Adoptium's asset metadata endpoint gives us a pinned SHA-256 tied to
    # the specific binary URL (rather than a checksum sidecar the attacker
    # could disagree with). If the metadata fetch fails we defer to
    # Invoke-VerifiedDownload's fail-closed policy.
    $expectedSha = $null
    if (-not $script:SkipHashCheck) {
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            $assets = Invoke-RestMethod -Uri $assetUrl -UseBasicParsing -Headers @{ 'User-Agent' = 'RtxRemixMinecraft-Installer' } -ErrorAction Stop
            $pkg = $assets | ForEach-Object { $_.binary } | Where-Object { $_.image_type -eq 'jdk' -and $_.os -eq 'windows' -and $_.architecture -eq $jdkArch } | Select-Object -First 1
            if ($pkg -and $pkg.package.checksum) {
                # Defense in depth: confirm the returned checksum is in fact a
                # 64-char SHA-256 hex. If Adoptium's schema evolves to return
                # a different algorithm, a silent mismatch downstream would
                # be opaque, so fail closed here with a clear message instead.
                if ($pkg.package.checksum -match '^[0-9a-fA-F]{64}$') {
                    $expectedSha = $pkg.package.checksum
                } else {
                    Write-Warn "Adoptium checksum isn't a SHA-256 hex (got '$($pkg.package.checksum)'); ignoring."
                }
            } else {
                Write-Warn 'Adoptium metadata had no .package.checksum field (schema change?); relying on -Unattended fail-closed policy downstream.'
            }
        } catch {
            Write-Warn "Could not resolve Adoptium SHA-256 ($($_.Exception.Message))."
        }
    }

    # When $expectedSha is null and the metadata fetch failed, give
    # Invoke-VerifiedDownload nothing to verify against; it will throw
    # in unattended mode, prompt in interactive.
    Invoke-VerifiedDownload -Url $binaryUrl -Destination $zip -ExpectedSha256 $expectedSha

    Write-Info 'Extracting...'
    if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
    try {
        Expand-Archive -LiteralPath $zip -DestinationPath $staging -Force -ErrorAction Stop
    } catch {
        throw "Failed to extract JDK $($script:JAVA_TARGET) archive: $($_.Exception.Message)"
    }
    $nested = Get-ChildItem -LiteralPath $staging -Directory | Select-Object -First 1
    if (-not $nested) { throw 'Extracted JDK archive had no top-level directory: unexpected format.' }
    if (Test-Path -LiteralPath $script:MANAGED_JDK_DIR) { Remove-Item -LiteralPath $script:MANAGED_JDK_DIR -Recurse -Force }
    $parent = Split-Path -Parent $script:MANAGED_JDK_DIR
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    Move-Item -LiteralPath $nested.FullName -Destination $script:MANAGED_JDK_DIR

    $major = Get-JavaMajor (Join-Path $script:MANAGED_JDK_DIR 'bin\java.exe')
    if ($major -ne $script:JAVA_TARGET) {
        throw "JDK $($script:JAVA_TARGET) install completed but java -version reports major=$major."
    }
    Write-Info "JDK $($script:JAVA_TARGET) ready at $($script:MANAGED_JDK_DIR)"
}

# --- Launcher detection ---
function Find-PrismExe {
    # prismlauncher.exe of a Prism install other than our portable copy: on
    # PATH, where Prism's own installer puts it, or where a Start Menu
    # shortcut points (a custom install folder). $null when not found.
    $onPath = Get-Command prismlauncher -ErrorAction SilentlyContinue
    $candidates = @(
        $(if ($onPath) { $onPath.Source }),
        (Join-Path $env:LOCALAPPDATA 'Programs\PrismLauncher\prismlauncher.exe'),
        (Join-Path $env:ProgramFiles 'PrismLauncher\prismlauncher.exe')
    )
    $found = $candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
    if ($found) { return $found }
    $menus = @(
        (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'),
        (Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs')
    )
    foreach ($menu in $menus) {
        foreach ($lnk in @(Get-ChildItem -LiteralPath $menu -Filter 'Prism*.lnk' -Recurse -ErrorAction SilentlyContinue)) {
            try {
                $target = (New-Object -ComObject WScript.Shell).CreateShortcut($lnk.FullName).TargetPath
                if ($target -and $target -like '*prismlauncher.exe' -and (Test-Path -LiteralPath $target)) { return $target }
            } catch { Write-Trace "Unreadable shortcut $($lnk.FullName): $($_.Exception.Message)" }
        }
    }
    return $null
}

function Get-PrismDataDirForExe {
    # Where the Prism at $Exe keeps its data: beside the program when
    # portable.txt marks a portable copy, otherwise %APPDATA%\PrismLauncher.
    param([string]$Exe)
    $folder = Split-Path -Parent $Exe
    if (Test-Path -LiteralPath (Join-Path $folder 'portable.txt')) { return $folder }
    return Join-Path $env:APPDATA 'PrismLauncher'
}

function Read-PrismDataDirRecord {
    # The data folder of the Prism named at an earlier launcher question that
    # got the instance, or $null when none is recorded or that folder is gone.
    if (-not (Test-Path -LiteralPath $script:PRISM_DATA_DIR_RECORD)) { return $null }
    $dir = [System.IO.File]::ReadAllText($script:PRISM_DATA_DIR_RECORD).Trim()
    # Not -PathType Container: that throws for a share that cannot be reached.
    if ($dir -and (Test-Path -LiteralPath $dir)) { return $dir }
    return $null
}

function Get-MsLauncherProfilesPath {
    $root = Join-Path $env:APPDATA '.minecraft'
    $classic = Join-Path $root 'launcher_profiles.json'
    $store = Join-Path $root 'launcher_profiles_microsoft_store.json'
    if (Test-Path -LiteralPath $classic -PathType Leaf) { return $classic }
    if (Test-Path -LiteralPath $store -PathType Leaf) { return $store }
    return $classic
}

function Get-LauncherState {
    # Detection order: explicit -PrismDataDir > the record of a Prism named at
    # an earlier launcher question > our portable > an installed Prism's data
    # dir (Get-PrismDataDirForExe) > nothing.
    # Not $prismDataDir: variable names are case-insensitive, so that would
    # shadow the -PrismDataDir parameter and the override would never be seen.
    $dataDir      = $null
    $hasPrism     = $false
    if ($PrismDataDir) {
        if (Test-Path -LiteralPath $PrismDataDir) {
            $hasPrism = $true
            $dataDir = (Resolve-Path -LiteralPath $PrismDataDir).ProviderPath
            Write-Trace "Prism data dir overridden to: $dataDir"
        } else {
            if ($script:Unattended) {
                throw "-PrismDataDir path does not exist: $PrismDataDir"
            }
            Write-Warn "-PrismDataDir path does not exist: $PrismDataDir (ignoring; will fall back to auto-detection)"
        }
    }
    if (-not $hasPrism) {
        $recorded = Read-PrismDataDirRecord
        if ($recorded) {
            $hasPrism = $true
            $dataDir = $recorded
        }
    }
    if (-not $hasPrism -and (Test-Path -LiteralPath (Join-Path $script:PRISM_PORTABLE_DIR 'prismlauncher.exe'))) {
        $hasPrism = $true
        $dataDir = $script:PRISM_PORTABLE_DIR
    }
    if (-not $hasPrism) {
        $exe = Find-PrismExe
        if ($exe) {
            # The program decides, not %APPDATA%\PrismLauncher: Prism's uninstaller
            # leaves that folder (and its cfg) behind, so data alone is not an install.
            $hasPrism = $true
            $dataDir = Get-PrismDataDirForExe -Exe $exe
        }
    }
    $hasMs = Test-Path -LiteralPath (Get-MsLauncherProfilesPath)
    return [pscustomobject]@{
        HasPrism     = $hasPrism
        HasMs        = $hasMs
        PrismDataDir = $dataDir
    }
}

function Get-PrismInstancesDir {
    param([Parameter(Mandatory)] [string]$DataDir)
    $default = Join-Path $DataDir 'instances'
    $config = Join-Path $DataDir 'prismlauncher.cfg'
    if (-not (Test-Path -LiteralPath $config -PathType Leaf)) { return $default }
    try {
        $line = [IO.File]::ReadAllLines($config) |
            Where-Object { $_ -match '^InstanceDir=' } |
            Select-Object -First 1
        if (-not $line) { return $default }
        $value = ($line.Substring('InstanceDir='.Length)).Trim().Trim('"')
        if (-not $value) { return $default }
        if ([IO.Path]::IsPathRooted($value)) { return $value }
        return Join-Path $DataDir $value
    } catch {
        Write-Trace "Could not read Prism InstanceDir from ${config}: $($_.Exception.Message)"
        return $default
    }
}

function Get-PrismInstanceDir {
    param(
        [Parameter(Mandatory)] [string]$DataDir,
        [string]$InstanceName = $script:PRISM_INSTANCE_NAME
    )
    return Join-Path (Get-PrismInstancesDir -DataDir $DataDir) $InstanceName
}

function Get-McDirForLauncher {
    param(
        [string]$Launcher,
        [string]$PrismDataDir,
        [string]$ModLoader
    )
    if ($Launcher -eq 'Prism') {
        return Join-Path (Get-PrismInstanceDir -DataDir $PrismDataDir) '.minecraft'
    }
    if ($ModLoader) { return Get-MsProfileGameDir -ModLoader $ModLoader }
    return Join-Path $env:APPDATA '.minecraft'
}

function Get-ContentOnlyMcDir {
    # The game folder a run without the mod puts the pack and the world in:
    # the RTX Remix Prism instance's, or the Microsoft launcher's .minecraft.
    # $null when it does not exist, because only the mod's steps make it.
    param([ValidateSet('Prism', 'Microsoft')] [string]$Launcher, [string]$PrismDataDir, [string]$ModLoader)
    if ($Launcher -eq 'Prism' -and -not $PrismDataDir) { return $null }
    $mcDir = Get-McDirForLauncher -Launcher $Launcher -PrismDataDir $PrismDataDir -ModLoader $ModLoader
    if (Test-Path -LiteralPath $mcDir -PathType Container) { return $mcDir }
    return $null
}

function Get-InstalledModVersion {
    param([string]$McDir, [string]$ModLoader)
    if (-not (Test-Path -LiteralPath $McDir)) { return $null }
    $modsDir = Join-Path $McDir 'mods'
    if (-not (Test-Path -LiteralPath $modsDir)) { return $null }
    $jars = @(Get-ChildItem -LiteralPath $modsDir -Filter 'rtx-remix-renderer-*.jar' -ErrorAction SilentlyContinue |
        Where-Object {
            $artifactLoader = Get-ModArtifactLoader -FileName $_.Name
            if (-not $artifactLoader) { return $false }
            return (-not $ModLoader) -or $artifactLoader -eq (Get-LoaderProvider -ModLoader $ModLoader).DisplayName
        })
    if ($jars.Count -gt 1) { return "(ambiguous: $(($jars.Name | Sort-Object) -join ', '))" }
    if ($jars.Count -eq 1) { return $jars[0].Name }
    return $null
}

function Get-DistModJar {
    param([Parameter(Mandatory)] [string]$ModLoader)
    # Locate THE runtime mod JAR in dist/mods/<loader>/. Excludes classifier variants
    # (-sources, -dev, -javadoc). Fails loudly if ambiguous so a bad dist
    # archive can't silently install the wrong artifact.
    $provider = Get-LoaderProvider -ModLoader $ModLoader
    $path = Join-Path $script:SCRIPT_DIR $provider.ArtifactDirectory
    if (-not (Test-Path -LiteralPath $path)) { return $null }
    # The loader-specific directory is the ownership boundary.  Older Forge
    # bundles used the neutral renderer base name, so keep accepting those
    # while the manifest drives which directory is selected.
    $candidates = @(Get-ChildItem -LiteralPath $path -Filter 'rtx-remix-renderer-*.jar' |
        Where-Object { $_.Name -notmatch '-(sources|dev|javadoc)\.jar$' })
    if ($candidates.Count -eq 0) { return $null }
    if ($candidates.Count -gt 1) {
        $names = ($candidates | Select-Object -ExpandProperty Name) -join ', '
        throw "More than one mod JAR is in ${path}: $names. A download carries exactly one $ModLoader JAR. Extract the download into an empty folder and run the installer again."
    }
    return $candidates[0].FullName
}

function Get-PrismLoader {
    param([string]$DataDir, [string]$InstanceName = $script:PRISM_INSTANCE_NAME)
    if (-not $DataDir) { return $null }
    $pack = Join-Path (Get-PrismInstanceDir -DataDir $DataDir -InstanceName $InstanceName) 'mmc-pack.json'
    if (-not (Test-Path -LiteralPath $pack -PathType Leaf)) { return $null }
    try {
        $json = [IO.File]::ReadAllText($pack, [Text.UTF8Encoding]::new($false)) | ConvertFrom-Json -ErrorAction Stop
        $found = @($script:LOADER_CATALOG.Values | Where-Object {
            $uid = $_.PrismUid
            [bool]($json.components | Where-Object { $_.uid -eq $uid })
        })
        if ($found.Count -gt 1) { return 'Ambiguous' }
        if ($found.Count -eq 1) { return $found[0].DisplayName }
    } catch { return 'Ambiguous' }
    return $null
}

function Get-MsProfileKey {
    param([Parameter(Mandatory)] [string]$ModLoader, [pscustomobject]$Profiles)
    $provider = Get-LoaderProvider -ModLoader $ModLoader
    if ($Profiles) {
        foreach ($property in $Profiles.PSObject.Properties) {
            $profile = $property.Value
            if ($profile -and $profile.PSObject.Properties['lastVersionId'] -and
                $profile.lastVersionId -eq $provider.VersionId) { return $property.Name }
        }
    }
    return $provider.MicrosoftProfileKey
}

function Get-MsProfileGameDir {
    param([Parameter(Mandatory)] [string]$ModLoader)
    $default = Join-Path $env:APPDATA '.minecraft'
    $lp = Get-MsLauncherProfilesPath
    if (-not (Test-Path -LiteralPath $lp -PathType Leaf)) { return $default }
    try {
        $json = [IO.File]::ReadAllText($lp, [Text.UTF8Encoding]::new($false)) | ConvertFrom-Json -ErrorAction Stop
        if (-not $json.PSObject.Properties['profiles'] -or -not $json.profiles) { return $default }
        $key = Get-MsProfileKey -ModLoader $ModLoader -Profiles $json.profiles
        if (-not $json.profiles.PSObject.Properties[$key]) { return $default }
        $profile = $json.profiles.$key
        if ($profile.PSObject.Properties['gameDir'] -and -not [string]::IsNullOrWhiteSpace($profile.gameDir)) {
            return [IO.Path]::GetFullPath([Environment]::ExpandEnvironmentVariables([string]$profile.gameDir))
        }
    } catch {
        Write-Warn "Could not resolve the $ModLoader Microsoft profile's game directory; using $default."
    }
    return $default
}

function Get-MicrosoftLoaders {
    $result = @()
    $lp = Get-MsLauncherProfilesPath
    if (-not (Test-Path -LiteralPath $lp -PathType Leaf)) { return $result }
    try {
        $json = [IO.File]::ReadAllText($lp, [Text.UTF8Encoding]::new($false)) | ConvertFrom-Json -ErrorAction Stop
        if (-not $json.PSObject.Properties['profiles'] -or -not $json.profiles) { return $result }
        foreach ($provider in $script:LOADER_CATALOG.Values) {
            $key = Get-MsProfileKey -ModLoader $provider.DisplayName -Profiles $json.profiles
            if ($json.profiles.PSObject.Properties[$key]) { $result += $provider.DisplayName }
        }
    } catch { return @('Ambiguous') }
    return @($result | Select-Object -Unique)
}

# --- TUI primitives ---
function Read-KeyRaw {
    return [Console]::ReadKey($true)
}

function Test-KeyAvailable {
    return [Console]::KeyAvailable
}

function Clear-QueuedKeys {
    # Keys typed ahead, such as one pressed during a download or the Enter
    # after a y/n answer, wait in the console's buffer and would satisfy the
    # next Read-KeyRaw at once.
    while (Test-KeyAvailable) { $null = Read-KeyRaw }
}

function Invoke-Menu {
    <#
      Arrow-key menu. Items: [pscustomobject]@{ Label; Hint; Value; Disabled }.
      Items with Disabled=$true are grayed out and not selectable; arrow
      navigation skips them, digit shortcut ignores them, Enter on one is
      a no-op.

      Returns the selected Value, or $null on Esc/Q.

      Redraw strategy: the menu always runs on the alternate screen, which
      has no scrollback and gives the original screen back when it closes,
      so prior log output is untouched. Every frame starts from the top-left,
      repaints the header clipped to fit the window, then the menu, and
      erases below. A window resize clears and repaints, so a reflowed old
      frame never lingers. A saved cursor row is not used: it goes stale as
      soon as drawing the menu scrolls the window.

      With -Header, the caller owns the alternate screen. Without it, this
      function enters and leaves the alternate screen itself.
    #>
    param(
        [Parameter(Mandatory)] [string]$Title,
        [Parameter(Mandatory)] [array]$Items,
        [string]$Footer = '  up/down navigate | Enter select | Q quit',
        # Repainted from the top-left of the window above the menu on every
        # frame, so it can never scroll away.
        [scriptblock]$Header,
        # The Value of the item the cursor starts on, when that item is
        # enabled; otherwise the first enabled item.
        [string]$Selected
    )
    if (-not $Header) {
        [Console]::Write("$ESC[?1049h")
        try { return Invoke-Menu -Title $Title -Items $Items -Footer $Footer -Header {} -Selected $Selected }
        finally { [Console]::Write("$ESC[?1049l") }
    }
    # Start at the -Selected item, else the first non-disabled one.
    $idx = -1
    for ($i = 0; $i -lt $Items.Count; $i++) {
        $d = $Items[$i].PSObject.Properties['Disabled'] -and $Items[$i].Disabled
        if ($d) { continue }
        if ($idx -lt 0) { $idx = $i }
        if ($Selected -and "$($Items[$i].Value)" -eq $Selected) { $idx = $i; break }
    }
    if ($idx -lt 0) { $idx = 0 }
    while ($true) {
        [Console]::Write("$ESC[H")
        # Clip the header so header + menu fit the window. A frame taller
        # than the window scrolls on every repaint and leaves residue.
        $winWidth  = try { [Console]::WindowWidth }  catch { 120 }
        $winHeight = try { [Console]::WindowHeight } catch { 50 }
        $budget = $winHeight - ($Items.Count + 4) - 1
        $rows = 0
        foreach ($l in @(& $Header 6>&1 | ForEach-Object { "$_" })) {
            $rows += [math]::Max(1, [math]::Ceiling((($l -replace "$ESC\[[0-9;?]*[A-Za-z]", '').Length) / $winWidth))
            if ($rows -gt $budget) { break }
            Write-Host "$l$ESC[K"
        }
        # Each line ends with erase-to-end-of-line so a shorter line never
        # leaves the tail of the one it replaced.
        Write-Host "$Title$ESC[K"
        Write-Host "$ESC[K"
        for ($i = 0; $i -lt $Items.Count; $i++) {
            $it = $Items[$i]
            $disabled = $it.PSObject.Properties['Disabled'] -and $it.Disabled
            if ($disabled) {
                $marker = ' '
                $labelStyled = "$($script:C_GRAY)$($it.Label)$($script:C_RESET)"
            } elseif ($i -eq $idx) {
                $marker = "$($script:C_NVGREEN)$([char]0x25B8)$($script:C_RESET)"
                $labelStyled = "$($script:C_BOLD)$($script:C_LIGHTGREEN)$($it.Label)$($script:C_RESET)"
            } else {
                $marker = ' '
                $labelStyled = "$($script:C_LIGHTYELLOW)$($it.Label)$($script:C_RESET)"
            }
            $line = "    $marker $labelStyled"
            if ($it.Hint) {
                $padTo = 32
                if ($it.Label.Length -lt $padTo) { $line += (' ' * ($padTo - $it.Label.Length)) }
                $hintColor = if ($disabled) { $script:C_GRAY } else { $script:C_DIM }
                $line += "$hintColor$($it.Hint)$($script:C_RESET)"
            }
            Write-Host "$line$ESC[K"
        }
        Write-Host "$ESC[K"
        Write-Host "$Footer$ESC[K"
        [Console]::Write("$ESC[0J")
        # Repaint from scratch when the window is resized, rather than
        # leaving the terminal's reflowed copy of the old frame behind.
        $size = "$([Console]::WindowWidth)x$([Console]::WindowHeight)"
        while (-not [Console]::KeyAvailable) {
            Start-Sleep -Milliseconds 20
            if ("$([Console]::WindowWidth)x$([Console]::WindowHeight)" -ne $size) { break }
        }
        if (-not [Console]::KeyAvailable) {
            [Console]::Write("$ESC[2J")
            continue
        }

        $key = Read-KeyRaw
        switch ($key.Key) {
            ([ConsoleKey]::UpArrow) {
                # Seek upward for the next non-disabled item; stay put if
                # nothing found above (instead of landing on disabled).
                $ni = $idx - 1
                while ($ni -ge 0 -and $Items[$ni].PSObject.Properties['Disabled'] -and $Items[$ni].Disabled) { $ni-- }
                if ($ni -ge 0) { $idx = $ni }
            }
            ([ConsoleKey]::DownArrow) {
                $ni = $idx + 1
                while ($ni -lt $Items.Count -and $Items[$ni].PSObject.Properties['Disabled'] -and $Items[$ni].Disabled) { $ni++ }
                if ($ni -lt $Items.Count) { $idx = $ni }
            }
            ([ConsoleKey]::Enter) {
                $d = $Items[$idx].PSObject.Properties['Disabled'] -and $Items[$idx].Disabled
                if (-not $d) { return $Items[$idx].Value }
            }
            ([ConsoleKey]::Escape) { return $null }
            ([ConsoleKey]::Q)      { return $null }
            default {
                # Digit shortcut: user-facing 1-based, internal 0-based. Ignore
                # shortcuts that land on disabled entries.
                if ($key.KeyChar -match '^[1-9]$') {
                    $n = [int]$key.KeyChar.ToString() - 1
                    if ($n -lt $Items.Count) {
                        $d = $Items[$n].PSObject.Properties['Disabled'] -and $Items[$n].Disabled
                        if (-not $d) { return $Items[$n].Value }
                    }
                }
            }
        }
    }
}

function Confirm-YesNo {
    param([string]$Prompt, [switch]$DefaultNo)
    $yn = if ($DefaultNo) { '[y/N]' } else { '[Y/n]' }
    while ($true) {
        Write-Host ''
        Write-Host -NoNewline "  $Prompt $yn "
        $key = Read-KeyRaw
        Write-Host $key.KeyChar
        if ($key.KeyChar -eq 'y' -or $key.KeyChar -eq 'Y') { return $true }
        if ($key.KeyChar -eq 'n' -or $key.KeyChar -eq 'N') { return $false }
        if ($key.Key -eq [ConsoleKey]::Enter) { return (-not $DefaultNo) }
        # Esc always cancels (= No). Matches typical dialog UX: escape-out
        # never commits. Other keys are ignored and re-prompt.
        if ($key.Key -eq [ConsoleKey]::Escape) { return $false }
    }
}

# --- State-detection summary (shown above main menu) ---
function Get-DetectedState {
    <#
      Collect everything the user might want to see on the main menu banner.
      Returned as a flat pscustomobject so the renderer can format it.

      HostArch             : AMD64 / ARM64 (OS arch, not process arch)
      JavaOnPath           : full path to `java` if present on PATH, else $null
      JavaOnPathMajor      : major version of that java, or 0
      JavaManaged          : path to our managed JDK bin\java.exe or $null
      JavaManagedMajor     : major version of managed JDK, or 0
      HasPrism / PrismDataDir / PrismIsPortable
      HasMs
      PrismLoader          : loader component declared by the managed Prism instance
      MsLoaderState        : catalog-keyed Microsoft version/profile readiness
      ModInstalled         : hashtable { Prism=<filename>, Microsoft=<filename> }
      ModLoaders           : loader inferred for each installed mod
      ModPackages          : packaged JAR names keyed by loader
      Status               : 'install' | 'upgrade' | 'repair' | 'no-package'
                             | 'ambiguous-package'
                             (coarse summary of what the main menu should
                              highlight; see Get-InstallStatus)
    #>
    $state = Get-LauncherState
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    $javaPath      = if ($onPath) { $onPath.Source } else { $null }
    $javaPathMajor = if ($javaPath) { Get-JavaMajor $javaPath } else { 0 }
    $managed       = Join-Path $script:MANAGED_JDK_DIR 'bin\java.exe'
    $javaManaged   = if (Test-Path -LiteralPath $managed) { $managed } else { $null }
    $javaManagedMajor = if ($javaManaged) { Get-JavaMajor $javaManaged } else { 0 }

    # Probe the MS launcher's bundled Mojang JRE. This runtime exists
    # specifically to launch Minecraft; reporting "Java: none detected"
    # when MS launcher is installed is misleading to users who never
    # installed a separate JDK.
    $javaMsLauncher = $null
    $javaMsLauncherMajor = 0
    if ($state.HasMs) {
        $runtimeRoots = @(
            (Join-Path $env:LOCALAPPDATA 'Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime'),
            'C:\Program Files (x86)\Minecraft Launcher\runtime',
            'C:\Program Files\Minecraft Launcher\runtime'
        )
        # Probe launcher runtime generations newest-first; suitability is
        # decided from their reported Java major, not the component name.
        $components = @('java-runtime-delta', 'java-runtime-gamma', 'java-runtime-beta', 'java-runtime-alpha', 'jre-legacy')
        foreach ($root in $runtimeRoots) {
            if (-not (Test-Path -LiteralPath $root)) { continue }
            foreach ($comp in $components) {
                $c = Join-Path $root "$comp\windows-x64\$comp\bin\java.exe"
                if (Test-Path -LiteralPath $c) {
                    $javaMsLauncher = $c
                    $javaMsLauncherMajor = Get-JavaMajor $c
                    break
                }
            }
            if ($javaMsLauncher) { break }
        }
    }

    # A Microsoft loader is ready only when both its version directory and a
    # visible profile exist.  Keep this keyed by the catalog rather than
    # adding a pair of state fields for every provider.
    $msLoaderState = [ordered]@{}
    if ($state.HasMs) {
        $registered = @(Get-MicrosoftLoaders)
        foreach ($provider in $script:LOADER_CATALOG.Values) {
            $hasVersion = Test-Path -LiteralPath (Join-Path $env:APPDATA ".minecraft\versions\$($provider.VersionId)")
            $hasProfile = $registered -contains $provider.DisplayName
            $msLoaderState[$provider.Id] = [pscustomobject]@{
                VersionDir = $hasVersion
                Profile = $hasProfile
                Ready = $hasVersion -and $hasProfile
            }
        }
    }
    $prismLoader = $null
    if ($state.HasPrism -and $state.PrismDataDir) {
        $prismLoader = Get-PrismLoader -DataDir $state.PrismDataDir
    }

    $mods = @{}
    $modLoaders = @{}
    $prismLegacyInstalled = $false
    if ($state.HasPrism -and $state.PrismDataDir) {
        $dir = Get-McDirForLauncher -Launcher 'Prism' -PrismDataDir $state.PrismDataDir
        $v = Get-InstalledModVersion -McDir $dir
        if ($v) {
            $mods['Prism'] = $v
            $modLoaders['Prism'] = if (Test-LoaderName $prismLoader) { $prismLoader } else {
                $fromArtifact = Get-ModArtifactLoader -FileName $v
                if ($fromArtifact) { $fromArtifact } else { 'Ambiguous' }
            }
        }
        # Also detect mods installed under legacy instance names; prior
        # installer versions used different instance labels. Surface the
        # state so users can uninstall/migrate, but don't fold it into
        # the primary 'Prism' slot (Install would then target the wrong
        # dir). We scan every legacy name and show the first hit.
        foreach ($legacyName in $script:PRISM_INSTANCE_LEGACY_NAMES) {
            $legacyDir = Join-Path (Get-PrismInstanceDir -DataDir $state.PrismDataDir -InstanceName $legacyName) '.minecraft'
            if (-not (Test-Path -LiteralPath $legacyDir)) { continue }
            $vLegacy = Get-InstalledModVersion -McDir $legacyDir
            if ($vLegacy) {
                $prismLegacyInstalled = $true
                $slot = "Prism (legacy: $legacyName)"
                $mods[$slot] = $vLegacy
                $legacyLoader = Get-PrismLoader -DataDir $state.PrismDataDir -InstanceName $legacyName
                $modLoaders[$slot] = if ($legacyLoader) { $legacyLoader } else { 'Ambiguous' }
            }
        }
    }
    if ($state.HasMs) {
        $installedProfiles = @()
        foreach ($loader in @(Get-MicrosoftLoaders | Where-Object { Test-LoaderName $_ })) {
            $dir = Get-McDirForLauncher -Launcher Microsoft -ModLoader $loader
            $v = Get-InstalledModVersion -McDir $dir -ModLoader $loader
            if ($v) {
                $installedProfiles += [pscustomobject]@{ Loader = $loader; Jar = $v; McDir = $dir }
            }
        }
        if ($installedProfiles.Count -eq 1) {
            $mods['Microsoft'] = $installedProfiles[0].Jar
            $modLoaders['Microsoft'] = $installedProfiles[0].Loader
        } elseif ($installedProfiles.Count -gt 1) {
            $mods['Microsoft'] = "(multiple: $(($installedProfiles.Jar | Sort-Object -Unique) -join ', '))"
            $modLoaders['Microsoft'] = 'Ambiguous'
        }
    }

    $packages = @{}
    foreach ($loader in Get-LoaderDisplayNames) {
        try {
            $p = Get-DistModJar -ModLoader $loader
            if ($p) { $packages[$loader] = (Get-LoaderProvider -ModLoader $loader).InstalledJar }
        } catch {
            $packages[$loader] = '(ambiguous, see Install)'
        }
    }
    $packageMod = if ($packages.Count -eq 1) { @($packages.Values)[0] } elseif ($packages.Count -eq 0) { $null } else { '(loader-specific)' }

    return [pscustomobject]@{
        HostArch             = Get-HostArch
        JavaOnPath           = $javaPath
        JavaOnPathMajor      = $javaPathMajor
        JavaManaged          = $javaManaged
        JavaManagedMajor     = $javaManagedMajor
        JavaMsLauncher       = $javaMsLauncher
        JavaMsLauncherMajor  = $javaMsLauncherMajor
        HasPrism             = $state.HasPrism
        PrismDataDir         = $state.PrismDataDir
        PrismIsPortable      = ($state.PrismDataDir -eq $script:PRISM_PORTABLE_DIR)
        HasMs                = $state.HasMs
        PrismLoader          = $prismLoader
        MsLoaderState        = $msLoaderState
        ModInstalled         = $mods
        ModLoaders           = $modLoaders
        ModPackage           = $packageMod
        ModPackages          = $packages
        PrismLegacyInstalled = $prismLegacyInstalled
    }
}

function Get-InstallStatus {
    <#
      Coarse one-word status to drive the main menu's default action:
        'install'   : nothing installed anywhere
        'upgrade'   : installed somewhere, version differs from package
        'repair'    : installed somewhere, version equals package (exact
                      filename match incl. timestamp + git hash)
        'no-package': we can't tell because dist/mods/ is empty
        'ambiguous-package': dist/mods/ holds more than one mod JAR
      When the user has BOTH launchers with different versions, 'upgrade'
      takes precedence over 'repair'.
    #>
    param([pscustomobject]$State)
    if ($State.PSObject.Properties['ModPackages'] -and $State.PSObject.Properties['ModLoaders']) {
        if (@($State.ModPackages.Values | Where-Object { $_ -like '(ambiguous*' }).Count -gt 0) {
            return 'ambiguous-package'
        }
        if ($State.ModPackages.Count -eq 0) { return 'no-package' }
        if ($State.ModInstalled.Count -eq 0) { return 'install' }
        $anyDiff = $false
        $anyMatch = $false
        foreach ($launcher in $State.ModInstalled.Keys) {
            $loader = $State.ModLoaders[$launcher]
            if (-not $loader -or $loader -eq 'Ambiguous' -or -not $State.ModPackages.ContainsKey($loader)) {
                $anyDiff = $true
            } elseif ($State.ModInstalled[$launcher] -eq $State.ModPackages[$loader]) {
                $anyMatch = $true
            } else {
                $anyDiff = $true
            }
        }
        if ($anyDiff) { return 'upgrade' }
        if ($anyMatch) { return 'repair' }
        return 'install'
    }
    if (-not $State.ModPackage -or $State.ModPackage -like '(ambiguous*') { return 'no-package' }
    if ($State.ModInstalled.Count -eq 0) { return 'install' }
    $anyDiff = $false
    $anyMatch = $false
    foreach ($v in $State.ModInstalled.Values) {
        if ($v -eq $State.ModPackage) { $anyMatch = $true } else { $anyDiff = $true }
    }
    if ($anyDiff)  { return 'upgrade' }
    if ($anyMatch) { return 'repair' }
    return 'install'
}

function Write-DetectedStateBlock {
    <#
      Pretty-print the detected-state block under the banner. Fixed-width
      left column so values align in the terminal regardless of key length.
    #>
    param([pscustomobject]$State)
    # Key column is padded to $KEY_COL display cells so values align across
    # rows. 16 chars fits the longest key ('Mod installed:') plus two
    # trailing spaces for breathing room.
    $KEY_COL = 16
    function _fmtKey([string]$key) {
        return "  $($script:C_DIM)$($key.PadRight($KEY_COL))$($script:C_RESET)"
    }
    # Indent continuation lines under the value column (2 leading + KEY_COL
    # for the key's slot). Used when a multi-value row (e.g. two launchers)
    # wraps onto its own lines.
    $CONT = '  ' + (' ' * $KEY_COL)

    # System
    Write-Host "$(_fmtKey 'System:')$($State.HostArch) (Windows)"

    # Java
    $javaParts = @()
    if ($State.JavaOnPath -and $State.JavaOnPathMajor -gt 0) {
        $javaParts += "PATH java $($State.JavaOnPathMajor) ($($State.JavaOnPath))"
    }
    if ($State.JavaManaged -and $State.JavaManagedMajor -gt 0) {
        $javaParts += "managed JDK $($State.JavaManagedMajor)"
    }
    if ($State.JavaMsLauncher -and $State.JavaMsLauncherMajor -gt 0) {
        $javaParts += "MS launcher bundled Java $($State.JavaMsLauncherMajor)"
    }
    if ($javaParts.Count -eq 0) { $javaParts += "$($script:C_YELLOW)none detected$($script:C_RESET)" }
    Write-Host "$(_fmtKey 'Java:')$($javaParts -join ' + ')"

    # Launcher(s): one per line so each launcher's path / descriptor
    # is readable without wrapping at terminal width.
    $lparts = @()
    if ($State.HasPrism) {
        $tag = if ($State.PrismIsPortable) { 'portable' } else { 'external' }
        $lparts += "Prism ($tag @ $($State.PrismDataDir))"
    }
    if ($State.HasMs) { $lparts += 'Microsoft launcher' }
    if ($lparts.Count -eq 0) { $lparts += "$($script:C_YELLOW)none detected$($script:C_RESET)" }
    $firstLine = $true
    foreach ($l in $lparts) {
        if ($firstLine) {
            Write-Host "$(_fmtKey 'Launchers:')$l"
            $firstLine = $false
        } else {
            Write-Host "$CONT$l"
        }
    }

    # Loader status per launcher. A pack/profile that claims multiple loaders is
    # shown as ambiguous rather than pretending either side is ready.
    $loaderParts = @()
    if ($State.HasPrism) {
        $label = if ($State.PrismLoader) { $State.PrismLoader } else { 'not installed' }
        $loaderParts += "Prism: $label"
    }
    if ($State.HasMs) {
        $ready = @()
        $incomplete = $false
        foreach ($provider in $script:LOADER_CATALOG.Values) {
            $loaderState = $State.MsLoaderState[$provider.Id]
            if ($loaderState.Ready) { $ready += $provider.DisplayName }
            if ($loaderState.VersionDir -xor $loaderState.Profile) { $incomplete = $true }
        }
        if ($ready.Count -eq 0 -and $incomplete) { $ready += 'incomplete profile state' }
        if ($ready.Count -eq 0) { $ready += 'not installed' }
        $loaderParts += "Microsoft: $($ready -join ' + ')"
    }
    if ($loaderParts.Count -gt 0) {
        Write-Host "$(_fmtKey 'Loaders:')$($loaderParts -join ', ')"
    } else {
        Write-Host "$(_fmtKey 'Loaders:')(no launcher to install into)"
    }

    # Mod
    if ($State.PSObject.Properties['ModPackages']) {
        foreach ($loader in Get-LoaderDisplayNames) {
            $pkgShown = if ($State.ModPackages.ContainsKey($loader)) { $State.ModPackages[$loader] } else { "$($script:C_YELLOW)missing$($script:C_RESET)" }
            Write-Host "$(_fmtKey "$loader package:")$pkgShown"
        }
    } else {
        $pkgShown = if ($State.ModPackage) { $State.ModPackage } else { "$($script:C_YELLOW)no dist JAR in $(Join-Path $script:SCRIPT_DIR 'mods')$($script:C_RESET)" }
        Write-Host "$(_fmtKey 'Mod package:')$pkgShown"
    }
    if ($State.ModInstalled.Count -eq 0) {
        Write-Host "$(_fmtKey 'Mod installed:')$($script:C_DIM)(none)$($script:C_RESET)"
    } else {
        $first = $true
        foreach ($k in $State.ModInstalled.Keys) {
            $label = if ($first) { 'Mod installed:' } else { '' }
            $val = $State.ModInstalled[$k]
            $loader = if ($State.PSObject.Properties['ModLoaders']) { $State.ModLoaders[$k] } else { $null }
            $expected = if ($loader -and $State.PSObject.Properties['ModPackages'] -and $State.ModPackages.ContainsKey($loader)) { $State.ModPackages[$loader] } else { $State.ModPackage }
            $match = ($val -eq $expected)
            $badge = if ($match) { "$($script:C_GREEN)[match]$($script:C_RESET)" } else { "$($script:C_YELLOW)[differs]$($script:C_RESET)" }
            $loaderLabel = if ($loader) { " [$loader]" } else { '' }
            Write-Host "$(_fmtKey $label)$k$loaderLabel : $val  $badge"
            $first = $false
        }
    }

    $pins = @($script:LOADER_CATALOG.Values | ForEach-Object { "$($_.DisplayName) $($_.LoaderVersion)" })
    Write-Host "$(_fmtKey 'Loader pins:')$($pins -join ', ')"
    foreach ($provider in $script:LOADER_CATALOG.Values | Where-Object Companion) {
        Write-Host "$(_fmtKey "$($provider.Companion.DisplayName):")$($provider.Companion.Version) ($($provider.DisplayName) only)"
    }
}

# --- Install / Upgrade implementation ---
# Launcher-selection policy:
#   - Install / Upgrade / Repair: pick ONE launcher. Prism preferred when the
#     user has a choice; always prompt in interactive mode if there's any
#     ambiguity (both detected, or MS-only-detected which is arguably still
#     ambiguous because the user might prefer us to install Prism portable).
#   - Uninstall: Select-LauncherForUninstall allows 'Both' when both launchers
#     have an installed mod, so the user can wipe both in one run.
function Get-SoleInstalledLauncher {
    <#
      The launcher Upgrade and Repair keep without asking: 'Prism' or
      'Microsoft' when exactly one of them has the mod, else $null. Legacy
      Prism instances do not count, because Install never targets them.
    #>
    param([hashtable]$ModInstalled)
    $installed = @($ModInstalled.Keys | Where-Object { $_ -in 'Prism', 'Microsoft' })
    if ($installed.Count -eq 1) { return $installed[0] }
    return $null
}

function Get-OtherLauncherLabel {
    # How the menu and messages name the launcher a run is not using.
    param([string]$Launcher)
    if ($Launcher -eq 'Prism') { return 'the Microsoft launcher' }
    return 'Prism'
}

# Offered by the launcher question whenever detection found no Prism.
$script:OWN_PRISM_MENU_ITEM = [pscustomobject]@{ Label = 'Use a Prism I already have'; Hint = 'a portable copy or one not detected; asks for its folder'; Value = 'OwnPrism' }

function Read-PrismDataDir {
    <#
      Asks where a Prism that detection missed is and returns its data
      folder. Takes the folder holding prismlauncher.exe or a data folder
      holding prismlauncher.cfg, or either file, quoted as Explorer's Copy as
      path quotes it. Throws 'Cancelled by user.' on an empty answer.
    #>
    Write-Host ''
    Write-Host '  Enter the folder holding your prismlauncher.exe, such as where you unpacked'
    Write-Host '  a portable copy. A Prism data folder holding prismlauncher.cfg works too.'
    while ($true) {
        Write-Host ''
        Write-Host -NoNewline '  Prism folder (Enter to cancel): '
        $entered = "$(Read-Host)".Trim().Trim('"')
        if (-not $entered) { throw 'Cancelled by user.' }
        # Typed text can hold characters no path may, which Test-Path reports as
        # an error. -PathType Container throws on those, and on an unreachable
        # share, so a folder is whatever exists and is not a file.
        $folder = $null
        if (Test-Path -LiteralPath $entered -PathType Leaf -ErrorAction SilentlyContinue) {
            $folder = Split-Path -Parent (Resolve-Path -LiteralPath $entered).ProviderPath
        } elseif (Test-Path -LiteralPath $entered -ErrorAction SilentlyContinue) {
            $folder = (Resolve-Path -LiteralPath $entered).ProviderPath
        }
        if ($folder) {
            $cfg = Join-Path $folder 'prismlauncher.cfg'
            if (Test-Path -LiteralPath $cfg) { return Split-Path -Parent $cfg }
            $exe = Join-Path $folder 'prismlauncher.exe'
            if (Test-Path -LiteralPath $exe) { return Get-PrismDataDirForExe -Exe $exe }
        }
        Write-Warn "No prismlauncher.exe or prismlauncher.cfg in $entered"
    }
}

function Select-OwnPrism {
    # The launcher question's answer for a Prism detection missed: this run
    # uses the folder the player names. Initialize-Prism records it for later
    # runs only once the instance is in that Prism, so naming the wrong one
    # and then cancelling, or a failed install, leaves nothing behind.
    param([pscustomobject]$State)
    $dataDir = Read-PrismDataDir
    $State.HasPrism = $true
    $State.PrismDataDir = $dataDir
    # Added, not set: not every caller's state has the property.
    $State | Add-Member -NotePropertyName PrismNamed -NotePropertyValue $true -Force
    return 'Prism'
}

function Select-Launcher {
    param(
        [pscustomobject]$State,
        [string]$Preferred,
        [switch]$Unattended,
        # Biases the pick: when set (by Upgrade/Repair callers), if exactly
        # one launcher has an existing install we pick THAT one without
        # prompting even if both launchers are present. Install mode DOES
        # NOT set this: a user who has an existing install in one launcher
        # may legitimately want to install fresh into the other, and
        # silently picking the first one would deny that option.
        [pscustomobject]$DetectedState,
        [switch]$BiasToInstalledLauncher
    )
    if ($Preferred) {
        $lname = $Preferred
        if ($lname -eq 'Microsoft' -and -not $State.HasMs) {
            throw 'Microsoft launcher not detected. Install it first from https://www.minecraft.net/en-us/download'
        }
        return $lname
    }
    if ($State.HasPrism -and -not $State.HasMs) { return 'Prism' }
    # Bias (Upgrade/Repair only, gated by -BiasToInstalledLauncher):
    # if exactly one launcher has an existing install, pick it without a
    # prompt, and say how to reach the other one, since this run never
    # offers it. Install mode never hits this branch; see param comment.
    if ($BiasToInstalledLauncher -and $DetectedState -and $DetectedState.ModInstalled) {
        $kept = Get-SoleInstalledLauncher -ModInstalled $DetectedState.ModInstalled
        if ($kept) {
            Write-Trace "Auto-selecting launcher '$kept': sole launcher with an existing install."
            Write-Info "Keeping the launcher that already has the mod. To install into $(Get-OtherLauncherLabel -Launcher $kept) instead, run the installer again and choose Install."
            return $kept
        }
    }
    if ($State.HasMs    -and -not $State.HasPrism) {
        # MS-only-detected in interactive mode: still prompt, because the
        # user may prefer Prism even though only MS was found. A silent
        # default here would lock them out of that choice.
        if ($Unattended) { return 'Microsoft' }
        $choice = Invoke-Menu -Title '  Microsoft launcher detected. Use it, or install Prism portable instead?' -Items @(
            [pscustomobject]@{ Label = 'Use Microsoft launcher';  Hint = 'already installed';                          Value = 'Microsoft' }
            [pscustomobject]@{ Label = 'Install Prism portable';  Hint = "recommended for modded setups (downloads $($script:MANAGED_JDK_DIR | Split-Path))"; Value = 'Prism' }
            $script:OWN_PRISM_MENU_ITEM
        )
        if (-not $choice) { throw 'Cancelled by user.' }
        if ($choice -eq 'OwnPrism') { return (Select-OwnPrism -State $State) }
        return $choice
    }
    if ($State.HasPrism -and $State.HasMs) {
        if ($Unattended) {
            # Contract: headless must not silently choose between launchers.
            throw 'Both Prism and Microsoft launchers are present. -Launcher is required in -Unattended mode.'
        }
        $choice = Invoke-Menu -Title '  Both launchers detected. Which do you prefer?' -Items @(
            [pscustomobject]@{ Label = 'Prism Launcher';     Hint = 'recommended for modded setups'; Value = 'Prism' }
            [pscustomobject]@{ Label = 'Microsoft Launcher'; Hint = 'official Minecraft launcher';   Value = 'Microsoft' }
        )
        if (-not $choice) { throw 'Cancelled by user.' }
        return $choice
    }
    if ($Unattended) { return 'Prism' }  # neither detected -> portable-install Prism
    $choice = Invoke-Menu -Title '  No launcher detected. Install Prism or point at Microsoft?' -Items @(
        [pscustomobject]@{ Label = 'Install Prism (portable)'; Hint = 'recommended'; Value = 'Prism' }
        [pscustomobject]@{ Label = 'Use Microsoft Launcher';  Hint = 'must be installed manually first'; Value = 'Microsoft' }
        $script:OWN_PRISM_MENU_ITEM
    )
    if (-not $choice) { throw 'Cancelled by user.' }
    if ($choice -eq 'Microsoft') {
        throw 'Microsoft launcher not detected. Install from https://www.minecraft.net/en-us/download and re-run.'
    }
    if ($choice -eq 'OwnPrism') { return (Select-OwnPrism -State $State) }
    return 'Prism'
}

function Select-LauncherForUninstall {
    <#
      Uninstall-specific variant: when both launchers have an installed mod,
      also offer 'Both' so the user can wipe everywhere in one run.
      Returns 'Prism' | 'Microsoft' | 'Both', or $null on cancel.
    #>
    param([pscustomobject]$State, [string]$Preferred, [switch]$Unattended)
    if ($Preferred) {
        return $Preferred  # respect explicit -Launcher even if other side has mods
    }
    $prismHasMod = $State.ModInstalled.ContainsKey('Prism')
    $msHasMod    = $State.ModInstalled.ContainsKey('Microsoft')
    if ($prismHasMod -and -not $msHasMod) { return 'Prism' }
    if ($msHasMod    -and -not $prismHasMod) { return 'Microsoft' }
    if (-not $prismHasMod -and -not $msHasMod) {
        # Fall back to the standard selector; lets Select-Launcher's
        # error messaging kick in for "nothing detected".
        return (Select-Launcher -State $State -Preferred $Preferred -Unattended:$Unattended)
    }
    if ($Unattended) {
        throw 'Both Prism and Microsoft launchers have RTX Remix installed. -Launcher (or pass Both) is required in -Unattended mode.'
    }
    $choice = Invoke-Menu -Title '  RTX Remix is installed in both launchers. Which should I uninstall from?' -Items @(
        [pscustomobject]@{ Label = 'Prism only';      Hint = "remove from Prism instance only";       Value = 'Prism' }
        [pscustomobject]@{ Label = 'Microsoft only';  Hint = 'remove from the shared .minecraft/';    Value = 'Microsoft' }
        [pscustomobject]@{ Label = 'Both launchers';  Hint = 'wipe RTX Remix from both in one pass';  Value = 'Both' }
    )
    if (-not $choice) { throw 'Cancelled by user.' }
    return $choice
}

function Get-InstallItemPresets {
    <#
      What Install, Upgrade, and Repair install before the player changes
      anything, from -Mod, -ArtPack, and -World: [pscustomobject]@{ Mod;
      ArtPack; World } of booleans. An unattended run installs the mod
      unless told to Skip it, and the pack and the world only when told to
      Install them. An interactive run's checklist starts with every item
      ticked that is not Skip.
    #>
    param([switch]$Unattended, [string]$ModChoice, [string]$ArtPackChoice, [string]$WorldChoice)
    if ($Unattended) {
        return [pscustomobject]@{ Mod = $ModChoice -ne 'Skip'; ArtPack = $ArtPackChoice -eq 'Install'; World = $WorldChoice -eq 'Install' }
    }
    return [pscustomobject]@{ Mod = $ModChoice -ne 'Skip'; ArtPack = $ArtPackChoice -ne 'Skip'; World = $WorldChoice -ne 'Skip' }
}

function Get-InstallStepCount {
    # Checking prerequisites and selecting the launcher, then selecting the
    # mod loader, preparing the launcher, configuring dependencies, and
    # installing the mod, plus one step each for the pack and the world.
    param([pscustomobject]$Items)
    $count = 2
    if ($Items.Mod) { $count += 4 }
    if ($Items.ArtPack) { $count++ }
    if ($Items.World) { $count++ }
    return $count
}

function Select-InstallItems {
    <#
      The checklist of what Install, Upgrade, and Repair install: RTX Remix
      Renderer (the mod), RTX Remix Materials, and the RTX Remix Showcase
      World. Returns [pscustomobject]@{ Mod; ArtPack; World } of booleans.
      An unattended run takes Get-InstallItemPresets as they are. An
      interactive run starts from them, and Enter on an item ticks or
      unticks it until the player picks Continue, which needs at least one
      ticked item. The cursor starts on Continue, because every menu before
      this one teaches Enter to accept. An item the download does not carry
      is unticked and cannot be ticked. Esc throws 'Cancelled by user.'
    #>
    param(
        [ValidateSet('Prism', 'Microsoft')] [string]$Launcher,
        [switch]$Unattended,
        [string]$ModLoader,
        [string]$ModChoice,
        [string]$ArtPackChoice,
        [string]$WorldChoice
    )
    $ticked = Get-InstallItemPresets -Unattended:$Unattended -ModChoice $ModChoice -ArtPackChoice $ArtPackChoice -WorldChoice $WorldChoice
    if ($Unattended) { return $ticked }
    $entries = @(
        [pscustomobject]@{ Value = 'Mod';     Label = 'RTX Remix Renderer';       Hint = 'the mod';        Find = {
            if ($ModLoader) {
                Get-DistModJar -ModLoader $ModLoader
            } else {
                @(Get-LoaderDisplayNames | ForEach-Object { Get-DistModJar -ModLoader $_ }) |
                    Where-Object { $_ } | Select-Object -First 1
            }
        } }
        [pscustomobject]@{ Value = 'ArtPack'; Label = 'RTX Remix Materials';      Hint = 'texture pack';   Find = { Get-DistArtPack } }
        [pscustomobject]@{ Value = 'World';   Label = 'RTX Remix Showcase World'; Hint = 'the demo world'; Find = { Get-DistWorld } }
    )
    # The Microsoft launcher's game folder is the player's everyday one.
    if ($Launcher -eq 'Microsoft') { $entries[1].Hint += '; also changes the look of your normal Minecraft' }
    foreach ($entry in $entries) {
        # A download that holds two of an item still offers it; its step
        # then says what is wrong.
        $carried = try { [bool](& $entry.Find) } catch { $true }
        $entry | Add-Member -NotePropertyName Carried -NotePropertyValue $carried
        if (-not $carried) { $ticked.($entry.Value) = $false }
    }
    $selected = 'Continue'
    while ($true) {
        $menuItems = @(foreach ($entry in $entries) {
            $box = if ($ticked.($entry.Value)) { '[x]' } else { '[ ]' }
            $hint = if ($entry.Carried) { $entry.Hint } else { 'not in this download' }
            [pscustomobject]@{ Label = "$box $($entry.Label)"; Hint = $hint; Value = $entry.Value; Disabled = -not $entry.Carried }
        })
        $anyTicked = $ticked.Mod -or $ticked.ArtPack -or $ticked.World
        $menuItems += [pscustomobject]@{ Label = 'Continue'; Hint = 'install the ticked items'; Value = 'Continue'; Disabled = -not $anyTicked }
        $choice = Invoke-Menu -Title '  Choose what to install. Enter ticks or unticks an item.' -Items $menuItems -Selected $selected
        if (-not $choice) { throw 'Cancelled by user.' }
        if ($choice -eq 'Continue') { return $ticked }
        $ticked.$choice = -not $ticked.$choice
        $selected = $choice
    }
}

function Select-ModLoader {
    param(
        [string]$Preferred,
        [switch]$Unattended,
        [string]$Mode,
        [string]$Launcher,
        [pscustomobject]$DetectedState
    )
    if ($Preferred) { return $Preferred }
    if ($Unattended) {
        throw "-ModLoader is required in -Unattended mode. Supported loaders: $((Get-LoaderDisplayNames) -join '|')."
    }
    if ($Mode -in @('Upgrade','Repair','Uninstall','Export') -and $DetectedState -and
        $DetectedState.PSObject.Properties['ModLoaders']) {
        $installed = $DetectedState.ModLoaders[$Launcher]
        if (Test-LoaderName $installed) {
            Write-Info "Keeping the installed mod loader: $installed. Choose Install to switch loaders."
            return $installed
        }
    }
    $items = @(foreach ($provider in $script:LOADER_CATALOG.Values) {
        $hint = "$($provider.DisplayName) $($provider.LoaderVersion)"
        if ($provider.Companion) { $hint += " + $($provider.Companion.DisplayName)" }
        [pscustomobject]@{ Label = $provider.DisplayName; Hint = $hint; Value = $provider.DisplayName }
    })
    $defaultLoader = $script:LOADER_CATALOG[$script:DEFAULT_LOADER_ID].DisplayName
    $choice = Invoke-Menu -Title '  Which mod loader should this installation use?' -Items $items -Selected $defaultLoader
    if (-not $choice) { throw 'Cancelled by user.' }
    return $choice
}

function Install-PrismPortable {
    # Arch-matched, SHA-256 verified. Supports -PrismVersion for CI (pins
    # avoid GitHub's 60 req/hour rate limit).
    $pattern = if (Test-HostIsArm64) { 'MSVC-arm64-Portable' } else { 'MSVC-Portable' }
    $zip = New-SessionTempPath 'PrismLauncher-Portable.zip'

    # Block versions known-broken on Windows MSVC portable. Prism 9.0's own
    # release notes flag that asset as broken and direct users to 9.1.
    if ($PrismVersion -ne 'latest' -and $PrismVersion -eq '9.0') {
        throw "Prism Launcher 9.0's Windows MSVC portable is broken per the upstream release notes. Use -PrismVersion $($script:PRISM_MIN_PORTABLE_VERSION) or newer."
    }

    $targetAssetName = $null
    $downloadUrl     = $null
    $checksumUrl     = $null
    $expectedSha     = $null
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

    if ($PrismVersion -eq 'latest') {
        # Three-tier "latest" resolution. Each tier falls through to the
        # next on failure; we only throw if all three are unreachable.
        #   1. GitHub REST API (best; gives us the .digest / sidecar assets)
        #   2. Atom feed of releases (no auth; not subject to the same 60/hr
        #      anonymous cap as api.github.com; parses the first <title>)
        #   3. Hardcoded $script:PRISM_FALLBACK_VERSION (loudly warned)
        $resolvedTag = $null
        $resolvedAsset = $null

        # Tier 1: GitHub REST API, optionally authenticated.
        Write-Info "Querying latest Prism Launcher release (arch: $pattern)..."
        try {
            $release = Invoke-RestMethod 'https://api.github.com/repos/PrismLauncher/PrismLauncher/releases/latest' `
                -UseBasicParsing -Headers (Get-GitHubHeaders) -ErrorAction Stop
            $resolvedTag = $release.tag_name
            $regex = "^PrismLauncher-Windows-$([regex]::Escape($pattern))-[\d.]+\.zip$"
            $resolvedAsset = $release.assets | Where-Object { $_.name -match $regex } | Select-Object -First 1
            if ($resolvedAsset) {
                $targetAssetName = $resolvedAsset.name
                $downloadUrl     = $resolvedAsset.browser_download_url
                if ($resolvedAsset.PSObject.Properties['digest'] -and $resolvedAsset.digest -match '^sha256:([0-9a-fA-F]{64})$') {
                    $expectedSha = $Matches[1]
                } else {
                    $sumAsset = $release.assets | Where-Object { $_.name -eq "$targetAssetName.sha256" } | Select-Object -First 1
                    if ($sumAsset) { $checksumUrl = $sumAsset.browser_download_url }
                }
            }
        } catch {
            $status = $null
            if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
            $errMsg = $_.Exception.Message
            $authed = [bool]((Get-GitHubHeaders).Authorization)
            if ($status -eq 403 -and -not $authed) {
                Write-Warn "GitHub REST API rate-limited (HTTP 403); falling back to releases atom feed. (Set GITHUB_TOKEN or pass -GitHubToken to authenticate and avoid the anon 60/hr cap.)"
            } else {
                Write-Warn "GitHub REST API lookup failed ($errMsg); falling back to releases atom feed."
            }
        }

        # Tier 2: Atom feed (no API auth needed, separate rate-limit bucket).
        # Parse the <id> of the first <entry>: it's
        #   tag:github.com,2008:Repository/{repoId}/{tag}
        # and <tag> is the actual release tag (what the download URL needs).
        # Do NOT use <title>: that's the release NAME (e.g. "Prism Launcher
        # 11.0.2"), which includes spaces and isn't URL-safe.
        if (-not $resolvedTag -or -not $resolvedAsset) {
            try {
                $feed = Invoke-WebRequest 'https://github.com/PrismLauncher/PrismLauncher/releases.atom' `
                    -UseBasicParsing -Headers @{ 'User-Agent' = 'RtxRemixMinecraft-Installer' } -ErrorAction Stop
                $feedContent = $feed.Content
                if ($feedContent -is [byte[]]) { $feedContent = [System.Text.Encoding]::UTF8.GetString($feedContent) }
                if ($feedContent -match '(?s)<entry>.*?<id>tag:github\.com,2008:Repository/\d+/([^<]+)</id>') {
                    $resolvedTag = $Matches[1].Trim()
                    Write-Info "Atom feed resolved Prism latest tag as '$resolvedTag'."
                } else {
                    Write-Warn "Atom feed returned but no <entry>/<id> parseable."
                }
            } catch {
                Write-Warn "Atom feed fallback failed: $($_.Exception.Message)"
            }
        }

        # Tier 3: hardcoded fallback version.
        if (-not $resolvedTag) {
            Write-Warn "Falling back to pinned PRISM_FALLBACK_VERSION $($script:PRISM_FALLBACK_VERSION). Install will proceed but may not be strictly latest."
            $resolvedTag = $script:PRISM_FALLBACK_VERSION
        }

        # If we only have a tag (not the full asset), rebuild the asset URL
        # deterministically (same convention Prism uses).
        if (-not $downloadUrl) {
            $targetAssetName = "PrismLauncher-Windows-$pattern-$resolvedTag.zip"
            $downloadUrl     = "https://github.com/PrismLauncher/PrismLauncher/releases/download/$resolvedTag/$targetAssetName"
            $checksumUrl     = "$downloadUrl.sha256"
        }

        # Even in "latest" mode, block 9.0 if that's what resolved.
        if ($resolvedTag -eq '9.0') {
            throw "Latest resolved to Prism 9.0, which is broken on Windows MSVC portable per upstream. Pin -PrismVersion $($script:PRISM_MIN_PORTABLE_VERSION) or newer."
        }

        # Last-ditch known-hash lookup: if the REST API gave us the digest
        # we're already done, but the atom-feed tier fell back through to
        # constructing the URL without a digest. Check our static map.
        if (-not $expectedSha) {
            $knownKey = "$resolvedTag|$pattern"
            if ($script:PRISM_KNOWN_HASHES.ContainsKey($knownKey)) {
                $expectedSha = $script:PRISM_KNOWN_HASHES[$knownKey]
                Write-Info "Using pinned SHA-256 for Prism $resolvedTag/$pattern from installer's known-hashes table."
            }
        }
    } else {
        # Pinned -> construct URLs directly, skip the API call.
        $targetAssetName = "PrismLauncher-Windows-$pattern-$PrismVersion.zip"
        $downloadUrl = "https://github.com/PrismLauncher/PrismLauncher/releases/download/$PrismVersion/$targetAssetName"
        $checksumUrl = "$downloadUrl.sha256"
        # Prefer the pinned known-hash table over the sidecar; it survives
        # CDN flakiness because it's baked into the installer.
        $knownKey = "$PrismVersion|$pattern"
        if ($script:PRISM_KNOWN_HASHES.ContainsKey($knownKey)) {
            $expectedSha = $script:PRISM_KNOWN_HASHES[$knownKey]
            Write-Info "Using pinned SHA-256 for Prism $PrismVersion/$pattern from installer's known-hashes table."
        }
    }

    Write-Info "Downloading $targetAssetName ..."
    try {
        Invoke-VerifiedDownload -Url $downloadUrl -Destination $zip -ExpectedSha256 $expectedSha -ChecksumUrl $checksumUrl
    } catch {
        # Better error UX for ARM64 users who pinned too old a Prism: ARM64
        # portable assets didn't exist before ~Prism 7.x.
        if ((Test-HostIsArm64) -and $_.Exception.Message -match 'HTTP 404|not found') {
            throw "Prism ARM64 portable not available for the pinned version '$PrismVersion'. ARM64 portable builds shipped starting around Prism 7.x. Use -PrismVersion $($script:PRISM_MIN_PORTABLE_VERSION) or newer. Underlying error: $($_.Exception.Message)"
        }
        throw
    }

    if (-not (Test-Path -LiteralPath $script:PRISM_PORTABLE_DIR)) {
        New-Item -ItemType Directory -Path $script:PRISM_PORTABLE_DIR -Force | Out-Null
    }
    try {
        Expand-Archive -LiteralPath $zip -DestinationPath $script:PRISM_PORTABLE_DIR -Force -ErrorAction Stop
    } catch {
        throw "Prism extraction failed: $($_.Exception.Message)"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $script:PRISM_PORTABLE_DIR 'prismlauncher.exe'))) {
        throw "Prism install incomplete: $(Join-Path $script:PRISM_PORTABLE_DIR 'prismlauncher.exe') is missing after extract."
    }
    Disable-PrismUpdateCheck -PortableDir $script:PRISM_PORTABLE_DIR
    Write-Info "Prism Launcher installed to $($script:PRISM_PORTABLE_DIR)"
}

function Disable-PrismUpdateCheck {
    <#
      Pre-write prismlauncher.cfg with AutoUpdate / AutoCheckUpdates disabled.
      Our portable install is managed by install.bat, and letting Prism check
      for updates on every launch just produces the same GitHub rate-limit
      403 that our own latest-lookup hits (observed in user logs). Users
      who want Prism updates can re-enable via Prism -> Settings -> External
      Tools, or just delete these keys and let Prism repopulate.

      Only safe to call right after a fresh portable install. We DO NOT
      modify an existing prismlauncher.cfg; that would clobber user prefs.
    #>
    param([Parameter(Mandatory)] [string]$PortableDir)
    $cfg = Join-Path $PortableDir 'prismlauncher.cfg'
    if (Test-Path -LiteralPath $cfg) {
        Write-Trace "prismlauncher.cfg already exists, skipping update-check disable (respecting user prefs)."
        return
    }
    $lines = @(
        '[General]'
        # AutoCheckUpdates=false stops Prism's periodic GitHub API poll for
        # new releases. Avoids the 403/rate-limit chatter in the launcher
        # log when our own installer can't hit the API either (same NAT).
        'AutoCheckUpdates=false'
        # AutoUpdate=false stops Prism from trying to download an update if
        # a newer version IS detected. We manage Prism via install.bat.
        'AutoUpdate=false'
    )
    try {
        ($lines -join "`r`n") + "`r`n" | Set-Content -LiteralPath $cfg -Encoding ascii -NoNewline
        Write-Info 'Disabled Prism update check for the managed portable install (prismlauncher.cfg).'
    } catch {
        # Non-fatal: user can disable it themselves if it annoys them.
        Write-Warn "Could not write prismlauncher.cfg (non-fatal): $($_.Exception.Message)"
    }
}

function Initialize-PrismInstance {
    param([string]$InstanceDir, [Parameter(Mandatory)] [string]$ModLoader)
    $provider = Get-LoaderProvider -ModLoader $ModLoader
    $cfg = Join-Path $InstanceDir 'instance.cfg'
    if (Test-Path -LiteralPath $cfg) {
        Write-Info "Reusing existing Prism instance: $($script:PRISM_INSTANCE_NAME)"
    } else {
        Write-Info "Creating Prism instance: $($script:PRISM_INSTANCE_NAME)"
        New-Item -ItemType Directory -Path (Join-Path $InstanceDir '.minecraft') -Force | Out-Null
        @(
            'InstanceType=OneSix'
            "name=$($script:PRISM_INSTANCE_NAME)"
            'JavaVersion=21'
        ) -join "`r`n" | Out-File -LiteralPath $cfg -Encoding ascii -NoNewline
    }

    $packPath = Join-Path $InstanceDir 'mmc-pack.json'
    $components = @()
    if (Test-Path -LiteralPath $packPath -PathType Leaf) {
        try {
            $existing = [IO.File]::ReadAllText($packPath, [Text.UTF8Encoding]::new($false)) | ConvertFrom-Json -ErrorAction Stop
            $loaderUids = @($script:LOADER_CATALOG.Values | ForEach-Object PrismUid)
            $components = @($existing.components | Where-Object {
                $_.uid -ne 'net.minecraft' -and $_.uid -notin $loaderUids
            })
        } catch {
            throw "Cannot safely switch the Prism loader because mmc-pack.json is invalid: $($_.Exception.Message)"
        }
    }
    $loaderComponent = @{
        cachedName = $provider.PrismCachedName
        uid = $provider.PrismUid
        version = $provider.LoaderVersion
    }
    $pack = [ordered]@{
        components = @(@{ cachedName = 'Minecraft'; important = $true; uid = 'net.minecraft'; version = $script:MC_VERSION }) +
            @($loaderComponent) + @($components)
        formatVersion = 1
    }
    Write-FileAtomic -Path $packPath -Content ($pack | ConvertTo-Json -Depth 8) -Backup
    Write-Info "Prism instance loader set to $ModLoader."
}

function Set-PrismJavaPin {
    # ARM64 only. Short-circuit on existing OverrideJavaLocation=true ONLY
    # if the pinned JavaPath is already an ARM64 binary (Get-PeMachine ==
    # 0xAA64). A pre-existing x64 pin is broken-by-definition on WoA: our
    # JNI bridge (remixbridge.dll) fails to load under x64-on-ARM emulation,
    # so clobber it rather than silently preserve a broken config.
    param([string]$InstanceCfg)
    if (-not (Test-HostIsArm64)) { return }
    if (-not (Test-Path -LiteralPath $InstanceCfg)) { return }
    $javaw = Join-Path $script:MANAGED_JDK_DIR 'bin\javaw.exe'
    if (-not (Test-Path -LiteralPath $javaw)) { return }

    $text = Read-PrismCfg -Cfg $InstanceCfg
    $overrideEnabled = (Get-PrismCfgValue -Text $text -Key 'OverrideJavaLocation') -eq 'true'
    $pinnedJava = Get-PrismCfgValue -Text $text -Key 'JavaPath'

    if ($overrideEnabled -and $pinnedJava) {
        # Resolve forward-slash paths that Prism writes back to native form.
        $resolved = $pinnedJava -replace '/', '\'
        if (Test-Path -LiteralPath $resolved) {
            $machine = Get-PeMachine -Path $resolved
            if ($machine -eq 0xAA64) {
                Write-Info 'Prism instance already pins an ARM64 Java path, leaving untouched.'
                return
            }
            Write-Warn "Existing Prism JavaPath points at a non-ARM64 JVM (PE Machine 0x$($machine.ToString('X4'))); WoA can't run our JNI bridge under x64 emulation. Replacing pin with the managed ARM64 JDK."
        } else {
            Write-Warn "Existing Prism JavaPath '$pinnedJava' does not exist on disk. Replacing pin with the managed ARM64 JDK."
        }
    }

    Set-PrismCfgValues -Cfg $InstanceCfg -Values ([ordered]@{
        OverrideJavaLocation = 'true'
        JavaPath             = $javaw -replace '\\', '/'
    })
    Write-Info 'Pinned Prism instance to managed ARM64 JDK.'
}

# --- Atomic file writes (for MS launcher JSON) ---
function Write-FileAtomic {
    <#
      Write $Content to $Path via temp-then-move to avoid half-written
      state if interrupted. UTF-8 encoding is explicit (BOM or no-BOM) so
      we don't rely on PS 5.1's default which writes a BOM and breaks
      BOM-sensitive consumers like the MS Minecraft launcher.
    #>
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$Content,
        [switch]$Bom,
        [switch]$Backup
    )
    $encoding = [System.Text.UTF8Encoding]::new($Bom.IsPresent)
    $dir = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $tmp = "$Path.tmp-$([guid]::NewGuid().ToString('N').Substring(0,8))"
    try {
        [System.IO.File]::WriteAllText($tmp, $Content, $encoding)
        if ($Backup -and (Test-Path -LiteralPath $Path)) {
            Copy-Item -LiteralPath $Path -Destination "$Path.bak" -Force
        }
        # Move-Item -Force maps to MoveFileEx(MOVEFILE_REPLACE_EXISTING),
        # atomic on same volume (tmp sits next to target, so always same volume here).
        Move-Item -LiteralPath $tmp -Destination $Path -Force
    } finally {
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue }
    }
}

function New-FileSnapshot {
    param([Parameter(Mandatory)] [string]$Path)
    $exists = Test-Path -LiteralPath $Path -PathType Leaf
    return [pscustomobject]@{
        Path    = $Path
        Existed = $exists
        Bytes   = if ($exists) { [IO.File]::ReadAllBytes($Path) } else { $null }
    }
}

function Restore-FileSnapshot {
    param([Parameter(Mandatory)] [pscustomobject]$Snapshot)
    if ($Snapshot.Existed) {
        $parent = Split-Path -Parent $Snapshot.Path
        if (-not (Test-Path -LiteralPath $parent)) {
            New-Item -ItemType Directory -Path $parent -Force | Out-Null
        }
        [IO.File]::WriteAllBytes($Snapshot.Path, $Snapshot.Bytes)
    } elseif (Test-Path -LiteralPath $Snapshot.Path) {
        Remove-Item -LiteralPath $Snapshot.Path -Force
    }
}

function Sync-JvmArgs {
    <#
      Three-way reconcile against RECOMMENDED_JVM_ARGS, in the form each
      launcher takes: as written for the Microsoft launcher, and without the
      heap sizes for Prism (Get-PrismJvmArgs):
        MATCH  -> no-op
        UNSET  -> apply silently
        DIFFER -> prompt (respect -Unattended = keep existing)
      Args an installer wrote are the installer's, not the user's, so they
      are replaced without asking, prompted or unattended
      (Test-InstallerJvmArgs): any earlier set in
      PREVIOUS_RECOMMENDED_JVM_ARGS in either launcher, and in a Prism
      instance also RECOMMENDED_JVM_ARGS as written, any earlier set
      without its heap sizes, and the args the installer's record says it
      wrote (PRISM_INSTALLER_RECORD). The list covers instances set up
      before the record existed. The log names the args replaced, and a
      Prism instance's previous instance.cfg is kept as instance.cfg.bak;
      the Microsoft launcher's write keeps launcher_profiles.json.bak.
      Prism refuses to launch with the sets that hold -Xmx. Memory on a
      Prism instance is the installer's only while it is what the record
      says the installer wrote (Sync-PrismMemory). Other differing args are
      the user's and stay unless the user accepts the prompt. When Prism refuses
      them too, the prompt and the message on keeping them say so, because
      the instance cannot launch until they change. -Unattended still keeps
      them, warns, and goes on with the install: it never overwrites args
      the installer did not write, and the instance could not launch before
      this run either.
    #>
    param(
        [ValidateSet('Prism','Microsoft')] [string]$For,
        [string]$PrismCfg,
        [string]$ModLoader,
        [switch]$Unattended
    )
    if ($For -eq 'Prism') {
        if (-not (Test-Path -LiteralPath $PrismCfg)) { return }
        # Prism sometimes stores JvmArgs wrapped in double quotes (seen
        # after the launcher rewrites the cfg file on its own). Strip a
        # matched pair of surrounding double quotes before comparing;
        # otherwise '"<args>"' vs '<args>' fails the equality check and
        # we perpetually re-prompt on every upgrade.
        $current = [string](Get-PrismCfgValue -Text (Read-PrismCfg -Cfg $PrismCfg) -Key 'JvmArgs')
        if ($current.Length -ge 2 -and $current.StartsWith('"') -and $current.EndsWith('"')) {
            $current = $current.Substring(1, $current.Length - 2).Trim()
        }
        $record = Read-PrismInstallerRecord -Cfg $PrismCfg
        $recommended = Get-PrismJvmArgs -JvmArgs $script:RECOMMENDED_JVM_ARGS
        if ($current -eq $recommended) {
            Write-Info 'Prism instance JVM args already match launcher defaults; no change.'
            Sync-PrismMemory -Cfg $PrismCfg -Record $record
            return
        }
        if ($current -eq '') {
            Write-Info 'Applying launcher default JVM args to Prism instance.'
            Write-PrismJvmArgs -Cfg $PrismCfg
            Sync-PrismMemory -Cfg $PrismCfg -Record $record
            return
        }
        if (Test-InstallerJvmArgs -JvmArgs $current -For Prism -Record $record) {
            $refused = @(Get-PrismRefusedJvmArgs -JvmArgs $current)
            $reason = if ($refused.Count -gt 0) { " Prism refuses to launch with their $($refused -join ' ')." } else { '' }
            Write-Info "Replacing the JVM args an earlier installer wrote to the Prism instance with launcher defaults.$reason"
            Backup-PrismCfg -Cfg $PrismCfg
            Write-Info "Previous JVM args, kept in instance.cfg.bak: $current"
            Write-PrismJvmArgs -Cfg $PrismCfg
            Sync-PrismMemory -Cfg $PrismCfg -Record $record -BackedUp
            return
        }
        $refusal = Get-PrismJvmArgsRefusal -JvmArgs $current
        if ($Unattended) {
            if ($refusal) {
                Write-Warn "Prism instance JVM args differ; keeping existing (unattended). $refusal Change them in the instance's Java settings in Prism, or run the installer without -Unattended to replace them."
            } else {
                Write-Info 'Prism instance JVM args differ; keeping existing (unattended).'
            }
            return
        }
        $heapMiB = (Get-PrismMemorySettings -JvmArgs $script:RECOMMENDED_JVM_ARGS)['MaxMemAlloc']
        $notes = @(
            $refusal
            if ($heapMiB) { "Replacing them also sets the instance's memory in Prism to $heapMiB MiB." }
        )
        if (Confirm-JvmArgsOverwrite -Label 'Prism instance' -Current $current -Recommended $recommended -Note $notes) {
            Write-PrismJvmArgs -Cfg $PrismCfg
            Write-Info 'Prism instance JVM args updated to launcher defaults.'
            Write-PrismMemory -Cfg $PrismCfg
        } elseif ($refusal) {
            Write-Warn "Keeping existing Prism JVM args. $refusal Change them in the instance's Java settings in Prism."
        } else {
            Write-Info 'Keeping existing Prism JVM args.'
        }
        return
    }

    # Microsoft launcher: defensive JSON read.
    $lp  = Get-MsLauncherProfilesPath
    if (-not (Test-Path -LiteralPath $lp)) { return }
    $json = $null
    try {
        $raw = Get-Content -LiteralPath $lp -Raw
        if (-not $raw) { return }
        $json = $raw | ConvertFrom-Json -ErrorAction Stop
    } catch {
        Write-Warn "Could not parse launcher_profiles.json ($($_.Exception.Message)); skipping JVM args reconciliation."
        return
    }
    if (-not $json -or -not $json.PSObject.Properties['profiles'] -or -not $json.profiles) {
        Write-Trace 'launcher_profiles.json has no profiles section; the loader installer will populate it.'
        return
    }
    $key = Get-MsProfileKey -ModLoader $ModLoader -Profiles $json.profiles
    if (-not $json.profiles.PSObject.Properties[$key]) {
        Write-Trace "MS launcher profile '$key' not present yet."
        return
    }
    $profileObj = $json.profiles.$key
    $current = if ($profileObj.PSObject.Properties['javaArgs']) { $profileObj.javaArgs } else { $null }
    if ($current -eq $script:RECOMMENDED_JVM_ARGS) { Write-Info 'Microsoft launcher profile JVM args already match launcher defaults; no change.'; return }
    if (-not $current) {
        Write-Info 'Applying launcher default JVM args to Microsoft launcher profile.'
        Write-MsLauncherJvmArgs -ModLoader $ModLoader
        return
    }
    if (Test-InstallerJvmArgs -JvmArgs $current -For Microsoft) {
        Write-Info 'Replacing the JVM args an earlier installer wrote to the Microsoft launcher profile with launcher defaults.'
        Write-MsLauncherJvmArgs -ModLoader $ModLoader
        Write-Info "Previous JVM args, kept in launcher_profiles.json.bak: $current"
        return
    }
    if ($Unattended) { Write-Info 'Microsoft launcher JVM args differ; keeping existing (unattended).'; return }
    if (Confirm-JvmArgsOverwrite -Label "Microsoft launcher profile '$key'" -Current $current -Recommended $script:RECOMMENDED_JVM_ARGS) {
        Write-MsLauncherJvmArgs -ModLoader $ModLoader
        Write-Info 'Microsoft launcher profile JVM args updated to launcher defaults.'
    } else {
        Write-Info 'Keeping existing Microsoft launcher JVM args.'
    }
}

function Get-InstallerJvmArgsSets {
    # Every set of recommended JVM args an installer has shipped, oldest
    # first: PREVIOUS_RECOMMENDED_JVM_ARGS, then RECOMMENDED_JVM_ARGS.
    return @($script:PREVIOUS_RECOMMENDED_JVM_ARGS) + @($script:RECOMMENDED_JVM_ARGS)
}

function Test-InstallerJvmArgs {
    <#
      Whether $JvmArgs are recommended JVM args exactly as an installer wrote
      them to $For: a set from Get-InstallerJvmArgsSets as it is, or, in a
      Prism instance, also without its heap sizes (Get-PrismJvmArgs), as this
      installer writes them there, or the JvmArgs its $Record holds.
      Compared case-insensitively, like the other JvmArgs matches here.
    #>
    param(
        [string]$JvmArgs,
        [ValidateSet('Prism','Microsoft')] [string]$For,
        [System.Collections.IDictionary]$Record
    )
    if ($For -eq 'Prism' -and $Record -and $Record.Contains('JvmArgs') -and $JvmArgs -eq $Record['JvmArgs']) {
        return $true
    }
    foreach ($set in Get-InstallerJvmArgsSets) {
        if ($JvmArgs -eq $set) { return $true }
        if ($For -eq 'Prism' -and $JvmArgs -eq (Get-PrismJvmArgs -JvmArgs $set)) { return $true }
    }
    return $false
}

function Test-PrismMemoryRecorded {
    # Whether the instance memory in $Text is still exactly what $Record says
    # the installer wrote: all three settings recorded, and all equal.
    param([string]$Text, [System.Collections.IDictionary]$Record)
    foreach ($key in 'OverrideMemory', 'MinMemAlloc', 'MaxMemAlloc') {
        if (-not ($Record -and $Record.Contains($key))) { return $false }
        if ((Get-PrismCfgValue -Text $Text -Key $key) -ne $Record[$key]) { return $false }
    }
    return $true
}

function Get-PrismInstallerRecordPath {
    param([string]$Cfg)
    return Join-Path (Split-Path -Parent $Cfg) $script:PRISM_INSTALLER_RECORD
}

function Read-PrismInstallerRecord {
    <#
      The installer's record beside $Cfg (PRISM_INSTALLER_RECORD) as a
      hashtable of the PRISM_INSTALLER_RECORD_KEYS it holds. Empty when there
      is no record, and, after a warning unless -Quiet, when it cannot be
      read, so that memory set on the instance then counts as the user's. A
      record is readable only as a JSON object whose formatVersion is 1 and
      whose keys hold strings, as Write-PrismInstallerRecord writes it; a
      record of any other version is not one this installer understands.
    #>
    param([string]$Cfg, [switch]$Quiet)
    $record = @{}
    $path = Get-PrismInstallerRecordPath -Cfg $Cfg
    if (-not (Test-Path -LiteralPath $path)) { return $record }
    $problem = $null
    try {
        $json = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false)) | ConvertFrom-Json -ErrorAction Stop
        if ($json -isnot [System.Management.Automation.PSCustomObject]) { $problem = 'it holds no JSON object' }
    } catch {
        $problem = $_.Exception.Message
    }
    if (-not $problem) {
        $version = $json.PSObject.Properties['formatVersion']
        if (-not ($version -and ($version.Value -is [int] -or $version.Value -is [long]) -and $version.Value -eq 1)) {
            $problem = 'its formatVersion is not 1'
        }
    }
    if (-not $problem) {
        foreach ($key in $script:PRISM_INSTALLER_RECORD_KEYS) {
            $property = $json.PSObject.Properties[$key]
            if (-not $property) { continue }
            if ($property.Value -isnot [string]) { $problem = "its $key is not a string"; break }
            $record[$key] = $property.Value
        }
    }
    if ($problem) {
        if (-not $Quiet) {
            Write-Warn "Could not read $($script:PRISM_INSTALLER_RECORD) beside the Prism instance's instance.cfg ($problem); memory set on the instance counts as yours."
        }
        return @{}
    }
    return $record
}

function Write-PrismInstallerRecord {
    # Record in the installer's record beside $Cfg that it wrote $Values to
    # the instance, keeping what the record already holds for other keys.
    param([string]$Cfg, [System.Collections.IDictionary]$Values)
    $record = Read-PrismInstallerRecord -Cfg $Cfg -Quiet
    foreach ($key in $Values.Keys) { $record[$key] = [string]$Values[$key] }
    $ordered = [ordered]@{ formatVersion = 1 }
    foreach ($key in $script:PRISM_INSTALLER_RECORD_KEYS) {
        if ($record.Contains($key)) { $ordered[$key] = $record[$key] }
    }
    Write-FileAtomic -Path (Get-PrismInstallerRecordPath -Cfg $Cfg) -Content (ConvertTo-Json -InputObject $ordered)
}

function Backup-PrismCfg {
    # Keep instance.cfg as instance.cfg.bak before the installer replaces
    # something there without asking. Export leaves instance.cfg.bak out.
    param([string]$Cfg)
    Copy-Item -LiteralPath $Cfg -Destination "$Cfg.bak" -Force
}

function Write-PrismJvmArgs {
    param([string]$Cfg)
    $jvmArgs = Get-PrismJvmArgs -JvmArgs $script:RECOMMENDED_JVM_ARGS
    Set-PrismCfgValues -Cfg $Cfg -Values ([ordered]@{
        OverrideJavaArgs = 'true'
        JvmArgs          = $jvmArgs
    })
    Write-PrismInstallerRecord -Cfg $Cfg -Values @{ JvmArgs = $jvmArgs }
}

function Get-PrismJvmArgs {
    <#
      $JvmArgs as a Prism instance's JvmArgs: in their order, without the
      heap sizes, which Prism gives the game from the instance's memory
      settings instead (Get-PrismMemorySettings). Prism refuses to launch
      with -Xmx, -Xms or -XX:InitialHeapSize in JvmArgs
      (Get-PrismRefusedJvmArgs), and the -Xmx it appends overrides
      -XX:MaxHeapSize.
    #>
    param([string]$JvmArgs)
    $heapOptions = $script:MAX_HEAP_OPTIONS + $script:MIN_HEAP_OPTIONS
    $kept = @(-split $JvmArgs | Where-Object {
        $argument = $_
        -not ($heapOptions | Where-Object { $argument.StartsWith($_, [StringComparison]::Ordinal) })
    })
    return $kept -join ' '
}

function Get-PrismRefusedJvmArgs {
    # The arguments in $JvmArgs with which Prism refuses to launch the
    # instance. No pattern in PRISM_REFUSED_JVM_ARG spans whitespace, so these
    # are exactly what Prism's check of the whole value matches.
    param([string]$JvmArgs)
    return @(-split $JvmArgs | Where-Object { [regex]::IsMatch($_, $script:PRISM_REFUSED_JVM_ARG) })
}

function Get-PrismJvmArgsRefusal {
    # A sentence naming what in $JvmArgs Prism refuses to launch the instance
    # with, or '' when Prism would launch with them.
    param([string]$JvmArgs)
    $refused = @(Get-PrismRefusedJvmArgs -JvmArgs $JvmArgs)
    if ($refused.Count -eq 0) { return '' }
    return "Prism refuses to launch the instance while its JVM args hold $($refused -join ' ')."
}

function Sync-PrismMemory {
    <#
      Reconcile the memory of an instance whose JvmArgs are the recommended
      set, because they already matched or were just written in place of
      none or of those an earlier installer wrote:
        MATCH                             -> no-op
        INSTALLER'S (as recorded)         -> replace silently
        UNSET (no OverrideMemory)         -> apply silently
        OWN MEMORY (OverrideMemory=true)  -> keep
        GLOBAL (any other OverrideMemory) -> keep Prism's global memory
      Memory is the installer's only while it is what $Record, the
      installer's record of what it last wrote, holds
      (Test-PrismMemoryRecorded). Any other memory was saved in Prism, even
      when it equals what some set of recommended args asks for, and is the
      user's. Without a readable record that leaves only UNSET for the
      installer. Replacing the installer's memory keeps instance.cfg.bak
      first unless the caller already has (-BackedUp). Prism writes
      OverrideMemory=false when the instance's Java settings are applied with
      Memory unticked.
    #>
    param([string]$Cfg, [System.Collections.IDictionary]$Record, [switch]$BackedUp)
    $memory = Get-PrismMemorySettings -JvmArgs $script:RECOMMENDED_JVM_ARGS
    if ($memory.Count -eq 0) { return }
    $text = Read-PrismCfg -Cfg $Cfg
    $differing = @($memory.Keys | Where-Object { (Get-PrismCfgValue -Text $text -Key $_) -ne $memory[$_] })
    if ($differing.Count -eq 0) { Write-Info 'Prism instance memory already matches the recommended heap; no change.'; return }
    if (Test-PrismMemoryRecorded -Text $text -Record $Record) {
        $previous = "minimum $(Get-PrismCfgValue -Text $text -Key 'MinMemAlloc') MiB, maximum $(Get-PrismCfgValue -Text $text -Key 'MaxMemAlloc') MiB"
        if (-not $BackedUp) { Backup-PrismCfg -Cfg $Cfg }
        Write-Info "Replacing the memory the installer set earlier on the Prism instance ($previous, kept in instance.cfg.bak)."
        Write-PrismMemory -Cfg $Cfg
        return
    }
    $override = Get-PrismCfgValue -Text $text -Key 'OverrideMemory'
    if ($override -eq 'true') {
        Write-Info "Keeping the memory set on the Prism instance, which gives the game its heap; the recommended maximum is $($memory.MaxMemAlloc) MiB."
        return
    }
    if ($null -ne $override) {
        Write-Info "Keeping Prism's global memory for the instance, as chosen in Prism. It gives the game its heap; the recommended maximum is $($memory.MaxMemAlloc) MiB."
        return
    }
    Write-PrismMemory -Cfg $Cfg
}

function Write-PrismMemory {
    param([string]$Cfg)
    $memory = Get-PrismMemorySettings -JvmArgs $script:RECOMMENDED_JVM_ARGS
    if ($memory.Count -eq 0) { return }
    Set-PrismCfgValues -Cfg $Cfg -Values $memory
    Write-PrismInstallerRecord -Cfg $Cfg -Values $memory
    Write-Info "Prism instance memory set to $($memory.MaxMemAlloc) MiB, the heap the recommended JVM args ask for."
}

function Get-PrismMemorySettings {
    <#
      The instance memory settings that give the game the heap $JvmArgs set.
      Prism refuses to launch with -Xmx or -Xms in the instance's JvmArgs, so
      the installer leaves the heap sizes out there (Get-PrismJvmArgs), and
      Prism appends -Xms<MinMemAlloc>m -Xmx<MaxMemAlloc>m from these
      settings, enabled by OverrideMemory. Without them the instance takes
      Prism's global memory (a 4096 MiB maximum by default with 6 GiB of RAM
      or more). The minimum is Prism's default when $JvmArgs set none, and
      never exceeds the maximum, because Prism swaps the two when it does.
      Empty when $JvmArgs leave the heap to the launcher.
      LowMemWarning stays Prism's: a player keeps its low-memory prompt, which
      the developer tools turn off only because their launches have no window.
    #>
    param([string]$JvmArgs)
    $maxMiB = Get-HeapMiB -JvmArgs $JvmArgs -Options $script:MAX_HEAP_OPTIONS
    if (-not $maxMiB) { return [ordered]@{} }
    $minMiB = Get-HeapMiB -JvmArgs $JvmArgs -Options $script:MIN_HEAP_OPTIONS
    if ($null -eq $minMiB) { $minMiB = $script:PRISM_MIN_MEM_MIB }
    return [ordered]@{
        OverrideMemory = 'true'
        MinMemAlloc    = [string][math]::Min($minMiB, $maxMiB)
        MaxMemAlloc    = [string]$maxMiB
    }
}

function Get-HeapMiB {
    # The heap size in MiB, rounded up, that the last of $Options in $JvmArgs
    # sets, or $null when none does. A size the JVM would reject is an error.
    param([string]$JvmArgs, [string[]]$Options)
    $arguments = @(-split $JvmArgs)
    [array]::Reverse($arguments)
    foreach ($argument in $arguments) {
        $option = $Options | Where-Object { $argument.StartsWith($_, [StringComparison]::Ordinal) } | Select-Object -First 1
        if (-not $option) { continue }
        $size = [regex]::Match($argument.Substring($option.Length), '^(\d+)([kmgt]?)$', 'IgnoreCase')
        if (-not $size.Success) { throw "$argument is not a heap size the JVM accepts." }
        $unitBytes = @{ '' = 1; 'k' = 1KB; 'm' = 1MB; 'g' = 1GB; 't' = 1TB }[$size.Groups[2].Value]
        return [int64][math]::Ceiling([int64]$size.Groups[1].Value * $unitBytes / 1MB)
    }
    return $null
}

function Read-PrismCfg {
    # Prism writes instance.cfg as UTF-8 without a BOM, which Windows
    # PowerShell would otherwise decode in the ANSI code page.
    param([string]$Cfg)
    return [System.IO.File]::ReadAllText($Cfg, [System.Text.UTF8Encoding]::new($false))
}

function Get-PrismCfgLines {
    <#
      instance.cfg's lines, each with its line ending, marked by whether it
      lies in the instance's own settings. Prism reads a saved instance.cfg
      through QSettings, where keys before the first section header or under
      [General] are the instance's settings and a key under any other section
      becomes Section/Key (INIFile::loadFile in Prism's
      launcher/settings/INIFile.cpp). The file Initialize-PrismInstance
      creates has no section headers.
    #>
    param([string]$Text)
    $section = 'General'
    foreach ($line in [regex]::Split($Text, '(?<=\n)')) {
        if ($line -eq '') { continue }
        $header = [regex]::Match($line, '^\s*\[([^\]]*)\]\s*$')
        if ($header.Success) { $section = $header.Groups[1].Value }
        [pscustomobject]@{ Text = $line; IsHeader = $header.Success; InGeneral = $section -eq 'General' }
    }
}

function Get-PrismCfgValue {
    # The instance's own setting for $Key, or $null when it has none.
    param([string]$Text, [string]$Key)
    $pattern = "^\s*$([regex]::Escape($Key))\s*=([^\r\n]*)"
    foreach ($line in @(Get-PrismCfgLines -Text $Text)) {
        if (-not $line.InGeneral) { continue }
        $match = [regex]::Match($line.Text, $pattern, 'IgnoreCase')
        if ($match.Success) { return $match.Groups[1].Value.Trim() }
    }
    return $null
}

function Set-PrismCfgValues {
    <#
      Set the instance's own settings: replace each key's line in [General],
      or add it after the last setting there. Every other line keeps its text
      and line ending.
    #>
    param([string]$Cfg, [System.Collections.IDictionary]$Values)
    $text = Read-PrismCfg -Cfg $Cfg
    $newline = if ($text.Contains("`r`n") -or -not $text.Contains("`n")) { "`r`n" } else { "`n" }
    $lines = [System.Collections.Generic.List[string]]::new()
    $missing = [System.Collections.Generic.List[string]]::new([string[]]@($Values.Keys))
    $insertAt = 0
    foreach ($line in @(Get-PrismCfgLines -Text $text)) {
        $content = $line.Text
        if ($line.InGeneral -and -not $line.IsHeader) {
            foreach ($key in $Values.Keys) {
                if ($content -match "^\s*$([regex]::Escape($key))\s*=") {
                    $content = "$key=$($Values[$key])" + [regex]::Match($line.Text, '\r?\n$').Value
                    [void]$missing.Remove($key)
                }
            }
        }
        $lines.Add($content)
        if ($line.InGeneral -and $content.Trim()) { $insertAt = $lines.Count }
    }
    if ($missing.Count -gt 0) {
        $added = [string[]]@($missing | ForEach-Object { "$_=$($Values[$_])$newline" })
        if ($insertAt -gt 0 -and -not $lines[$insertAt - 1].EndsWith("`n")) {
            # The settings end the file without a final line ending; so do the added ones.
            $lines[$insertAt - 1] += $newline
            $added[-1] = $added[-1].Substring(0, $added[-1].Length - $newline.Length)
        }
        $lines.InsertRange($insertAt, $added)
    }
    Write-FileAtomic -Path $Cfg -Content (-join $lines)
}

function Write-MsLauncherJvmArgs {
    # Atomic, BOM-free rewrite of launcher_profiles.json. The launcher writes
    # this file itself on shutdown, so an in-place write during launcher
    # runtime can corrupt it.
    param([Parameter(Mandatory)] [string]$ModLoader)
    $lp  = Get-MsLauncherProfilesPath
    try {
        # The launcher writes BOM-less UTF-8. Windows PowerShell 5.1 would
        # otherwise decode it through the active ANSI code page.
        $raw  = [System.IO.File]::ReadAllText($lp, [System.Text.UTF8Encoding]::new($false))
        $json = $raw | ConvertFrom-Json -ErrorAction Stop
    } catch {
        Write-Warn 'Could not parse launcher_profiles.json; JVM args update skipped.'
        return
    }
    $key = Get-MsProfileKey -ModLoader $ModLoader -Profiles $json.profiles
    if (-not $json.PSObject.Properties['profiles'] -or -not $json.profiles -or -not $json.profiles.PSObject.Properties[$key]) {
        Write-Trace "MS launcher profile '$key' not present; JVM args update skipped."
        return
    }
    $json.profiles.$key | Add-Member -NotePropertyName javaArgs -NotePropertyValue $script:RECOMMENDED_JVM_ARGS -Force
    $out = ($json | ConvertTo-Json -Depth 32)
    Write-FileAtomic -Path $lp -Content $out -Backup
}

function Confirm-JvmArgsOverwrite {
    param([string]$Label, [string]$Current, [string]$Recommended, [string[]]$Note)
    Write-Host ''
    Write-Host "  $($script:ICON_WARN) $($script:C_YELLOW)$Label$($script:C_RESET) has custom JVM args that differ from the Minecraft $($script:MC_VERSION) launcher defaults."
    Write-Host ''
    Write-Host "     $($script:C_BOLD)Current:$($script:C_RESET)     $($script:C_DIM)$Current$($script:C_RESET)"
    Write-Host "     $($script:C_BOLD)Recommended:$($script:C_RESET) $($script:C_DIM)$Recommended$($script:C_RESET)"
    Write-Host ''
    Write-Host "  Minecraft $($script:MC_VERSION) launcher defaults use G1GC and a 2 GB heap. Custom"
    Write-Host '  args may include profiling flags, test toggles, or more memory.'
    foreach ($line in $Note) { if ($line) { Write-Host "  $line" } }
    return (Confirm-YesNo -Prompt 'Overwrite current JVM args with launcher defaults?' -DefaultNo)
}

function Initialize-Prism {
    param([pscustomobject]$State, [switch]$Unattended, [Parameter(Mandatory)] [string]$ModLoader)
    # $State is [pscustomobject] (ref-typed). We intentionally mutate
    # .PrismDataDir below so the caller sees the resolved value.
    if ($State.PrismDataDir -and $State.PrismDataDir -ne $script:PRISM_PORTABLE_DIR) {
        Write-Info "Using existing Prism install: $($State.PrismDataDir)"
    } elseif (Test-Path -LiteralPath (Join-Path $script:PRISM_PORTABLE_DIR 'prismlauncher.exe')) {
        Write-Info "Using existing Prism portable install at $($script:PRISM_PORTABLE_DIR)"
    } else {
        Install-PrismPortable
    }
    if (-not $State.PrismDataDir) { $State.PrismDataDir = $script:PRISM_PORTABLE_DIR }
    $instanceDir = Get-PrismInstanceDir -DataDir $State.PrismDataDir
    Initialize-PrismInstance -InstanceDir $instanceDir -ModLoader $ModLoader
    # The record of a Prism named at the launcher question lives and dies with
    # the instance: written once the instance is there, and removed with it by
    # Remove-PrismInstance.
    if ($State.PrismNamed -and (Test-Path -LiteralPath (Join-Path $instanceDir 'instance.cfg'))) {
        Write-FileAtomic -Path $script:PRISM_DATA_DIR_RECORD -Content $State.PrismDataDir
    }
    Write-Info "Configuring Minecraft $($script:MC_VERSION) launcher JVM defaults..."
    Sync-JvmArgs -For Prism -PrismCfg (Join-Path $instanceDir 'instance.cfg') -Unattended:$Unattended
    Set-PrismJavaPin -InstanceCfg (Join-Path $instanceDir 'instance.cfg')
    return $instanceDir
}

function Test-MsFabricProfileRegistered {
    # Returns $true if launcher_profiles.json has a fabric-loader-{ver}-{mc}
    # profile entry. The Fabric installer writes both a versions/ dir AND a
    # profiles.* entry; a user who deletes the profile manually leaves the
    # versions dir behind, so version-dir-presence alone is NOT a reliable
    # signal that the launcher knows about Fabric.
    $lp = Get-MsLauncherProfilesPath
    if (-not (Test-Path -LiteralPath $lp)) { return $false }
    try {
        $json = Get-Content -LiteralPath $lp -Raw | ConvertFrom-Json -ErrorAction Stop
    } catch { return $false }
    if (-not $json.PSObject.Properties['profiles'] -or -not $json.profiles) { return $false }
    $key = (Get-LoaderProvider -ModLoader Fabric).MicrosoftProfileKey
    return [bool]$json.profiles.PSObject.Properties[$key]
}

function Initialize-MicrosoftLauncher {
    param(
        [string]$JavaExe,
        [switch]$Unattended,
        [Parameter(Mandatory)] [string]$ModLoader
    )
    $provider = Get-LoaderProvider -ModLoader $ModLoader
    if (-not (Test-Path -LiteralPath (Get-MsLauncherProfilesPath))) {
        throw 'Microsoft Minecraft launcher not detected. Install it first from https://www.minecraft.net/en-us/download'
    }
    if ($provider.MicrosoftKind -eq 'Installer') {
        Initialize-MicrosoftLoaderInstaller -JavaExe $JavaExe -ModLoader $provider.DisplayName
        Ensure-MsLoaderProfile -ModLoader $provider.DisplayName
        Remove-OtherManagedMsProfiles -SelectedModLoader $provider.DisplayName
        Write-Info "Configuring Minecraft $($script:MC_VERSION) launcher JVM defaults on the $($provider.DisplayName) profile..."
        Sync-JvmArgs -For Microsoft -ModLoader $provider.DisplayName -Unattended:$Unattended
        return
    }
    if ($provider.MicrosoftKind -ne 'Fabric') {
        throw "Loader '$($provider.DisplayName)' has no Microsoft launcher provider."
    }
    # Both conditions are required. Users who delete a Fabric profile entry in
    # the launcher UI leave the versions/ dir behind; if we only checked that,
    # we'd short-circuit and the launcher UI would have no entry to select.
    $versionDir   = Join-Path $env:APPDATA ".minecraft\versions\$($provider.VersionId)"
    $hasVersion   = Test-Path -LiteralPath $versionDir
    $hasProfile   = Test-MsFabricProfileRegistered
    if ($hasVersion -and $hasProfile) {
        Write-Info "Reusing existing $($provider.DisplayName) $($provider.LoaderVersion) for MC $($script:MC_VERSION)"
    } else {
        if ($hasVersion -and -not $hasProfile) {
            Write-Info "$($provider.DisplayName) $($provider.LoaderVersion) version dir exists but no launcher profile entry; re-running installer to re-register it."
        } else {
            Write-Info 'Downloading Fabric installer...'
        }
        $url = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/$($script:FABRIC_INSTALLER_VER)/fabric-installer-$($script:FABRIC_INSTALLER_VER).jar"
        $jar = New-SessionTempPath 'fabric-installer.jar'
        $pinned = if ($script:FABRIC_INSTALLER_SHA256) { $script:FABRIC_INSTALLER_SHA256 } else { $null }
        Invoke-VerifiedDownload -Url $url -Destination $jar -ExpectedSha256 $pinned -ChecksumUrl "$url.sha256"
        # NOT -noprofile: we want the fabric-loader profile registered in
        # launcher_profiles.json so it appears in the launcher UI. The
        # installer is idempotent, safe to re-run when the version dir
        # already exists; it just updates/re-registers the profile entry.
        # Captured, not left in the output stream: its lines would otherwise be
        # returned by Invoke-InstallOrUpgrade along with the exit code.
        $fabricOut = & $JavaExe -jar $jar client -mcversion $script:MC_VERSION -loader $provider.LoaderVersion 2>&1
        if ($LASTEXITCODE -ne 0) {
            $fabricOut | ForEach-Object { Write-Warn "$_" }
            throw 'Fabric installer execution failed (non-zero exit).'
        }
    }
    Set-MsLauncherProfileName
    Remove-OtherManagedMsProfiles -SelectedModLoader $provider.DisplayName
    Write-Info "Configuring Minecraft $($script:MC_VERSION) launcher JVM defaults on the Fabric profile..."
    Sync-JvmArgs -For Microsoft -ModLoader $provider.DisplayName -Unattended:$Unattended
}

function Initialize-MicrosoftLoaderInstaller {
    param(
        [Parameter(Mandatory)] [string]$JavaExe,
        [Parameter(Mandatory)] [string]$ModLoader
    )
    $provider = Get-LoaderProvider -ModLoader $ModLoader
    if ($provider.MicrosoftKind -ne 'Installer') {
        throw "Loader '$($provider.DisplayName)' does not use a Microsoft launcher installer."
    }
    $versionDir = Join-Path $env:APPDATA ".minecraft\versions\$($provider.VersionId)"
    $hasVersion = Test-Path -LiteralPath $versionDir
    $hasProfile = (Get-MicrosoftLoaders) -contains $provider.DisplayName
    if ($hasVersion -and $hasProfile) {
        Write-Info "Reusing existing $($provider.DisplayName) $($provider.LoaderVersion) for MC $($script:MC_VERSION)"
        return
    }
    if ($hasVersion) {
        Write-Info "$($provider.DisplayName) version files exist without a matching launcher profile; re-running the installer to repair registration."
    }
    Write-Info "Downloading pinned $($provider.DisplayName) installer $($script:MC_VERSION)-$($provider.LoaderVersion)..."
    $releaseVersion = if ($provider.InstallerReleaseIncludesMinecraft) {
        "$($script:MC_VERSION)-$($provider.LoaderVersion)"
    } else {
        $provider.LoaderVersion
    }
    $artifact = "$($provider.InstallerArtifact)-$releaseVersion-installer.jar"
    $url = "$($provider.InstallerRepository)/$($provider.InstallerGroupPath)/$releaseVersion/$artifact"
    $jar = New-SessionTempPath "$($provider.Provider)-installer.jar"
    $pinned = if ($provider.InstallerPinnedSha256) { $provider.InstallerPinnedSha256 } else { $null }
    Invoke-VerifiedDownload -Url $url -Destination $jar -ExpectedSha256 $pinned -ChecksumUrl "$url.sha256"
    $mcRoot = Join-Path $env:APPDATA '.minecraft'
    $installerOut = & $JavaExe -jar $jar $provider.InstallerClientArg $mcRoot 2>&1
    if ($LASTEXITCODE -ne 0) {
        $installerOut | ForEach-Object { Write-Warn "$_" }
        throw "$($provider.DisplayName) installer execution failed (non-zero exit)."
    }
    if (-not (Test-Path -LiteralPath $versionDir)) {
        throw "$($provider.DisplayName) installer reported success but did not create $versionDir."
    }
}

function Ensure-MsLoaderProfile {
    # Loader installers have changed whether they register a launcher profile.
    # Ensure one visible custom profile targets the pinned version either way.
    param([Parameter(Mandatory)] [string]$ModLoader)
    $lp = Get-MsLauncherProfilesPath
    try {
        $raw = [IO.File]::ReadAllText($lp, [Text.UTF8Encoding]::new($false))
        $json = $raw | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "Could not parse launcher_profiles.json while registering ${ModLoader}: $($_.Exception.Message)"
    }
    if (-not $json.PSObject.Properties['profiles'] -or -not $json.profiles) {
        $json | Add-Member -NotePropertyName profiles -NotePropertyValue ([pscustomobject]@{}) -Force
    }
    $provider = Get-LoaderProvider -ModLoader $ModLoader
    if ($provider.MicrosoftKind -ne 'Installer') {
        throw "Loader '$($provider.DisplayName)' does not use a managed Microsoft launcher profile."
    }
    $key = Get-MsProfileKey -ModLoader $provider.DisplayName -Profiles $json.profiles
    if (-not $json.profiles.PSObject.Properties[$key]) {
        $profile = [pscustomobject][ordered]@{
            created       = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
            icon          = 'Furnace'
            lastUsed      = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
            lastVersionId = $provider.VersionId
            name          = $provider.MicrosoftProfileName
            type          = 'custom'
        }
        $json.profiles | Add-Member -NotePropertyName $provider.MicrosoftProfileKey -NotePropertyValue $profile
    } else {
        $profile = $json.profiles.$key
        $profile | Add-Member -NotePropertyName lastVersionId -NotePropertyValue $provider.VersionId -Force
        if (-not $profile.PSObject.Properties['name'] -or [string]::IsNullOrWhiteSpace($profile.name) -or
            $profile.name -match '^(neo)?forge') {
            $profile | Add-Member -NotePropertyName name -NotePropertyValue $provider.MicrosoftProfileName -Force
        }
        $profile | Add-Member -NotePropertyName type -NotePropertyValue 'custom' -Force
    }
    Write-FileAtomic -Path $lp -Content ($json | ConvertTo-Json -Depth 32) -Backup
    Write-Info "Microsoft launcher $($provider.DisplayName) profile is available as '$($profile.name)'."
}

function Remove-OtherManagedMsProfiles {
    param([Parameter(Mandatory)] [string]$SelectedModLoader)
    $selectedProvider = Get-LoaderProvider -ModLoader $SelectedModLoader
    $lp = Get-MsLauncherProfilesPath
    if (-not (Test-Path -LiteralPath $lp -PathType Leaf)) { return }
    try {
        $json = [IO.File]::ReadAllText($lp, [Text.UTF8Encoding]::new($false)) | ConvertFrom-Json -ErrorAction Stop
    } catch {
        Write-Warn "Could not parse launcher_profiles.json while reconciling loader profiles: $($_.Exception.Message)"
        return
    }
    if (-not $json.PSObject.Properties['profiles'] -or -not $json.profiles) { return }

    $removed = @()
    foreach ($property in @($json.profiles.PSObject.Properties)) {
        $profile = $property.Value
        $name = if ($profile -and $profile.PSObject.Properties['name']) { [string]$profile.name } else { '' }
        $lastVersion = if ($profile -and $profile.PSObject.Properties['lastVersionId']) {
            [string]$profile.lastVersionId
        } else { '' }
        $managedOther = $false
        foreach ($provider in $script:LOADER_CATALOG.Values | Where-Object Id -ne $selectedProvider.Id) {
            $managedNames = @($provider.MicrosoftProfileName, 'NVIDIA RTX Remix', 'RTX Remix')
            if (($property.Name -eq $provider.MicrosoftProfileKey -or $lastVersion -eq $provider.VersionId) -and
                $name -in $managedNames) {
                $managedOther = $true
                break
            }
        }
        if ($managedOther) {
            [void]$json.profiles.PSObject.Properties.Remove($property.Name)
            $removed += $property.Name
        }
    }
    if ($removed.Count -eq 0) { return }
    Write-FileAtomic -Path $lp -Content ($json | ConvertTo-Json -Depth 32) -Backup
    Write-Info "Removed the superseded managed Microsoft launcher profile: $($removed -join ', ')"
}

function Set-MsLauncherProfileName {
    <#
      Rename the Fabric profile in launcher_profiles.json from the Fabric
      installer's default (typically 'fabric-loader-<mc>', same as the
      profile key) to our user-facing label. Also treats prior names we've
      shipped as "default" so re-running the installer after we rename
      updates cleanly. Any other name is treated as a user customization
      and left alone.
    #>
    $lp = Get-MsLauncherProfilesPath
    if (-not (Test-Path -LiteralPath $lp)) { return }
    try {
        $raw = Get-Content -LiteralPath $lp -Raw
        $json = $raw | ConvertFrom-Json -ErrorAction Stop
    } catch {
        Write-Warn "Could not parse launcher_profiles.json; profile rename skipped."
        return
    }
    $provider = Get-LoaderProvider -ModLoader Fabric
    $key = $provider.MicrosoftProfileKey
    if (-not $json.PSObject.Properties['profiles'] -or -not $json.profiles -or -not $json.profiles.PSObject.Properties[$key]) {
        Write-Trace "MS launcher profile '$key' not present; profile rename skipped."
        return
    }
    $profileObj = $json.profiles.$key
    $currentName = if ($profileObj.PSObject.Properties['name']) { $profileObj.name } else { '' }
    $defaults = @(
        $key                                              # Fabric installer default (e.g. 'fabric-loader-1.21.1')
        ''                                                # empty name
        'fabric-loader'                                   # legacy Fabric installer default
        "fabric-loader-$($provider.LoaderVersion)-$($script:MC_VERSION)"  # older loader-included key
        'NVIDIA RTX Remix'                                # our prior label
        'RTX Remix'                                       # any earlier labels we might have used
    )
    if ($currentName -eq $provider.MicrosoftProfileName) {
        Write-Trace "MS launcher profile already named '$($provider.MicrosoftProfileName)'; no change."
        return
    }
    if ($defaults -notcontains $currentName) {
        Write-Info "MS launcher profile has a custom name ('$currentName'); leaving untouched. Rename manually in the launcher UI if you'd prefer '$($provider.MicrosoftProfileName)'."
        return
    }
    $profileObj | Add-Member -NotePropertyName name -NotePropertyValue $provider.MicrosoftProfileName -Force
    $out = ($json | ConvertTo-Json -Depth 32)
    Write-FileAtomic -Path $lp -Content $out -Backup
    Write-Info "Renamed Microsoft launcher profile to '$($provider.MicrosoftProfileName)'."
}

function Install-ModFiles {
    param([string]$McDir, [Parameter(Mandatory)] [string]$ModLoader)
    $provider = Get-LoaderProvider -ModLoader $ModLoader
    Write-Info "Installing mod files to $McDir"
    if (-not (Test-Path -LiteralPath $McDir))                    { New-Item -ItemType Directory -Path $McDir -Force | Out-Null }
    if (-not (Test-Path -LiteralPath (Join-Path $McDir 'mods'))) { New-Item -ItemType Directory -Path (Join-Path $McDir 'mods') -Force | Out-Null }

    # Resolve + validate the new JAR BEFORE touching anything. A broken dist
    # must not turn an upgrade into a destructive failure.
    $newJar = Get-DistModJar -ModLoader $ModLoader
    if (-not $newJar) { throw "No $ModLoader rtx-remix-renderer JAR found in $(Join-Path $script:SCRIPT_DIR $provider.ArtifactDirectory)" }

    # Stage beside the target. Keep every working old JAR until the new JAR and
    # loader-specific resource-pack migration both succeed.
    $finalPath  = Join-Path $McDir "mods\$($provider.InstalledJar)"
    $stagedPath = "$finalPath.new"
    $rollbackPath = "$finalPath.rollback"
    if (Test-Path -LiteralPath $stagedPath) { Remove-Item -LiteralPath $stagedPath -Force }
    if (Test-Path -LiteralPath $rollbackPath) { Remove-Item -LiteralPath $rollbackPath -Force }
    Copy-Item -LiteralPath $newJar -Destination $stagedPath -Force -ErrorAction Stop
    $oldJars = @(Get-ChildItem -LiteralPath (Join-Path $McDir 'mods') -Filter 'rtx-remix-renderer-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -ne $stagedPath })
    if (Test-Path -LiteralPath $finalPath) {
        Move-Item -LiteralPath $finalPath -Destination $rollbackPath -Force -ErrorAction Stop
    }
    # The staged file can vanish or stay locked for a moment: real-time
    # antivirus scanning or quarantining a fresh JAR, or a second installer
    # run sharing the same .new name. Re-stage and retry before giving up.
    $moved = $false
    for ($try = 1; $try -le 3 -and -not $moved; $try++) {
        try {
            if (-not (Test-Path -LiteralPath $stagedPath)) {
                Copy-Item -LiteralPath $newJar -Destination $stagedPath -Force -ErrorAction Stop
            }
            Move-Item -LiteralPath $stagedPath -Destination $finalPath -Force -ErrorAction Stop
            $moved = $true
        } catch {
            Write-Trace "Staging attempt $try failed: $($_.Exception.Message)"
            Start-Sleep -Milliseconds 500
        }
    }
    if (-not $moved) {
        if (Test-Path -LiteralPath $rollbackPath) {
            Move-Item -LiteralPath $rollbackPath -Destination $finalPath -Force
        }
        throw "Could not place $($provider.InstalledJar) in $(Join-Path $McDir 'mods'): the staged file kept disappearing or stayed locked. Antivirus real-time protection may be quarantining it (check Windows Security > Protection history), or another installer run is still going. Close other installers, allow the file, and run the installer again."
    }
    try {
        & (Join-Path $script:SCRIPT_DIR 'migrate_resource_packs.ps1') `
            -McDir $McDir `
            -LegacyPackName (Split-Path $script:LEGACY_RESOURCE_PACK_PATH -Leaf) `
            -MinecraftVersion $script:MC_VERSION `
            -ModLoader $ModLoader
    } catch {
        Remove-Item -LiteralPath $finalPath -Force -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $rollbackPath) {
            Move-Item -LiteralPath $rollbackPath -Destination $finalPath -Force
        }
        throw
    }
    $oldJars | Where-Object { $_.FullName -ne $finalPath } | ForEach-Object {
        Write-Info "Replacing old mod JAR: $($_.Name)"
        Remove-Item -LiteralPath $_.FullName -Force
    }
    if (Test-Path -LiteralPath $rollbackPath) { Remove-Item -LiteralPath $rollbackPath -Force }
    Write-Info "Installed $($provider.InstalledJar)"
}

function Install-FabricApi {
    <#
      For Prism (isolated instance): prune older fabric-api-*.jar whether or
      not we download a new one: a half-succeeded prior install could have
      left both versions behind, and Fabric loader's classpath ordering
      warnings aren't worth ignoring. For Microsoft launcher (shared
      .minecraft/mods): install ours alongside; DO NOT prune others, since
      other Fabric profiles on the same shared dir would break.
    #>
    param([string]$McDir, [ValidateSet('Prism','Microsoft')] [string]$Launcher)
    $provider = @($script:LOADER_CATALOG.Values | Where-Object { $_.Companion -and $_.Companion.Kind -eq 'FabricApi' })
    if ($provider.Count -ne 1) { throw "Fabric API provider must resolve to exactly one manifest loader." }
    $apiVersion = $provider[0].Companion.Version
    $desired = "fabric-api-$apiVersion.jar"
    $target  = Join-Path $McDir "mods\$desired"

    # Stale-version pruning on Prism is deferred until AFTER a successful
    # download. Otherwise a network failure mid-upgrade would leave the
    # instance with no fabric-api at all (install-side), breaking Fabric
    # loader's classpath. Snapshot stale files so we can roll back if the
    # download fails after we've moved any of them around.
    $staleJars = @()
    if ($Launcher -eq 'Prism') {
        $staleJars = @(Get-ChildItem -LiteralPath (Join-Path $McDir 'mods') -Filter 'fabric-api-*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -ne $desired })
    }

    if (Test-Path -LiteralPath $target) {
        Write-Info "Fabric API already at desired version: $desired"
        # Desired version is in place; we can safely remove stale versions now
        # without a rollback window: if the instance already works, pruning
        # co-installed older versions only reduces Fabric classpath noise.
        $staleJars | ForEach-Object { Write-Info "Removing stale Fabric API: $($_.Name)"; Remove-Item -LiteralPath $_.FullName -Force }
        return
    }
    $url = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$apiVersion/$desired"
    $tmp = New-SessionTempPath "$desired.part"
    Write-Info "Downloading Fabric API $apiVersion..."
    try {
        # Prefer a pinned literal hash (script constant) over the Maven
        # sidecar when present. Falls through to sidecar if empty.
        $pinned = if ($script:FABRIC_API_SHA256) { $script:FABRIC_API_SHA256 } else { $null }
        Invoke-VerifiedDownload -Url $url -Destination $tmp -ExpectedSha256 $pinned -ChecksumUrl "$url.sha256"
    } catch {
        Write-Err "Fabric API download failed: $url"
        Write-Err 'Existing fabric-api-*.jar (if any) left untouched.'
        throw
    }
    if ((Get-Item -LiteralPath $tmp).Length -lt 1024) {
        throw 'Fabric API download looks truncated (<1 KB).'
    }
    Move-Item -LiteralPath $tmp -Destination $target -Force

    # Download succeeded; now safe to prune stale versions.
    $staleJars | ForEach-Object { Write-Info "Replacing stale Fabric API: $($_.Name)"; Remove-Item -LiteralPath $_.FullName -Force }

    if ($Launcher -eq 'Microsoft') {
        $others = @(Get-ChildItem -LiteralPath (Join-Path $McDir 'mods') -Filter 'fabric-api-*.jar' | Where-Object { $_.Name -ne $desired })
        if ($others.Count -gt 0) {
            Write-Info "Leaving $($others.Count) other fabric-api-*.jar file(s) untouched (shared with your other Fabric profiles)."
        }
    }
    Write-Info "Installed $desired"
}

function Invoke-MoveWithRetry {
    <#
      Runs $Move, which moves files the installer has just written into
      place, and tries again for about three seconds while it fails with an
      I/O or access error. Antivirus software and the search indexer open
      newly written files for a moment, and Windows renames no folder with a
      file open inside it. Once -Creates exists, a failure is not retried:
      something else is already there, and waiting cannot change that. The
      last failure is rethrown.
    #>
    param([Parameter(Mandatory)] [scriptblock]$Move, [string]$Creates)
    $tries = 10
    for ($try = 1; ; $try++) {
        try {
            & $Move
            return
        } catch {
            $cause = $_.Exception.GetBaseException()
            $held = $cause -is [System.IO.IOException] -or $cause -is [System.UnauthorizedAccessException]
            if (-not $held -or $try -ge $tries -or ($Creates -and (Test-Path -LiteralPath $Creates))) { throw }
            Start-Sleep -Milliseconds 300
        }
    }
}

# --- RTX Remix Materials pack ---
function Get-DistArtPack {
    # The Materials pack this download carries, or $null for a download without one.
    $dir = Join-Path $script:SCRIPT_DIR 'resourcepacks'
    $packs = @(Get-ChildItem -LiteralPath $dir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match $script:ART_PACK_PATTERN })
    if ($packs.Count -gt 1) { throw "Expected one RTX Remix Materials pack in $dir, found $($packs.Count): $(($packs | ForEach-Object Name) -join ', ')" }
    if ($packs.Count -eq 1) { return $packs[0].FullName }
    return $null
}

function Get-InstalledArtPacks {
    param([string]$McDir)
    return @(Get-ChildItem -LiteralPath (Join-Path $McDir 'resourcepacks') -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match $script:ART_PACK_PATTERN })
}

function Set-ArtPackOption {
    <#
      Points options.txt's resourcePacks at the art pack $PackName: the first
      entry for any art pack becomes it, in place, and later ones are dropped,
      so a pack the player turned off stays off across versions. An empty
      $PackName drops every art pack entry. -Enable lists $PackName just above
      Fabric's pack when no art pack is listed, below the player's own packs
      so those still win, and creates options.txt when there is none. The file
      is rewritten only when the list changes. Returns whether $PackName is
      on afterwards, or $null for a resourcePacks line it could not read.
    #>
    param([string]$McDir, [string]$PackName, [switch]$Enable, [string]$BuiltInResourcePack)
    if (-not $BuiltInResourcePack) {
        $BuiltInResourcePack = $script:LOADER_CATALOG[$script:DEFAULT_LOADER_ID].BuiltInResourcePack
    }
    $path = Join-Path $McDir 'options.txt'
    $exists = Test-Path -LiteralPath $path -PathType Leaf
    if (-not $exists -and -not $Enable) { return $false }
    $text = if ($exists) { [IO.File]::ReadAllText($path) } else { '' }
    $match = [regex]::Match($text, '(?m)^resourcePacks:[^\r\n]*')
    $listed = @()
    if ($match.Success) {
        try {
            foreach ($pack in (ConvertFrom-Json $match.Value.Substring('resourcePacks:'.Length) -ErrorAction Stop)) { $listed += [string]$pack }
        } catch {
            Write-Warn "Left $path alone: its resourcePacks line is not a JSON list. Turn RTX Remix Materials on or off in Options, Resource Packs."
            return $null
        }
    }
    $entry = if ($PackName) { "file/$PackName" } else { $null }
    $packs = New-Object System.Collections.Generic.List[string]
    $placed = $false
    foreach ($pack in $listed) {
        if ($pack.StartsWith('file/') -and $pack.Substring(5) -match $script:ART_PACK_PATTERN) {
            if ($entry -and -not $placed) { $packs.Add($entry); $placed = $true }
            continue
        }
        $packs.Add($pack)
    }
    if ($Enable -and $entry -and -not $placed) {
        # The selected loader adds its own pack above every listed one when
        # the list leaves it out, which would put the baseline over this pack.
        if (-not $packs.Contains('vanilla')) { $packs.Insert(0, 'vanilla') }
        if (-not $packs.Contains($BuiltInResourcePack)) { $packs.Insert($packs.IndexOf('vanilla') + 1, $BuiltInResourcePack) }
        $packs.Insert($packs.IndexOf($BuiltInResourcePack) + 1, $entry)
        $placed = $true
    }
    if ($exists -and ($packs -join "`n") -ceq ($listed -join "`n")) { return $placed }
    $line = 'resourcePacks:' + (ConvertTo-Json -InputObject @($packs) -Compress)
    if ($match.Success) {
        $updated = $text.Remove($match.Index, $match.Length).Insert($match.Index, $line)
    } elseif ($exists) {
        $newline = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
        $separator = if ($text.Length -gt 0 -and -not $text.EndsWith("`n")) { $newline } else { '' }
        $updated = $text + $separator + $line + $newline
    } else {
        # The game fills in every other option with its default.
        $updated = "version:$($script:MC_DATA_VERSION)`r`n$line`r`n"
    }
    if ($updated -ceq $text) { return $placed }
    $bytes = if ($exists) { [IO.File]::ReadAllBytes($path) } else { @() }
    $bom = $bytes.Length -ge 3 -and $bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191
    Write-FileAtomic -Path $path -Content $updated -Bom:$bom
    return $placed
}

function Install-ArtPack {
    <#
      Installs the RTX Remix Materials pack the download carries. It runs
      only when the player ticked the pack, so it never asks. Returns one of:
        Installed  no pack was in resourcepacks\; this one is, turned on
        Updated    an installed pack was replaced in place, and stays on or
                   off as it was
        Missing    the download carries no pack
        Failed     the pack could not be copied, or an older one removed,
                   as while the game holds it
    #>
    param([string]$McDir, [string]$BuiltInResourcePack)
    if (-not $BuiltInResourcePack) {
        $BuiltInResourcePack = $script:LOADER_CATALOG[$script:DEFAULT_LOADER_ID].BuiltInResourcePack
    }
    $packaged = Get-DistArtPack
    if (-not $packaged) {
        Write-Warn 'This download carries no RTX Remix Materials pack, so none was installed.'
        return 'Missing'
    }
    $name = Split-Path $packaged -Leaf
    $installed = @(Get-InstalledArtPacks -McDir $McDir)
    $enable = $installed.Count -eq 0
    $dir = Join-Path $McDir 'resourcepacks'
    $finalPath = Join-Path $dir $name
    $stagedPath = "$finalPath.new"
    try {
        if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force -ErrorAction Stop | Out-Null }
        Copy-Item -LiteralPath $packaged -Destination $stagedPath -Force -ErrorAction Stop
        Invoke-MoveWithRetry -Move { Move-Item -LiteralPath $stagedPath -Destination $finalPath -Force -ErrorAction Stop }
    } catch {
        # A running game holds its enabled packs open. Warn and go on, so the
        # rest of the run still completes.
        Remove-Item -LiteralPath $stagedPath -Force -ErrorAction SilentlyContinue
        Write-Warn "Could not place $name in ${dir}: $($_.Exception.Message) Close Minecraft and run the installer again."
        return 'Failed'
    }
    foreach ($old in $installed) {
        if ($old.Name -eq $name) { continue }
        Write-Info "Replacing the older RTX Remix Materials pack $($old.Name)"
        try {
            Remove-Item -LiteralPath $old.FullName -Force -ErrorAction Stop
        } catch {
            # An upgrade while the game runs: it holds the older pack, the one
            # it has on. options.txt keeps naming that file, which still
            # exists, and the next run replaces it, since both packs match.
            Write-Warn "Could not remove the older $($old.Name) from ${dir}: $($_.Exception.Message) Close Minecraft and run the installer again."
            return 'Failed'
        }
    }
    $on = Set-ArtPackOption -McDir $McDir -PackName $name -Enable:$enable -BuiltInResourcePack $BuiltInResourcePack
    if ($enable) {
        Write-Info "Installed and turned on $name"
        return 'Installed'
    }
    Write-Info "Installed $name"
    if ($on -eq $false) { Write-Info 'RTX Remix Materials stays off, as you left it. Turn it on in Options, Resource Packs.' }
    return 'Updated'
}

# --- RTX Remix Showcase World ---
function Get-DistWorld {
    # The Showcase World zip this download carries, or $null for a download without one.
    $dir = Join-Path $script:SCRIPT_DIR 'saves'
    $zips = @(Get-ChildItem -LiteralPath $dir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match $script:WORLD_ZIP_PATTERN })
    if ($zips.Count -gt 1) { throw "Expected one RTX Remix Showcase World zip in $dir, found $($zips.Count): $(($zips | ForEach-Object Name) -join ', ')" }
    if ($zips.Count -eq 1) { return $zips[0].FullName }
    return $null
}

function Install-ShowcaseWorld {
    <#
      Unpacks the RTX Remix Showcase World the download carries into saves\.
      It runs only when the player ticked the world, so it never asks. A
      world already in saves\ under its name is never replaced: it may hold
      the player's progress. Returns one of:
        Installed  the world was unpacked into saves\
        Kept       saves\ already holds the world; it was left as it was
        Missing    the download carries no world
        Failed     the world could not be unpacked; nothing of it is left
                   in saves\
    #>
    param([string]$McDir)
    try {
        $packaged = Get-DistWorld
    } catch {
        Write-Warn "The RTX Remix Showcase World was not installed: $($_.Exception.Message)"
        return 'Failed'
    }
    if (-not $packaged) {
        Write-Warn 'This download carries no RTX Remix Showcase World, so none was installed.'
        return 'Missing'
    }
    $saves = Join-Path $McDir 'saves'
    $target = Join-Path $saves $script:WORLD_FOLDER_NAME
    if (Test-Path -LiteralPath $target) {
        Write-Info "Kept the $($script:WORLD_FOLDER_NAME) world already in ${saves}. For a fresh copy, rename or delete that folder and run the installer again."
        return 'Kept'
    }
    # The zip unpacks into a folder of its own beside the world's place, on
    # the same drive, so the world moves into place whole or not at all.
    $stage = Join-Path $saves ".rtx-remix-world-$([guid]::NewGuid().ToString('N'))"
    try {
        try {
            # Expand-Archive is slow on a world this size under Windows
            # PowerShell 5.1, and draws a progress bar.
            Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
            [IO.Compression.ZipFile]::ExtractToDirectory($packaged, $stage)
        } catch {
            # A damaged zip, or a full drive. A Windows error message ends in a
            # line break.
            Write-Warn "Could not unpack the RTX Remix Showcase World into ${saves}: $($_.Exception.GetBaseException().Message.Trim()) Check the free space on that drive, or download the installer again."
            return 'Failed'
        }
        $world = Join-Path $stage $script:WORLD_FOLDER_NAME
        if (-not (Test-Path -LiteralPath (Join-Path $world 'level.dat') -PathType Leaf)) {
            Write-Warn "$(Split-Path $packaged -Leaf) holds no $($script:WORLD_FOLDER_NAME)\level.dat, so the RTX Remix Showcase World was not installed."
            return 'Failed'
        }
        try {
            # Should a world of that name appear meanwhile, this fails rather than
            # moving the new copy inside it, as Move-Item would.
            Invoke-MoveWithRetry -Creates $target -Move { [IO.Directory]::Move($world, $target) }
        } catch {
            $reason = $_.Exception.GetBaseException().Message.Trim()
            if (Test-Path -LiteralPath $target) {
                Write-Warn "Could not move the RTX Remix Showcase World into ${saves}: $reason Something else created $target meanwhile, and it was left as it is."
            } else {
                Write-Warn "Could not move the RTX Remix Showcase World into ${saves}: $reason Windows or antivirus software is still scanning the new files. Run the installer again."
            }
            return 'Failed'
        }
    } finally {
        if (Test-Path -LiteralPath $stage) {
            try {
                Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction Stop
            } catch {
                Write-Warn "Could not remove the unpacking folder ${stage}: $($_.Exception.Message) Delete it yourself."
            }
        }
    }
    Write-Info "Installed the RTX Remix Showcase World as $target"
    return 'Installed'
}

function Remove-FabricApiForNonFabric {
    param(
        [string]$McDir,
        [ValidateSet('Prism','Microsoft')] [string]$Launcher,
        [Parameter(Mandatory)] [string]$ModLoader
    )
    if ($Launcher -ne 'Prism') {
        Write-Info 'Leaving Fabric API files untouched in the shared Microsoft mods directory.'
        return
    }
    Get-ChildItem -LiteralPath (Join-Path $McDir 'mods') -Filter 'fabric-api-*.jar' -ErrorAction SilentlyContinue |
        ForEach-Object {
            Write-Info "Removing Fabric-only dependency from $ModLoader Prism instance: $($_.Name)"
            Remove-Item -LiteralPath $_.FullName -Force
        }
}

function Install-LoaderCompanions {
    param([string]$McDir, [string]$Launcher, [Parameter(Mandatory)] [string]$ModLoader)
    $provider = Get-LoaderProvider -ModLoader $ModLoader
    if (-not $provider.Companion) { return }
    switch ($provider.Companion.Kind) {
        'FabricApi' { Install-FabricApi -McDir $McDir -Launcher $Launcher }
        default { throw "Loader '$($provider.DisplayName)' declares unknown companion provider '$($provider.Companion.Kind)'." }
    }
}

function Reconcile-OtherLoaderCompanions {
    param([string]$McDir, [string]$Launcher, [Parameter(Mandatory)] [string]$ModLoader)
    $selected = Get-LoaderProvider -ModLoader $ModLoader
    foreach ($provider in $script:LOADER_CATALOG.Values | Where-Object Id -ne $selected.Id) {
        if (-not $provider.Companion) { continue }
        switch ($provider.Companion.Kind) {
            'FabricApi' {
                Remove-FabricApiForNonFabric -McDir $McDir -Launcher $Launcher -ModLoader $selected.DisplayName
            }
            default { throw "Loader '$($provider.DisplayName)' declares unknown companion provider '$($provider.Companion.Kind)'." }
        }
    }
}

function Get-ServersDatHosts {
    <#
      Parse a Minecraft servers.dat (NBT, big-endian, uncompressed) and
      return the list of "ip" values inside the top-level "servers" list.

      Minimal parser: only decodes enough NBT to walk root Compound -> find
      'servers' List of Compound -> extract each entry's 'ip' String. Other
      tag types are length-skipped. Compressed servers.dat (very rare) is
      not supported; on any parse error we return an empty array so the
      caller falls back to "not present" semantics.

      See https://minecraft.wiki/w/NBT_format for the binary format spec.
    #>
    param([string]$Path)
    $result = @()
    if (-not (Test-Path -LiteralPath $Path)) { return $result }
    try {
        $bytes = [System.IO.File]::ReadAllBytes($Path)
    } catch {
        Write-Trace "Could not read $Path : $($_.Exception.Message)"
        return $result
    }
    if ($bytes.Length -eq 0) { return $result }
    # Compressed NBT starts with 0x1F 0x8B (gzip magic). We don't try to
    # decompress in-process; caller treats 'empty list' as "don't know".
    if ($bytes[0] -eq 0x1F -and $bytes.Length -gt 1 -and $bytes[1] -eq 0x8B) {
        Write-Trace "$Path is gzip-compressed NBT; parser doesn't decompress; treating as unknown."
        return $result
    }

    # Reader helpers operate on $script:_bytes / $script:_pos / $script:_len
    # which are initialized below. The script-scoped names sidestep
    # PowerShell's awkward function-local scoping when reading them via
    # closure from nested function definitions.
    function _ReadByte { if ($script:_pos -ge $script:_len) { throw 'EOF' }; $v = $script:_bytes[$script:_pos]; $script:_pos++; return $v }
    function _ReadBE16 { if ($script:_pos + 2 -gt $script:_len) { throw 'EOF' }; $v = ([int]$script:_bytes[$script:_pos] -shl 8) -bor [int]$script:_bytes[$script:_pos+1]; $script:_pos += 2; return $v }
    function _ReadBE32 { if ($script:_pos + 4 -gt $script:_len) { throw 'EOF' }; $v = ([int]$script:_bytes[$script:_pos] -shl 24) -bor ([int]$script:_bytes[$script:_pos+1] -shl 16) -bor ([int]$script:_bytes[$script:_pos+2] -shl 8) -bor [int]$script:_bytes[$script:_pos+3]; $script:_pos += 4; return $v }
    function _ReadName { $nlen = _ReadBE16; if ($nlen -eq 0) { return '' }; $n = [System.Text.Encoding]::UTF8.GetString($script:_bytes, $script:_pos, $nlen); $script:_pos += $nlen; return $n }
    function _ReadString { $slen = _ReadBE16; if ($slen -eq 0) { return '' }; $s = [System.Text.Encoding]::UTF8.GetString($script:_bytes, $script:_pos, $slen); $script:_pos += $slen; return $s }
    function _SkipPayload {
        param([int]$TagId)
        switch ($TagId) {
            1  { $null = _ReadByte }                                # TAG_Byte
            2  { $null = _ReadBE16 }                                # TAG_Short
            3  { $null = _ReadBE32 }                                # TAG_Int
            4  { $script:_pos += 8 }                                # TAG_Long
            5  { $script:_pos += 4 }                                # TAG_Float
            6  { $script:_pos += 8 }                                # TAG_Double
            7  { $n = _ReadBE32; $script:_pos += $n }               # TAG_ByteArray
            8  { $null = _ReadString }                              # TAG_String
            9  {
                $inner = _ReadByte; $cnt = _ReadBE32
                for ($i=0; $i -lt $cnt; $i++) { _SkipPayload $inner }
            }
            10 { # TAG_Compound: skip named tags until TAG_End
                while ($true) {
                    $tid = _ReadByte; if ($tid -eq 0) { break }
                    $null = _ReadName
                    _SkipPayload $tid
                }
            }
            11 { $n = _ReadBE32; $script:_pos += ($n * 4) }          # TAG_IntArray
            12 { $n = _ReadBE32; $script:_pos += ($n * 8) }          # TAG_LongArray
            default { throw "Unknown NBT tag id $TagId at offset $($script:_pos - 1)" }
        }
    }

    try {
        $script:_bytes = $bytes
        $script:_pos   = 0
        $script:_len   = $bytes.Length

        $rootTag = _ReadByte
        if ($rootTag -ne 10) { return $result }        # must be TAG_Compound
        $null = _ReadName                              # root name (usually empty)

        # Walk root compound looking for a 'servers' List of Compound.
        while ($script:_pos -lt $script:_len) {
            $tid = _ReadByte
            if ($tid -eq 0) { break }
            $name = _ReadName
            if ($tid -eq 9 -and $name -eq 'servers') {
                $inner = _ReadByte
                $cnt = _ReadBE32
                if ($inner -ne 10) { return $result }  # should be List of Compound
                for ($i=0; $i -lt $cnt; $i++) {
                    # Entry is an unnamed compound; walk its tags.
                    $entryIp = $null
                    while ($true) {
                        $etid = _ReadByte
                        if ($etid -eq 0) { break }
                        $ename = _ReadName
                        if ($etid -eq 8 -and $ename -eq 'ip') {
                            $entryIp = _ReadString
                        } else {
                            _SkipPayload $etid
                        }
                    }
                    if ($entryIp) { $result += $entryIp }
                }
                return $result  # found & consumed the servers list; done
            } else {
                _SkipPayload $tid
            }
        }
    } catch {
        Write-Trace "NBT parse error in $Path : $($_.Exception.Message). Treating as unknown."
        return @()
    } finally {
        Remove-Variable -Name _bytes, _pos, _len -Scope Script -ErrorAction SilentlyContinue
    }
    return $result
}

function Test-ServersDatContainsHost {
    <#
      Returns $true if servers.dat at $Path contains the host (case-
      insensitive, port-ignoring match on the 'ip' field). Returns $false
      if file missing, parse failure, or host absent.
    #>
    param([string]$Path, [string]$ServerHost)
    $hosts = Get-ServersDatHosts -Path $Path
    foreach ($h in $hosts) {
        $hostOnly = ($h -split ':', 2)[0]
        $targetOnly = ($ServerHost -split ':', 2)[0]
        if ($hostOnly -ieq $targetOnly) { return $true }
    }
    return $false
}

function Write-ServersDat {
    <#
      Write a minimal servers.dat (NBT, big-endian, uncompressed) with a
      single server entry. Only called when no existing servers.dat is
      present; we never overwrite user data.

      NBT layout:
        TAG_Compound (root, empty name)
          TAG_List "servers" of TAG_Compound, count=1
            TAG_Compound:
              TAG_String "ip":   <host[:port]>
              TAG_String "name": <display name>
            TAG_End
          TAG_End (list's implicit end isn't written; TAG_List is
                  self-delimiting via its count prefix)
        TAG_End

      See https://minecraft.wiki/w/NBT_format for the binary format.
    #>
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$ServerHost,
        [Parameter(Mandatory)] [string]$ServerName
    )

    # Helpers that write to a BinaryWriter in NBT's big-endian convention.
    # PS 5.1's BinaryWriter writes little-endian by default, so we do it
    # by hand via [IPAddress]::HostToNetworkOrder on shorts/ints.
    $fs = [System.IO.File]::Open($Path, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write)
    try {
        $bw = [System.IO.BinaryWriter]::new($fs, [System.Text.Encoding]::UTF8, $true)
        try {
            function _WriteNameBytes([System.IO.BinaryWriter]$w, [string]$name) {
                $nb = [System.Text.Encoding]::UTF8.GetBytes($name)
                $lenBE = [System.Net.IPAddress]::HostToNetworkOrder([int16]$nb.Length)
                $w.Write([int16]$lenBE)
                if ($nb.Length -gt 0) { $w.Write($nb) }
            }
            function _WriteStringTag([System.IO.BinaryWriter]$w, [string]$tagName, [string]$value) {
                $w.Write([byte]8)               # TAG_String
                _WriteNameBytes $w $tagName
                _WriteNameBytes $w $value        # payload is also a length-prefixed UTF-8 string
            }

            # Root compound (empty name)
            $bw.Write([byte]10)                 # TAG_Compound
            _WriteNameBytes $bw ''

            # List "servers" of TAG_Compound, count 1
            $bw.Write([byte]9)                  # TAG_List
            _WriteNameBytes $bw 'servers'
            $bw.Write([byte]10)                 # inner type: TAG_Compound
            $countBE = [System.Net.IPAddress]::HostToNetworkOrder([int32]1)
            $bw.Write([int32]$countBE)           # count = 1

            # Server entry compound (no name; list-inner compounds are anonymous)
            _WriteStringTag $bw 'ip'   $ServerHost
            _WriteStringTag $bw 'name' $ServerName
            $bw.Write([byte]0)                  # TAG_End for the entry compound

            $bw.Write([byte]0)                  # TAG_End for the root compound
        } finally {
            $bw.Dispose()
        }
    } finally {
        $fs.Dispose()
    }
}

function Install-DevServer {
    <#
      Pre-populate <mc>/servers.dat with a single dev-server entry. Only
      writes a fresh servers.dat when none exists; if one's already there
      we don't try to merge (NBT write-back is complex and user risk is
      nonzero). Caller should use Test-ServersDatContainsHost to check
      presence first; this function handles the "already there" case
      silently with a verbose log.
    #>
    param(
        [Parameter(Mandatory)] [string]$McDir,
        [Parameter(Mandatory)] [string]$ServerHost,
        [string]$ServerName = 'NVIDIA RTX Remix Dev'
    )
    $serversDat = Join-Path $McDir 'servers.dat'
    if (Test-Path -LiteralPath $serversDat) {
        if (Test-ServersDatContainsHost -Path $serversDat -ServerHost $ServerHost) {
            Write-Info "Dev server '$ServerHost' is already in $serversDat; no change."
        } else {
            Write-Warn "Not modifying existing $serversDat. Add '$ServerHost' manually via the in-game Multiplayer menu if you want it alongside your existing servers."
        }
        return
    }
    if (-not (Test-Path -LiteralPath $McDir)) {
        New-Item -ItemType Directory -Path $McDir -Force | Out-Null
    }
    try {
        Write-ServersDat -Path $serversDat -ServerHost $ServerHost -ServerName $ServerName
        Write-Info "Added server '$ServerName' ($ServerHost) to the instance's saved server list."
    } catch {
        Write-Err "Failed to write servers.dat: $($_.Exception.Message). Add '$ServerHost' manually from the in-game Multiplayer menu."
    }
}

function Invoke-InstallItem {
    # Runs a ticked item's step and returns its result. The steps report
    # their own failures; anything one throws instead, such as a download
    # that holds two packs, is a warning too and counts as Failed, so the
    # run still installs the other items.
    param([string]$Name, [scriptblock]$Step)
    try {
        return @(& $Step)[-1]
    } catch {
        Write-Warn "$Name was not installed: $($_.Exception.Message)"
        return 'Failed'
    }
}

function Invoke-InstallOrUpgrade {
    param([string]$PreferredLauncher, [string]$PreferredModLoader, [switch]$Unattended, [string]$Mode)
    # An interactive run starts at the first step; the banner heads headless logs.
    if ($Unattended) { Write-Banner -Title "RTX Remix for Minecraft - $Mode" -Subtitle "v$($script:APP_VERSION)" }
    # Repair / Upgrade / Install all share this code path; differ only in
    # banner copy. Per-step messages let the user trace what's happening
    # regardless of which mode dispatched here. The total counts the items
    # the flags ask for until the checklist settles them.
    $presets = Get-InstallItemPresets -Unattended:$Unattended -ModChoice $Mod -ArtPackChoice $ArtPack -WorldChoice $World
    # An unattended run has no checklist, so its flags are the whole selection.
    if ($Unattended -and -not ($presets.Mod -or $presets.ArtPack -or $presets.World)) {
        Write-Err 'Nothing to install: -Mod Skip needs -ArtPack Install or -World Install.'
        return 2
    }
    Start-StepCounter -Total (Get-InstallStepCount -Items $presets)

    Write-Step 'Checking prerequisites'
    if (-not (Test-ElevationAllowed)) { return 1 }
    $state = Get-LauncherState
    # Fetch the richer state too; lets Select-Launcher do the "auto-pick
    # the launcher with an existing install" bias on Upgrade/Repair.
    $detected = Get-DetectedState

    Write-Step 'Selecting launcher'
    # Only Upgrade/Repair bias toward the installed launcher. Install (and
    # its forced-from-main-menu variant) always prompts so the user can
    # pick a different launcher than the one they may already have.
    $biasUpgrade = ($Mode -in @('Upgrade', 'Repair'))
    $launcher = Select-Launcher -State $state -Preferred $PreferredLauncher -Unattended:$Unattended -DetectedState $detected -BiasToInstalledLauncher:$biasUpgrade
    Write-Info "Using launcher: $($script:C_BOLD)$launcher$($script:C_RESET)"
    $ticked = Select-InstallItems -Launcher $launcher -Unattended:$Unattended -ModLoader $PreferredModLoader -ModChoice $Mod -ArtPackChoice $ArtPack -WorldChoice $World
    $script:StepTotal = Get-InstallStepCount -Items $ticked

    $selectedLoader = $null
    if ($ticked.Mod) {
        Write-Step 'Selecting mod loader'
        $selectedLoader = Select-ModLoader -Preferred $PreferredModLoader -Unattended:$Unattended -Mode $Mode -Launcher $launcher -DetectedState $detected
        Write-Info "Using mod loader: $($script:C_BOLD)$selectedLoader$($script:C_RESET)"
        if (-not (Get-DistModJar -ModLoader $selectedLoader)) {
            $artifactDirectory = (Get-LoaderProvider -ModLoader $selectedLoader).ArtifactDirectory
            throw "No $selectedLoader rtx-remix-renderer JAR found in $(Join-Path $script:SCRIPT_DIR $artifactDirectory)"
        }
        if ($launcher -eq 'Microsoft' -and -not $Unattended) { Wait-MinecraftLauncherClosed }

        # Java handling matrix (same as install.bat original):
        #   ARM64           -> always install managed JDK
        #   x64 + Prism     -> no Java needed (Prism Wizard handles at runtime)
        #   x64 + Microsoft -> resolve Java for the selected loader installer
        $installerJava = $null
        if (Test-HostIsArm64) {
            $installerJava = Resolve-GameJavaForArm
        } elseif ($launcher -eq 'Microsoft') {
            $installerJava = Resolve-JavaForLoaderInstaller -ModLoader $selectedLoader
        }

        $probeDataDir = if ($state.PrismDataDir) { $state.PrismDataDir } else { $script:PRISM_PORTABLE_DIR }
        $launcherMetadataPath = if ($launcher -eq 'Prism') {
            Join-Path (Get-PrismInstanceDir -DataDir $probeDataDir) 'mmc-pack.json'
        } else {
            Get-MsLauncherProfilesPath
        }
        $launcherMetadataSnapshot = New-FileSnapshot -Path $launcherMetadataPath
        try {
            Write-Step 'Preparing launcher'
            if ($launcher -eq 'Prism') {
                Test-PathLengthBudget -PrismDataDir $probeDataDir
                $instanceDir = Initialize-Prism -State $state -Unattended:$Unattended -ModLoader $selectedLoader
                $mcDir = Join-Path $instanceDir '.minecraft'
            } else {
                Initialize-MicrosoftLauncher -JavaExe $installerJava -Unattended:$Unattended -ModLoader $selectedLoader
                $mcDir = Get-McDirForLauncher -Launcher Microsoft -ModLoader $selectedLoader
            }

            Write-Step 'Configuring loader dependencies'
            Install-LoaderCompanions -McDir $mcDir -Launcher $launcher -ModLoader $selectedLoader

            Write-Step 'Installing mod files'
            Install-ModFiles -McDir $mcDir -ModLoader $selectedLoader
            $script:ModFilesCopied = $true
        } catch {
            Restore-FileSnapshot -Snapshot $launcherMetadataSnapshot
            Write-Warn 'Restored the previous launcher loader configuration after the install failed.'
            throw
        }
        try {
            Reconcile-OtherLoaderCompanions -McDir $mcDir -Launcher $launcher -ModLoader $selectedLoader
        } catch {
            Write-Warn "$selectedLoader is installed, but a stale companion dependency could not be removed: $($_.Exception.Message)"
        }
    } else {
        # Without the mod, nothing prepares a launcher, so the pack and the
        # world need the game folder an earlier install made.
        $selectedLoader = Select-ModLoader -Preferred $PreferredModLoader -Unattended:$Unattended `
            -Mode $Mode -Launcher $launcher -DetectedState $detected
        $mcDir = Get-ContentOnlyMcDir -Launcher $launcher -PrismDataDir $state.PrismDataDir -ModLoader $selectedLoader
        if (-not $mcDir) {
            $launcherName = if ($launcher -eq 'Prism') { 'Prism' } else { 'the Microsoft launcher' }
            Write-Err "Install RTX Remix Renderer first: $launcherName has no RTX Remix game folder yet."
            return 1
        }
    }

    # Each ticked item runs whatever the others returned. One that failed
    # fails the run once every step is done; one the download does not carry
    # only warns.
    $failedItems = @()
    $worldResult = $null
    if ($ticked.ArtPack) {
        Write-Step 'Installing RTX Remix Materials'
        $builtInPack = (Get-LoaderProvider -ModLoader $selectedLoader).BuiltInResourcePack
        $packResult = Invoke-InstallItem -Name 'RTX Remix Materials' -Step {
            Install-ArtPack -McDir $mcDir -BuiltInResourcePack $builtInPack
        }
        if ($packResult -eq 'Failed') { $failedItems += 'RTX Remix Materials' }
    }
    if ($ticked.World) {
        Write-Step 'Installing RTX Remix Showcase World'
        $worldResult = Invoke-InstallItem -Name 'RTX Remix Showcase World' -Step { Install-ShowcaseWorld -McDir $mcDir }
        if ($worldResult -eq 'Failed') { $failedItems += 'RTX Remix Showcase World' }
    }

    # A server entry is added only when -DevServer names a host. An existing
    # populated servers.dat is never merged into.
    $serversDatPath = Join-Path $mcDir 'servers.dat'
    if ($DevServer) {
        if (Test-ServersDatContainsHost -Path $serversDatPath -ServerHost $DevServer) {
            Write-Info "Dev server '$DevServer' already in $serversDatPath; nothing to do."
        } else {
            Install-DevServer -McDir $mcDir -ServerHost $DevServer
        }
    }

    Write-FinalInstructions -Launcher $launcher -PrismDataDir $state.PrismDataDir -ModLoader $selectedLoader -Unattended:$Unattended -World:($worldResult -in 'Installed', 'Kept') -Failed $failedItems
    # A headless run is a script or CI job; it must not open a window.
    if (-not $Unattended) { Start-InstalledLauncher -Launcher $launcher -PrismDataDir $state.PrismDataDir }
    if ($failedItems.Count -gt 0) { return 1 }
    return 0
}

function Wait-MinecraftLauncherClosed {
    # The launcher rewrites launcher_profiles.json when it exits, which can
    # drop the profile the installer adds. Wait for the user to close it.
    # The classic launcher is MinecraftLauncher.exe; the Store edition is
    # Minecraft.exe inside the Microsoft.4297127D64EC6 package.
    # Only this Windows session's launchers: another user's, under fast user
    # switching or a remote session, is one this user cannot close.
    $sessionId = (Get-Process -Id $PID).SessionId
    $running = { Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.SessionId -eq $sessionId -and ($_.Name -eq 'MinecraftLauncher' -or $_.Path -like '*\Microsoft.4297127D64EC6_*\Minecraft.exe') } }
    if (-not (& $running)) { return }
    # CLOSE alternates yellow and light red every half second on one line that
    # is rewritten in place. The process list is read every 500 ms, not on every
    # 100 ms tick.
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $phase = -1
    $tick = 0
    $isRunning = $true
    while ($isRunning) {
        $p = [int][math]::Floor($sw.Elapsed.TotalSeconds * 2) % 2
        if ($p -ne $phase) {
            $phase = $p
            $word = if ($p -eq 0) { "$($script:C_BOLD)$($script:C_YELLOW)CLOSE$($script:C_RESET)" } else { "$($script:C_BOLD)$ESC[91mCLOSE$($script:C_RESET)" }
            [Console]::Write("`r  $($script:ICON_WARN) Please, $word Minecraft Launcher to continue [Press P to proceed anyway]...$ESC[K")
        }
        # P is the way out if the launcher cannot be closed, for example one that is hidden or stuck.
        if ([Console]::KeyAvailable -and [Console]::ReadKey($true).Key -eq [ConsoleKey]::P) {
            [Console]::Write("`r$ESC[K")
            Write-Warn 'Proceeding with the launcher open. If the profile is missing, close it and run the installer again.'
            return
        }
        Start-Sleep -Milliseconds 100
        $tick++
        if ($tick % 5 -eq 0) { $isRunning = [bool](& $running) }
    }
    [Console]::Write("`r$ESC[K")
    Write-Info 'Minecraft Launcher closed.'
}

function Start-InstalledLauncher {
    # Opens the launcher the install went into. Never fails the install: a
    # launcher that cannot be found or started is a warning, since the mod is
    # already in place and "Next steps" says how to open it by hand.
    param([string]$Launcher, [string]$PrismDataDir)
    try {
        if ($Launcher -eq 'Prism') {
            # A portable copy, ours included, keeps prismlauncher.exe in its data folder.
            $exe = Join-Path $(if ($PrismDataDir) { $PrismDataDir } else { $script:PRISM_PORTABLE_DIR }) 'prismlauncher.exe'
            if (-not (Test-Path -LiteralPath $exe)) { $exe = Find-PrismExe }
            if (-not $exe) { Write-Warn 'Could not find prismlauncher.exe to start; open Prism Launcher yourself.'; return }
            Write-Info 'Starting Prism Launcher...'
            $startArgs = @{ FilePath = $exe; WorkingDirectory = (Split-Path -Parent $exe) }
            # Only a data folder other than the one Prism runs from (a portable
            # copy's) and Prism's own default needs --dir; default launches stay
            # as they were. One string, because Start-Process does not quote
            # array elements: a path with spaces is quoted, and trailing
            # backslashes are doubled so they cannot escape the closing quote.
            $dir = if ($PrismDataDir) { $PrismDataDir.TrimEnd('\') } else { $null }
            if ($dir -and $dir -ne (Split-Path -Parent $exe) -and $dir -ne (Join-Path $env:APPDATA 'PrismLauncher')) {
                $startArgs.ArgumentList = '--dir "{0}"' -f ($PrismDataDir -replace '(\\+)$', '$1$1')
            }
            # Prism attaches to the console of whatever starts it and writes its
            # whole log there, and it then holds this window open and dies with it.
            if ($startArgs.ContainsKey('ArgumentList')) {
                # Explorer cannot pass --dir, so redirect its output instead:
                # Start-Process needs two different files, and a fresh name per
                # launch because a running Prism still holds the last pair.
                Get-ChildItem $env:TEMP -Filter 'rtx-remix-prism-*.log' -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
                $logBase = Join-Path $env:TEMP "rtx-remix-prism-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
                $startArgs.RedirectStandardOutput = "$logBase-out.log"
                $startArgs.RedirectStandardError = "$logBase-err.log"
                Start-Process @startArgs
            } else {
                # Started by Explorer, Prism has no console of ours to attach to:
                # no log spill, and it outlives this window.
                Start-Process explorer.exe -ArgumentList ('"{0}"' -f $exe)
            }
        } else {
            $exe = @(
                (Join-Path ${env:ProgramFiles(x86)} 'Minecraft Launcher\MinecraftLauncher.exe'),
                (Join-Path $env:ProgramFiles 'Minecraft Launcher\MinecraftLauncher.exe')
            ) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
            Write-Info 'Starting the Minecraft Launcher...'
            if ($exe) {
                Start-Process -FilePath $exe
            } else {
                # Microsoft Store edition.
                Start-Process 'shell:AppsFolder\Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft'
            }
        }
    } catch {
        Write-Warn "Could not start the launcher: $($_.Exception.Message)"
    }
}

function Write-FinalInstructions {
    # -World adds where to find the RTX Remix Showcase World. -Failed names
    # the ticked items that were not installed: the box then says so in
    # yellow, and a line below it names each one, since the warning that
    # explains it has usually scrolled away by now.
    param([string]$Launcher, [string]$PrismDataDir, [string]$ModLoader, [switch]$Unattended, [switch]$World, [string[]]$Failed = @())
    if ($Failed.Count -gt 0) {
        $msg   = 'RTX Remix for Minecraft installed with problems'
        $color = $script:C_YELLOW
        $glyph = [char]0x26A0
        $icon  = $script:ICON_WARN
    } else {
        $msg   = 'RTX Remix for Minecraft install complete'
        $color = $script:C_GREEN
        $glyph = [char]0x2713
        $icon  = $script:ICON_OK
    }
    # Inner box width must match the 0x2550 count in the top/bottom borders.
    # Leading gap (3 spaces) + icon glyph (1 cell) + gap (2) + message.
    # Trailing is padded to fill the remaining cells, computed rather
    # than hand-counted so message-text edits can't desynchronize again.
    $inner   = 66
    $leading = "   $glyph  "                   # 3 spaces + icon + 2 spaces
    $consumed = $leading.Length + $msg.Length  # one display cell per leading/trailing char
    $trailing = ' ' * [math]::Max(0, $inner - $consumed)

    $bar  = "$color$([char]0x2551)$($script:C_RESET)"
    $top  = "$color$([char]0x2554)$([string][char]0x2550 * $inner)$([char]0x2557)$($script:C_RESET)"
    $bot  = "$color$([char]0x255A)$([string][char]0x2550 * $inner)$([char]0x255D)$($script:C_RESET)"
    $blank = "$bar$(' ' * $inner)$bar"

    Write-Host ''
    Write-Host $top
    Write-Host $blank
    # Colorize the visible content with the icon / C_BOLD in-place; the
    # box sides ($bar) stay on the outside so trailing pad length is
    # computed from visible cells only.
    $contentLeading = "   $icon  "
    Write-Host "$bar$contentLeading$($script:C_BOLD)$msg$($script:C_RESET)$trailing$bar"
    Write-Host $blank
    Write-Host $bot
    foreach ($item in $Failed) { Write-Warn "$item was not installed. See the messages above." }
    Write-Host ''
    Write-Host "$($script:C_BOLD)Next steps:$($script:C_RESET)"
    Write-Host ''
    # An interactive run opens the launcher right after this (Start-InstalledLauncher).
    if ($Launcher -eq 'Prism') {
        if ($PrismDataDir -eq $script:PRISM_PORTABLE_DIR) {
            $portableExe = "$($script:C_NVGREEN)$(Join-Path $script:PRISM_PORTABLE_DIR 'prismlauncher.exe')$($script:C_RESET)"
            if ($Unattended) { Write-Host "  $($script:ICON_INFO) Launch Prism from: $portableExe" }
            else { Write-Host "  $($script:ICON_INFO) Prism Launcher opens next. To start it later, run: $portableExe" }
        } else {
            if ($Unattended) { Write-Host "  $($script:ICON_INFO) Open your existing Prism Launcher." }
            else { Write-Host "  $($script:ICON_INFO) Prism Launcher opens next." }
        }
        Write-Host "  $($script:ICON_INFO) Sign in to your Microsoft account via Accounts if not already done."
        Write-Host "  $($script:ICON_INFO) Select the $($script:C_NVGREEN)'$($script:PRISM_INSTANCE_NAME)'$($script:C_RESET) instance and click Launch."
    } else {
        if ($Unattended) { Write-Host "  $($script:ICON_INFO) Launch the Microsoft Minecraft Launcher." }
        else { Write-Host "  $($script:ICON_INFO) The Microsoft Minecraft Launcher opens next." }
        Write-Host "  $($script:ICON_INFO) Sign in if prompted."
        $profileName = if ($ModLoader) {
            (Get-LoaderProvider -ModLoader $ModLoader).MicrosoftProfileName
        } else {
            'NVIDIA RTX Remix for Minecraft'
        }
        Write-Host "  $($script:ICON_INFO) Select the $($script:C_NVGREEN)'$profileName'$($script:C_RESET) profile and click Play."
    }
    if ($World) { Write-Host "  $($script:ICON_INFO) Open Singleplayer and choose $($script:WORLD_FOLDER_NAME)." }
    Write-Host "  $($script:ICON_INFO) Once in the world, verify with $($script:C_NVGREEN)/rtx status$($script:C_RESET) in chat."
    Write-Host ''
}

# --- Uninstall ---
function Invoke-Uninstall {
    param([string]$PreferredLauncher, [string]$PreferredModLoader, [switch]$Unattended, [switch]$AcknowledgeDataLoss)
    if ($Unattended) { Write-Banner -Title 'RTX Remix for Minecraft - Uninstall' -Subtitle "v$($script:APP_VERSION)" }
    $detected = Get-DetectedState
    $launcherChoice = Select-LauncherForUninstall -State $detected -Preferred $PreferredLauncher -Unattended:$Unattended
    if (-not $launcherChoice) { Write-Info 'Cancelled.'; return 0 }
    $loaderChoice = $null
    if ($launcherChoice -ne 'Both' -or $PreferredModLoader) {
        $loaderChoice = Select-ModLoader -Preferred $PreferredModLoader -Unattended:$Unattended -Mode Uninstall `
            -Launcher $(if ($launcherChoice -eq 'Both') { $null } else { $launcherChoice }) -DetectedState $detected
    }

    # Resolve the launcher, loader, and game directory together. "Both" may
    # mean Fabric in one launcher and Forge in the other; never collapse that
    # into one loader choice and silently leave half the installation behind.
    $targets = @()
    if ($launcherChoice -in @('Prism', 'Both')) {
        if ($detected.PrismDataDir) {
            $targetLoader = if ($loaderChoice) { $loaderChoice } else { $detected.ModLoaders['Prism'] }
            if (-not (Test-LoaderName $targetLoader)) {
                throw 'Cannot determine the Prism installation loader. Uninstall each loader explicitly with -ModLoader.'
            }
            $targets += [pscustomobject]@{
                Launcher = 'Prism'
                Loader   = $targetLoader
                McDir    = Join-Path (Get-PrismInstanceDir -DataDir $detected.PrismDataDir) '.minecraft'
            }
        }
    }
    if ($launcherChoice -in @('Microsoft', 'Both')) {
        $targetLoader = if ($loaderChoice) { $loaderChoice } else { $detected.ModLoaders['Microsoft'] }
        if (-not (Test-LoaderName $targetLoader)) {
            throw 'Cannot determine the Microsoft installation loader. Uninstall each loader explicitly with -ModLoader.'
        }
        $targets += [pscustomobject]@{
            Launcher = 'Microsoft'
            Loader   = $targetLoader
            McDir    = Get-McDirForLauncher -Launcher Microsoft -ModLoader $targetLoader
        }
    }

    # Drop targets that have no installed mod; surface a warning if everything
    # we were asked to uninstall is already clean.
    $actionable = @($targets | Where-Object { Get-InstalledModVersion -McDir $_.McDir -ModLoader $_.Loader })
    if ($actionable.Count -eq 0) {
        Write-Warn "No RTX Remix mod found in any of the selected launcher target(s); nothing to uninstall."
        return 0
    }

    foreach ($t in $actionable) {
        $ver = Get-InstalledModVersion -McDir $t.McDir -ModLoader $t.Loader
        Write-Info "$($t.Launcher): found $($t.Loader) $ver  ($($t.McDir))"
    }

    if ($Unattended) {
        if (-not $AcknowledgeDataLoss) {
            Write-Err '-AcknowledgeDataLoss is required alongside -Unattended for uninstall (safety guard).'
            return 2
        }
    } else {
        Write-Host ''
        Write-Host '  The following will be removed from each target:'
        foreach ($t in $actionable) {
            Write-Host "    - $($script:C_DIM)$($t.Launcher): <mc>/mods/rtx-remix-renderer-* ($($t.Loader))$($script:C_RESET)"
        }
        $companions = @($actionable | ForEach-Object { (Get-LoaderProvider -ModLoader $_.Loader).Companion } | Where-Object { $_ })
        foreach ($companion in $companions | Sort-Object DisplayName -Unique) {
            Write-Host "    - $($script:C_DIM)<mc>/mods/$($companion.DisplayName.ToLowerInvariant().Replace(' ', '-'))-$($companion.Version).jar$($script:C_RESET) (provider-managed companion dependency)"
        }
        Write-Host "    - $($script:C_DIM)<mc>/remix-sdk/, <mc>/rtx.conf, <mc>/.rtx-remix-version$($script:C_RESET)"
        Write-Host "    - $($script:C_DIM)<mc>/textures/overcast_thunderstorm_bc6u.dds and any legacy water_normals_flipbook.dds$($script:C_RESET)"
        Write-Host "    - $($script:C_DIM)<mc>/resourcepacks/RTX-Remix-Materials-*.zip$($script:C_RESET) (RTX Remix Materials, and its entry in options.txt)"
        Write-Host "    - $($script:C_DIM)<mc>/resourcepacks/RtxRemix-LabPBR/$($script:C_RESET) (if left by an earlier version)"
        Write-Host '  Preserved:'
        Write-Host "    - $($script:C_DIM)<mc>/rtx-remix/mods/minecraft-logic/$($script:C_RESET) (authored profiles)"
        Write-Host "    - $($script:C_DIM)<mc>/saves/$($script:WORLD_FOLDER_NAME)/$($script:C_RESET) (RTX Remix Showcase World)"
        if (-not (Confirm-YesNo -Prompt 'Proceed?' -DefaultNo)) {
            Write-Info 'Cancelled.'
            return 0
        }
    }

    foreach ($t in $actionable) {
        Write-Info "Uninstalling $($t.Launcher)..."
        Remove-ModArtifacts -McDir $t.McDir -Launcher $t.Launcher -ModLoader $t.Loader
    }
    Write-Info 'Mod files removed.'

    if (-not $Unattended) {
        # Deeper-cleanup menu. Only show options that are meaningful given
        # what's actually on disk; a user with no managed JDK shouldn't see
        # "remove managed JDK". Any Prism-specific options only appear when
        # Prism was one of the uninstall targets.
        $touchedPrism = @($actionable | Where-Object { $_.Launcher -eq 'Prism' }).Count -gt 0
        $hasManagedJdk = Test-Path -LiteralPath (Join-Path $script:MANAGED_JDK_DIR 'bin\java.exe')
        $hasPortable   = Test-Path -LiteralPath (Join-Path $script:PRISM_PORTABLE_DIR 'prismlauncher.exe')

        $menuItems = @(
            [pscustomobject]@{ Label = 'Leave everything else alone'; Hint = 'just removed mod files'; Value = 'none' }
        )
        if ($hasManagedJdk) {
            $jdkHint = "Adoptium JDK $($script:JAVA_TARGET) at $($script:MANAGED_JDK_DIR)"
            if (Test-HostIsArm64) {
                # On ARM64 this JDK IS what Prism uses to launch (we pinned it).
                # Removing it without replacing with another ARM64 JDK will make
                # launches fail until the user reconfigures Prism's Java.
                $jdkHint += "  (WARNING: ARM64, Prism depends on this)"
            } else {
                $jdkHint += "  (only used by this installer / Prism; safe to remove)"
            }
            $menuItems += [pscustomobject]@{ Label = 'Also remove installer-managed Java'; Hint = $jdkHint; Value = 'jdk' }
        }
        if ($touchedPrism -and $hasPortable) {
            $menuItems += [pscustomobject]@{ Label = 'Also remove our Prism portable'; Hint = "$($script:PRISM_PORTABLE_DIR)"; Value = 'prism' }
        }
        if ($touchedPrism -and $detected.PrismDataDir) {
            $instancePath = Get-PrismInstanceDir -DataDir $detected.PrismDataDir
            if (Test-Path -LiteralPath $instancePath) {
                $menuItems += [pscustomobject]@{ Label = 'Remove the RTX Remix Prism instance only'; Hint = 'also deletes its worlds; keeps Prism and your other instances'; Value = 'instance' }
            }
        }
        if ($touchedPrism -and ($hasManagedJdk -or $hasPortable -or ($detected.PrismDataDir -and (Test-Path -LiteralPath (Get-PrismInstanceDir -DataDir $detected.PrismDataDir))))) {
            $menuItems += [pscustomobject]@{ Label = 'Scorched earth'; Hint = 'instance and its worlds + managed Java + our Prism portable'; Value = 'all' }
        }

        if ($menuItems.Count -gt 1) {
            $extras = Invoke-Menu -Title '  Optional deeper cleanup - select one or skip:' -Items $menuItems
            switch ($extras) {
                'jdk'      { Remove-ManagedJdk }
                'prism'    { Remove-PrismPortable }
                'instance' { Remove-PrismInstance -DataDir $detected.PrismDataDir }
                'all' {
                    Remove-PrismInstance -DataDir $detected.PrismDataDir
                    Remove-ManagedJdk
                    Remove-PrismPortable
                }
            }
        }
    }

    Write-Host ''
    Write-Host "  $($script:ICON_OK) $($script:C_GREEN)Uninstall complete.$($script:C_RESET)"
    return 0
}

function Remove-ModArtifacts {
    param(
        [string]$McDir,
        [ValidateSet('Prism','Microsoft')] [string]$Launcher,
        [string]$ModLoader
    )
    if (-not $ModLoader) {
        $ModLoader = $script:LOADER_CATALOG[$script:DEFAULT_LOADER_ID].DisplayName
    }
    $provider = Get-LoaderProvider -ModLoader $ModLoader
    if (-not (Test-Path -LiteralPath $McDir)) { return }

    Get-ChildItem -LiteralPath (Join-Path $McDir 'mods') -Filter 'rtx-remix-renderer-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { (Get-ModArtifactLoader -FileName $_.Name) -eq $provider.DisplayName } |
        ForEach-Object { Write-Info "Removing $($_.Name)"; Remove-Item -LiteralPath $_.FullName -Force }

    # Fabric API handling mirrors Install-FabricApi's scoping:
    #   Prism (isolated instance) -> clean up every fabric-api-*.jar we find.
    #   Microsoft (shared .minecraft/mods) -> only our version-pinned one.
    if ($provider.Companion -and $provider.Companion.Kind -eq 'FabricApi' -and $Launcher -eq 'Prism') {
        Get-ChildItem -LiteralPath (Join-Path $McDir 'mods') -Filter 'fabric-api-*.jar' -ErrorAction SilentlyContinue |
            ForEach-Object { Write-Info "Removing $($_.Name)"; Remove-Item -LiteralPath $_.FullName -Force }
    } elseif ($provider.Companion -and $provider.Companion.Kind -eq 'FabricApi') {
        $fabricJar = Join-Path $McDir "mods\fabric-api-$($provider.Companion.Version).jar"
        if (Test-Path -LiteralPath $fabricJar) { Write-Info "Removing $(Split-Path $fabricJar -Leaf)"; Remove-Item -LiteralPath $fabricJar -Force }
    }
    $remainingMod = @(Get-ChildItem -LiteralPath (Join-Path $McDir 'mods') -Filter 'rtx-remix-renderer-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-(sources|dev|javadoc)\.jar$' })
    if ($remainingMod.Count -eq 0) {
        foreach ($path in @(
            (Join-Path $McDir 'remix-sdk')
            (Join-Path $McDir 'rtx.conf')
            (Join-Path $McDir '.rtx-remix-version')
            (Join-Path $McDir 'textures\water_normals_flipbook.dds')
            (Join-Path $McDir 'textures\overcast_thunderstorm_bc6u.dds')
            (Join-Path $McDir $script:LEGACY_RESOURCE_PACK_PATH)
        )) {
            if (Test-Path -LiteralPath $path) {
                Write-Info "Removing $((Split-Path $path -Leaf))"
                Remove-Item -LiteralPath $path -Recurse -Force
            }
        }
    } else {
        Write-Info 'Keeping shared RTX Remix runtime files because another loader-specific renderer JAR remains.'
    }
    foreach ($pack in (Get-InstalledArtPacks -McDir $McDir)) {
        Write-Info "Removing $($pack.Name)"
        Remove-Item -LiteralPath $pack.FullName -Force
    }
    $null = Set-ArtPackOption -McDir $McDir -PackName ''
    # The world may hold the player's progress, so it stays.
    $saves = Join-Path $McDir 'saves'
    if (Test-Path -LiteralPath (Join-Path $saves $script:WORLD_FOLDER_NAME)) {
        Write-Info "Kept the $($script:WORLD_FOLDER_NAME) world in ${saves}. Delete that folder yourself if you no longer want it."
    }
}

function Remove-ManagedJdk {
    if (Test-Path -LiteralPath $script:MANAGED_JDK_DIR) {
        Remove-Item -LiteralPath $script:MANAGED_JDK_DIR -Recurse -Force
        Write-Info "Removed managed JDK at $($script:MANAGED_JDK_DIR)"
    }
}

function Remove-PrismPortable {
    if (Test-Path -LiteralPath $script:PRISM_PORTABLE_DIR) {
        Remove-Item -LiteralPath $script:PRISM_PORTABLE_DIR -Recurse -Force
        Write-Info "Removed Prism portable at $($script:PRISM_PORTABLE_DIR)"
    }
}

function Remove-PrismInstance {
    param([string]$DataDir, [string]$InstanceName = $script:PRISM_INSTANCE_NAME)
    if (-not $DataDir) { return }
    $path = Get-PrismInstanceDir -DataDir $DataDir -InstanceName $InstanceName
    if (Test-Path -LiteralPath $path) {
        Remove-Item -LiteralPath $path -Recurse -Force
        Write-Info "Removed Prism instance at $path"
    }
    # The record only leads later runs to this instance.
    if ((Read-PrismDataDirRecord) -eq $DataDir) { Remove-Item -LiteralPath $script:PRISM_DATA_DIR_RECORD -Force }
}

# --- Export ---
# Transient / privacy-sensitive paths stripped from Export archives.
# Directory entries also implicitly strip everything beneath them.
$script:EXPORT_STRIP_PATHS = @(
    # User play state: recipient starts with a clean slate.
    '.minecraft\logs'
    '.minecraft\crash-reports'
    '.minecraft\saves'
    '.minecraft\screenshots'
    # Privacy: saved servers + realms session hold IPs / session tokens.
    '.minecraft\servers.dat'
    '.minecraft\servers.dat_old'
    '.minecraft\realms_persistence.json'
    '.minecraft\usercache.json'
    '.minecraft\usernamecache.json'
    # Size: re-downloaded on first launch.
    '.minecraft\libraries'
    '.minecraft\assets'
    '.minecraft\.cache'
    '.minecraft\natives'
    # Prism's own transient / cache state scoped to the instance.
    '.mmc-auto.json'
    'instance.cfg.bak'
    'mmc-pack.json.bak'
)

function Test-PathOutputValid {
    <#
      Validate -OutputPath for Export. Returns a canonical absolute path on
      success; throws on invalid input. Rejects: non-.zip extension, target
      is an existing directory, reserved Windows device names, trailing
      dot/space (legal in the API but frequently breaks Explorer).
    #>
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'OutputPath cannot be empty.' }
    try { $full = [System.IO.Path]::GetFullPath($Path) }
    catch { throw "OutputPath is not a valid path: $Path" }
    # OrdinalIgnoreCase rather than ToLower() so intent is explicit and we
    # don't depend on invariant-culture lowercasing.
    if (-not $full.EndsWith('.zip', [StringComparison]::OrdinalIgnoreCase)) { throw 'OutputPath must end in .zip' }
    if (Test-Path -LiteralPath $full -PathType Container) { throw "OutputPath refers to an existing directory: $full" }
    $leaf = [System.IO.Path]::GetFileNameWithoutExtension($full)
    # Windows reserves CON/PRN/AUX/NUL and COM0-9 / LPT0-9. (COM0/LPT0 are
    # reserved on modern Windows per the same MSDN device-name rules even
    # though most documentation historically showed 1-9.)
    if ($leaf -match '^(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])$') {
        throw "OutputPath uses a reserved Windows device name: $leaf"
    }
    if ($full -match '[. ]$') { throw 'OutputPath must not end with a trailing dot or space.' }
    return $full
}

function Invoke-Export {
    param([string]$PreferredLauncher, [string]$PreferredModLoader, [switch]$Unattended, [string]$OutputPath)
    if ($Unattended) { Write-Banner -Title 'RTX Remix for Minecraft - Export' -Subtitle "v$($script:APP_VERSION)" }
    $state = Get-LauncherState

    $launcher = Select-Launcher -State $state -Preferred $PreferredLauncher -Unattended:$Unattended
    if ($launcher -ne 'Prism') {
        Write-Err 'Export is Prism-only in v1 (the Microsoft launcher has no self-contained instance to zip).'
        Write-Err 'For MS-launcher users: install on the target machine with install.bat and copy worlds manually.'
        return 2
    }
    if (-not $state.PrismDataDir) { throw 'Prism data dir not resolved; cannot locate instance to export.' }

    $instanceDir = Get-PrismInstanceDir -DataDir $state.PrismDataDir
    if (-not (Test-Path -LiteralPath $instanceDir)) {
        Write-Err "No Prism instance found at $instanceDir; nothing to export."
        return 1
    }
    $detected = Get-DetectedState
    $loader = Select-ModLoader -Preferred $PreferredModLoader -Unattended:$Unattended -Mode Export -Launcher Prism -DetectedState $detected
    $installedLoader = Get-PrismLoader -DataDir $state.PrismDataDir
    if ($installedLoader -ne $loader) {
        Write-Err "Cannot export as ${loader}: the Prism instance loader is '$installedLoader'. Repair or switch it first."
        return 2
    }
    $mcDir = Join-Path $instanceDir '.minecraft'
    $modVer = Get-InstalledModVersion -McDir $mcDir
    if (-not $modVer) { Write-Warn 'No RTX Remix mod JAR in the instance; exporting anyway.' }

    if (-not $OutputPath) {
        if ($Unattended) {
            Write-Err '-OutputPath is required in unattended export mode.'
            return 2
        }
        $default = Join-Path $HOME "Downloads\rtx-remix-portable-$($script:APP_VERSION).zip"
        Write-Host ''
        Write-Host -NoNewline "  Output path [$($script:C_DIM)$default$($script:C_RESET)]: "
        $entered = Read-Host
        $OutputPath = if ([string]::IsNullOrWhiteSpace($entered)) { $default } else { $entered }
    }
    $OutputPath = Test-PathOutputValid -Path $OutputPath
    $parent = Split-Path -Parent $OutputPath
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    if (Test-Path -LiteralPath $OutputPath) {
        if ((-not $Unattended) -and (-not (Confirm-YesNo -Prompt "Overwrite existing $OutputPath?" -DefaultNo))) {
            Write-Info 'Cancelled.'
            return 0
        }
        Remove-Item -LiteralPath $OutputPath -Force
    }

    Write-Info 'Staging export contents...'
    $stage = New-SessionTempPath 'export-stage'
    New-Item -ItemType Directory -Path $stage -Force | Out-Null
    $dest  = Join-Path $stage $script:PRISM_INSTANCE_NAME

    # Manual copy: skips reparse points and strip-list paths up front. Never
    # materializes bloat or privacy data into the stage dir.
    Copy-InstanceForExport -Source $instanceDir -Dest $dest

    $readme = @"
RTX Remix for Minecraft: portable export
Generated (UTC): $((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss'))
Source instance: $modVer
Mod loader: $loader

To install on another machine:
1. Install Prism Launcher: https://prismlauncher.org
2. Extract this zip into Prism's instances/ folder, e.g.:
     Windows: %APPDATA%\PrismLauncher\instances\
     Linux  : ~/.local/share/PrismLauncher/instances/
3. Open Prism -> select '$($script:PRISM_INSTANCE_NAME)' -> Launch.

Not included (intentionally):
- servers.dat / servers.dat_old, Realms persistence, and user-name caches
  (privacy: re-add servers on the target machine).
- Worlds, screenshots, crash-reports, and .minecraft/logs (start fresh on the
  target). Other custom log/config paths are included unless removed before export.
- The libraries/, assets/, natives/ caches; Prism/Minecraft re-download these
  on first launch.
- Prism transient metadata: .mmc-auto.json, instance.cfg.bak, mmc-pack.json.bak.

Notes:
- The export includes the current remix-sdk/ and its version marker. A different
  mod version will refresh the bundled payload on the next launch.
- On ARM64 Windows (WoA), install our managed Adoptium JDK via install.bat
  before first launch. Mojang does not publish an ARM64 Windows JRE, so
  Prism's Java Wizard cannot help there.
"@
    [System.IO.File]::WriteAllText(
        (Join-Path $stage 'README.txt'),
        $readme,
        [System.Text.UTF8Encoding]::new($false))

    Write-Info "Creating $OutputPath ..."
    # .NET API instead of Compress-Archive: the latter has a 2 GB archive-size
    # ceiling on PS 5.1 (Int32 size field). A populated Prism instance can
    # easily exceed that.
    Add-Type -AssemblyName 'System.IO.Compression.FileSystem'
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $stage, $OutputPath,
        [System.IO.Compression.CompressionLevel]::Optimal,
        $false)  # includeBaseDirectory=false -> archive root is $stage's children

    $size = [math]::Round((Get-Item -LiteralPath $OutputPath).Length / 1MB, 1)
    Write-Host ''
    Write-Host "  $($script:ICON_OK) $($script:C_GREEN)Export complete:$($script:C_RESET) $OutputPath ($size MB)"
    return 0
}

function Copy-InstanceForExport {
    <#
      Recursively copy $Source to $Dest while:
       - skipping reparse points (junctions/symlinks); Prism sometimes
         uses a junction into a shared runtime cache; following it would
         balloon the archive and leak data outside the instance.
       - skipping paths matching $script:EXPORT_STRIP_PATHS (transient,
         privacy-sensitive, or re-downloadable).
      Manual walker rather than Copy-Item -Recurse so we can inspect each
      entry's attributes.
    #>
    param([string]$Source, [string]$Dest)
    if (-not (Test-Path -LiteralPath $Source)) { throw "Source not found: $Source" }
    $srcFull = (Resolve-Path -LiteralPath $Source).ProviderPath
    if (-not (Test-Path -LiteralPath $Dest)) { New-Item -ItemType Directory -Path $Dest -Force | Out-Null }

    $stripSet = @{}
    foreach ($p in $script:EXPORT_STRIP_PATHS) { $stripSet[$p.ToLowerInvariant()] = $true }

    function Test-StripPath([string]$rel) {
        $lr = $rel.ToLowerInvariant()
        if ($stripSet.ContainsKey($lr)) { return $true }
        foreach ($k in $stripSet.Keys) {
            if ($lr.StartsWith($k + '\')) { return $true }
        }
        return $false
    }

    $skippedReparse = 0
    $skippedByRule  = 0

    $stack = New-Object System.Collections.Stack
    $stack.Push([pscustomobject]@{ Src = $srcFull; Rel = '' })
    while ($stack.Count -gt 0) {
        $node = $stack.Pop()
        $src = $node.Src
        $rel = $node.Rel

        $attrs = [System.IO.File]::GetAttributes($src)
        # Only skip reparse points BELOW the root. If the user's Prism
        # instances/ dir is itself a junction (legitimate, e.g. onto a
        # secondary drive), we still need to traverse into it for the
        # export; skipping the root would produce an empty archive.
        if ($rel -and (($attrs -band [System.IO.FileAttributes]::ReparsePoint) -ne 0)) {
            $skippedReparse++
            Write-Trace "Skipping reparse point: $rel"
            continue
        }

        $isDir = ($attrs -band [System.IO.FileAttributes]::Directory) -ne 0
        if ($rel -and (Test-StripPath $rel)) {
            $skippedByRule++
            Write-Trace "Stripping from export: $rel"
            continue
        }

        $dst = if ($rel) { Join-Path $Dest $rel } else { $Dest }
        if ($isDir) {
            if (-not (Test-Path -LiteralPath $dst)) { New-Item -ItemType Directory -Path $dst -Force | Out-Null }
            foreach ($child in Get-ChildItem -LiteralPath $src -Force) {
                $childRel = if ($rel) { Join-Path $rel $child.Name } else { $child.Name }
                $stack.Push([pscustomobject]@{ Src = $child.FullName; Rel = $childRel })
            }
        } else {
            Copy-Item -LiteralPath $src -Destination $dst -Force
        }
    }
    if ($skippedReparse -gt 0) { Write-Info "Skipped $skippedReparse reparse point(s) in export." }
    if ($skippedByRule  -gt 0) { Write-Info "Stripped $skippedByRule transient/private path(s) from export." }
}

# --- Main menu ---
function Show-MainMenu {
    <#
      Main TUI entry. Runs the menu on the alternate screen, which has no
      scrollback and gives the original screen back when the menu closes.
      Invoke-Menu repaints the banner + detected-state block ABOVE the menu
      on every frame, so they stay visible however the user navigates or
      resizes. Once an action is picked, the menu is gone and that action's
      own banner and steps print on the original screen.
    #>
    $state  = Get-DetectedState
    $status = Get-InstallStatus -State $state
    $hasAnyInstall = $state.ModInstalled.Count -gt 0

    # Headline that summarizes what action the installer thinks is most
    # relevant right now. Uses 'Install' / 'Upgrade' / 'Repair' / etc.
    $headline = switch ($status) {
        'install'    { "Ready to $($script:C_BOLD)install$($script:C_RESET); no existing mod detected." }
        'upgrade'    { "An $($script:C_BOLD)upgrade$($script:C_RESET) is available; installed version differs from package." }
        'repair'     { "Package version matches installed version; pick $($script:C_BOLD)Repair$($script:C_RESET) to reinstall packaged files." }
        'no-package' { "$($script:C_YELLOW)No package mod JAR found in $(Join-Path $script:SCRIPT_DIR 'mods'); Install/Upgrade/Repair will fail until a dist archive is present.$($script:C_RESET)" }
        'ambiguous-package' { "$($script:C_YELLOW)More than one mod JAR is in $(Join-Path $script:SCRIPT_DIR 'mods'). Extract the download into an empty folder and run the installer again.$($script:C_RESET)" }
        default      { '' }
    }
    # Install, Upgrade and Repair all need exactly one packaged mod JAR.
    $noPackage = $status -in @('no-package', 'ambiguous-package')
    $header = {
        Write-Banner -Title 'RTX Remix for Minecraft - Installer' -Subtitle "v$($script:APP_VERSION)"
        Write-DetectedStateBlock -State $state
        Write-Host ''
        if ($headline) {
            Write-Host "  $headline"
            Write-Host ''
        }
    }

    # Build menu with Install vs Upgrade vs Repair primary action, and
    # gray out modes that don't make sense in the current state.
    $installLabel = switch ($status) {
        'upgrade'    { 'Upgrade' }
        'repair'     { 'Repair' }
        default      { 'Install' }
    }
    $installValue = switch ($status) {
        'upgrade'    { 'Upgrade' }
        'repair'     { 'Repair' }
        default      { 'Install' }
    }
    $installHint = switch ($status) {
        'install'    { 'fresh install' }
        'upgrade'    { "update to $($state.ModPackage)" }
        'repair'     { 'reinstall the matching version' }
        'no-package' { 'no mod JAR in dist/mods/' }
        'ambiguous-package' { 'more than one mod JAR in mods\' }
        default      { '' }
    }

    $items = @(
        [pscustomobject]@{ Label = $installLabel; Hint = $installHint; Value = $installValue; Disabled = $noPackage }
    )
    # If the primary action is Upgrade or Repair, also offer Install
    # (forces fresh-install UX even when we detected an existing one) and
    # the other of Upgrade/Repair as a secondary option. Upgrade and Repair
    # keep the launcher that already has the mod, so Install, which asks,
    # is the way into the other launcher; its hint names it. With Prism and
    # no Microsoft launcher, Select-Launcher takes Prism without asking, so
    # the hint says what the Microsoft launcher needs first instead.
    if ($installValue -ne 'Install') {
        $kept = Get-SoleInstalledLauncher -ModInstalled $state.ModInstalled
        $switchHint = if ($state.HasPrism -and -not $state.HasMs) {
            'reinstall into Prism; for the Microsoft launcher, install and start it first'
        } elseif ($kept) {
            "choose the launcher, e.g. to use $(Get-OtherLauncherLabel -Launcher $kept) instead"
        } else {
            'choose the launcher'
        }
        $items += [pscustomobject]@{ Label = 'Install'; Hint = $switchHint; Value = 'Install'; Disabled = $noPackage }
    }
    if ($installValue -eq 'Upgrade') {
        # User could also repair-at-current-version by forcing Install; keep it simple, don't add a separate Repair alongside Upgrade.
    }
    $items += [pscustomobject]@{
        Label = 'Uninstall'; Value = 'Uninstall'
        Hint  = if ($hasAnyInstall) { 'remove installed mod files' } else { 'no installed mod detected' }
        Disabled = -not $hasAnyInstall
    }
    $items += [pscustomobject]@{
        Label = 'Export'; Value = 'Export'
        Hint  = if ($state.HasPrism) {
            if ($state.ModInstalled.ContainsKey('Prism')) { 'zip Prism instance for another PC' } else { 'Prism detected but no mod installed in it' }
        } else { 'Prism only; no Prism detected' }
        Disabled = (-not $state.HasPrism) -or (-not $state.ModInstalled.ContainsKey('Prism'))
    }
    $items += [pscustomobject]@{ Label = 'Quit'; Hint = ''; Value = 'Quit' }

    # Alternate screen: no scrollback to scroll through, and the original
    # screen comes back when the menu closes.
    [Console]::Write("$ESC[?1049h")
    try {
        return Invoke-Menu -Title '  Choose an action:' -Items $items -Header $header
    } finally {
        [Console]::Write("$ESC[?1049l")
    }
}

# --- Entry point ---
try {
    # Promote the script-level param switches to $script: so deeply-nested
    # helpers don't have to thread them through every signature. Setting
    # once here (never mutated afterwards) avoids the dynamic-scoping
    # fragility of reading bare `$Unattended` / `$SkipHashCheck` from
    # inside functions.
    $script:Unattended     = $Unattended.IsPresent
    $script:SkipHashCheck  = $SkipHashCheck.IsPresent
    $script:ModFilesCopied = $false
    $script:HoldWindowOnExit = $false

    Initialize-Session

    if ($Unattended -and -not $MinecraftVersion) {
        Write-Err "-MinecraftVersion $($script:AVAILABLE_MINECRAFT_VERSIONS -join '|') is required with -Unattended."
        exit 2
    }
    $null = Select-MinecraftVersion -Preferred $MinecraftVersion -Unattended:$Unattended

    if (-not $Mode) {
        if ($Unattended) {
            Write-Err '-Unattended requires -Mode.'
            exit 2
        }
        if (-not (Test-InteractiveHost)) {
            Write-Err 'Non-interactive host detected (stdin is redirected).'
            Write-Err 'The interactive TUI needs a real terminal. Re-run with an explicit -Mode and -Unattended for CI/piped use.'
            exit 2
        }
        # A double-clicked install.bat owns its console window, which
        # closes the moment the installer exits and takes the result, or
        # the error, with it. A run from the menu waits for a key first
        # (in the finally below); Quit just leaves.
        $script:HoldWindowOnExit = $true
        $choice = Show-MainMenu
        if (-not $choice -or $choice -eq 'Quit') {
            $script:HoldWindowOnExit = $false
            Write-Host ''
            Write-Host "  $($script:ICON_INFO) Goodbye."
            exit 0
        }
        $Mode = $choice
    }
    if ($Launcher -eq 'Both' -and $Mode -ne 'Uninstall') {
        Write-Err '-Launcher Both is valid only with -Mode Uninstall.'
        exit 2
    }
    if ($ModLoader -and -not (Test-LoaderName $ModLoader)) {
        Write-Err "Unknown -ModLoader '$ModLoader'. Supported loaders: $((Get-LoaderDisplayNames) -join '|')."
        exit 2
    }
    if ($Unattended -and -not $ModLoader -and
        -not ($Mode -eq 'Uninstall' -and $Launcher -eq 'Both')) {
        Write-Err "-ModLoader $((Get-LoaderDisplayNames) -join '|') is required with -Unattended."
        exit 2
    }

    switch ($Mode) {
        'Install'   { $code = Invoke-InstallOrUpgrade -PreferredLauncher $Launcher -PreferredModLoader $ModLoader -Unattended:$Unattended -Mode 'Install' }
        'Upgrade'   { $code = Invoke-InstallOrUpgrade -PreferredLauncher $Launcher -PreferredModLoader $ModLoader -Unattended:$Unattended -Mode 'Upgrade' }
        'Repair'    { $code = Invoke-InstallOrUpgrade -PreferredLauncher $Launcher -PreferredModLoader $ModLoader -Unattended:$Unattended -Mode 'Repair' }
        'Uninstall' { $code = Invoke-Uninstall        -PreferredLauncher $Launcher -PreferredModLoader $ModLoader -Unattended:$Unattended -AcknowledgeDataLoss:$AcknowledgeDataLoss }
        'Export'    { $code = Invoke-Export           -PreferredLauncher $Launcher -PreferredModLoader $ModLoader -Unattended:$Unattended -OutputPath $OutputPath }
        default     { Write-Err "Unknown mode: $Mode"; $code = 2 }
    }
    # The mode functions return the exit code last; anything else they leaked
    # into the output stream must not turn it into an array.
    $code = @($code)[-1]
    exit $code
} catch {
    # Choosing Esc or Q at a launcher prompt is not a failure.
    if ($_.Exception.Message -eq 'Cancelled by user.') {
        Write-Host ''
        Write-Host "  $($script:ICON_INFO) Cancelled. Nothing was changed."
        exit 0
    }
    Write-Err $_.Exception.Message
    if ($VerbosePreference -eq 'Continue') { Write-Err ($_ | Out-String) }
    if ($Mode -in 'Install', 'Upgrade', 'Repair' -and -not $script:ModFilesCopied) {
        Write-Err 'No mod files were copied.'
    }
    exit 1
} finally {
    Remove-Session
    if ($script:HoldWindowOnExit) {
        # Only a key pressed after the prompt closes the window.
        Clear-QueuedKeys
        Write-Host ''
        Write-Host -NoNewline "  $($script:ICON_INFO) Press any key to close this window."
        $null = Read-KeyRaw
        Write-Host ''
    }
}
