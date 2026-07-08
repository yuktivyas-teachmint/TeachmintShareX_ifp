package com.teachmint.sharex.share.shared

/**
 * Android [InputInjector] that delegates to [RemoteControlAccessibilityService].
 * The service must be enabled by the user in Settings > Accessibility before
 * [isAvailable] returns true.
 */
class AndroidInputInjector : InputInjector {

    override val isAvailable: Boolean
        get() = RemoteControlAccessibilityService.instance != null

    // Active gesture trackers per pointer id
    private val activeGestures = mutableMapOf<Int, RemoteControlAccessibilityService.GestureTracker>()

    override fun inject(event: RemoteInputEvent) {
        val service = RemoteControlAccessibilityService.instance
        if (service == null) {
            println("REMOTE_CONTROL: AccessibilityService not available; dropping event")
            return
        }

        val (screenWidth, screenHeight) = service.getScreenSize()
        val coords = when (event) {
            is RemoteInputEvent.Tap -> "(${event.x * screenWidth}, ${event.y * screenHeight}) norm=(${event.x},${event.y})"
            is RemoteInputEvent.PointerDown -> "(${event.x * screenWidth}, ${event.y * screenHeight}) norm=(${event.x},${event.y})"
            is RemoteInputEvent.PointerMove -> "(${event.x * screenWidth}, ${event.y * screenHeight}) norm=(${event.x},${event.y})"
            is RemoteInputEvent.PointerUp -> "(${event.x * screenWidth}, ${event.y * screenHeight}) norm=(${event.x},${event.y})"
            is RemoteInputEvent.Scroll -> "(${event.x * screenWidth}, ${event.y * screenHeight}) d=(${event.deltaX},${event.deltaY})"
            else -> ""
        }
        println("REMOTE_CONTROL: inject ${event::class.simpleName} $coords screen=${screenWidth}x${screenHeight}")

        when (event) {
            is RemoteInputEvent.Tap -> {
                val sx = event.x * screenWidth
                val sy = event.y * screenHeight
                if (!service.clickAtScreenCoord(sx, sy)) {
                    service.tap(sx, sy)
                }
            }

            is RemoteInputEvent.PointerDown -> {
                val sx = event.x * screenWidth
                val sy = event.y * screenHeight
                val tracker = service.pointerDown(sx, sy)
                activeGestures[event.pointerId] = tracker
            }

            is RemoteInputEvent.PointerMove -> {
                val sx = event.x * screenWidth
                val sy = event.y * screenHeight
                activeGestures[event.pointerId]?.move(sx, sy)
            }

            is RemoteInputEvent.PointerUp -> {
                val sx = event.x * screenWidth
                val sy = event.y * screenHeight
                activeGestures.remove(event.pointerId)?.up(sx, sy)
            }

            is RemoteInputEvent.Scroll -> {
                val sx = event.x * screenWidth
                val sy = event.y * screenHeight
                service.scroll(sx, sy, event.deltaX, event.deltaY)
            }

            is RemoteInputEvent.GlobalAction -> {
                service.globalAction(event.action)
            }

            is RemoteInputEvent.KeyEvent -> {
                // Keyboard injection via AccessibilityService is limited.
                // Could use AccessibilityNodeInfo.ACTION_SET_TEXT for focused fields.
                println("REMOTE_CONTROL: KeyEvent not yet implemented via AccessibilityService")
            }

            is RemoteInputEvent.TextInput -> {
                // Could target focused field with ACTION_SET_TEXT.
                println("REMOTE_CONTROL: TextInput not yet implemented via AccessibilityService")
            }
        }
    }

    override fun release() {
        activeGestures.clear()
    }
}

fun createInputInjector(): InputInjector = AndroidInputInjector()
