package com.teachmint.sharex.share.miracast

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.example.teachmintsharex.share.miracast.MiracastPlaybackManager
import com.example.teachmintsharex.share.miracast.MiracastPlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dedicated full-screen view where Miracast stream is rendered.
 */
class MiracastReceiverActivity : Activity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusJob: Job? = null

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            MiracastPlaybackManager.attachSurface(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            MiracastPlaybackManager.attachSurface(holder.surface)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            MiracastPlaybackManager.detachSurface(holder.surface)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = SurfaceView(this).apply {
            holder.addCallback(surfaceCallback)
            keepScreenOn = true
        }

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(32, 24, 32, 24)
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            text = "Waiting for Miracast stream..."
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                surfaceView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                statusText,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START,
                ),
            )
        }

        setContentView(root)
        MiracastPlaybackManager.registerViewer(this)
    }

    override fun onStart() {
        super.onStart()
        statusJob?.cancel()
        statusJob = uiScope.launch {
            while (true) {
                val snapshot = MiracastPlaybackManager.state.value
                statusText.text = buildStatusText(snapshot)

                if (snapshot.session == null && !snapshot.isSessionActive) {
                    // Session ended, close this screen if it was launched only for Miracast preview.
                    finish()
                    break
                }

                delay(1_000)
            }
        }
    }

    override fun onStop() {
        statusJob?.cancel()
        statusJob = null
        super.onStop()
    }

    override fun onDestroy() {
        surfaceView.holder.removeCallback(surfaceCallback)
        MiracastPlaybackManager.detachSurface(null)
        if (isFinishing) {
            MiracastPlaybackManager.stopSession(closeViewer = false)
        }
        MiracastPlaybackManager.unregisterViewer(this)
        uiScope.cancel()
        super.onDestroy()
    }

    private fun buildStatusText(state: MiracastPlaybackState): String {
        val session = state.session
        if (session == null) {
            return "Miracast session ended"
        }

        val lastFrameAge = state.lastFrameAtElapsedMs?.let { now ->
            val ageMs = (android.os.SystemClock.elapsedRealtime() - now).coerceAtLeast(0)
            "${ageMs}ms ago"
        } ?: "--"

        val error = state.lastError?.let { "\nError: $it" } ?: ""

        return (
            "Miracast Receiver\n" +
                "Source: ${session.clientAddress}\n" +
                "RTP Port: ${session.rtpPort}\n" +
                "Packets: ${state.receivedRtpPackets}\n" +
                "Samples: ${state.extractedVideoSamples}\n" +
                "Decoded Frames: ${state.decodedFrames}\n" +
                "Last Frame: $lastFrameAge" +
                error
            )
    }
}
