# Platform Build & Runtime Notes

## Build Status

✅ **iOS**: Builds successfully without errors
✅ **Desktop (JVM)**: Builds successfully with improved error handling
❓ **Runtime**: Desktop WebRTC requires native library loading

## iOS Platform

The iOS app builds and compiles successfully. It uses the `webrtc-kmp` library which provides native WebRTC support for iOS.

**Dependencies:**
- `webrtc-kmp` v0.125.11
- Native WebRTC framework included via Swift Package Manager

## Desktop (JVM) Platform

### Fixed Issues

1. **Added WebRTC initialization with better error handling**
   - Location: `composeApp/src/jvmMain/kotlin/com/example/teachmintsharex/share/WebRtcEngine.jvm.kt`
   - Added diagnostic logging to help debug native library loading issues
   - Improved error messages with platform information

2. **Enhanced main entry point**
   - Location: `composeApp/src/jvmMain/kotlin/com/example/teachmintsharex/main.kt`
   - Added early WebRTC class loading to trigger native library initialization
   - Graceful error handling with warnings instead of crashes

3. **Updated Gradle build configuration**
   - Location: `composeApp/build.gradle.kts`
   - Added JVM arguments for native library loading
   - Configured to include all modules in distribution

### Dependencies

- `webrtc-java` v0.14.0
- Native libraries for macOS, Windows, and Linux (bundled in JAR)

### Potential Runtime Issues

The desktop version uses `webrtc-java` which relies on JNI (Java Native Interface) to load platform-specific native libraries. You may encounter the following:

**Error:** `Load library 'webrtc-java' failed`

**Causes:**
- Native libraries not compatible with your OS/architecture
- Missing system dependencies (on Linux)
- JVM unable to extract or load native libraries

**Solutions:**

1. **Check Platform Support:**
   ```bash
   # The library should work on:
   - macOS (x86_64, arm64)
   - Windows (x86_64)
   - Linux (x86_64)
   ```

2. **Run with diagnostic logging:**
   ```bash
   ./gradlew :composeApp:run
   # Check console output for WebRTC initialization messages
   ```

3. **If native loading fails:**
   - The app will still start but screen sharing won't work
   - You'll see a warning message in the console
   - The error is handled gracefully to prevent crashes

4. **Linux-specific:** Install required dependencies:
   ```bash
   # Ubuntu/Debian
   sudo apt-get install libgtk-3-0 libx11-dev libxtst-dev

   # Fedora/RHEL
   sudo dnf install gtk3 libX11-devel libXtst-devel
   ```

## Running the Apps

### iOS
```bash
# Open in Xcode
open iosApp/iosApp.xcodeproj

# Or build from command line
cd iosApp
xcodebuild -project iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator build
```

### Desktop
```bash
# Run in development mode
./gradlew :composeApp:run

# Create distributable package
./gradlew :composeApp:packageDistributionForCurrentOS

# The package will be in:
# composeApp/build/compose/binaries/main/[dmg|msi|deb]/
```

## Known Limitations

1. **Desktop WebRTC:** Native library compatibility varies by platform
2. **iOS Screen Capture:** Limited by iOS privacy restrictions (uses camera fallback)
3. **Cross-platform consistency:** Different WebRTC implementations (webrtc-java vs webrtc-kmp)

## Troubleshooting

### Desktop won't start
- Check console output for detailed error messages
- Verify Java version: `java -version` (requires Java 11+)

### Screen sharing doesn't work
- **Desktop:** Check native library loading messages
- **iOS:** Ensure camera permissions are granted
- **All platforms:** Check network connectivity for signaling

### Build fails
- Clean build: `./gradlew clean`
- Invalidate caches: `./gradlew --stop && rm -rf .gradle build */build`
- Update dependencies: `./gradlew --refresh-dependencies`

## Future Improvements

Consider migrating desktop to use a pure-JVM WebRTC implementation or exploring these alternatives:
- `jitsi-webrtc` - More actively maintained
- Custom WebRTC implementation using Ktor + media libraries
- Web-based approach using Compose for Web
