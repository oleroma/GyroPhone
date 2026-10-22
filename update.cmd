@echo off
rem Build GyroPhone on this PC and update the companion and the phone.
rem Usage: update.cmd [commit]   (default: HEAD; commit your changes first)
rem
rem Builds go in build\ inside the repo (git-ignored):
rem   build\gyrophone.apk, build\GyroPhoneCompanion.exe   the newest build
rem   build\builds\0.1.<n>-<commit>\                       the last 4 builds (APK, exe, BUILD.txt)
rem   build\builds\0.1.<n>-<commit>\src\                   the git worktree each one was built from
rem <n> is the commit count, so a later commit always gets a higher build number.
rem The companion needs the .NET 8 SDK; without it only the APK is built.
rem PC: the companion is installed at one fixed path, %LOCALAPPDATA%\Programs\GyroPhone, so its
rem firewall rule and Start with Windows entry survive updates; the Start menu entry opens it there.
rem A running companion is swapped by renaming it to .old; it restarts into the new build by itself
rem once no phone is connected (pairing and settings live in %APPDATA%\GyroPhone).
rem Phone: in-place update only (adb install -r), keeps settings, never uninstalls.
rem It never starts or closes apps; you launch them yourself.
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
if exist "%OUT%\GyroPhoneCompanion.exe" copy /y "%OUT%\GyroPhoneCompanion.exe" "%BUILD%\GyroPhoneCompanion.exe" >nul
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

rem ---- PC ----
set "APP=%LOCALAPPDATA%\Programs\GyroPhone"
set "APP_EXE=%APP%\GyroPhoneCompanion.exe"
set "PC="
if not exist "%OUT%\GyroPhoneCompanion.exe" goto :phone
if not exist "%APP%" mkdir "%APP%"
rem VERSION.txt names the build at APP_EXE (comparing the 76 MB exes byte by byte is slow).
set "HAVE="
if exist "%APP_EXE%" if exist "%APP%\VERSION.txt" set /p HAVE=<"%APP%\VERSION.txt"
if "%HAVE%"=="%VER%" (set "PC=same" & goto :shortcut)
rem .old files still in use belong to a companion that has not restarted yet; they go next time.
del /q "%APP%\GyroPhoneCompanion.exe*.old" >nul 2>&1
copy /y "%OUT%\GyroPhoneCompanion.exe" "%APP_EXE%.tmp" >nul || goto :pcfail
set "OLD=%APP_EXE%.old"
if exist "%OLD%" set "OLD=%APP_EXE%.%RANDOM%.old"
if exist "%APP_EXE%" move /y "%APP_EXE%" "%OLD%" >nul || goto :pcfail
move /y "%APP_EXE%.tmp" "%APP_EXE%" >nul || goto :pcfail
>"%APP%\VERSION.txt" echo %VER%
set "PC=installed"
goto :shortcut
:pcfail
set "PC=failed"
:shortcut
powershell -NoProfile -Command "$s=(New-Object -ComObject WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Programs')+'\GyroPhone Companion.lnk'); $s.TargetPath='%APP_EXE%'; $s.WorkingDirectory='%APP%'; $s.Save()"

:phone

rem ---- Phone ----
set "PHONE="
for /f "skip=1 tokens=1,2" %%a in ('adb devices') do if "%%b"=="device" set "PHONE=%%a"
if "%PHONE%"=="" (
  echo Phone not connected over adb: not updated.
  goto :done
)
set "ON_PHONE="
for /f "tokens=2 delims==" %%v in ('adb shell dumpsys package com.oleroma.gyrophone ^| findstr versionName') do set "ON_PHONE=%%v"
if "%ON_PHONE%"=="%VER%" (
  echo Phone already has %VER%.
  goto :done
)
if not exist "%OUT%\gyrophone.apk" (
  echo gyrophone.apk missing in %OUT%
  goto :done
)
rem -d: builds from different branches can have a lower build number than the one installed.
adb install -r -d "%OUT%\gyrophone.apk" && echo Phone updated: %ON_PHONE% -^> %VER%

:done
echo.
echo Newest: %BUILD%   (GyroPhone %VER%)
if "%PC%"=="installed" echo PC: companion %VER% installed in %APP%. A running companion switches to it when no phone is connected.
if "%PC%"=="same" echo PC: companion in %APP% is already %VER%.
if "%PC%"=="failed" echo PC: could not install the companion in %APP%.
if "%PC%"=="" echo This build has no companion.
endlocal
