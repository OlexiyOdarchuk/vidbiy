@echo off
rem Збирає release APK для Android. Можна запускати подвійним кліком.
rem Уся логіка в build-android.ps1.
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-android.ps1" %*
set "code=%errorlevel%"
rem Якщо скрипт запустили подвійним кліком, вікно не закриється, поки не прочитаєте результат.
echo %cmdcmdline% | find /i "%~0" >nul && pause
exit /b %code%
