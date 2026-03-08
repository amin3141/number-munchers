@echo off
setlocal

cd /d "%~dp0"

if exist "gradlew.bat" (
  call gradlew.bat run %*
  exit /b %ERRORLEVEL%
)

where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
  gradle run %*
  exit /b %ERRORLEVEL%
)

echo Gradle was not found.
echo Install Gradle or add Gradle Wrapper files (gradlew.bat and gradle/wrapper/*).
pause
exit /b 1
