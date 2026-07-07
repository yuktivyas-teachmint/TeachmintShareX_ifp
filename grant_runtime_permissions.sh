#!/usr/bin/env bash
set -euo pipefail

PKG_NAME="com.teachmint.shareX"
LEGACY_PKG_NAMES=("com.teachmint.sharex" "com.teachmint.teachmintsharex" "com.example.teachmintsharex")
SERIAL=""

usage() {
  cat <<EOF
Usage: $(basename "$0") -s <device_serial> [--package <package_name>]

Grants runtime permissions required for Win+K / Wi-Fi Direct discovery and
prints verification from dumpsys.

Examples:
  ./grant_runtime_permissions.sh -s adb-SKGRj..._adb-tls-connect._tcp
  ./grant_runtime_permissions.sh -s emulator-5554 --package com.teachmint.shareX
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--serial)
      SERIAL="${2:-}"
      shift 2
      ;;
    -p|--package)
      PKG_NAME="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1"
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$SERIAL" ]]; then
  echo "❌ Serial is required."
  usage
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "❌ adb not found in PATH."
  exit 1
fi

ADB=(adb -s "$SERIAL")

echo "🔎 Using device: $SERIAL"
echo "📦 Package: $PKG_NAME"

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "❌ Device is not reachable."
  exit 1
fi

if ! "${ADB[@]}" shell pm path "$PKG_NAME" >/dev/null 2>&1; then
  switched=0
  for legacy_pkg in "${LEGACY_PKG_NAMES[@]}"; do
    if "${ADB[@]}" shell pm path "$legacy_pkg" >/dev/null 2>&1; then
      echo "ℹ️ Primary package not found, switching to legacy package: $legacy_pkg"
      PKG_NAME="$legacy_pkg"
      switched=1
      break
    fi
  done
  if [[ "$switched" -eq 0 ]]; then
    echo "❌ Package not installed: $PKG_NAME"
    exit 1
  fi
fi

grant_perm() {
  local permission="$1"
  set +e
  local output
  output=$("${ADB[@]}" shell pm grant "$PKG_NAME" "$permission" 2>&1)
  local status=$?
  set -e

  if [[ $status -eq 0 ]]; then
    echo "✅ Granted $permission"
    return 0
  fi

  if echo "$output" | grep -qiE "already|granted|not a changeable permission"; then
    echo "ℹ️ $permission already satisfied ($output)"
    return 0
  fi

  echo "⚠️ Could not grant $permission: $output"
  return 1
}

echo "🛡️ Granting runtime permissions..."
grant_perm "android.permission.ACCESS_FINE_LOCATION" || true
grant_perm "android.permission.ACCESS_COARSE_LOCATION" || true
grant_perm "android.permission.NEARBY_WIFI_DEVICES" || true
grant_perm "android.permission.CHANGE_WIFI_STATE" || true
grant_perm "android.permission.POST_NOTIFICATIONS" || true
grant_perm "android.permission.CAMERA" || true
grant_perm "android.permission.RECORD_AUDIO" || true

echo "🔧 Setting app ops best-effort..."
"${ADB[@]}" shell appops set "$PKG_NAME" ACCESS_FINE_LOCATION allow >/dev/null 2>&1 || true
"${ADB[@]}" shell appops set "$PKG_NAME" NEARBY_WIFI_DEVICES allow >/dev/null 2>&1 || true
"${ADB[@]}" shell settings put global wifi_display_on 1 >/dev/null 2>&1 || true

echo ""
echo "🔍 Verification:"
"${ADB[@]}" shell dumpsys package "$PKG_NAME" | grep -E "android.permission.(ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|NEARBY_WIFI_DEVICES|POST_NOTIFICATIONS): granted=" || true
WIFI_DISPLAY_ON=$("${ADB[@]}" shell settings get global wifi_display_on 2>/dev/null | tr -d '\r')
WIFIP2P_STATE=$("${ADB[@]}" shell dumpsys wifip2p 2>/dev/null | tr -d '\r' | grep -m1 "curState=" || true)
echo "   wifi_display_on: ${WIFI_DISPLAY_ON:-<unknown>}"
echo "   wifip2p: ${WIFIP2P_STATE:-<unavailable>}"

echo ""
echo "✅ Done."
echo "Next:"
echo "  1) Force-stop app: adb -s \"$SERIAL\" shell am force-stop $PKG_NAME"
echo "  2) Launch host again and confirm logs show:"
echo "     WIFI_DIRECT: ✅ Miracast DNS-SD service advertised"
echo "     WIFI_DIRECT: ✅ Peer discovery started for Win+K"
