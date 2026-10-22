@echo off
rem Install the newest GyroPhone build from build\ on this PC and the phone. Build it first with build.cmd.
rem Usage: update.cmd
rem It installs only what build.cmd left directly in build\ (gyrophone.apk, GyroPhoneCompanion.exe,
rem BUILD.txt); it never builds or downloads anything.
rem PC: the companion is installed at one fixed path, %LOCALAPPDATA%\Programs\GyroPhone, so its
rem firewall rule and Start with Windows entry survive updates; the Start menu entry opens it there.
rem A running companion is swapped by renaming it to .old; it restarts into the new build by itself
rem once no phone is connected (pairing and settings live in %APPDATA%\GyroPhone).
rem Phone: in-place update only (adb install -r), keeps settings, never uninstalls.
rem Key pairing: with the phone on adb it also swaps key files, so the phone connects over Wi-Fi
rem without a code: the phone's phone.pub goes to %APPDATA%\GyroPhoneuthorized_phones and the
rem PC's host.pub to the phone's keys	rusted_hosts. Only public keys are copied.
rem It never starts or closes apps; you launch them yourself.
setlocal
cd /d "%~dp0"
set "OUT=%~dp0build"
if not exist "%OUT%\BUILD.txt" (
  echo No build in %OUT%: run build.cmd first.
  exit /b 1
)
rem BUILD.txt starts with "GyroPhone <version>".
set "VER="
for /f "usebackq tokens=2" %%v in ("%OUT%\BUILD.txt") do if not defined VER set "VER=%%v"
if "%VER%"=="" (
  echo %OUT%\BUILD.txt names no version.
  exit /b 1
)
echo Installing GyroPhone %VER%

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
if not exist "%OUT%\gyrophone.apk" (
  echo gyrophone.apk missing in %OUT%
  goto :keys
)
rem -d: builds from different branches can have a lower build number than the one installed.
adb install -r -d "%OUT%\gyrophone.apk" && echo Phone updated: %ON_PHONE% -^> %VER%

:keys
rem ---- Key pairing ----
set "PHONE_KEYS=/sdcard/Android/data/com.oleroma.gyrophone/files/keys"
set "GP_DATA=%APPDATA%\GyroPhone"
rem The companion makes host.pub when it runs; this script never starts it.
if not exist "%GP_DATA%\host.pub" (
  echo Key pairing: the PC has no key yet. Start GyroPhone Companion once, then run update.cmd again.
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
if not exist "%GP_DATA%uthorized_phones" mkdir "%GP_DATA%uthorized_phones"
set "PHONE_DEST=%GP_DATA%uthorized_phones\%PHONE_NAME%.pub"
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
echo Installed from: %OUT%   (GyroPhone %VER%)
if "%PC%"=="installed" echo PC: companion %VER% installed in %APP%. A running companion switches to it when no phone is connected.
if "%PC%"=="same" echo PC: companion in %APP% is already %VER%.
if "%PC%"=="failed" echo PC: could not install the companion in %APP%.
if "%PC%"=="" echo This build has no companion.
endlocal
