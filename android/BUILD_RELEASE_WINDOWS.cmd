@echo off
setlocal
cd /d "%~dp0"
if not exist "keystore.properties" (
  echo Missing android\keystore.properties.
  echo Run GENERATE_RELEASE_SHA1_WINDOWS.ps1 first.
  exit /b 1
)
call gradlew.bat --no-daemon :app:verifyAmapConfiguration :app:verifyReleaseSigning :app:assembleRelease
if errorlevel 1 (
  echo Release build failed. No verified APK produced.
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0VERIFY_RELEASE_APK_WINDOWS.ps1"
if errorlevel 1 exit /b 1
echo Signed Release APK ready: app\build\outputs\apk\release\app-release.apk
exit /b 0
