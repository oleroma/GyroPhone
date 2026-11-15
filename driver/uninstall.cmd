@echo off
rem Removes the GyroPhone pad driver, its device and this PC's signing certificate (kept while the input driver uses it). Asks for admin once.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall.ps1"
