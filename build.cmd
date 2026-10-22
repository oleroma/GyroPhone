@echo off
rem Build GyroPhone on this PC. Run update.cmd afterwards to install the build.
rem Usage: build.cmd [commit]   (default: HEAD; commit your changes first)
rem
rem Builds go in build\ inside the repo (git-ignored):
rem   build\gyrophone.apk, build\GyroPhoneCompanion.exe   the newest build
rem   build\builds\0.1.<n>-<commit>\                       the last 4 builds (APK, exe, BUILD.txt)
rem   build\builds\0.1.<n>-<commit>\src\                   the git worktree each one was built from
rem <n> is the commit count, so a later commit always gets a higher build number.
rem The companion needs the .NET 8 SDK; without it only the APK is built.
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "REF=%~1"
if "%REF%"=="" set "REF=HEAD"
if not defined JAVA_HOME set "JAVA_HOME=%~dp0..\tools\jdk17"
if not defined ANDROID_HOME set "ANDROID_HOME=%~dp0..\tools\android-sdk"
set "BUILD=%~dp0build"

for /f %%c in ('git rev-parse --short "%REF%"') do set "SHA=%%c"
if "%SHA%"=="" exit /b 1
for /f %%n in ('git rev-list --count %SHA%') do set "NUM=%%n"
set "VER=0.1.%NUM%-%SHA%"
set "OUT=%BUILD%\builds\%VER%"
echo Building GyroPhone %VER%

if not exist "%OUT%\src" git worktree add --detach "%OUT%\src" %SHA% || exit /b 1
set "GP_BUILD=%NUM%"
set "GP_COMMIT=%SHA%"
if not exist "%OUT%\gyrophone.apk" (
  pushd "%OUT%\src"
  call "%OUT%\src\gradlew.bat" --no-daemon :app:assembleDebug || (popd & exit /b 1)
  popd
  copy /y "%OUT%\src\app\build\outputs\apk\debug\app-debug.apk" "%OUT%\gyrophone.apk" >nul || exit /b 1
)
set "HAVE_SDK="
for /f %%s in ('dotnet --list-sdks 2^>nul ^| findstr /b "8. 9. 10."') do set "HAVE_SDK=1"
if defined HAVE_SDK if not exist "%OUT%\GyroPhoneCompanion.exe" (
  dotnet publish "%OUT%\src\companion\src\GyroPhone.Tray" -c Release -r win-x64 --self-contained true ^
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true ^
    -p:PublishReadyToRun=true -p:DebugType=none -p:Version=0.1.%NUM% -p:InformationalVersion=%VER% ^
    -p:IncludeSourceRevisionInInformationalVersion=false -o "%OUT%" || exit /b 1
)
if not exist "%OUT%\GyroPhoneCompanion.exe" echo No .NET 8 SDK found: built the APK only.
> "%OUT%\BUILD.txt" (
  echo GyroPhone %VER%
  echo commit  %SHA%
)

rem The newest build sits directly in build\.
copy /y "%OUT%\gyrophone.apk" "%BUILD%\gyrophone.apk" >nul
if exist "%OUT%\GyroPhoneCompanion.exe" (copy /y "%OUT%\GyroPhoneCompanion.exe" "%BUILD%\GyroPhoneCompanion.exe" >nul) else del /q "%BUILD%\GyroPhoneCompanion.exe" >nul 2>&1
copy /y "%OUT%\BUILD.txt" "%BUILD%\BUILD.txt" >nul

rem Keep the newest 4 build folders, removing older ones with their worktrees.
set /a KEEP=0
for /f "delims=" %%d in ('dir /b /ad /o-d "%BUILD%\builds"') do (
  set /a KEEP+=1
  if !KEEP! gtr 4 (
    git worktree remove --force "%BUILD%\builds\%%d\src"
    rmdir /s /q "%BUILD%\builds\%%d"
  )
)
del /q "%BUILD%\debug-*" >nul 2>&1

echo.
echo Newest: %BUILD%   (GyroPhone %VER%)
echo Run update.cmd to install it on the PC and the phone.
endlocal
