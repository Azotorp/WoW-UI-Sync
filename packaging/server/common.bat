@echo off
set "WUS_SCRIPT_DIR=%~dp0"
set "WUS_RELEASE_DIR=%~dp0"
if exist "%~dp0active-release" (
 set /p WUS_ACTIVE=<"%~dp0active-release"
 powershell.exe -NoProfile -NonInteractive -Command "if($env:WUS_ACTIVE -notmatch '^(\.|releases/[A-Za-z0-9.-]+/server)$'){exit 1}"
 if errorlevel 1 (echo Invalid active release pointer & exit /b 1)
 set "WUS_RELEASE_DIR=!WUS_SCRIPT_DIR!!WUS_ACTIVE!\"
)
set "WUS_JAR=%WUS_RELEASE_DIR%wow-ui-sync.server.jar"
if not exist "%WUS_JAR%" if exist "%WUS_RELEASE_DIR%wow-ui-sync-server.jar" set "WUS_JAR=%WUS_RELEASE_DIR%wow-ui-sync-server.jar"
if not defined WUS_JAVA set "WUS_JAVA=java"
if not exist "%WUS_JAR%" (echo Missing server JAR & exit /b 1)
exit /b 0
