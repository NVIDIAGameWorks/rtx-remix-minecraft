:: SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
:: SPDX-License-Identifier: Apache-2.0

@echo off
setlocal enabledelayedexpansion

REM ═══════════════════════════════════════════════════════════════════════
REM  build_native.bat: Compile remixbridge.dll using MSVC
REM  Usage: build_native.bat [x64|arm64]   (default: x64)
REM  Requires: Visual Studio with C++ workload, JAVA_HOME set
REM
REM  Output goes to build/native/{win64|arm64}/ under the repository root.
REM  Runtime directories under remix-runtime/ can be Packman junctions into
REM  the shared cache. Never write bridge outputs there. Consumers layer the
REM  bridge over the runtime when staging or packaging.
REM ═══════════════════════════════════════════════════════════════════════

REM ── Target architecture ───────────────────────────────────────────────
set "ARCH=%~1"
if "%ARCH%"=="" set "ARCH=x64"

REM ── Map ARCH to vcvarsall target. Pin toolset only when cross-compiling
REM    for ARM64: cross-compiling needs the ARM64 CRT libraries, which ship
REM    with the 14.44 toolset (not the default 14.34). x64 builds use whatever
REM    default toolset is installed, so dev machines with any recent VS
REM    install keep working without needing 14.44 specifically.
if /I "%ARCH%"=="x64" (
    set "VCARCH=x64"
    set "VCVARS_EXTRA_ARGS="
) else if /I "%ARCH%"=="arm64" (
    set "VCARCH=x64_arm64"
    set "VCVARS_EXTRA_ARGS=-vcvars_ver=14.44"
) else (
    echo ERROR: Unknown architecture: %ARCH%. Use x64 or arm64.
    exit /b 1
)

REM ── Find Visual Studio via vswhere ──────────────────────────────────
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" (
    echo ERROR: vswhere.exe not found. Is Visual Studio installed?
    exit /b 1
)

for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VS_PATH=%%i"
if not defined VS_PATH (
    echo ERROR: No Visual Studio installation found.
    exit /b 1
)

REM ── Set up MSVC environment for target arch ─────────────────────────
call "%VS_PATH%\VC\Auxiliary\Build\vcvarsall.bat" %VCARCH% %VCVARS_EXTRA_ARGS% >nul 2>&1
if errorlevel 1 (
    echo ERROR: vcvarsall.bat failed. Ensure MSVC v143 C++ workload is installed.
    if defined VCVARS_EXTRA_ARGS echo        ARM64 builds additionally require toolset 14.44.
    exit /b 1
)

REM ── Check JAVA_HOME ─────────────────────────────────────────────────
if not defined JAVA_HOME (
    echo ERROR: JAVA_HOME is not set.
    exit /b 1
)
if not exist "%JAVA_HOME%\include\jni.h" (
    echo ERROR: jni.h not found in %JAVA_HOME%\include
    exit /b 1
)

REM ── Remix C header (override with REMIX_INCLUDE env var) ────────────
if not defined REMIX_INCLUDE set "REMIX_INCLUDE=%~dp0include"
if not exist "%REMIX_INCLUDE%\remix_c.h" (
    echo ERROR: remix_c.h not found in %REMIX_INCLUDE%
    echo Set REMIX_INCLUDE to the directory containing remix_c.h
    exit /b 1
)

REM ── Output: build/native/{win64|arm64}/ (per-arch, repo-local) ──────
if /I "%ARCH%"=="x64" (
    set "OUT_ARCH=win64"
) else (
    set "OUT_ARCH=arm64"
)
set "OUT_DIR=%~dp0..\..\build\native\%OUT_ARCH%"
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

echo Compiling remixbridge.dll for %OUT_ARCH% ...
cl /nologo /O2 /Zi /LD /EHsc /std:c++17 ^
   /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" /I"%REMIX_INCLUDE%" ^
   "%~dp0remixbridge.cpp" ^
   /Fe"%OUT_DIR%\remixbridge.dll" ^
   /Fo"%OUT_DIR%\remixbridge.obj" ^
   /Fd"%OUT_DIR%\remixbridge.pdb" ^
   /link /nologo /DEBUG dbghelp.lib

if errorlevel 1 (
    echo ERROR: Compilation failed.
    exit /b 1
)

echo remixbridge.dll built successfully: %OUT_DIR%\remixbridge.dll
endlocal
