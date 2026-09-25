@echo off
setlocal
cd /d "%~dp0"

set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot"
set "ANDROID_HOME=D:\Utilities\AndroidSDK"
set "ANDROID_SDK_ROOT=D:\Utilities\AndroidSDK"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: JDK 21 not found:
    echo %JAVA_HOME%
    pause
    exit /b 1
)

if not exist "%ANDROID_HOME%\platforms\android-36\android.jar" (
    echo ERROR: Android SDK Platform 36 not found:
    echo %ANDROID_HOME%
    pause
    exit /b 1
)

call gradlew.bat assembleRelease --no-daemon --console=plain
if errorlevel 1 exit /b %errorlevel%
echo.
echo APK: app\build\outputs\apk\release\app-release.apk
pause
endlocal
