@echo off
setlocal
set "PSModulePath=%SystemRoot%\System32\WindowsPowerShell\v1.0\Modules"
powershell.exe -NoProfile -File "%~dp0OpenCode-Hurricane.ps1" %*
if errorlevel 1 pause
endlocal
