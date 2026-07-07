# Project-level release keep rules.
# Keep these tight and intentional; broad keep rules reduce shrink benefits.

# JNI bridge depends on exact class/method names in native symbol lookup.
-keep class com.example.teachmintsharex.share.miracast.rtp.NativeRtpReceiver { *; }

# JNI callback looks up this method by name/signature at runtime.
-keepclassmembers class com.example.teachmintsharex.share.miracast.rtp.NativeRtpReceiver$* {
    void onH264Data(byte[], long, int, boolean);
}

# F-007: Targeted WebRTC keep rules — only preserve public API and JNI-sensitive classes.
# Avoids exposing all internal WebRTC classes to reverse engineering.
-keep class org.webrtc.PeerConnection { *; }
-keep class org.webrtc.PeerConnection$* { *; }
-keep class org.webrtc.PeerConnectionFactory { *; }
-keep class org.webrtc.PeerConnectionFactory$* { *; }
-keep class org.webrtc.SessionDescription { *; }
-keep class org.webrtc.SessionDescription$* { *; }
-keep class org.webrtc.IceCandidate { *; }
-keep class org.webrtc.MediaStream { *; }
-keep class org.webrtc.MediaStreamTrack { *; }
-keep class org.webrtc.VideoTrack { *; }
-keep class org.webrtc.AudioTrack { *; }
-keep class org.webrtc.DataChannel { *; }
-keep class org.webrtc.DataChannel$* { *; }
-keep class org.webrtc.RtpSender { *; }
-keep class org.webrtc.RtpReceiver { *; }
-keep class org.webrtc.RtpTransceiver { *; }
-keep class org.webrtc.RtpTransceiver$* { *; }
-keep class org.webrtc.SdpObserver { *; }
-keep class org.webrtc.MediaConstraints { *; }
-keep class org.webrtc.MediaConstraints$* { *; }
-keep class org.webrtc.VideoSource { *; }
-keep class org.webrtc.VideoSink { *; }
-keep class org.webrtc.EglBase { *; }
-keep class org.webrtc.EglBase$* { *; }
-keep class org.webrtc.SurfaceTextureHelper { *; }
-keep class org.webrtc.ScreenCapturerAndroid { *; }
-keep class org.webrtc.DefaultVideoEncoderFactory { *; }
-keep class org.webrtc.DefaultVideoDecoderFactory { *; }
-keep class org.webrtc.SoftwareVideoEncoderFactory { *; }
-keep class org.webrtc.SoftwareVideoDecoderFactory { *; }
# Keep JNI native methods that are looked up by name
-keepclassmembers class org.webrtc.** { native <methods>; }
-keep class org.webrtc.Logging { *; }
-keep class org.webrtc.NativeLibrary { *; }

# Preserve annotation/signature metadata used by libraries.
-keepattributes Signature,*Annotation*

# composeApp classes referenced by MainActivity — keep from app-level R8 pass.
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

# Suppress missing class warnings for classes kept above.
-dontwarn com.example.teachmintsharex.share.miracast.MiracastPlaybackManager
-dontwarn com.teachmint.sharex.AppKt
-dontwarn com.teachmint.sharex.share.shared.**

# Ktor pulls a JVM-only debug detector type that references java.lang.management.
# Android runtime doesn't provide these classes; suppress as recommended by R8.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Tink (pulled in via androidx.security:security-crypto for EncryptedSharedPreferences)
# references compile-only errorprone annotations that aren't on the runtime classpath.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Firebase Remote Config
-keep class com.teachmint.sharex.remoteconfig.** { *; }
