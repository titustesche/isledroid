package com.titus.dynamicisland.data.media

import android.app.PendingIntent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap

enum class RepeatMode { OFF, ALL, ONE }

/** A PlaybackState custom action with its icon loaded from the player app's resources. */
data class MediaCustomAction(val action: String, val name: String, val icon: ImageBitmap?)

/** Snapshot of the currently relevant media session. */
data class MediaInfo(
    val packageName: String,
    val title: String,
    val artist: String?,
    val art: Bitmap?,
    val accentColor: Int?,
    val isPlaying: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    /** `SystemClock.elapsedRealtime()` at which [positionMs] was reported. */
    val positionUpdatedAt: Long,
    val playbackSpeed: Float,
    val sessionActivity: PendingIntent?,
    /** App-defined buttons (shuffle, repeat, favorite …), as Android's own media controls show them. */
    val customActions: List<MediaCustomAction> = emptyList(),
    /** Null when the player doesn't support shuffle. */
    val shuffle: Boolean? = null,
    /** Null when the player doesn't support repeat. */
    val repeat: RepeatMode? = null,
) {
    /** Extrapolates the playback position, since sessions only report it on state changes. */
    fun positionAt(elapsedRealtime: Long): Long {
        if (!isPlaying || positionUpdatedAt <= 0L) return positionMs.coerceAtLeast(0)
        val advanced = positionMs + ((elapsedRealtime - positionUpdatedAt) * playbackSpeed).toLong()
        return if (durationMs > 0) advanced.coerceIn(0, durationMs) else advanced.coerceAtLeast(0)
    }
}
