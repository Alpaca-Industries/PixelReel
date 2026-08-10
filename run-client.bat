@echo off
rem Launches a Minecraft 26.3-snapshot-5 development client with the pixelReel mod.
rem Requires 64-bit VLC to be installed for video playback (https://www.videolan.org).
setlocal
cd /d "%~dp0"
call gradlew.bat runClient
endlocal
