@echo off
rem SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
rem SPDX-License-Identifier: Apache-2.0

setlocal
rem Typed at a PowerShell 7 prompt, this inherits PowerShell 7's PSModulePath,
rem whose modules shadow Windows PowerShell's own: the uv installer below then
rem cannot load Get-ExecutionPolicy. Clearing it, for the installer and for
rem every Windows PowerShell the tools start, lets each build its default path.
set "PSModulePath="
set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"
where uv >nul 2>nul
if errorlevel 1 (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-RestMethod https://astral.sh/uv/install.ps1 | Invoke-Expression"
    set "PATH=%USERPROFILE%\.local\bin;%PATH%"
)
where uv >nul 2>nul
if errorlevel 1 (
    echo uv installation failed. Install uv 0.13 or newer and try again.
    exit /b 1
)
set "PYTHONUNBUFFERED=1"
uv run rtx %*
set "EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %EXIT_CODE%
