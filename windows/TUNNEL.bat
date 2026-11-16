@echo off
title RelapsedMC - friend tunnel (playit.gg)
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\tunnel.ps1"
echo.
pause
