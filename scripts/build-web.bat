@echo off
rem Збирає сайт у dist\web. Параметри передаються далі, напр. --serve.
chcp 65001 >nul
set "PY="
where py >nul 2>&1 && set "PY=py -3"
if not defined PY where python >nul 2>&1 && set "PY=python"
if not defined PY (
    echo Помилка: потрібен Python 3 ^(https://www.python.org^).
    exit /b 1
)
set PYTHONUTF8=1
%PY% "%~dp0build_web.py" %*
set "code=%errorlevel%"
echo %cmdcmdline% | find /i "%~0" >nul && pause
exit /b %code%
