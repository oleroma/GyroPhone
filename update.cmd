@echo off
rem Bring the phone app and the PC companion up to the newest successful CI build.
rem Usage: update.cmd [branch]   (default: newest build on any branch)
rem Phone: in-place update only (adb install -r), keeps settings, never uninstalls.
rem Skips anything already at that build, so it is safe to run as often as you like.
rem Downloads go to %LOCALAPPDATA%\GyroPhone\builds\<run id>, outside any repo checkout.
rem It never starts or closes apps on the PC or the phone; you launch them yourself.
setlocal
set "REPO=oleroma/GyroPhone"
set "BRANCH_ARG="
if not "%~1"=="" set "BRANCH_ARG=--branch %~1"

for /f "tokens=1,2 delims=	" %%i in ('gh run list -R %REPO% --workflow Build %BRANCH_ARG% --status success --limit 1 --json databaseId^,headBranch --jq ".[0] | [.databaseId, .headBranch] | @tsv"') do (
  set "RUN_ID=%%i"
  set "RUN_BRANCH=%%j"
)
if "%RUN_ID%"=="" (
  echo No successful Build run found.
  exit /b 1
)
echo Newest successful build: run %RUN_ID% on %RUN_BRANCH%

set "STATE=%LOCALAPPDATA%\GyroPhone\builds"
set "OUT=%STATE%\%RUN_ID%"
if not exist "%OUT%" mkdir "%OUT%" || exit /b 1

rem ---- PC companion ----
set "PC_DONE="
if exist "%STATE%\companion.last" set /p PC_DONE=<"%STATE%\companion.last"
if "%PC_DONE%"=="%RUN_ID%" (
  echo Companion already at %RUN_ID%.
) else (
  if not exist "%OUT%\GyroPhoneCompanion.exe" gh run download %RUN_ID% -R %REPO% -n gyrophone-companion-win-x64 -D "%OUT%"
  if exist "%OUT%\GyroPhoneCompanion.exe" (
    >"%STATE%\companion.last" echo %RUN_ID%
    echo Companion downloaded: %OUT%\GyroPhoneCompanion.exe
    echo Close the running companion and start this one when you are ready.
  ) else (
    echo This build has no companion yet.
  )
)

rem ---- Phone ----
set "PHONE_DONE="
if exist "%STATE%\phone.last" set /p PHONE_DONE=<"%STATE%\phone.last"
if "%PHONE_DONE%"=="%RUN_ID%" (
  echo Phone already at %RUN_ID%.
  goto :end
)
set "PHONE="
for /f "skip=1 tokens=1,2" %%a in ('adb devices') do if "%%b"=="device" set "PHONE=%%a"
if "%PHONE%"=="" (
  echo Phone not connected over adb; it will update next time.
  goto :end
)
if not exist "%OUT%\app-debug.apk" gh run download %RUN_ID% -R %REPO% -n gyrophone-debug-apk -D "%OUT%"
if not exist "%OUT%\app-debug.apk" (
  echo app-debug.apk not found in %OUT%
  goto :end
)
adb install -r "%OUT%\app-debug.apk" && (
  >"%STATE%\phone.last" echo %RUN_ID%
  echo Phone updated to %RUN_ID%.
)

:end
endlocal
