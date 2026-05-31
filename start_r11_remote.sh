#!/bin/bash
set -euo pipefail

BASE_IP="192.168.31"
PHONE_SUFFIX="${1:-11}"
PHONE_IP="${BASE_IP}.${PHONE_SUFFIX}"
ADB_PORT="5555"
PC_PORT="54345"
PACKAGE_NAME="io.github.teamclouday.AndroidMic.debug"
MAIN_ACTIVITY="io.github.teamclouday.androidMic.ui.MainActivity"
MODE="WIFI"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_BUNDLE="$SCRIPT_DIR/AndroidMic.app"
APP_BIN="$APP_BUNDLE/Contents/MacOS/android-mic"
DEBUG_BIN="$SCRIPT_DIR/RustApp/target/debug/android-mic"
ADB_SERIAL="${PHONE_IP}:${ADB_PORT}"
DESKTOP_LOG="/tmp/androidmic_r11_remote.log"

find_local_ip() {
    local ip
    ip="$(ipconfig getifaddr en1 2>/dev/null || ipconfig getifaddr en0 2>/dev/null || true)"
    if [ -z "$ip" ]; then
        echo "ERROR: Could not detect local IP." >&2
        exit 1
    fi
    printf '%s\n' "$ip"
}

wait_for_listen() {
    local port="$1" i
    for ((i = 1; i <= 20; i++)); do
        if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    echo "ERROR: Desktop app did not listen on TCP $port in time." >&2
    tail -n 40 "$DESKTOP_LOG" >&2 || true
    exit 1
}

wait_port_free() {
    local port="$1" i
    for ((i = 1; i <= 10; i++)); do
        if ! lsof -nP -iTCP:"$port" >/dev/null 2>&1; then
            return 0
        fi
        sleep 0.5
    done
}

adb_shell() { adb -s "$ADB_SERIAL" shell "$@"; }

command -v adb >/dev/null || { echo "ERROR: adb not found" >&2; exit 1; }

LOCAL_IP="$(find_local_ip)"

echo "----------------------------------------"
echo "Local IP : $LOCAL_IP"
echo "Phone    : $ADB_SERIAL"
echo "PC Port  : $PC_PORT"
echo "----------------------------------------"

# 1. ADB connect with retry
echo "1/4 Connecting ADB..."
adb disconnect "$ADB_SERIAL" >/dev/null 2>&1 || true
sleep 1
for attempt in 1 2 3; do
    if adb connect "$ADB_SERIAL" 2>&1 | grep -q "connected"; then
        break
    fi
    [ "$attempt" -eq 3 ] && { echo "ERROR: ADB connect failed" >&2; exit 1; }
    sleep 2
done
adb -s "$ADB_SERIAL" wait-for-device

# 2. Kill old Android app state
echo "2/4 Cleaning up Android app..."
adb_shell input keyevent KEYCODE_WAKEUP 2>/dev/null || true
adb_shell am force-stop "$PACKAGE_NAME" 2>/dev/null || true
sleep 1

# 3. Start desktop app (ensure port is free first)
echo "3/4 Starting desktop app..."
pkill -f "android-mic" 2>/dev/null || true
wait_port_free "$PC_PORT"
rm -f "$DESKTOP_LOG"

if [ -x "$APP_BIN" ]; then
    "$APP_BIN" -i "$LOCAL_IP" -m TCP --listen >"$DESKTOP_LOG" 2>&1 &
elif [ -x "$DEBUG_BIN" ]; then
    "$DEBUG_BIN" -i "$LOCAL_IP" -m TCP --listen >"$DESKTOP_LOG" 2>&1 &
else
    echo "ERROR: No AndroidMic executable found." >&2; exit 1
fi
wait_for_listen "$PC_PORT"

# 4. Launch Android app via Activity ONLY (no separate service intent)
#    Activity's handleIntent will forward auto_connect to Service exactly once
echo "4/4 Launching Android app with auto-connect..."
adb_shell am start -n "$PACKAGE_NAME/$MAIN_ACTIVITY" \
    --ez auto_connect true \
    --es mode "$MODE" \
    --es ip "$LOCAL_IP" \
    --es port "$PC_PORT" >/dev/null

# Auto-stop at 01:00
(
    now=$(date +%s)
    target=$(date -j -f "%H:%M:%S" "01:00:00" +%s 2>/dev/null)
    [ "$target" -le "$now" ] && target=$((target + 86400))
    sleep $((target - now))
    pkill -f "android-mic"
) &

echo "----------------------------------------"
echo "Done. Auto-stop at 01:00."
echo "----------------------------------------"
