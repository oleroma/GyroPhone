@echo off
rem Removes the GyroPhone input driver and its device; the certificate too unless the pad driver uses it. Asks for admin once.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall.ps1"
