#!/usr/bin/env bash
# Install a macOS LaunchAgent so the compile server starts at login and restarts if it dies.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LABEL="com.lc-live.server"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
JAVA_BIN="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || true)}"
JAVA_BIN="${JAVA_BIN:+$JAVA_BIN/bin/}java"
JAVA_BIN="$(command -v "$JAVA_BIN")"
PORT="${1:-47321}"
LOG="$HOME/Library/Logs/lc-live.log"
mkdir -p "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"
cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array>
    <string>$JAVA_BIN</string>
    <string>-Xms64m</string><string>-Xmx512m</string><string>-XX:+UseSerialGC</string><string>-XX:TieredStopAtLevel=1</string>
    <string>$DIR/LcLiveServer.java</string>
    <string>$PORT</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ProcessType</key><string>Background</string>
  <key>StandardOutPath</key><string>$LOG</string>
  <key>StandardErrorPath</key><string>$LOG</string>
</dict></plist>
PL
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "Installed $LABEL (java: $JAVA_BIN, port: $PORT). Log: $LOG"
for i in $(seq 1 40); do curl -sf "http://127.0.0.1:$PORT/health" >/dev/null 2>&1 && { echo "Server is up."; exit 0; }; sleep 0.5; done
echo "Server did not answer yet; check $LOG" >&2; exit 1
