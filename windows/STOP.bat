@echo off
title laG Network - stop
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\stop.ps1"
timeout /t 4 >nul
