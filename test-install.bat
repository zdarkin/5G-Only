@echo off
echo ========================================================
echo  5G NSA Gatekeeper: Fast Local Build & ADB Install
echo ========================================================

rem Check if Java is available
where javac >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] JDK 17 is not in your PATH.
    echo To install JDK 17 with one command in PowerShell:
    echo    winget install Microsoft.OpenJDK.17
    pause
    exit /b 1
)

echo.
echo [1/2] Compiling debug APK...
call gradlew.bat assembleDebug
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Gradle build failed!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [2/2] Installing app-debug.apk to connected phone...
where adb >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [NOTICE] adb was not found in PATH.
    echo If you want 1-click automatic installation to your phone:
    echo    winget install Google.PlatformTools
    echo.
    echo Your compiled APK is ready at:
    echo    app\build\outputs\apk\debug\app-debug.apk
    pause
    exit /b 0
)

adb install -r app\build\outputs\apk\debug\app-debug.apk
if %ERRORLEVEL% EQU 0 (
    echo.
    echo [SUCCESS] App installed on phone successfully!
) else (
    echo [WARNING] adb install failed. Make sure your phone is connected and USB/Wireless Debugging is authorized.
)
pause
