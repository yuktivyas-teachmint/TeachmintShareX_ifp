package com.teachmint.sharex.share.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Input events sent from a remote-control client to the host over WebRTC data channel.
 * All coordinates are **normalized** to [0.0, 1.0] relative to the captured display
 * so the host can map them to its actual screen resolution.
 */
@Serializable
sealed class RemoteInputEvent {

    /** Single tap at the given normalized position. */
    @Serializable
    @SerialName("tap")
    data class Tap(
        val x: Float,
        val y: Float,
    ) : RemoteInputEvent()

    /** Finger / pointer pressed down – starts a gesture. */
    @Serializable
    @SerialName("pointer_down")
    data class PointerDown(
        val pointerId: Int,
        val x: Float,
        val y: Float,
    ) : RemoteInputEvent()

    /** Finger / pointer moved – continues a gesture. */
    @Serializable
    @SerialName("pointer_move")
    data class PointerMove(
        val pointerId: Int,
        val x: Float,
        val y: Float,
    ) : RemoteInputEvent()

    /** Finger / pointer released – ends a gesture. */
    @Serializable
    @SerialName("pointer_up")
    data class PointerUp(
        val pointerId: Int,
        val x: Float,
        val y: Float,
    ) : RemoteInputEvent()

    /** Scroll / mouse-wheel at the given normalized position. */
    @Serializable
    @SerialName("scroll")
    data class Scroll(
        val x: Float,
        val y: Float,
        val deltaX: Float,
        val deltaY: Float,
    ) : RemoteInputEvent()

    /** Physical-keyboard key press or release. */
    @Serializable
    @SerialName("key_event")
    data class KeyEvent(
        val keyCode: Int,
        val isDown: Boolean,
    ) : RemoteInputEvent()

    /** Committed text from a soft-keyboard or IME (e.g. paste, auto-complete). */
    @Serializable
    @SerialName("text_input")
    data class TextInput(
        val text: String,
    ) : RemoteInputEvent()

    /** System-level navigation action (Back, Home, Recents, etc.). */
    @Serializable
    @SerialName("global_action")
    data class GlobalAction(
        val action: RemoteGlobalAction,
    ) : RemoteInputEvent()
}

@Serializable
enum class RemoteGlobalAction {
    @SerialName("back") Back,
    @SerialName("home") Home,
    @SerialName("recents") Recents,
    @SerialName("notifications") Notifications,
}
