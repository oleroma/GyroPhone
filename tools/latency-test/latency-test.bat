@echo off
rem The video latency test (README.md). Double-click: opens the test window; drop the video on it to time it.
rem Or drop a video on this file to time it here. Builds the tool into build\latency-test first (quick once built).
setlocal
set "OUT=%~dp0..\..\build\latency-test\app"
dotnet build "%~dp0App\LatencyTest.csproj" -c Release -nologo -v q -o "%OUT%"
if errorlevel 1 (
  echo The latency test couldn't be built: it needs the .NET 10 SDK.
  pause
  exit /b 1
)
if "%~1"=="" (
  start "" "%OUT%\LatencyTest.exe"
  exit /b 0
)
"%OUT%\LatencyTest.exe" %*
pause
