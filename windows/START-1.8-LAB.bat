@echo off
title laG 1.8 Cannon Lab
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\start-lab.ps1"
if errorlevel 1 pause
