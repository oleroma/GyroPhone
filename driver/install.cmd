@echo off
rem Installs or updates the GyroPhone pad driver (virtual DualSense). Asks for admin once.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1"
