@echo off
setlocal
java -jar "%~dp0..\dist\wow-ui-sync-server.jar" stop %*
exit /b %ERRORLEVEL%
