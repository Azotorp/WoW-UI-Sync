@echo off
setlocal EnableExtensions EnableDelayedExpansion
if "%~1"=="--help" (echo Usage: setup.bat --data path & exit /b 0)
call "%~dp0common.bat"
if errorlevel 1 exit /b %ERRORLEVEL%
"%WUS_JAVA%" "-Dwowuisync.home=%~dp0." -jar "%WUS_JAR%" setup %*
exit /b %ERRORLEVEL%
