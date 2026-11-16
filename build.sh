#!/usr/bin/env bash
# Builds dist/argus-server.jar (host) and dist/argus-agent.jar (VMs). Needs JDK 11 or newer.
set -e
cd "$(dirname "$0")"
rm -rf build dist && mkdir -p build/common build/agent build/server dist

echo "[1/4] Compiling shared RMI interfaces..."
javac --release 11 -encoding UTF-8 -d build/common $(find common/src -name '*.java')

echo "[2/4] Compiling agent..."
javac --release 11 -encoding UTF-8 -cp build/common -d build/agent $(find agent/src -name '*.java')

echo "[3/4] Compiling server..."
javac --release 11 -encoding UTF-8 -cp build/common -d build/server $(find server/src -name '*.java')
cp -r server/resources/* build/server/

echo "[4/4] Packaging jars..."
printf "Main-Class: argus.agent.AgentMain\n" > build/agent.mf
printf "Main-Class: argus.server.ServerMain\n" > build/server.mf
jar cfm dist/argus-agent.jar  build/agent.mf  -C build/common . -C build/agent .
jar cfm dist/argus-server.jar build/server.mf -C build/common . -C build/server .
echo "Done: dist/argus-server.jar (run on the host)  and  dist/argus-agent.jar (copy to each VM)"
