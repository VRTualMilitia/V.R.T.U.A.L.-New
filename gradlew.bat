@echo off
setlocal
set VERSION=8.7
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set BASE=%GRADLE_USER_HOME%\vrtual-bootstrap\gradle-%VERSION%
if not exist "%BASE%\bin\gradle.bat" (
  if not exist "%GRADLE_USER_HOME%\vrtual-bootstrap" mkdir "%GRADLE_USER_HOME%\vrtual-bootstrap"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile '%GRADLE_USER_HOME%\vrtual-bootstrap\gradle-%VERSION%-bin.zip'; Expand-Archive -Force '%GRADLE_USER_HOME%\vrtual-bootstrap\gradle-%VERSION%-bin.zip' '%GRADLE_USER_HOME%\vrtual-bootstrap'"
  if errorlevel 1 exit /b 1
)
call "%BASE%\bin\gradle.bat" %*
