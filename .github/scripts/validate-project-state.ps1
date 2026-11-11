# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

[CmdletBinding()]
param(
    [string]$GitHubEnvironment,
    [string]$GitHubOutput,
    [string]$ReleaseAssetsDirectory,
    [string]$MinecraftVersion,
    [string]$ModLoader
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$manifestPath = Join-Path $projectRoot 'project-state.json'

if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Missing project-state.json at the repository root."
}

try {
    $state = Get-Content -LiteralPath $manifestPath -Raw -Encoding utf8 | ConvertFrom-Json
} catch {
    throw "project-state.json is not valid JSON: $($_.Exception.Message)"
}

function Require-String {
    param($Object, [string]$Name, [string]$Path)
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $property.Value -isnot [string] -or -not $property.Value.Trim()) {
        throw "project-state.json $Path must be a non-empty string."
    }
    return $property.Value.Trim()
}

function Require-Object {
    param($Object, [string]$Name, [string]$Path)
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $property.Value -isnot [pscustomobject]) {
        throw "project-state.json $Path must be an object."
    }
    return $property.Value
}

$schema = Require-String $state 'schema' 'schema'
if ($schema -cne 'multi-version/v1') {
    throw "project-state.json schema must be 'multi-version/v1'."
}

$mod = Require-Object $state 'mod' 'mod'
$modId = Require-String $mod 'id' 'mod.id'
$modGroup = Require-String $mod 'group' 'mod.group'
$modVersion = Require-String $mod 'version' 'mod.version'
[void](Require-String $mod 'displayName' 'mod.displayName')
if ($modId -cnotmatch '^[a-z][a-z0-9-]*$' -or $modGroup -notmatch '^[A-Za-z0-9_.-]+$' -or
    $modVersion -notmatch '^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$') {
    throw "project-state.json mod identity or version is malformed."
}

$versions = Require-Object $state 'versions' 'versions'
$versionProperties = @($versions.PSObject.Properties | Where-Object { $_.MemberType -eq 'NoteProperty' })
if ($versionProperties.Count -eq 0) {
    throw "project-state.json versions must declare at least one Minecraft version."
}
$defaultMinecraftVersion = Require-String $state 'defaultMinecraftVersion' 'defaultMinecraftVersion'
if (-not $versions.PSObject.Properties[$defaultMinecraftVersion]) {
    throw "project-state.json defaultMinecraftVersion '$defaultMinecraftVersion' is not present in versions."
}
if ($ModLoader -and -not $MinecraftVersion) {
    throw "-ModLoader requires -MinecraftVersion."
}

