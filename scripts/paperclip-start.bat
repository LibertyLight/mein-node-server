@echo off
rem Startet Paperclip im Terminal und oeffnet die UI im Browser.
title Paperclip
set PAPERCLIP_URL=http://localhost:3100

rem Browser nach 10 Sekunden oeffnen, waehrend der Server im Vordergrund startet.
start "" /b cmd /c "timeout /t 10 /nobreak >nul & start "" %PAPERCLIP_URL%"

call npx paperclipai run
echo.
echo Paperclip wurde beendet.
pause
