package com.teachmint.sharex.share.shared

/**
 * Platform interface for injecting input events into the device's UI.
 * On Android this is implemented via AccessibilityService.
 * Other platforms provide no-op stubs (host is always Android).
 */
interface InputInjector {
    /** Whether the injector is currently connected and able to dispatch events. */
    val isAvailable: Boolean

    /** Inject a single [RemoteInputEvent] into the system. */
    fun inject(event: RemoteInputEvent)

    /** Release any resources held by the injector. */
    fun release() {}
}

/** Factory function to obtain the platform [InputInjector]. */
expect fun createInputInjector(): InputInjector
