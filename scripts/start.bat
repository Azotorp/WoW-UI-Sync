@echo off
setlocal
java -jar "%~dp0..\dist\wow-ui-sync-server.jar" run %*
exit /b %ERRORLEVEL%
