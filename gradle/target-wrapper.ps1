# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

<#
.SYNOPSIS
Prints the Gradle wrapper directory gradlew.bat runs for a Minecraft target.

.DESCRIPTION
A Minecraft version in project-state.json may pin its own Gradle under
versions.<version>.gradle when its loader plugins need a Gradle that the
repository's default wrapper does not run. The wrapper JAR reads its
distribution from the properties file beside it, so a pinned target gets a
generated directory, gradle\wrapper-<gradle version>, that holds a copy of the
wrapper JAR and properties naming the pinned distribution and its checksum.
A version without a pin uses gradle\wrapper unchanged.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$MinecraftVersion
)

$ErrorActionPreference = 'Stop'
$defaultDirectory = Join-Path $PSScriptRoot 'wrapper'
$manifestPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'project-state.json'
$state = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$version = $state.versions.PSObject.Properties[$MinecraftVersion]
$pin = if ($null -ne $version) { $version.Value.PSObject.Properties['gradle'] } else { $null }
if ($null -eq $pin) {
    Write-Output $defaultDirectory
    exit 0
}

$gradleVersion = [string]$pin.Value.version
$checksum = [string]$pin.Value.distributionSha256Sum
if ($gradleVersion -notmatch '^\d+\.\d+(?:\.\d+)?$' -or $checksum -notmatch '^[0-9a-f]{64}$') {
    throw "project-state.json versions.$MinecraftVersion.gradle needs a Gradle version and a SHA-256 checksum."
}

$directory = Join-Path $PSScriptRoot "wrapper-$gradleVersion"
[void](New-Item -ItemType Directory -Force -Path $directory)

# A running build holds its wrapper JAR open, so the copy is refreshed only
# when it differs from the default wrapper's.
$sourceJar = Join-Path $defaultDirectory 'gradle-wrapper.jar'
$targetJar = Join-Path $directory 'gradle-wrapper.jar'
function Get-Sha256([string]$Path) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString($sha.ComputeHash([IO.File]::ReadAllBytes($Path)))
    } finally {
        $sha.Dispose()
    }
}
if (-not (Test-Path -LiteralPath $targetJar -PathType Leaf) -or
    (Get-Sha256 $sourceJar) -ne (Get-Sha256 $targetJar)) {
    Copy-Item -LiteralPath $sourceJar -Destination $targetJar -Force
}

$properties = foreach ($line in Get-Content -LiteralPath (Join-Path $defaultDirectory 'gradle-wrapper.properties')) {
    if ($line -like 'distributionUrl=*') {
        "distributionUrl=https\://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip"
    } elseif ($line -like 'distributionSha256Sum=*') {
        "distributionSha256Sum=$checksum"
    } else {
        $line
    }
}
$propertiesText = ($properties -join "`r`n") + "`r`n"
$targetProperties = Join-Path $directory 'gradle-wrapper.properties'
if (-not (Test-Path -LiteralPath $targetProperties -PathType Leaf) -or
    [IO.File]::ReadAllText($targetProperties) -cne $propertiesText) {
    [IO.File]::WriteAllText($targetProperties, $propertiesText, (New-Object Text.UTF8Encoding($false)))
}
Write-Output $directory
