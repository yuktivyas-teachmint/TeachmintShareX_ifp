#!/usr/bin/env bash
set -euo pipefail

PKG_NAME="com.teachmint.shareX"
LEGACY_PKG_NAMES=("com.teachmint.sharex" "com.teachmint.teachmintsharex" "com.example.teachmintsharex")
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_PATH="$SCRIPT_DIR/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
PERM_FILE_PRIMARY="$SCRIPT_DIR/privapp-permissions-com.teachmint.shareX.xml"
PERM_FILE_COMPAT="$SCRIPT_DIR/privapp-permissions-com.example.teachmintsharex.xml"
PERM_FILE=""
PERM_DEST="/system/etc/permissions/privapp-permissions-com.teachmint.shareX.xml"
DEFAULT_PERM_FILE_PRIMARY="$SCRIPT_DIR/default-permissions-com.teachmint.shareX.xml"
DEFAULT_PERM_FILE_COMPAT="$SCRIPT_DIR/default-permissions-com.example.teachmintsharex.xml"
DEFAULT_PERM_FILE=""
DEFAULT_PERM_DEST="/system/etc/default-permissions/default-permissions-com.teachmint.shareX.xml"
APK_DEST=""
PRIV_APP_DIR=""

SERIAL=""
SKIP_BUILD=0
NO_REBOOT=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [-s SERIAL] [--skip-build] [--no-reboot]

Options:
  -s SERIAL       Target specific device serial (recommended if multiple devices connected)
  --skip-build    Skip Gradle APK build step
  --no-reboot     Do not reboot at the end (not recommended)
  -h, --help      Show this help

Example:
  ./deploy_miracast_privapp.sh -s <device_serial>
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s)
      SERIAL="${2:-}"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD=1
      shift
      ;;
    --no-reboot)
      NO_REBOOT=1
      shift
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

if ! command -v adb >/dev/null 2>&1; then
  echo "❌ adb not found in PATH."
  exit 1
fi

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB+=( -s "$SERIAL" )
fi

# If no serial is specified, fail fast when multiple online devices are present.
if [[ -z "$SERIAL" ]]; then
  ONLINE_COUNT="$(adb devices | awk 'NR>1 && $2=="device" {count++} END {print count+0}')"
  if [[ "$ONLINE_COUNT" -gt 1 ]]; then
    echo "❌ Multiple devices connected. Re-run with -s <serial>."
    adb devices -l
    exit 1
  fi
fi

run_adb() {
  "${ADB[@]}" "$@"
}

enable_wifi_display_best_effort() {
  echo "📡 Enabling Wi-Fi Display framework setting (wifi_display_on=1)..."
  run_adb shell "settings put global wifi_display_on 1" >/dev/null 2>&1 || true
}

print_wifi_direct_framework_state() {
  local wifi_display
  local p2p_state
  wifi_display="$(run_adb shell settings get global wifi_display_on 2>/dev/null | tr -d '\r')"
  p2p_state="$(run_adb shell dumpsys wifip2p 2>/dev/null | tr -d '\r' | grep -m1 'curState=' || true)"
  echo "   wifi_display_on: ${wifi_display:-<unknown>}"
  echo "   wifip2p: ${p2p_state:-<unavailable>}"
}

detect_system_apk_dest() {
  local candidates=(
    "/system/priv-app/ShareX/ShareX.apk"
    "/system/priv-app/ShareX/base.apk"
    "/system/priv-app/TeachmintShareX/TeachmintShareX.apk"
    "/system/priv-app/TeachmintShareX/base.apk"
  )

  for candidate in "${candidates[@]}"; do
    if run_adb shell "[ -f '$candidate' ]" >/dev/null 2>&1; then
      echo "$candidate"
      return 0
    fi
  done

  # Fallback for first-time install on this platform family.
  echo "/system/priv-app/ShareX/ShareX.apk"
}

echo "🚀 Deploying $PKG_NAME as a privileged system app for Miracast..."

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  echo "📦 Building debug APK..."
  (cd "$SCRIPT_DIR" && ./gradlew :androidApp:assembleDebug -x lint --no-daemon)
fi

if [[ ! -f "$APK_PATH" ]]; then
  echo "❌ APK not found: $APK_PATH"
  exit 1
fi
if [[ -f "$PERM_FILE_PRIMARY" ]]; then
  PERM_FILE="$PERM_FILE_PRIMARY"
elif [[ -f "$PERM_FILE_COMPAT" ]]; then
  PERM_FILE="$PERM_FILE_COMPAT"
else
  echo "❌ Privapp permission file not found:"
  echo "   - $PERM_FILE_PRIMARY"
  echo "   - $PERM_FILE_COMPAT"
  exit 1
fi

if [[ -f "$DEFAULT_PERM_FILE_PRIMARY" ]]; then
  DEFAULT_PERM_FILE="$DEFAULT_PERM_FILE_PRIMARY"
elif [[ -f "$DEFAULT_PERM_FILE_COMPAT" ]]; then
  DEFAULT_PERM_FILE="$DEFAULT_PERM_FILE_COMPAT"
