@echo off
rem Installs or updates the GyroPhone input driver (virtual mouse and keyboard). Asks for admin once.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1"
