@echo off
setlocal
cd /d "%~dp0"
set "TMPDIR=%TEMP%\fg-java-test-%RANDOM%"
mkdir "%TMPDIR%"
javac -encoding UTF-8 -d "%TMPDIR%" app\src\main\java\com\familyguard\app\AccuracyPolicy.java app\src\main\java\com\familyguard\app\CoordinateReference.java app\src\main\java\com\familyguard\app\ServerAddress.java tests\AccuracyPolicySelfTest.java tests\CoordinateReferenceSelfTest.java tests\ServerAddressSelfTest.java
if errorlevel 1 exit /b 1
java -cp "%TMPDIR%" com.familyguard.app.AccuracyPolicySelfTest
if errorlevel 1 exit /b 1
java -cp "%TMPDIR%" com.familyguard.app.CoordinateReferenceSelfTest
if errorlevel 1 exit /b 1
java -cp "%TMPDIR%" com.familyguard.app.ServerAddressSelfTest
if errorlevel 1 exit /b 1
rmdir /s /q "%TMPDIR%"
