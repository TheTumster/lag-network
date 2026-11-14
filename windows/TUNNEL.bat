@echo off
title laG Network - friend tunnel (playit.gg)
cd /d "%~dp0"
if not exist "tools\playit.exe" (
  echo Downloading playit.gg (free tunnel so friends can join without port forwarding)...
  powershell -NoProfile -Command "Invoke-WebRequest 'https://github.com/playit-cloud/playit-agent/releases/latest/download/playit-windows-x86_64-signed.exe' -OutFile 'tools\playit.exe' -UseBasicParsing"
)
echo.
echo  First time: open the link it shows, sign in, and add a "Minecraft Java" tunnel to port 25565.
echo  It then gives you an address like  something.joinmc.link  - that's what friends type in.
echo.
tools\playit.exe
