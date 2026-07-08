package com.teachmint.sharex.share.shared

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

private val GESTURE_RESULT_CALLBACK = object : AccessibilityService.GestureResultCallback() {
    override fun onCompleted(gestureDescription: GestureDescription?) {
        println("REMOTE_CONTROL: gesture COMPLETED")
    }
    override fun onCancelled(gestureDescription: GestureDescription?) {
        println("REMOTE_CONTROL: gesture CANCELLED — touch likely blocked (obscured / FLAG_SECURE / hidden-overlay filter)")
    }
}

/**
 * AccessibilityService that injects remote-control input events into the
 * Android UI on behalf of a connected client. The service must be manually
 * enabled by the user in Settings > Accessibility.
 *
 * It is a singleton: [instance] is set when the system binds the service
 * and cleared when it disconnects.
 */
class RemoteControlAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        println("REMOTE_CONTROL: AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't consume external accessibility events.
    }

    override fun onInterrupt() {
        // Nothing to clean up.
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        println("REMOTE_CONTROL: AccessibilityService destroyed")
    }

    // ── Public API ──────────────────────────────────────────────────────

    /** Dispatch a single tap at screen coordinates. */
    fun tap(screenX: Float, screenY: Float) {
        val path = Path().apply { moveTo(screenX, screenY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, GESTURE_RESULT_CALLBACK, null)
    }

    /**
     * Try clicking the deepest clickable node at the given screen coordinates via
     * AccessibilityNodeInfo.ACTION_CLICK. Returns true if a click was performed.
     *
     * This is the fallback for views (e.g. DocumentsUI file rows) that filter
     * accessibility-injected MotionEvents at the click-listener level: dispatchGesture
     * synthesizes touches that draw ripples but never fire onClick.
     */
    fun clickAtScreenCoord(screenX: Float, screenY: Float): Boolean {
        val root = rootInActiveWindow ?: run {
            println("REMOTE_CONTROL: clickAtScreenCoord — no rootInActiveWindow")
            return false
        }
        val x = screenX.toInt()
        val y = screenY.toInt()
        val target = findDeepestClickableNode(root, x, y)
        if (target == null) {
            println("REMOTE_CONTROL: clickAtScreenCoord — no clickable node at ($x,$y)")
            return false
        }
        val targetBounds = Rect().also { target.getBoundsInScreen(it) }
        val (screenWidth, screenHeight) = getScreenSize()
        val screenArea = (screenWidth * screenHeight).coerceAtLeast(1)
        val targetArea = (targetBounds.width() * targetBounds.height()).coerceAtLeast(0)
        val className = target.className?.toString().orEmpty()
        val isGenericContainer = className in setOf(
            "android.view.View",
            "android.view.ViewGroup",
            "android.widget.FrameLayout",
            "android.widget.LinearLayout",
            "android.widget.RelativeLayout",
        )
        val isOversizedTarget = targetArea > (screenArea * 0.60f)
        if (isGenericContainer && isOversizedTarget) {
            println(
                "REMOTE_CONTROL: clickAtScreenCoord ignoring broad container '$className' " +
                    "bounds=$targetBounds at ($x,$y), falling back to gesture tap",
            )
            return false
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        println(
            "REMOTE_CONTROL: clickAtScreenCoord ACTION_CLICK at ($x,$y) " +
                "on '${target.className}' bounds=$targetBounds result=$ok",
        )
        return ok
    }

    private fun findDeepestClickableNode(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y) || !node.isVisibleToUser) return null
        // Traverse from topmost child to bottommost so overlapping layouts prefer
        // the visually front-most clickable node.
        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChild(i) ?: continue
            val match = findDeepestClickableNode(child, x, y)
            if (match != null) return match
        }
        return if (node.isClickable) node else null
    }

    /** Begin a swipe/drag gesture at the given coordinates. Returns a stroke that can be continued. */
    fun pointerDown(screenX: Float, screenY: Float): GestureTracker {
        return GestureTracker(screenX, screenY)
    }

    /** Perform a global action (Back, Home, Recents, Notifications). */
    fun globalAction(action: RemoteGlobalAction) {
        val androidAction = when (action) {
            RemoteGlobalAction.Back -> GLOBAL_ACTION_BACK
            RemoteGlobalAction.Home -> GLOBAL_ACTION_HOME
            RemoteGlobalAction.Recents -> GLOBAL_ACTION_RECENTS
            RemoteGlobalAction.Notifications -> GLOBAL_ACTION_NOTIFICATIONS
        }
        performGlobalAction(androidAction)
    }

    /** Dispatch a scroll gesture at the given screen coordinates. */
    fun scroll(screenX: Float, screenY: Float, deltaX: Float, deltaY: Float) {
        val path = Path().apply {
            moveTo(screenX, screenY)
            lineTo(screenX - deltaX * SCROLL_PIXEL_MULTIPLIER, screenY - deltaY * SCROLL_PIXEL_MULTIPLIER)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, SCROLL_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, GESTURE_RESULT_CALLBACK, null)
    }

    /** Get the current screen dimensions for coordinate mapping. */
    fun getScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    /**
     * Tracks an ongoing multi-step gesture (pointer down → moves → up).
     * Uses [GestureDescription] continuation on API 26+ for smooth swipes,
     * or falls back to a single-stroke drag on older APIs.
     */
    inner class GestureTracker(startX: Float, startY: Float) {
        private val points = mutableListOf(startX to startY)

        fun move(screenX: Float, screenY: Float) {
            points.add(screenX to screenY)
        }

        fun up(screenX: Float, screenY: Float) {
            points.add(screenX to screenY)
            dispatchFullGesture()
        }

        private fun dispatchFullGesture() {
            if (points.size < 2) {
                val (x, y) = points.first()
                tap(x, y)
                return
            }
            val (sx, sy) = points.first()
            val maxDeltaSq = points.maxOf { (px, py) ->
                val dx = px - sx
                val dy = py - sy
                dx * dx + dy * dy
            }
            if (maxDeltaSq <= TAP_SLOP_PX * TAP_SLOP_PX) {
                val (ex, ey) = points.last()
                if (clickAtScreenCoord(ex, ey)) return
                tap(ex, ey)
                return
            }
            val path = Path().apply {
                moveTo(sx, sy)
                for (i in 1 until points.size) {
                    val (px, py) = points[i]
                    lineTo(px, py)
                }
            }
            val duration = (points.size * SWIPE_POINT_DURATION_MS).coerceIn(
                SWIPE_MIN_DURATION_MS, SWIPE_MAX_DURATION_MS,
            )
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, GESTURE_RESULT_CALLBACK, null)
        }
    }

    companion object {
        @Volatile
        var instance: RemoteControlAccessibilityService? = null
            private set

        private const val TAP_DURATION_MS = 50L
        private const val SCROLL_DURATION_MS = 200L
        private const val SCROLL_PIXEL_MULTIPLIER = 100f
        private const val SWIPE_POINT_DURATION_MS = 16L
        private const val SWIPE_MIN_DURATION_MS = 100L
        private const val SWIPE_MAX_DURATION_MS = 1_000L
        private const val TAP_SLOP_PX = 16f
    }
}
