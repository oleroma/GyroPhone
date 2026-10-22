@echo off
rem Download the newest successful CI build of GyroPhone and update the phone.
rem Usage: update.cmd [branch]   (default: newest build on any branch)
rem
rem Builds go in builds\ inside the repo (git-ignored):
rem   builds\0.1.<build>-<commit>\       one folder per build (GyroPhoneCompanion.exe, app-debug.apk, BUILD.txt)
rem   builds\latest\                     always the newest one
rem PC: the companion is installed at one fixed path, %LOCALAPPDATA%\Programs\GyroPhone, so its
rem firewall rule and Start with Windows entry survive updates; the Start menu entry opens it there.
rem A running companion is swapped by renaming it to .old; it restarts into the new build by itself
rem once no phone is connected (pairing and settings live in %APPDATA%\GyroPhone).
rem Phone: in-place update only (adb install -r), keeps settings, never uninstalls.
rem It never starts or closes apps; you launch them yourself.
rem Key pairing: with the phone on adb it also swaps key files, so the phone connects over Wi-Fi
rem without a code: the phone's phone.pub goes to %APPDATA%\GyroPhone\authorized_phones and the
rem PC's host.pub to the phone's keys\trusted_hosts. Only public keys are copied.
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
  goto :keys
)
if not exist "%OUT%\app-debug.apk" (
  echo app-debug.apk missing in %OUT%
  goto :keys
)
rem -d: builds from different branches can have a lower build number than the one installed.
adb install -r -d "%OUT%\app-debug.apk" && echo Phone updated: %ON_PHONE% -^> %VER%

:keys
rem ---- Key pairing ----
set "PHONE_KEYS=/sdcard/Android/data/com.oleroma.gyrophone/files/keys"
set "GP_DATA=%APPDATA%\GyroPhone"
if not exist "%GP_DATA%\host.pub" (
  echo Key pairing: start GyroPhone Companion once so it makes its key, then run update.cmd again.
  goto :done
)
set "PHONE_PUB=%TEMP%\gyrophone-phone.pub"
del /q "%PHONE_PUB%" >nul 2>&1
adb pull "%PHONE_KEYS%/phone.pub" "%PHONE_PUB%" >nul 2>&1
if not exist "%PHONE_PUB%" (
  echo Key pairing: the phone has no key yet. Open GyroPhone once, then run update.cmd again.
  goto :done
)
rem The file is named after the phone (the name in phone.pub, made safe for a file name).
powershell -NoProfile -Command "$p = (Get-Content -LiteralPath $env:PHONE_PUB -TotalCount 1) -split ' ', 3; $n = if ($p.Count -ge 3) { $p[2] } else { 'phone' }; $n = ($n -replace '[^\w .-]', '_').Trim(' .'); if (-not $n) { $n = 'phone' }; $n" > "%TEMP%\gyrophone-phone-name.txt"
set "PHONE_NAME=phone"
set /p PHONE_NAME=<"%TEMP%\gyrophone-phone-name.txt"
if not exist "%GP_DATA%\authorized_phones" mkdir "%GP_DATA%\authorized_phones"
set "PHONE_DEST=%GP_DATA%\authorized_phones\%PHONE_NAME%.pub"
rem Overwrite only when it changed, so the companion is not poked for nothing.
fc /b "%PHONE_PUB%" "%PHONE_DEST%" >nul 2>&1 || copy /y "%PHONE_PUB%" "%PHONE_DEST%" >nul || goto :keysfail
adb shell mkdir -p "%PHONE_KEYS%/trusted_hosts" >nul 2>&1
adb push "%GP_DATA%\host.pub" "%PHONE_KEYS%/trusted_hosts/%COMPUTERNAME%.pub" >nul 2>&1 || goto :keysfail
echo Key pairing: phone and PC know each other.
goto :done
:keysfail
echo Key pairing: could not copy the key files; code pairing still works.

:done
echo.
echo Latest: %ROOT%\latest   (GyroPhone %VER%)
if "%PC%"=="installed" echo PC: companion %VER% installed in %APP%. A running companion switches to it when no phone is connected.
if "%PC%"=="same" echo PC: companion in %APP% is already %VER%.
if "%PC%"=="failed" echo PC: could not install the companion in %APP%.
if "%PC%"=="" echo This build has no companion.
endlocal
