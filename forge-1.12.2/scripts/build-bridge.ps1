# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param(
    [string]$JdkPath = $env:JAVA_HOME,
    [string]$JomlJar,
    [string]$RuntimeDirectory,
    [switch]$GpuSmoke
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$bridgeRoot = Join-Path $repositoryRoot 'forge-1.12.2/bridge'
$sharedBridgeRoot = Join-Path $repositoryRoot 'rtxremix-java'
if ($RuntimeDirectory) { $RuntimeDirectory = (Resolve-Path -LiteralPath $RuntimeDirectory).Path }
if ($GpuSmoke -and -not $RuntimeDirectory) { throw '-GpuSmoke requires -RuntimeDirectory.' }
if (-not $JdkPath -or -not (Test-Path -LiteralPath (Join-Path $JdkPath 'bin/javac.exe'))) {
    throw 'Supply a Java 8 JDK through -JdkPath or JAVA_HOME.'
}
$JdkPath = (Resolve-Path -LiteralPath $JdkPath).Path
$javac = Join-Path $JdkPath 'bin/javac.exe'
$java = Join-Path $JdkPath 'bin/java.exe'
try {
    # Java 8 reports its version on stderr, which PowerShell 5.1 treats as errors.
    $ErrorActionPreference = 'Continue'
    $version = & $javac -version 2>&1 | Out-String
    $versionExitCode = $LASTEXITCODE
} finally { $ErrorActionPreference = 'Stop' }
if ($versionExitCode -ne 0 -or $version -notmatch 'javac 1\.8\.') {
    throw 'Use a Java 8 JDK to check both syntax and the Java 8 standard library.'
}

$buildRoot = Join-Path $bridgeRoot 'build'
$classes = Join-Path $buildRoot 'classes'
$headers = Join-Path $buildRoot 'headers'
$testClasses = Join-Path $buildRoot 'test-classes'
$nativeOutput = Join-Path $buildRoot 'native/win64'
$libs = Join-Path $buildRoot 'libs'
# Rebuild generated Java outputs so removed sources cannot remain in the JAR.
foreach ($output in @($classes, $headers, $testClasses)) {
    $resolved = [IO.Path]::GetFullPath($output)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath($buildRoot) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Generated output escaped the bridge build directory: $output"
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
New-Item -ItemType Directory -Force -Path $classes,$headers,$testClasses,$nativeOutput,$libs | Out-Null
if (-not $JomlJar) {
    $dependencies = Join-Path $buildRoot 'dependencies'
    New-Item -ItemType Directory -Force -Path $dependencies | Out-Null
    $JomlJar = Join-Path $dependencies 'joml-1.10.5.jar'
    if (-not (Test-Path -LiteralPath $JomlJar)) {
        Invoke-WebRequest -UseBasicParsing -Uri 'https://repo.maven.apache.org/maven2/org/joml/joml/1.10.5/joml-1.10.5.jar' -OutFile $JomlJar
    }
}
$JomlJar = (Resolve-Path -LiteralPath $JomlJar).Path
if ((Get-FileHash -LiteralPath $JomlJar -Algorithm SHA256).Hash -ne 'CAC9F22F83A7AA33EEBDA73C16FF5261E3CB4911B6BAFCF4C79EA486099D0C9A') {
    throw 'Expected the unmodified JOML 1.10.5 JAR.'
}
$sources = @(Get-ChildItem -LiteralPath (Join-Path $sharedBridgeRoot 'src'),(Join-Path $bridgeRoot 'src/main/java') -Recurse -Filter '*.java' |
    ForEach-Object FullName)
& $javac -encoding UTF-8 -cp $JomlJar -h $headers -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Bridge Java compilation failed.' }

$metadata = Join-Path $classes 'META-INF'
New-Item -ItemType Directory -Force -Path $metadata | Out-Null
foreach ($notice in @('LICENSE','NOTICE','THIRD_PARTY_NOTICES.md')) {
    Copy-Item -LiteralPath (Join-Path $repositoryRoot $notice) -Destination $metadata
}
$jarPath = Join-Path $libs 'rtxremix-java8.jar'
& (Join-Path $JdkPath 'bin/jar.exe') cf $jarPath -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Bridge JAR packaging failed.' }

$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$visualStudio = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $visualStudio) { throw 'Visual Studio with x64 C++ tools is required.' }
$variables = @{
    BRIDGE_JAVA_HOME = $JdkPath
    BRIDGE_ROOT = $bridgeRoot
    BRIDGE_SHARED_ROOT = $sharedBridgeRoot
    BRIDGE_VISUAL_STUDIO = $visualStudio
    BRIDGE_GPU_SMOKE = $(if ($GpuSmoke) { '1' } else { '0' })
}
$previous = @{}
try {
    foreach ($key in $variables.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $variables[$key], 'Process')
    }
    & $env:ComSpec /d /c (Join-Path $bridgeRoot 'native/build-native.cmd')
    if ($LASTEXITCODE -ne 0) { throw 'Bridge native compilation or ABI validation failed.' }
} finally {
    foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
}

$testSources = @(Get-ChildItem -LiteralPath (Join-Path $bridgeRoot 'src/test/java') -Recurse -Filter '*.java' |
    ForEach-Object FullName)
& $javac -encoding UTF-8 -cp "$classes;$JomlJar" -d $testClasses @testSources
if ($LASTEXITCODE -ne 0) { throw 'Bridge test compilation failed.' }
$classpath = "$classes;$testClasses;$JomlJar"
& $java -ea -cp $classpath com.nvidia.rtxremix.BridgeContractTest $sharedBridgeRoot $bridgeRoot
if ($LASTEXITCODE -ne 0) { throw 'Bridge contract tests failed.' }
$smokeArgs = @((Join-Path $nativeOutput 'remixbridge.dll'))
if ($RuntimeDirectory) { $smokeArgs += (Join-Path $RuntimeDirectory 'd3d9.dll') }
Push-Location $buildRoot
try {
    & $java -Xcheck:jni -cp $classpath com.nvidia.rtxremix.NativeBridgeSmokeTest @smokeArgs
    if ($LASTEXITCODE -ne 0) { throw 'Bridge native smoke test failed.' }
} finally { Pop-Location }
if ($GpuSmoke) {
    $smokeRoot = Join-Path $buildRoot 'gpu-smoke'
    New-Item -ItemType Directory -Force -Path $smokeRoot | Out-Null
    Copy-Item -LiteralPath (Join-Path $repositoryRoot 'packaging/rtx.conf') -Destination (Join-Path $smokeRoot 'rtx.conf')
    Push-Location $smokeRoot
    try {
        & $java "-Djava.library.path=$nativeOutput" -cp $classpath com.nvidia.rtxremix.GlInteropSmokeTest (Join-Path $nativeOutput 'remixbridge.dll') (Join-Path $nativeOutput 'gltestcontext.dll') (Join-Path $RuntimeDirectory 'd3d9.dll')
        if ($LASTEXITCODE -ne 0) { throw 'Bridge GPU interop smoke test failed.' }
    } finally { Pop-Location }
}
Write-Output "Built $jarPath"
Write-Output "Built $(Join-Path $nativeOutput 'remixbridge.dll')"
