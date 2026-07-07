#!/bin/bash
set -e

# Configuration
PKG_NAME="com.teachmint.shareX"
APK_PATH="androidApp/build/outputs/apk/debug/androidApp-debug.apk"
PRIV_APP_DIR="/system/priv-app/ShareX"
PERM_FILE="privapp-permissions-com.teachmint.shareX.xml"
PERM_DEST="/system/etc/permissions/$PERM_FILE"
DEFAULT_PERM_FILE="default-permissions-com.teachmint.shareX.xml"
DEFAULT_PERM_DEST="/system/etc/default-permissions/$DEFAULT_PERM_FILE"

echo "🚀 Deploying $PKG_NAME as system app..."

# 0. Check device connectivity
if ! adb get-state &>/dev/null; then
    echo "❌ No device connected. Connect a device and try again."
    exit 1
fi

# 1. Build APK
echo "📦 Building debug APK..."
./gradlew :androidApp:assembleDebug

if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK not found at $APK_PATH"
    exit 1
fi

# 2. ADB Root & Remount
echo "🔑 Requesting ADB Root..."
adb root
adb wait-for-device
sleep 2

echo "🔓 Remounting system..."
adb remount
sleep 2

# 3. Remove existing /data/app overlay (so system version is actually used)
echo "🧹 Removing existing app updates from /data/app..."
adb shell pm uninstall -k --user 0 $PKG_NAME 2>/dev/null || true
adb shell pm install-existing $PKG_NAME 2>/dev/null || true

# 4. Create Directories & Push Files
echo "📤 Pushing APK to $PRIV_APP_DIR..."
adb shell mkdir -p "$PRIV_APP_DIR"
adb push "$APK_PATH" "$PRIV_APP_DIR/ShareX.apk"

echo "📤 Pushing Permissions Whitelist to $PERM_DEST..."
adb push "$PERM_FILE" "$PERM_DEST"

echo "📤 Pushing Default Permissions to $DEFAULT_PERM_DEST..."
adb shell mkdir -p "/system/etc/default-permissions"
adb push "$DEFAULT_PERM_FILE" "$DEFAULT_PERM_DEST"

# 5. Set Permissions
echo "🔒 Setting file permissions..."
adb shell chmod 755 "$PRIV_APP_DIR"
adb shell chmod 644 "$PRIV_APP_DIR/ShareX.apk"
adb shell chmod 644 "$PERM_DEST"
adb shell chmod 644 "$DEFAULT_PERM_DEST"

# 6. Pre-enable Accessibility & Overlay (best-effort before reboot)
echo "✨ Pre-enabling Accessibility & Overlay..."

# Enable Accessibility Service (still needed for screenshots)
adb shell settings put secure enabled_accessibility_services $PKG_NAME/.accessibility.EduAccessibilityService
adb shell settings put secure accessibility_enabled 1

# Grant System Alert Window (Application Overlay)
adb shell appops set $PKG_NAME SYSTEM_ALERT_WINDOW allow

# 7. Reboot
echo "🔄 Rebooting device to apply system changes..."
adb reboot

# 8. Wait for device and grant runtime permissions
echo "⏳ Waiting for device to boot..."
adb wait-for-device
sleep 30  # Wait for system to fully boot and package manager to be ready

echo "🛡️ Granting runtime permissions..."
adb shell pm grant $PKG_NAME android.permission.CAMERA || true
adb shell pm grant $PKG_NAME android.permission.RECORD_AUDIO || true
adb shell pm grant $PKG_NAME android.permission.READ_EXTERNAL_STORAGE || true
adb shell pm grant $PKG_NAME android.permission.WRITE_EXTERNAL_STORAGE || true
adb shell pm grant $PKG_NAME android.permission.READ_MEDIA_IMAGES || true
adb shell pm grant $PKG_NAME android.permission.READ_MEDIA_VIDEO || true
adb shell pm grant $PKG_NAME android.permission.READ_MEDIA_AUDIO || true

echo "📡 Enabling Wi-Fi Display framework setting..."
adb shell settings put global wifi_display_on 1 || true

# Re-enable accessibility after reboot (settings may reset)
adb shell settings put secure enabled_accessibility_services $PKG_NAME/.accessibility.EduAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell appops set $PKG_NAME SYSTEM_ALERT_WINDOW allow

# 9. Verify
echo ""
echo "🔍 Verifying deployment..."
echo "---"

REAL_GET_TASKS_STATUS=$(adb shell dumpsys package $PKG_NAME 2>/dev/null | grep "android.permission.REAL_GET_TASKS:" | head -1 | grep -o "granted=[a-z]*")
INSTALL_LOCATION=$(adb shell dumpsys package $PKG_NAME 2>/dev/null | grep "codePath=" | head -1)
WIFI_DISPLAY_ON=$(adb shell settings get global wifi_display_on 2>/dev/null | tr -d '\r')
WIFIP2P_STATE=$(adb shell dumpsys wifip2p 2>/dev/null | tr -d '\r' | grep -m1 "curState=" || true)

echo "   REAL_GET_TASKS: $REAL_GET_TASKS_STATUS"
echo "   Install location: $INSTALL_LOCATION"
echo "   wifi_display_on: ${WIFI_DISPLAY_ON:-<unknown>}"
echo "   wifip2p: ${WIFIP2P_STATE:-<unavailable>}"

if echo "$REAL_GET_TASKS_STATUS" | grep -q "granted=true"; then
    echo ""
    echo "✅ Deployment complete! REAL_GET_TASKS is granted."
else
    echo ""
    echo "⚠️  Deployment complete but REAL_GET_TASKS is NOT granted."
    echo "   This may happen if the device needs another reboot."
    echo "   Run: adb shell dumpsys package $PKG_NAME | grep REAL_GET_TASKS"
fi

echo ""
echo "   Verify on device:"
echo "   1. App is installed as system app"
echo "   2. REAL_GET_TASKS permission is granted (for foreground app detection)"
echo "   3. Accessibility service is ON (for screenshots)"
echo "   4. Overlay permission is granted"
echo "   5. wifi_display_on is 1 and wifip2p curState is not disabled"
