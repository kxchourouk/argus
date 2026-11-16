@echo off
REM Builds dist\argus-server.jar (host) and dist\argus-agent.jar (VMs). Needs JDK 11 or newer.
cd /d "%~dp0"
if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
mkdir build\common build\agent build\server dist

echo [1/4] Compiling shared RMI interfaces...
javac --release 11 -encoding UTF-8 -d build\common common\src\argus\common\*.java || goto :error

echo [2/4] Compiling agent...
javac --release 11 -encoding UTF-8 -cp build\common -d build\agent agent\src\argus\agent\*.java || goto :error

echo [3/4] Compiling server...
javac --release 11 -encoding UTF-8 -cp build\common -d build\server server\src\argus\server\*.java server\src\argus\server\ai\*.java server\src\argus\server\web\*.java || goto :error
xcopy /e /i /q server\resources build\server > nul

echo [4/4] Packaging jars...
echo Main-Class: argus.agent.AgentMain> build\agent.mf
echo Main-Class: argus.server.ServerMain> build\server.mf
jar cfm dist\argus-agent.jar build\agent.mf -C build\common . -C build\agent . || goto :error
jar cfm dist\argus-server.jar build\server.mf -C build\common . -C build\server . || goto :error
echo Done: dist\argus-server.jar (run on the host) and dist\argus-agent.jar (copy to each VM)
exit /b 0

:error
echo BUILD FAILED
exit /b 1
