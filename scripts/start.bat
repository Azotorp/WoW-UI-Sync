@echo off
setlocal
call "%~dp0common.bat" || exit /b 1
if not "%~1"=="--loop" (
  start "WoW UI Sync server" cmd /k ""%~f0" --loop %*"
  echo Server started in its own window. Stop it with stop.bat.
  exit /b 0
)
shift
for /f "usebackq delims=" %%i in (`"%JAVA%" -jar "%JAR%" data-dir %1 %2`) do set "DATA_DIR=%%i"
if not defined DATA_DIR exit /b 1
set "STOP_FLAG=%DATA_DIR%\stop.flag"
if exist "%STOP_FLAG%" del "%STOP_FLAG%"

:loop
"%JAVA%" -jar "%JAR%" run %1 %2
set CODE=%ERRORLEVEL%
if exist "%STOP_FLAG%" goto stopped
echo Server exited unexpectedly with code %CODE%. Restarting in 10 seconds (Ctrl+C to cancel)...
timeout /t 10 /nobreak >nul
if exist "%STOP_FLAG%" goto stopped
goto loop

:stopped
del "%STOP_FLAG%"
echo Server stopped.
exit /b 0