$targets = @()
$validatedVersions = [ordered]@{}
foreach ($versionProperty in $versionProperties) {
    $versionId = $versionProperty.Name
    $version = $versionProperty.Value
    $versionPath = "versions.$versionId"
    if ($versionId -cnotmatch '^\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.-]+)?$' -or
        $version -isnot [pscustomobject]) {
        throw "Every project-state.json version key must be a Minecraft version string with an object value."
    }
    $supportTier = Require-String $version 'supportTier' "$versionPath.supportTier"
    if ($supportTier -notin @('primary', 'active', 'maintenance', 'archived')) {
        throw "project-state.json $versionPath.supportTier must be primary, active, maintenance, or archived."
    }
    $minecraft = Require-Object $version 'minecraft' "$versionPath.minecraft"
    $declaredMinecraftVersion = Require-String $minecraft 'version' "$versionPath.minecraft.version"
    if ($declaredMinecraftVersion -cne $versionId) {
        throw "project-state.json $versionPath.minecraft.version must match its version key."
    }
    $minecraftModule = Require-String $minecraft 'module' "$versionPath.minecraft.module"
    if ($minecraftModule -cne "versions/$versionId/minecraft") {
        throw "project-state.json $versionPath.minecraft.module must be 'versions/$versionId/minecraft'."
    }
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot $minecraftModule) -PathType Container)) {
        throw "Declared Minecraft module '$minecraftModule' does not exist."
    }
    [void](Require-String $minecraft 'versionRange' "$versionPath.minecraft.versionRange")
    if (($minecraft.javaTarget -isnot [int] -and $minecraft.javaTarget -isnot [long]) -or
        $minecraft.javaTarget -lt 8) {
        throw "project-state.json $versionPath.minecraft.javaTarget must be an integer Java release."
    }
    if (($minecraft.dataVersion -isnot [int] -and $minecraft.dataVersion -isnot [long]) -or
        $minecraft.dataVersion -le 0) {
        throw "project-state.json $versionPath.minecraft.dataVersion must be a positive integer."
    }
    [void](Require-String $minecraft 'hostGraphicsBackend' "$versionPath.minecraft.hostGraphicsBackend")

    $loaders = Require-Object $version 'loaders' "$versionPath.loaders"
    $loaderProperties = @($loaders.PSObject.Properties | Where-Object { $_.MemberType -eq 'NoteProperty' })
    if ($loaderProperties.Count -eq 0) {
        throw "project-state.json $versionPath.loaders must declare at least one loader."
    }
    $defaultLoader = Require-String $version 'defaultLoader' "$versionPath.defaultLoader"
    $normalizedLoaders = @()
    foreach ($loaderProperty in $loaderProperties) {
        $loaderId = $loaderProperty.Name
        $loader = $loaderProperty.Value
        $loaderPath = "$versionPath.loaders.$loaderId"
        if ($loaderId -cnotmatch '^[a-z][a-z0-9-]*$' -or $loader -isnot [pscustomobject]) {
            throw "Every project-state.json loader key must be a lowercase slug with an object value."
        }
        if ($loader.PSObject.Properties['id'] -and $loader.id -cne $loaderId) {
            throw "project-state.json loader key '$loaderId' disagrees with entry id '$($loader.id)'."
        }
        [void](Require-String $loader 'displayName' "$loaderPath.displayName")
        $module = Require-String $loader 'module' "$loaderPath.module"
        if ($module -cne "versions/$versionId/$loaderId") {
            throw "project-state.json $loaderPath.module must be 'versions/$versionId/$loaderId'."
        }
        $provider = Require-String $loader 'provider' "$loaderPath.provider"
        $projectPath = Require-String $loader 'projectPath' "$loaderPath.projectPath"
        $loaderSupportTier = Require-String $loader 'supportTier' "$loaderPath.supportTier"
        if ($loaderSupportTier -notin @('active', 'maintenance', 'experimental', 'archived')) {
            throw "project-state.json $loaderPath.supportTier is invalid."
        }
        if ($provider -cnotmatch '^[a-z][a-z0-9-]*$') {
            throw "project-state.json $loaderPath.provider must be a lowercase slug."
        }
        $expectedProjectPath = ':' + (($module.Replace('\', '/').Trim('/')) -replace '/', ':')
        if ($projectPath -cne $expectedProjectPath) {
            throw "project-state.json $loaderPath.projectPath must match module '$module'."
        }
        $gradle = Require-Object $loader 'gradle' "$loaderPath.gradle"
        foreach ($taskName in @('build', 'test', 'package')) {
            $task = Require-String $gradle $taskName "$loaderPath.gradle.$taskName"
            if (-not $task.StartsWith("${projectPath}:")) {
                throw "project-state.json Gradle task '$task' does not belong to '$projectPath'."
            }
        }
        $gradlePlugin = Require-Object $loader 'gradlePlugin' "$loaderPath.gradlePlugin"
        [void](Require-String $gradlePlugin 'id' "$loaderPath.gradlePlugin.id")
        [void](Require-String $gradlePlugin 'version' "$loaderPath.gradlePlugin.version")
        $loaderPin = Require-Object $loader 'loader' "$loaderPath.loader"
        [void](Require-String $loaderPin 'coordinate' "$loaderPath.loader.coordinate")
        $loaderVersion = Require-String $loaderPin 'version' "$loaderPath.loader.version"
        [void](Require-String $loaderPin 'versionRange' "$loaderPath.loader.versionRange")
        if ($loader.PSObject.Properties['api']) {
            $apiPin = Require-Object $loader 'api' "$loaderPath.api"
            [void](Require-String $apiPin 'coordinate' "$loaderPath.api.coordinate")
            [void](Require-String $apiPin 'version' "$loaderPath.api.version")
            [void](Require-String $apiPin 'versionRange' "$loaderPath.api.versionRange")
        }
        $developmentArtifact = Require-Object $loader 'developmentArtifact' "$loaderPath.developmentArtifact"
        $developmentDirectory = Require-String $developmentArtifact 'directory' "$loaderPath.developmentArtifact.directory"
        [void](Require-String $developmentArtifact 'pattern' "$loaderPath.developmentArtifact.pattern")
        if (-not ($developmentDirectory.Replace('\', '/')).StartsWith("$module/")) {
            throw "project-state.json $loaderPath.developmentArtifact.directory must be under module '$module'."
        }
        [void](Require-String $loader 'installedJar' "$loaderPath.installedJar")
        [void](Require-String $loader 'builtinResourcePack' "$loaderPath.builtinResourcePack")
        $prism = Require-Object $loader 'prism' "$loaderPath.prism"
        [void](Require-String $prism 'uid' "$loaderPath.prism.uid")
        [void](Require-String $prism 'name' "$loaderPath.prism.name")
        $prismVersion = Require-String $prism 'version' "$loaderPath.prism.version"
        if ($prismVersion -cne $loaderVersion) {
            throw "project-state.json $loaderPath.prism.version must match loader.version."
        }
        [void](Require-String $loader 'metadataFile' "$loaderPath.metadataFile")
        [void](Require-String $loader 'archiveBaseName' "$loaderPath.archiveBaseName")
        [void](Require-String $loader 'packageSourceTask' "$loaderPath.packageSourceTask")
        if (-not (Test-Path -LiteralPath (Join-Path $projectRoot "$module\build.gradle") -PathType Leaf)) {
            throw "Declared target '$versionId/$loaderId' has no build.gradle under module '$module'."
        }
        $normalizedLoaders += $loaderId
        $targets += [ordered]@{
            minecraftVersion = $versionId
            loader = $loaderId
            javaTarget = [int]$minecraft.javaTarget
            supportTier = $supportTier
            loaderSupportTier = $loaderSupportTier
            buildTask = [string]$gradle.build
            testTask = [string]$gradle.test
            packageTask = [string]$gradle.package
        }
    }
    if ($normalizedLoaders -cnotcontains $defaultLoader) {
        throw "project-state.json $versionPath.defaultLoader '$defaultLoader' is not present in loaders."
    }
    $validatedVersions[$versionId] = [pscustomobject]@{
        State = $version
        Minecraft = $minecraft
        SupportTier = $supportTier
        DefaultLoader = $defaultLoader
        LoaderIds = $normalizedLoaders
        LoaderProperties = $loaderProperties
    }
}

$targetVersionId = if ($MinecraftVersion) { $MinecraftVersion } else { $defaultMinecraftVersion }
if (-not $validatedVersions.Contains($targetVersionId)) {
    throw "Unknown Minecraft version '$targetVersionId'. Declared versions: $($validatedVersions.Keys -join ', ')."
}
$targetVersion = $validatedVersions[$targetVersionId]
$selectedLoaderProperties = $targetVersion.LoaderProperties
if ($ModLoader) {
    $selectedLoaderProperties = @($selectedLoaderProperties | Where-Object Name -ceq $ModLoader)
    if ($selectedLoaderProperties.Count -ne 1) {
        throw "Unknown loader '$ModLoader' for Minecraft $targetVersionId. Declared loaders: $($targetVersion.LoaderIds -join ', ')."
    }
}
$selectedLoaderIds = @($selectedLoaderProperties | ForEach-Object Name)
$selectedTasks = foreach ($loaderProperty in $selectedLoaderProperties) {
    $loaderProperty.Value.gradle.build
    $loaderProperty.Value.gradle.test
    $loaderProperty.Value.gradle.package
}
$matrix = @{ include = @($targets) } | ConvertTo-Json -Compress -Depth 5
$versionSummary = @($validatedVersions.Keys | ForEach-Object {
    "$($_):$($validatedVersions[$_].LoaderIds -join ',')"
}) -join ';'
$gradleProperties = "-PmcVersion=$targetVersionId"
if ($ModLoader) { $gradleProperties += " -Ploader=$ModLoader" }

if ($GitHubEnvironment) {
    @(
        "PROJECT_MINECRAFT_VERSION=$targetVersionId"
        "PROJECT_DEFAULT_MINECRAFT_VERSION=$defaultMinecraftVersion"
        "PROJECT_JAVA_TARGET=$($targetVersion.Minecraft.javaTarget)"
        "PROJECT_MOD_VERSION=$modVersion"
        "PROJECT_SUPPORT_TIER=$($targetVersion.SupportTier)"
        "PROJECT_DEFAULT_LOADER=$($targetVersion.DefaultLoader)"
        "PROJECT_LOADERS=$($selectedLoaderIds -join ',')"
        "PROJECT_LOADER=$ModLoader"
        "PROJECT_LOADER_COUNT=$($selectedLoaderIds.Count)"
        "PROJECT_GRADLE_TASKS=$($selectedTasks -join ' ')"
        "PROJECT_GRADLE_PROPERTIES=$gradleProperties"
        "PROJECT_VERSION_MATRIX=$versionSummary"
    ) | Out-File -FilePath $GitHubEnvironment -Append -Encoding utf8
}
if ($GitHubOutput) {
    @(
        "matrix=$matrix"
        "versionMatrix=$versionSummary"
        "javaTarget=$($targetVersion.Minecraft.javaTarget)"
        "minecraftVersion=$targetVersionId"
        "defaultMinecraftVersion=$defaultMinecraftVersion"
        "mcVersionGradleProperty=-PmcVersion=$targetVersionId"
        "loader=$ModLoader"
        "loaderGradleProperty=$(if ($ModLoader) { "-Ploader=$ModLoader" } else { '' })"
        "modVersion=$modVersion"
        "supportTier=$($targetVersion.SupportTier)"
    ) | Out-File -FilePath $GitHubOutput -Append -Encoding utf8
}

if ($ReleaseAssetsDirectory) {
    $assetDirectory = (Resolve-Path -LiteralPath $ReleaseAssetsDirectory).Path
    $releaseJars = @(Get-ChildItem -LiteralPath $assetDirectory -File -Filter '*.jar')
    if ($releaseJars.Count -ne $targetVersion.LoaderIds.Count) {
        throw "Expected one staged release JAR per loader for Minecraft $targetVersionId ($($targetVersion.LoaderIds.Count)); found $($releaseJars.Count)."
    }
    $releaseHashes = @($releaseJars | ForEach-Object { (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash })
    foreach ($loaderProperty in $targetVersion.LoaderProperties) {
        $loaderId = $loaderProperty.Name
        $loader = $loaderProperty.Value
        $releaseArchiveBaseName = "$modId-$loaderId-mc$targetVersionId"
        $distributionDirectory = Join-Path $projectRoot "$($loader.module)\build\distributions"
        $moduleJars = @(Get-ChildItem -LiteralPath $distributionDirectory -File |
            Where-Object { $_.Name -like "$releaseArchiveBaseName-*.jar" })
        if ($moduleJars.Count -eq 0) {
            throw "No packaged JAR exists for target '$targetVersionId/$loaderId'."
        }
        $moduleHashes = @($moduleJars | ForEach-Object {
            (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
        })
        if (-not ($moduleHashes | Where-Object { $releaseHashes -contains $_ })) {
            throw "The staged release assets do not contain the packaged '$targetVersionId/$loaderId' JAR."
        }
    }
    @(
        @{ Pattern = 'RTX-Remix-Materials-*.zip'; Name = 'materials archive' }
        @{ Pattern = 'RTX-Remix-Showcase-World-*.zip'; Name = 'showcase world archive' }
        @{ Pattern = '*-installer.zip'; Name = 'installer archive' }
    ) | ForEach-Object {
        $matches = @(Get-ChildItem -LiteralPath $assetDirectory -File -Filter $_.Pattern)
        if ($matches.Count -ne 1) {
            throw "Expected one staged $($_.Name); found $($matches.Count)."
        }
    }
}

Write-Host "Project state is valid: $versionSummary. Selected Minecraft $targetVersionId; loaders $($selectedLoaderIds -join ', ')."
