# Consumer rules applied when composeApp is consumed by an Android app.

# JNI bridge uses named symbol binding for this class.
-keep class com.example.teachmintsharex.share.miracast.rtp.NativeRtpReceiver { *; }

# Native code resolves this callback method by exact signature.
-keepclassmembers class com.example.teachmintsharex.share.miracast.rtp.NativeRtpReceiver$* {
    void onH264Data(byte[], long, int, boolean);
}

# WebRTC JNI integration is sensitive to obfuscation.
-keep class org.webrtc.** { *; }

# Classes referenced by androidApp's MainActivity — R8 cannot see cross-module usage
# when the library is minified independently.
-keep class com.example.teachmintsharex.share.miracast.MiracastPlaybackManager { *; }
-keep class com.teachmint.sharex.AppKt { *; }
-keep class com.teachmint.sharex.share.shared.AndroidBindingsKt { *; }
-keep class com.teachmint.sharex.share.shared.AndroidContextHolder { *; }
-keep class com.teachmint.sharex.share.shared.AndroidRoleHolder { *; }
-keep class com.teachmint.sharex.share.shared.DeviceRole { *; }
-keep class com.teachmint.sharex.share.shared.DeviceTypeDetectorKt { *; }
-keep class com.teachmint.sharex.share.shared.MiracastAdvertiserService { *; }
-keep class com.teachmint.sharex.share.shared.MiracastAdvertiserService$Companion { *; }
-keep class com.teachmint.sharex.share.shared.MultiWindowMode_androidKt { *; }

# Screen capture exception classes caught by type in DefaultClientController.
-keep class com.teachmint.sharex.share.shared.ScreenCapturePermissionRequired { *; }
-keep class com.teachmint.sharex.share.shared.ScreenCaptureNotSupported { *; }

# Sealed class used in `is` checks throughout navigation and controller code.
-keep class com.teachmint.sharex.share.shared.ScreenCaptureState { *; }
-keep class com.teachmint.sharex.share.shared.ScreenCaptureState$* { *; }

# Foreground service instantiated by Android service manager.
-keep class com.teachmint.sharex.share.shared.ScreenCaptureService { *; }
-keep class com.teachmint.sharex.share.shared.ScreenCaptureService$Companion { *; }
