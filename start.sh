#!/usr/bin/env bash
# Starts the TouchPort receiver. If the phone is plugged in, it also opens the app.
# Press Ctrl+C to stop: the app closes, the USB link is removed and every key is released.
set -euo pipefail

DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
RECEIVER="$DIR/receiver/touchport-receiver"
APP="${TOUCHPORT_APP:-dev.touchport}/dev.touchport.MainActivity"

# Build the receiver when it is missing or older than its source.
if [[ ! -x "$RECEIVER" || "$DIR/receiver/touchport-receiver.c" -nt "$RECEIVER" ]]; then
    echo "Building receiver…"
    make -s -C "$DIR/receiver"
fi

if pgrep -f "$RECEIVER" >/dev/null; then
    echo "The receiver is already running (pid $(pgrep -f "$RECEIVER")). Stop it first." >&2
    exit 1
fi

phone=false
if command -v adb >/dev/null && adb get-state >/dev/null 2>&1; then
    phone=true
    echo "Phone found over USB: $(adb shell getprop ro.product.model | tr -d '\r')"
else
    echo "No phone on USB. Plug it in any time, or use Wi-Fi."
fi

cleanup() {
    if command -v adb >/dev/null && adb get-state >/dev/null 2>&1; then
        adb shell am force-stop "${APP%%/*}" >/dev/null 2>&1 || true
        adb reverse --remove tcp:47800 >/dev/null 2>&1 || true
    fi
    echo "Stopped."
}
trap cleanup EXIT

# The receiver sets up "adb reverse" by itself; start the app a moment after it is ready.
if $phone; then
    (sleep 1; adb shell am start -n "$APP" >/dev/null 2>&1 || true) &
fi

echo "Press Ctrl+C to stop."
"$RECEIVER" "$@" || true
