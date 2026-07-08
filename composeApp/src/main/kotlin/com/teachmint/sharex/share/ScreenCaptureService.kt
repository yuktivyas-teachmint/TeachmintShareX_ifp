package com.teachmint.sharex.share.shared

import com.teachmint.sharex.R
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class ScreenCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val enteredForeground = runCatching {
            // For Android 14+, must specify FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
            // MICROPHONE is only included when RECORD_AUDIO is runtime-granted; otherwise
            // Android rejects the FGS start entirely and screen sharing would never begin.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (hasRecordAudioPermission()) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    println(
                        "SCREEN_CAPTURE_SERVICE: RECORD_AUDIO not granted; starting FGS " +
                            "without microphone type (screen sharing only)."
                    )
                }
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(),
                    fgsType,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }.onFailure { error ->
            println("SCREEN_CAPTURE_SERVICE: Failed to start foreground service: ${error.message}")
            markForegroundStartFailed()
        }.isSuccess

        if (!enteredForeground) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        markForegroundReady()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        markForegroundStopped()
        super.onDestroy()
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun buildNotification(): android.app.Notification {
        val channelId = NOTIFICATION_CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelId,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("TeachmintShareX")
            .setContentText("Screen sharing is active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "sharex_screen_capture"
        private const val NOTIFICATION_ID = 1001
        private const val FOREGROUND_READY_TIMEOUT_MS = 2500L

        @Volatile
        private var isForegroundReady: Boolean = false

        @Volatile
        private var foregroundReadySignal: CompletableDeferred<Unit> = CompletableDeferred()

        @Synchronized
        private fun prepareForStartAttempt() {
            if (!isForegroundReady && foregroundReadySignal.isCompleted) {
                foregroundReadySignal = CompletableDeferred()
            }
        }

        @Synchronized
        private fun markForegroundReady() {
            isForegroundReady = true
            if (!foregroundReadySignal.isCompleted) {
                foregroundReadySignal.complete(Unit)
            }
        }

        @Synchronized
        private fun markForegroundStartFailed() {
            isForegroundReady = false
            if (!foregroundReadySignal.isCompleted) {
                foregroundReadySignal.complete(Unit)
            }
        }

        @Synchronized
        private fun markForegroundStopped() {
            isForegroundReady = false
            foregroundReadySignal = CompletableDeferred()
        }

        fun start(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java)
            prepareForStartAttempt()
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { error ->
                println("SCREEN_CAPTURE_SERVICE: Failed to request service start: ${error.message}")
                markForegroundStartFailed()
            }
        }

        suspend fun ensureStartedAndReady(context: Context): Boolean {
            if (isForegroundReady) return true
            start(context)
            if (isForegroundReady) return true

            val readiness = foregroundReadySignal
            val completed = withTimeoutOrNull(FOREGROUND_READY_TIMEOUT_MS) {
                readiness.await()
                true
            } ?: false
            return completed && isForegroundReady
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java)
            context.stopService(intent)
            markForegroundStopped()
        }
    }
}
