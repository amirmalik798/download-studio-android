@echo off
setlocal
cd /d "%~dp0"

if "%ANDROID_HOME%"=="" if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if "%ANDROID_SDK_ROOT%"=="" if not "%ANDROID_HOME%"=="" set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

if "%ANDROID_HOME%"=="" (
  echo Android SDK not found.
  echo Install Android Studio and SDK Platform 36, then set ANDROID_HOME.
  pause
  exit /b 1
)
if not exist "%ANDROID_HOME%\platforms\android-36\android.jar" (
  echo Android SDK Platform 36 is missing: %ANDROID_HOME%\platforms\android-36
  echo Install Android SDK Platform 36 using Android Studio SDK Manager.
  pause
  exit /b 1
)

where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle 8.13 was not found on PATH.
  echo Install Gradle 8.13 or use Android Studio: Build ^> Build APK(s).
  pause
  exit /b 1
)

gradle --version
if errorlevel 1 exit /b 1

echo Building Download Studio debug APK...
gradle assembleDebug
if errorlevel 1 (
  echo Build failed. Check JDK 17+, Gradle 8.13 and Android SDK Platform 36.
  pause
  exit /b 1
)

if exist "app\build\outputs\apk\debug\app-debug.apk" (
  echo.
  echo APK created:
  echo %CD%\app\build\outputs\apk\debug\app-debug.apk
) else (
  echo Gradle completed but app-debug.apk was not found.
  exit /b 1
)
pause
