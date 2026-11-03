# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$GameDirectory,
    [Parameter(Mandatory=$true)][string]$Package,
    [string]$DiagnosticJar
)
$ErrorActionPreference = 'Stop'
$game = (Resolve-Path -LiteralPath $GameDirectory).Path
$packageFile = Get-Item -LiteralPath $Package
if ($packageFile.Extension -ne '.jar') { throw 'Package must be a packaged Forge JAR.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($packageFile.FullName)
try {
    if (-not $archive.GetEntry('native/forge-runtime.sha256')) {
        throw 'Use the packageForge output; a lightweight JAR has no runtime payload.'
    }
} finally { $archive.Dispose() }
$mods = Join-Path $game 'mods'
New-Item -ItemType Directory -Force -Path $mods | Out-Null
$destination = Join-Path $mods 'rtx-remix-forge.jar'
Copy-Item -LiteralPath $packageFile.FullName -Destination $destination -Force
if ($DiagnosticJar) {
    Copy-Item -LiteralPath (Resolve-Path -LiteralPath $DiagnosticJar).Path -Destination (Join-Path $mods 'rtx-remix-forge-diagnostic.jar') -Force
}
Write-Output "Installed $destination. Launch this instance through your signed-in Prism Launcher."