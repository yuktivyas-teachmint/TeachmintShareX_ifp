# Keep Ktor ServiceLoaders and Engines
-keep class io.ktor.client.HttpClientEngineContainer { *; }
-keep class io.ktor.client.engine.** { *; }
-dontwarn io.ktor.network.sockets.SocketBase

# Keep KotlinX Serialization Providers (Fixes your exact crash)
-keep class io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider { *; }
-keep class io.ktor.serialization.kotlinx.json.KotlinxSerializationJsonExtensionProvider { *; }

# Keep standard KotlinX Serialization classes
-keepattributes *Annotation*, InnerClasses
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** {
    static <fields>;
}

# Keep Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep WebRTC classes. Some logging/bridge classes are loaded indirectly and
# can be removed by shrinking, which breaks release uber jars at runtime.
-keep class dev.onvoid.webrtc.** { *; }
-keep class dev.onvoid.webrtc.logging.** { *; }