else
  echo "⚠️ Default permission file not found (continuing):"
  echo "   - $DEFAULT_PERM_FILE_PRIMARY"
  echo "   - $DEFAULT_PERM_FILE_COMPAT"
fi

echo "🔑 Requesting adb root..."
run_adb root >/dev/null || true
run_adb wait-for-device
sleep 2

echo "🔓 Remounting writable system partitions..."
if ! run_adb remount; then
  echo "❌ adb remount failed. Device must be rooted/userdebug/eng with remount support."
  exit 1
fi

echo "🧹 Removing /data/app overlay version (if present)..."
run_adb shell pm uninstall "$PKG_NAME" >/dev/null 2>&1 || true
for legacy_pkg in "${LEGACY_PKG_NAMES[@]}"; do
  run_adb shell pm uninstall "$legacy_pkg" >/dev/null 2>&1 || true
done

APK_DEST="$(detect_system_apk_dest)"
PRIV_APP_DIR="$(dirname "$APK_DEST")"
echo "📍 System APK destination resolved to: $APK_DEST"

echo "📤 Installing APK to $APK_DEST ..."
run_adb shell "mkdir -p '$PRIV_APP_DIR'"
run_adb push "$APK_PATH" "$APK_DEST" >/dev/null

echo "📤 Installing privapp whitelist to $PERM_DEST ..."
run_adb push "$PERM_FILE" "$PERM_DEST" >/dev/null
if [[ -f "$DEFAULT_PERM_FILE" ]]; then
  echo "📤 Installing default runtime-permission grants to $DEFAULT_PERM_DEST ..."
  run_adb push "$DEFAULT_PERM_FILE" "$DEFAULT_PERM_DEST" >/dev/null
fi

echo "🔒 Setting file permissions..."
run_adb shell "chmod 755 '$PRIV_APP_DIR'"
run_adb shell "chmod 644 '$APK_DEST'"
run_adb shell "chmod 644 '$PERM_DEST'"
if [[ -f "$DEFAULT_PERM_FILE" ]]; then
  run_adb shell "chmod 644 '$DEFAULT_PERM_DEST'"
fi

echo "🧩 Restoring SELinux file contexts (if available)..."
if [[ -f "$DEFAULT_PERM_FILE" ]]; then
  run_adb shell "command -v restorecon >/dev/null 2>&1 && restorecon -RF '$PRIV_APP_DIR' '$PERM_DEST' '$DEFAULT_PERM_DEST' || true"
else
  run_adb shell "command -v restorecon >/dev/null 2>&1 && restorecon -RF '$PRIV_APP_DIR' '$PERM_DEST' || true"
fi

if [[ "$NO_REBOOT" -eq 0 ]]; then
  echo "🔄 Rebooting device..."
  run_adb reboot || true
  run_adb wait-for-device
  sleep 25
else
  echo "⚠️ Skipping reboot (--no-reboot). Some privileged permissions may not apply until reboot."
fi

echo "🛡️ Granting runtime permissions used by Wi-Fi Direct discovery..."
run_adb shell pm grant "$PKG_NAME" android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1 || true
run_adb shell pm grant "$PKG_NAME" android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1 || true
run_adb shell pm grant "$PKG_NAME" android.permission.NEARBY_WIFI_DEVICES >/dev/null 2>&1 || true
run_adb shell pm grant "$PKG_NAME" android.permission.CHANGE_WIFI_STATE >/dev/null 2>&1 || true
enable_wifi_display_best_effort

echo ""
echo "🔍 Verifying install state..."
PM_PATH="$(run_adb shell pm path "$PKG_NAME" 2>/dev/null | tr -d '\r')"
echo "   pm path: $PM_PATH"
run_adb shell "cmd package install-existing '$PKG_NAME' >/dev/null 2>&1 || true"

DUMPSYS="$(run_adb shell dumpsys package "$PKG_NAME" 2>/dev/null | tr -d '\r')"
CONFIG_WIFI_DISPLAY_LINE="$(printf "%s\n" "$DUMPSYS" | grep -i "android.permission.CONFIGURE_WIFI_DISPLAY: granted" | head -1 || true)"
CODEPATH_LINE="$(printf "%s\n" "$DUMPSYS" | grep -m1 -i "codePath=" || true)"
echo "   codePath: ${CODEPATH_LINE:-<not found>}"
echo "   CONFIGURE_WIFI_DISPLAY: ${CONFIG_WIFI_DISPLAY_LINE:-<not found in dumpsys output>}"
print_wifi_direct_framework_state

echo ""
echo "✅ Done."
echo "Next checks:"
echo "  1) Ensure pm path points to /system/priv-app (not /data/app)"
echo "  2) Ensure CONFIGURE_WIFI_DISPLAY shows granted=true"
echo "  3) Ensure wifi_display_on is 1 and wifip2p state is not disabled"
echo "  4) Launch app and check logcat for:"
echo "     WIFI_DIRECT: ✅ WFD sink info configured"
