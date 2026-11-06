package com.titus.dynamicisland.data.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.titus.dynamicisland.data.assistant.GeoPoint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun hasFine(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasAny(context: Context) = hasFine(context) ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Where the phone is now, as precisely as allowed: a fresh GPS fix if it comes within
     * [timeoutS], otherwise the best recent one. Blocks; call off the main thread. Null without permission or fix.
     */
    @SuppressLint("MissingPermission")
    fun current(context: Context, timeoutS: Long = 12): Location? {
        if (!hasAny(context)) return null
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = lm.getProviders(true)
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 2 * 60_000L }
            .minByOrNull { it.accuracy }
        if (recent != null && recent.accuracy <= 25f) return recent
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return recent
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { it in providers } ?: return recent
        val latch = CountDownLatch(1)
        var result: Location? = null
        val cancel = CancellationSignal()
        val executor = Executors.newSingleThreadExecutor()
        try {
            lm.getCurrentLocation(provider, cancel, executor) { result = it; latch.countDown() }
            if (!latch.await(timeoutS, TimeUnit.SECONDS)) cancel.cancel()
        } finally {
            executor.shutdown()
        }
        return result ?: recent
    }

    fun Location.toPoint() = GeoPoint(latitude, longitude, if (hasAccuracy()) accuracy else null)

    /** Great-circle distance in metres. */
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Direction from [a] to [b], degrees clockwise from true north. */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val la = Math.toRadians(a.lat)
        val lb = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lb)
        val x = cos(la) * sin(lb) - sin(la) * cos(lb) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** "350 m", "1.2 km" (spoken answers spell numbers out themselves; this is for the screen). */
    fun distanceLabel(m: Double): String = when {
        m < 10 -> "here"
        m < 1000 -> "${(m / 5).toInt() * 5} m"
        else -> String.format(java.util.Locale.getDefault(), "%.1f km", m / 1000)
    }
}
