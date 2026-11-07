@echo off
setlocal
call "%~dp0common.bat" || exit /b 1
"%JAVA%" -jar "%JAR%" stop %*
