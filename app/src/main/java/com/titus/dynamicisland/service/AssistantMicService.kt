package com.titus.dynamicisland.service

import android.Manifest
import android.app.Notification
import android.content.pm.PackageManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.titus.dynamicisland.R
import com.titus.dynamicisland.appGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the microphone available for the whole assistant conversation.
 *
 * Android only lets apps listen while they're in the foreground. Started while the assist screen is
 * up, a microphone foreground service keeps that right after the screen is gone, so the app below
 * stays live (no frozen, half-black screen) and follow-up questions still work.
 */
class AssistantMicService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            appGraph.assistant.stop("notification") // which stops this service too
            return START_NOT_STICKY
        }
        val ok = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), types())
        }.onFailure { Log.w(TAG, "Microphone service refused; the assist screen stays up instead", it) }.isSuccess
        _running.value = ok
        if (!ok) stopSelf()
        return START_NOT_STICKY
    }

    /** The microphone, plus location when allowed (weather "here" while the app is in the background). */
    private fun types(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
    }

    override fun onDestroy() {
        _running.value = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Assistant conversation", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the assistant keeps listening for follow-up questions"
                    setShowBadge(false)
                },
            )
        }
        val end = PendingIntent.getService(
            this, 0, Intent(this, AssistantMicService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_assistant_mic)
            .setContentTitle("Assistant conversation")
            .setContentText("Listening for follow-ups. Swipe the island up to end.")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "End", end)
            .build()
    }

    companion object {
        private const val TAG = "AssistantMicService"
        private const val CHANNEL = "assistant"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_END = "com.titus.dynamicisland.END_CONVERSATION"

        private val _running = MutableStateFlow(false)
        /** In the foreground with microphone access. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Call while one of our activities is in front, or Android refuses the microphone. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, AssistantMicService::class.java)) }
                .onFailure { Log.w(TAG, "Couldn't start the microphone service", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AssistantMicService::class.java))
        }
    }
}
