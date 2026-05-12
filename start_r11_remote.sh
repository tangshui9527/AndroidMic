#!/bin/bash
set -euo pipefail

# AndroidMic 一键连接脚本
# 目标：运行一次脚本后，自动完成 ADB 连接、桌面端监听、安卓端拉起与自动连接。

PHONE_IP="192.168.31.11"
ADB_PORT="5555"
PC_PORT="54345"
PACKAGE_NAME="io.github.teamclouday.AndroidMic.debug"
MAIN_ACTIVITY="io.github.teamclouday.androidMic.ui.MainActivity"
SERVICE_NAME="io.github.teamclouday.androidMic.domain.service.ForegroundService"
AUTO_CONNECT_ACTION="io.github.teamclouday.androidMic.AUTO_CONNECT"
MODE="WIFI"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_BUNDLE="$SCRIPT_DIR/AndroidMic.app"
APP_BIN="$APP_BUNDLE/Contents/MacOS/android-mic"
DEBUG_BIN="$SCRIPT_DIR/RustApp/target/debug/android-mic"
ADB_SERIAL="${PHONE_IP}:${ADB_PORT}"
DESKTOP_LOG="/tmp/androidmic_r11_remote.log"

find_local_ip() {
    local ip=""
    ip="$(ipconfig getifaddr en1 2>/dev/null || true)"
    if [ -z "$ip" ]; then
        ip="$(ipconfig getifaddr en0 2>/dev/null || true)"
    fi

    if [ -z "$ip" ]; then
        echo "ERROR: Could not detect local IP from en1 or en0." >&2
        exit 1
    fi

    printf '%s\n' "$ip"
}

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "ERROR: Missing required command: $1" >&2
        exit 1
    fi
}

wait_for_listen() {
    local port="$1"
    local attempts=20

    for ((i = 1; i <= attempts; i++)); do
        if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done

    echo "ERROR: Desktop app did not start listening on TCP $port in time." >&2
    echo "--- Desktop process ---" >&2
    pgrep -af "android-mic" >&2 || true
    echo "--- Desktop log ---" >&2
    tail -n 80 "$DESKTOP_LOG" >&2 || true
    exit 1
}

start_desktop_app() {
    pkill -f "$APP_BIN" >/dev/null 2>&1 || true
    pkill -f "$DEBUG_BIN" >/dev/null 2>&1 || true
    rm -f "$DESKTOP_LOG"

    if [ -x "$APP_BIN" ]; then
        echo "Launching AndroidMic app binary..."
        "$APP_BIN" -i "$LOCAL_IP" -m TCP --listen >"$DESKTOP_LOG" 2>&1 &
    elif [ -x "$DEBUG_BIN" ]; then
        echo "Launching debug binary..."
        "$DEBUG_BIN" -i "$LOCAL_IP" -m TCP --listen >"$DESKTOP_LOG" 2>&1 &
    else
        echo "ERROR: Could not find AndroidMic executable." >&2
        exit 1
    fi
}

adb_shell() {
    adb -s "$ADB_SERIAL" shell "$@"
}

require_command adb
require_command ipconfig
require_command lsof

LOCAL_IP="$(find_local_ip)"

echo "----------------------------------------"
echo "Local IP : $LOCAL_IP"
echo "Phone IP : $PHONE_IP"
echo "ADB Port : $ADB_PORT"
echo "PC Port  : $PC_PORT"
echo "----------------------------------------"

echo "1/5 Connecting ADB..."
adb connect "$ADB_SERIAL" >/dev/null
adb -s "$ADB_SERIAL" wait-for-device

echo "2/5 Waking phone and clearing old AndroidMic state..."
adb_shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb_shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true

echo "3/5 Starting desktop app in TCP listen mode..."
start_desktop_app
wait_for_listen "$PC_PORT"

echo "4/5 Sending AUTO_CONNECT_ACTION to Android foreground service..."
adb_shell am start-foreground-service \
    -n "$PACKAGE_NAME/$SERVICE_NAME" \
    -a "$AUTO_CONNECT_ACTION" \
    --ez auto_connect true \
    --es mode "$MODE" \
    --es ip "$LOCAL_IP" \
    --es port "$PC_PORT" >/dev/null

echo "5/5 Opening Android app UI..."
adb_shell am start \
    -n "$PACKAGE_NAME/$MAIN_ACTIVITY" \
    --ez auto_connect true \
    --es mode "$MODE" \
    --es ip "$LOCAL_IP" \
    --es port "$PC_PORT" >/dev/null

echo "----------------------------------------"
echo "AndroidMic one-key connect triggered."
echo "Desktop should be listening and phone should auto-connect now."
echo "----------------------------------------"
