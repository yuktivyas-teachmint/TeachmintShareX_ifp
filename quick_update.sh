#!/usr/bin/env bash
set -euo pipefail

# Quick update script - updates system APK without rebooting
# Use this for code changes that don't require permission changes

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_PATH="$SCRIPT_DIR/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
SERIAL="${1:-}"
PKG_NAME="com.teachmint.shareX"
LEGACY_PKG_NAMES=("com.teachmint.sharex" "com.teachmint.teachmintsharex" "com.example.teachmintsharex")
PRIMARY_ACTIVITY_NAME="com.teachmint.sharex.androidapp.MainActivity"
LEGACY_ACTIVITY_NAME="com.teachmint.teachmintsharex.androidapp.MainActivity"

if [[ -z "$SERIAL" ]]; then
  echo "Usage: $0 <device_serial>"
  echo ""
  echo "Example: $0 adb-SKGKux9PYIBMdmc4QpXj-8bASyF._adb-tls-connect._tcp"
  exit 1
fi

ADB=(adb -s "$SERIAL")

echo "🚀 Quick update (no reboot)..."

# Build APK
echo "📦 Building APK..."
(cd "$SCRIPT_DIR" && ./gradlew :androidApp:assembleDebug --no-daemon -q)

if [[ ! -f "$APK_PATH" ]]; then
  echo "❌ APK not found: $APK_PATH"
  exit 1
fi

# Get root and remount
echo "🔑 Getting root access..."
"${ADB[@]}" root >/dev/null 2>&1 || true
"${ADB[@]}" wait-for-device
sleep 2

echo "🔓 Remounting system..."
"${ADB[@]}" remount >/dev/null 2>&1 || true

# Stop the app
echo "🛑 Stopping app..."
"${ADB[@]}" shell am force-stop "$PKG_NAME" >/dev/null 2>&1 || true
for legacy_pkg in "${LEGACY_PKG_NAMES[@]}"; do
  "${ADB[@]}" shell am force-stop "$legacy_pkg" >/dev/null 2>&1 || true
done

# Update APK
RESOLVED_PKG_NAME="$PKG_NAME"
APK_DEST=$("${ADB[@]}" shell pm path "$PKG_NAME" 2>/dev/null | tr -d '\r' | cut -d: -f2)
if [[ -z "$APK_DEST" ]]; then
  for legacy_pkg in "${LEGACY_PKG_NAMES[@]}"; do
    APK_DEST=$("${ADB[@]}" shell pm path "$legacy_pkg" 2>/dev/null | tr -d '\r' | cut -d: -f2)
    if [[ -n "$APK_DEST" ]]; then
      RESOLVED_PKG_NAME="$legacy_pkg"
      break
    fi
  done
fi
if [[ -z "$APK_DEST" ]]; then
    echo "❌ App not installed as system app. Use deploy_miracast_privapp.sh first."
    exit 1
fi

echo "📤 Updating APK at $APK_DEST..."
"${ADB[@]}" push "$APK_PATH" "$APK_DEST" >/dev/null 2>&1

echo "📡 Enabling Wi-Fi Display framework setting..."
"${ADB[@]}" shell settings put global wifi_display_on 1 >/dev/null 2>&1 || true

echo "🔄 Restarting app..."
"${ADB[@]}" shell am start -n "$RESOLVED_PKG_NAME/$PRIMARY_ACTIVITY_NAME" >/dev/null 2>&1 || \
  "${ADB[@]}" shell am start -n "$RESOLVED_PKG_NAME/$LEGACY_ACTIVITY_NAME" >/dev/null 2>&1 || true

WIFI_DISPLAY_ON=$("${ADB[@]}" shell settings get global wifi_display_on 2>/dev/null | tr -d '\r')
WIFIP2P_STATE=$("${ADB[@]}" shell dumpsys wifip2p 2>/dev/null | tr -d '\r' | grep -m1 "curState=" || true)

echo ""
echo "✅ Done! App updated without reboot."
echo "   wifi_display_on: ${WIFI_DISPLAY_ON:-<unknown>}"
echo "   wifip2p: ${WIFIP2P_STATE:-<unavailable>}"
echo "   Check logcat for startup logs:"
echo "   adb -s \"$SERIAL\" logcat | grep -E \"(MIRACAST|WIFI_DIRECT)\""
