#!/usr/bin/env bash
# Usage: ./run-agent.sh <vm-name> [host-ip]
NAME=${1:-$(hostname)}
HOST=${2:-192.168.56.1}
java -jar "$(dirname "$0")/argus-agent.jar" --server "$HOST" --name "$NAME"
