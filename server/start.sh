#!/usr/bin/env bash
# Start the lc-live compile server in the foreground.
# Usage: server/start.sh [port]      (default port 47321)
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if ! "$JAVA_BIN" -version >/dev/null 2>&1; then
  echo "java not found. Install a JDK (11+) or set JAVA_HOME." >&2; exit 1
fi
exec "$JAVA_BIN" -Xms64m -Xmx512m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 "$DIR/LcLiveServer.java" "${1:-47321}"
