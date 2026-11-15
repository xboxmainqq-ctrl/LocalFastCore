@echo off
cd /d "%~dp0"
if "%JAVA%"=="" (set JAVA=java)
%JAVA% -Xms1G -Xmx2G -jar nexora-server.jar --data-dir .\nexora-data %*
pause
