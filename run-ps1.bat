@echo off
setlocal

if "%~1"=="" (
    echo Usage: %~nx0 ^<script.ps1^> [args...]
    exit /b 1
)

set "SCRIPT=%~1"
if not exist "%SCRIPT%" (
    echo Error: Script not found: "%SCRIPT%"
    exit /b 2
)

shift
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
set "EXITCODE=%ERRORLEVEL%"

exit /b %EXITCODE%
