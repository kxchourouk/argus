@echo off
REM Starts the host server. Change the IP if your host-only adapter uses another one.
cd /d "%~dp0\.."
java -jar dist\argus-server.jar --ip 192.168.56.1 %*
