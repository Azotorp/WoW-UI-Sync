@echo off
setlocal
if "%~1"=="--help" (echo Usage: package.bat [--version 1.1.1-alpha] & exit /b 0)
if not "%~1"=="" (
 if not "%~1"=="--version" exit /b 2
 if not "%~2"=="1.1.1-alpha" exit /b 2
 if not "%~3"=="" exit /b 2
)
call "%~dp0compile.bat"
if errorlevel 1 exit /b %ERRORLEVEL%
java -jar "%~dp0dist\wow-ui-sync-server.jar" package --source "%~dp0."
exit /b %ERRORLEVEL%
