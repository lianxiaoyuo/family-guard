@echo off
setlocal
set "ROOT=%~dp0"
if not exist "%ROOT%gradle\wrapper\gradle-wrapper.jar" (
 powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT%BOOTSTRAP_GRADLE_WINDOWS.ps1"
 if errorlevel 1 exit /b 1
)
if defined JAVA_HOME (
 set "JAVACMD=%JAVA_HOME%\bin\java.exe"
) else (
 set "JAVACMD=java.exe"
)
"%JAVACMD%" -classpath "%ROOT%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %errorlevel%
