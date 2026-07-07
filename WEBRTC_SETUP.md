# WebRTC Native Libraries Setup for Desktop

## Status: ✅ FIXED

The WebRTC native libraries are now automatically configured in the build system. You don't need to run any setup scripts!

## Running the Desktop App

Simply run:

```bash
./gradlew :composeApp:run -Dteachmint.device.role=CLIENT
```

The platform-specific native libraries (macOS ARM64, macOS x86_64, Linux, Windows) are automatically downloaded and included by Gradle.

## Manual Setup

If the script doesn't work, follow these manual steps:

### For macOS

1. **Download the native library:**
   ```bash
   mkdir -p webrtc-natives
   cd webrtc-natives

   # For Intel Macs:
   curl -L https://repo1.maven.org/maven2/dev/onvoid/webrtc/webrtc-java/0.14.0/webrtc-java-0.14.0-macos-x86_64.jar -o webrtc-natives.jar

   # For Apple Silicon (M1/M2/M3):
   curl -L https://repo1.maven.org/maven2/dev/onvoid/webrtc/webrtc-java/0.14.0/webrtc-java-0.14.0-macos-aarch64.jar -o webrtc-natives.jar

   # Extract the JAR:
   jar xf webrtc-natives.jar
   cd ..
   ```

2. **Run with native library path:**
   ```bash
   ./gradlew :composeApp:run -Dteachmint.device.role=CLIENT -Djava.library.path=./webrtc-natives
   ```

### For Linux

1. **Download the native library:**
   ```bash
   mkdir -p webrtc-natives
   cd webrtc-natives
   curl -L https://repo1.maven.org/maven2/dev/onvoid/webrtc/webrtc-java/0.14.0/webrtc-java-0.14.0-linux-x86_64.jar -o webrtc-natives.jar
   jar xf webrtc-natives.jar
   cd ..
   ```

2. **Run with native library path:**
   ```bash
   ./gradlew :composeApp:run -Dteachmint.device.role=CLIENT -Djava.library.path=./webrtc-natives
   ```

### For Windows

1. **Download the native library:**
   - Go to: https://repo1.maven.org/maven2/dev/onvoid/webrtc/webrtc-java/0.14.0/
   - Download `webrtc-java-0.14.0-windows-x86_64.jar`
   - Extract it to a `webrtc-natives` folder in your project root

2. **Run with native library path:**
   ```powershell
   .\gradlew.bat :composeApp:run -Dteachmint.device.role=CLIENT -Djava.library.path=.\webrtc-natives
   ```

## Verify It Works

After setup, when you run the client and click "Start Mirroring", you should see:

```
=== Attempting to initialize WebRTC ===
Initializing WebRTC for desktop...
Platform: Mac OS X arm64
WebRTC initialized successfully!
WEBRTC_ENGINE: Starting screen capture...
WEBRTC_ENGINE: Found X desktop sources
WEBRTC_ENGINE: ✅ Video capture started successfully
```

Instead of the error about WebRTC not being available.

## Alternative: Use Android for Screen Sharing

If desktop WebRTC setup is too complex, you can use the Android app for screen sharing:

```bash
./gradlew :androidApp:installDebug
```

Android has better WebRTC support out of the box and doesn't require manual native library setup.

## Troubleshooting

### Still getting "WebRTC not available"?

1. **Check the console output** - Look for the detailed error message after "❌ WebRTC initialization failed!"

2. **Verify native libraries are extracted**:
   ```bash
   ls -la webrtc-natives/
   ```
   You should see `.dylib` files (macOS), `.so` files (Linux), or `.dll` files (Windows)

3. **Check library path**:
   ```bash
   java -XshowSettings:properties 2>&1 | grep java.library.path
   ```

### macOS: "Library not loaded" error

Grant screen recording permission:
- System Settings → Privacy & Security → Screen Recording
- Add Terminal or your IDE to the list

### Linux: Missing dependencies

Install required dependencies:
```bash
sudo apt-get install libgtk-3-0 libglib2.0-0 libasound2
```

## Need Help?

1. Run with detailed error logging and share the output:
   ```bash
   ./gradlew :composeApp:run -Dteachmint.device.role=CLIENT --stacktrace
   ```

2. Check the WebRTC initialization logs at startup

3. Verify your OS and architecture are supported by webrtc-java:
   - macOS x86_64 ✅
   - macOS arm64 (Apple Silicon M1/M2/M3) ✅
   - Linux x86_64 ✅
   - Windows x86_64 ✅
