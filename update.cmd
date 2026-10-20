@echo off
rem Download the newest successful CI build of GyroPhone and update the phone.
rem Usage: update.cmd [branch]   (default: newest build on any branch)
rem
rem Builds go in builds\ inside the repo (git-ignored):
rem   builds\0.1.<build>-<commit>\       one folder per build (GyroPhoneCompanion.exe, app-debug.apk, BUILD.txt)
rem   builds\latest\                     always the newest one
rem The Start menu entry "GyroPhone Companion" opens latest\GyroPhoneCompanion.exe.
rem Phone: in-place update only (adb install -r), keeps settings, never uninstalls.
rem It never starts or closes apps; you launch them yourself.
setlocal
set "REPO=oleroma/GyroPhone"
set "ROOT=%~dp0builds"
set "BRANCH_ARG="
if not "%~1"=="" set "BRANCH_ARG=--branch %~1"

for /f "tokens=1-4 delims=	" %%a in ('gh run list -R %REPO% --workflow Build %BRANCH_ARG% --status success --limit 1 --json databaseId^,number^,headBranch^,headSha --jq ".[0] | [.databaseId, .number, .headBranch, .headSha[0:7]] | @tsv"') do (
  set "RUN_ID=%%a"
  set "NUM=%%b"
  set "BRANCH=%%c"
  set "SHA=%%d"
)
if "%RUN_ID%"=="" (
  echo No successful Build run found.
  exit /b 1
)
set "VER=0.1.%NUM%-%SHA%"
set "OUT=%ROOT%\%VER%"
echo Newest build: %VER%  (branch %BRANCH%)

if not exist "%OUT%" mkdir "%OUT%" || exit /b 1
if not exist "%OUT%\GyroPhoneCompanion.exe" gh run download %RUN_ID% -R %REPO% -n gyrophone-companion-win-x64 -D "%OUT%"
if not exist "%OUT%\app-debug.apk" gh run download %RUN_ID% -R %REPO% -n gyrophone-debug-apk -D "%OUT%"
> "%OUT%\BUILD.txt" (
  echo GyroPhone %VER%
  echo branch  %BRANCH%
  echo commit  %SHA%
  echo CI run  https://github.com/%REPO%/actions/runs/%RUN_ID%
)

rem latest -> this build (a folder link, so a companion running from an older build is untouched).
if exist "%ROOT%\latest" rmdir "%ROOT%\latest"
mklink /J "%ROOT%\latest" "%OUT%" >nul
if exist "%OUT%\GyroPhoneCompanion.exe" powershell -NoProfile -Command "$s=(New-Object -ComObject WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Programs')+'\GyroPhone Companion.lnk'); $s.TargetPath='%ROOT%\latest\GyroPhoneCompanion.exe'; $s.WorkingDirectory='%ROOT%\latest'; $s.Save()"

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
if not exist "%OUT%\app-debug.apk" (
  echo app-debug.apk missing in %OUT%
  goto :done
)
rem -d: builds from different branches can have a lower build number than the one installed.
adb install -r -d "%OUT%\app-debug.apk" && echo Phone updated: %ON_PHONE% -^> %VER%

:done
echo.
echo Latest: %ROOT%\latest   (GyroPhone %VER%)
if exist "%OUT%\GyroPhoneCompanion.exe" (
  echo PC: start "GyroPhone Companion" from the Start menu. Close the running one first if it is older.
) else (
  echo This build has no companion.
)
endlocal
