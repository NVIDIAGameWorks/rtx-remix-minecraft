:: SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
:: SPDX-License-Identifier: Apache-2.0

@echo off
REM Thin launcher: delegates to installer.ps1. All arguments forward through.
REM
REM   install.bat                                                          -> interactive TUI
REM   install.bat -Mode Install                                             -> forced mode, interactive
REM   install.bat -Mode Install -Unattended -Launcher Prism                 -> headless (CI)
REM   install.bat -Mode Export -Unattended -OutputPath out.zip              -> headless export
REM   install.bat -Mode Uninstall -Unattended -AcknowledgeDataLoss          -> headless uninstall
REM                                                                           (-AcknowledgeDataLoss is the safety guard)
REM   install.bat -PrismVersion 9.1                                         -> pin Prism (avoids GitHub API rate limit)
REM
REM For the full parameter list:
REM   powershell -NoProfile -Command "Get-Help '%~dp0installer.ps1' -Full"
REM
REM -ExecutionPolicy Bypass is safe here: we're running a script we shipped
REM alongside the launcher, not something downloaded at runtime.
REM
REM Typed at a PowerShell 7 prompt, this inherits PowerShell 7's PSModulePath,
REM whose modules shadow Windows PowerShell's own and leave Get-FileHash
REM unloadable. Clearing it lets Windows PowerShell build its default path.
setlocal
set "PSModulePath="
powershell.exe -NoProfile -NoLogo -ExecutionPolicy Bypass -File "%~dp0installer.ps1" %*
exit /b %ERRORLEVEL%
