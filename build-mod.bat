@echo off
rem Builds the pixelReel mod JAR into build\libs\
setlocal
cd /d "%~dp0"
call gradlew.bat clean build
if errorlevel 1 (
	echo.
	echo Build FAILED. Check the errors above.
	exit /b 1
)
echo.
echo Build finished. The mod JAR is in build\libs\
dir /b build\libs\*.jar
endlocal
