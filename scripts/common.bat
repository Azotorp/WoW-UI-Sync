@rem Shared helpers for WoW UI Sync server scripts. Called, not run directly.
set "SCRIPT_DIR=%~dp0"
if exist "%SCRIPT_DIR%wow-ui-sync-server.jar" (
  set "JAR=%SCRIPT_DIR%wow-ui-sync-server.jar"
) else if exist "%SCRIPT_DIR%..\wow-ui-sync-server.jar" (
  set "JAR=%SCRIPT_DIR%..\wow-ui-sync-server.jar"
) else (
  echo wow-ui-sync-server.jar not found next to this script or one folder up.
  exit /b 1
)
if not defined JAVA set "JAVA=java"
where "%JAVA%" >nul 2>nul
if errorlevel 1 (
  echo Java was not found. Install Java 21 ^(for example from https://adoptium.net^).
  exit /b 1
)
exit /b 0
