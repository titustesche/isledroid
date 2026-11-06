package com.titus.dynamicisland.service

import android.app.Notification
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
import com.titus.dynamicisland.R
import com.titus.dynamicisland.appGraph
import com.titus.dynamicisland.data.system.Geo

/**
 * Keeps location coming while the island's map is up. Android only gives "while in use" location
 * to apps in the foreground; the island alone doesn't count, a location foreground service does.
 * Started while the assistant is in front (its microphone service), so Android allows it.
 */
class MapLocationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) {
            appGraph.map.close() // which stops this service too
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        val ok = Geo.hasAny(this) && runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type) }
            .onFailure { Log.w(TAG, "Location service refused; the map gets whatever fixes Android still hands out", it) }
            .isSuccess
        if (!ok) stopSelf()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Map in the island", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the island's map follows your position"
                    setShowBadge(false)
                },
            )
        }
        val close = PendingIntent.getService(
            this, 0, Intent(this, MapLocationService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val target = appGraph.map.target.value?.label ?: "the place"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_map_pin)
            .setContentTitle("Showing the way to $target")
            .setContentText("The island follows your position. Long-press it to close.")
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Close map", close)
            .build()
    }

    companion object {
        private const val TAG = "MapLocationService"
        private const val CHANNEL = "map"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_CLOSE = "close"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, MapLocationService::class.java)) }
                .onFailure { Log.w(TAG, "Couldn't start", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MapLocationService::class.java))
        }
    }
}
