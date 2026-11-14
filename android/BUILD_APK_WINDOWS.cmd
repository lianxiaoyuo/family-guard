@echo off
setlocal
cd /d "%~dp0"
rem Prefer the JDK bundled with Android Studio if Java is not on PATH.
if not defined JAVA_HOME (
  if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
)
if not defined ANDROID_HOME (
  if exist "%LOCALAPPDATA%\Android\Sdk\platforms\android-35\android.jar" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
)
if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if defined JAVA_HOME (
  if not exist "%JAVA_HOME%\bin\java.exe" (
    echo JAVA_HOME does not point to a valid JDK 17 installation.
    exit /b 1
  )
) else (
  where java >nul 2>nul
  if errorlevel 1 (
    echo Java 17 not found. Open this project using Android Studio with Gradle JDK 17.
    exit /b 1
  )
)
if not defined ANDROID_HOME (
  echo Android SDK location missing. Open the project in Android Studio to configure SDK Platform 35.
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0BOOTSTRAP_GRADLE_WINDOWS.ps1"
if errorlevel 1 (
  echo Gradle Wrapper initialization failed. Check internet or proxy settings.
  exit /b 1
)
call "%~dp0gradlew.bat" --no-daemon assembleDebug
if errorlevel 1 (
  echo Android build failed. See Gradle output. No APK produced.
  exit /b 1
)
echo APK ready: app\build\outputs\apk\debug\app-debug.apk
exit /b 0
