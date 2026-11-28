# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

param(
    [Parameter(Mandatory)] [string]$McDir,
    [Parameter(Mandatory)]
    [ValidateSet('RtxRemix-LabPBR', 'rtx-remix', 'rtx-remix-labpbr')]
    [string]$LegacyPackName,
    [ValidateNotNullOrEmpty()]
    [string]$BuiltInResourcePack,
    [string]$MinecraftVersion,
    # Backward-compatible for development launch tasks and tools.  The
    # release installer passes the selected loader.
    [string]$ModLoader
)

$ErrorActionPreference = 'Stop'

function Read-LoaderManifest {
    $scriptDir = $PSScriptRoot
    $manifestPath = @(
        (Join-Path $scriptDir 'project-state.json')
        (Join-Path (Split-Path -Parent $scriptDir) 'project-state.json')
    ) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (-not $manifestPath) {
        throw 'Missing project-state.json beside the migration script or at the repository root.'
    }
    try {
        $manifest = [IO.File]::ReadAllText($manifestPath, [Text.UTF8Encoding]::new($false)) |
            ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "Cannot read loader manifest ${manifestPath}: $($_.Exception.Message)"
    }
    if ([string]$manifest.schema -cne 'multi-version/v1' -or
        $manifest.versions -isnot [pscustomobject] -or
        $manifest.versions.PSObject.Properties.Count -eq 0) {
        throw "Invalid project-state.json: expected schema 'multi-version/v1' and a non-empty versions object."
    }
    return $manifest
}

$manifest = Read-LoaderManifest
$selectedVersion = if ($MinecraftVersion) {
    $MinecraftVersion
} else {
    [string]$manifest.defaultMinecraftVersion
}
$versionProperty = $manifest.versions.PSObject.Properties[$selectedVersion]
if ($null -eq $versionProperty -or
    $versionProperty.Value.loaders -isnot [pscustomobject] -or
    $versionProperty.Value.loaders.PSObject.Properties.Count -eq 0) {
    throw "Minecraft version '$selectedVersion' has no loader catalog in project-state.json."
}
$loaderDeclarations = @($versionProperty.Value.loaders.PSObject.Properties)
$builtInPacks = @($loaderDeclarations | ForEach-Object {
    $pack = [string]$_.Value.builtinResourcePack
    if ([string]::IsNullOrWhiteSpace($pack)) {
        throw "project-state.json loader '$($_.Name)' has no builtinResourcePack."
    }
    $pack
} | Select-Object -Unique)
if (-not $BuiltInResourcePack) {
    $selected = @($loaderDeclarations | Where-Object {
        $_.Name -ieq $ModLoader -or [string]$_.Value.displayName -ieq $ModLoader
    })
    if ($selected.Count -ne 1) {
        throw "Mod loader '$ModLoader' does not identify exactly one project-state.json loader."
    }
    $BuiltInResourcePack = [string]$selected[0].Value.builtinResourcePack
} elseif ($BuiltInResourcePack -notin $builtInPacks) {
    throw "Built-in resource pack '$BuiltInResourcePack' is not declared by project-state.json."
}

function Remove-LegacyResourcePack {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $true }
    if ((Get-Item -LiteralPath $Path).Attributes -band [IO.FileAttributes]::ReparsePoint) { return $false }
    if (Test-Path -LiteralPath (Join-Path $Path '.rtx-remix-world-pack')) { return $false }
    $metadataPath = Join-Path $Path 'pack.mcmeta'
    # The name alone is not ownership: world-local and user packs may reuse it.
    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath (Join-Path $Path 'BLOCK_TEXTURES.txt') -PathType Leaf)) { return $false }
    try {
        $metadata = [IO.File]::ReadAllText($metadataPath) | ConvertFrom-Json
    } catch [ArgumentException] {
        return $false
    }
    if ((ConvertTo-Json -InputObject $metadata -Compress -Depth 10) -cne
        '{"pack":{"pack_format":34,"description":"RTX Remix LabPBR Development Pack"}}') { return $false }
    Remove-Item -LiteralPath $Path -Recurse -Force
    Write-Host "Removed retired baseline resource pack: $Path"
    return $true
}

function Update-ResourcePackOptions {
    param([string]$Path, [string]$LegacyPackName, [string]$BuiltInPackName,
        [string[]]$AllBuiltInPackNames, [bool]$RemoveLegacyEntry)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return }
    $text = [IO.File]::ReadAllText($Path)
    $match = [regex]::Match($text, '(?m)^resourcePacks:[^\r\n]*')
    $packs = if ($match.Success) { @($match.Value.Substring('resourcePacks:'.Length) | ConvertFrom-Json) } else { @() }
    $ordered = @('vanilla', $BuiltInPackName) + @($packs | Where-Object {
        $_ -cne 'vanilla' -and $_ -notin $AllBuiltInPackNames -and
        (-not $RemoveLegacyEntry -or $_ -cne "file/$LegacyPackName")
    })
    $line = 'resourcePacks:' + (ConvertTo-Json -InputObject $ordered -Compress)
    if ($match.Success) {
        $updated = $text.Remove($match.Index, $match.Length).Insert($match.Index, $line)
    } else {
        $newline = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
        $separator = if ($text.Length -gt 0 -and -not $text.EndsWith("`n")) { $newline } else { '' }
        $updated = $text + $separator + $line + $newline
    }
    if ($updated -ceq $text) { return }
    $bytes = [IO.File]::ReadAllBytes($Path)
    $bom = $bytes.Length -ge 3 -and $bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191
    $temporary = "$Path.tmp-$([guid]::NewGuid().ToString('N'))"
    try {
        [IO.File]::WriteAllText($temporary, $updated, [Text.UTF8Encoding]::new($bom))
        Move-Item -LiteralPath $temporary -Destination $Path -Force
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

$legacyPack = Join-Path $McDir "resourcepacks\$LegacyPackName"
$removed = Remove-LegacyResourcePack -Path $legacyPack
Update-ResourcePackOptions -Path (Join-Path $McDir 'options.txt') -LegacyPackName $LegacyPackName `
    -BuiltInPackName $BuiltInResourcePack -AllBuiltInPackNames $builtInPacks -RemoveLegacyEntry $removed
