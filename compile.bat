@echo off
setlocal EnableExtensions EnableDelayedExpansion
if "%~1"=="--help" (echo Usage: compile.bat [--clean] [--skip-tests] ^(JDK 21+, Maven 3.9+^) & exit /b 0)
set "WUS_SKIP=false"
for %%A in (%*) do (
 if "%%A"=="--skip-tests" (set "WUS_SKIP=true") else if not "%%A"=="--clean" (echo Unknown option: %%A & exit /b 2)
)
where javac >nul 2>&1 || (echo Install JDK 21+ & exit /b 1)
where mvn >nul 2>&1 || (echo Install Maven 3.9+ & exit /b 1)
pushd "%~dp0"
call mvn -B clean package -DskipTests=!WUS_SKIP!
set "WUS_CODE=%ERRORLEVEL%"
popd
exit /b %WUS_CODE%
