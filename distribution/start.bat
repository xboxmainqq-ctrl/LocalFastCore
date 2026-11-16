@echo off
cd /d "%~dp0"
if "%JAVA%"=="" (set JAVA=java)
if not exist .\nexora-data mkdir .\nexora-data
if not exist .\nexora-data\server.properties copy server.properties .\nexora-data\server.properties
%JAVA% -Xms1G -Xmx2G -jar nexora-server.jar --data-dir .\nexora-data %*
pause
