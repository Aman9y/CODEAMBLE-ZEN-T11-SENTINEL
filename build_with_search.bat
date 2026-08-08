@echo off
echo Building Android App with Search Functionality...
echo.

REM Try to set JAVA_HOME if Android Studio is installed
if exist "C:\Program Files\Android\Android Studio\jre" (
    set JAVA_HOME=C:\Program Files\Android\Android Studio\jre
    echo Set JAVA_HOME to Android Studio JRE
) else if exist "C:\Program Files\Android\Android Studio\jbr" (
    set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
    echo Set JAVA_HOME to Android Studio JBR
) else if exist "%LOCALAPPDATA%\Android\Sdk\platforms\android-*" (
    echo Android SDK found, but need Java JRE
) else (
    echo Please install Android Studio or set JAVA_HOME manually
    pause
    exit /b 1
)

REM Change to project directory
cd /d "d:\pushkar given project\new full working\master2\master2"

REM Clean and build
echo Cleaning project...
.\gradlew clean

echo Building debug APK...
.\gradlew assembleDebug

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ✅ BUILD SUCCESSFUL! 
    echo APK Location: app\build\outputs\apk\debug\app-debug.apk
    echo.
    echo Install the APK on your device to see the search functionality!
) else (
    echo.
    echo ❌ BUILD FAILED - Check Java environment
)

pause
