# Device Role Detection

## Overview

The app automatically detects whether a device should be a **HOST** (can share screen) or **CLIENT** (can view shared screens) based on hardware capabilities.

## Detection Logic

### Android
Uses sensor and telephony detection to identify Interactive Flat Panels (IFPs):

```kotlin
// A device is an IFP (HOST) if it has:
// 1. NO gyroscope sensor AND
// 2. NO accelerometer sensor AND
// 3. NO telephony capabilities

// All other Android devices (phones, tablets) are CLIENTS
```

**IFP Characteristics:**
- Smart displays/TVs without motion sensors
- No cellular capabilities
- Examples: TeachmintX IFP, Smart Boards, Large Touch Displays

**Mobile/Tablet Characteristics:**
- Have gyroscope and/or accelerometer
- May have telephony
- Examples: Phones, Tablets

### iOS
```kotlin
// iOS devices are ALWAYS clients
// iPhones and iPads always have motion sensors
```

### Desktop (JVM)
```kotlin
// Desktop devices are CLIENTS by default
// Can be configured as HOST via system property:
// -Dteachmint.device.role=HOST
```

## How It Works

### 1. Device Detection (Android)

Located in: `DeviceTypeDetector.android.kt`

```kotlin
fun isInteractiveFlatPanel(): Boolean {
    // 1. Check cached value in SharedPreferences
    // 2. If not cached:
    //    - Check for gyroscope sensor
    //    - Check for accelerometer sensor
    //    - Check for telephony capabilities
    // 3. Cache the result
    // 4. Return true if NO sensors AND NO telephony
}
```

### 2. Role Determination

All platforms use the same logic in `DeviceTypeDetector.kt`:

```kotlin
fun getDeviceRole(): DeviceRole {
    return if (isInteractiveFlatPanel()) {
        DeviceRole.HOST   // Can share screen to clients
    } else {
        DeviceRole.CLIENT // Can view shared screens
    }
}
```

### 3. Platform Bindings

Each platform's bindings use the device role:

```kotlin
// AndroidBindings.kt, JvmBindings.kt, IosBindings.kt
actual fun getAppRole(): AppRole {
    return when (getDeviceRole()) {
        DeviceRole.HOST -> AppRole.Host
        DeviceRole.CLIENT -> AppRole.Client
    }
}
```

## Testing

### Test on IFP (HOST mode)
- Deploy app to actual IFP device
- App will automatically detect HOST role
- "Start Sharing" option will be available

### Test on Phone/Tablet (CLIENT mode)
- Deploy app to phone or tablet
- App will automatically detect CLIENT role
- "Connect to Host" option will be available

### Test Desktop as HOST
```bash
# Run desktop app as HOST
./gradlew :composeApp:run -Dteachmint.device.role=HOST

# Or configure in IDE:
# Run Configuration → VM Options: -Dteachmint.device.role=HOST
```

### Test Desktop as CLIENT (default)
```bash
# Run desktop app as CLIENT (default)
./gradlew :composeApp:run
```

## Caching

Detection results are cached to avoid repeated sensor checks:

- **Android:** SharedPreferences (`"teachmint_ifp"`)
- **Desktop:** Java Preferences (`"device_role"`)
- **iOS:** Not cached (always returns CLIENT)

### Clear Cache (Android)
```kotlin
// To reset device detection:
val prefs = context.getSharedPreferences("Teachmint", Context.MODE_PRIVATE)
prefs.edit().remove("teachmint_ifp").apply()
```

### Clear Cache (Desktop)
```kotlin
// To reset device detection:
val prefs = Preferences.userRoot().node("com/example/teachmintsharex")
prefs.remove("device_role")
```

## Architecture

```
Common (DeviceTypeDetector.kt)
├── expect fun isInteractiveFlatPanel(): Boolean
└── fun getDeviceRole(): DeviceRole

Platform Implementations
├── Android (DeviceTypeDetector.android.kt)
│   └── Sensor + Telephony detection
├── iOS (DeviceTypeDetector.ios.kt)
│   └── Always returns false (never IFP)
└── Desktop (DeviceTypeDetector.jvm.kt)
    └── System property based (default CLIENT)
```

## Files Modified/Created

### Created
- `composeApp/src/commonMain/kotlin/.../DeviceTypeDetector.kt`
- `composeApp/src/androidMain/kotlin/.../DeviceTypeDetector.android.kt`
- `composeApp/src/iosMain/kotlin/.../DeviceTypeDetector.ios.kt`
- `composeApp/src/jvmMain/kotlin/.../DeviceTypeDetector.jvm.kt`

### Modified
- `composeApp/src/androidMain/kotlin/.../AndroidBindings.kt`
- `composeApp/src/iosMain/kotlin/.../IosBindings.kt`
- `composeApp/src/jvmMain/kotlin/.../JvmBindings.kt`

## Benefits

1. **Automatic Detection** - No manual configuration needed
2. **Hardware-Based** - Reliable detection using device capabilities
3. **Cached Results** - Fast subsequent app launches
4. **Platform-Specific** - Each platform uses appropriate detection method
5. **Flexible Desktop** - Desktop can be configured as either HOST or CLIENT

## Summary

- **IFPs (Interactive Flat Panels)** → Detected automatically → **HOST** role
- **Mobile Devices (Phones/Tablets)** → Detected automatically → **CLIENT** role
- **iOS Devices** → Always → **CLIENT** role
- **Desktop** → Default **CLIENT** role (configurable to HOST)
