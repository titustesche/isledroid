package com.titus.dynamicisland.domain

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.titus.dynamicisland.data.media.MediaInfo
import com.titus.dynamicisland.data.media.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Routine sources outside the assistant: alarms set in any clock app (Android only tells us the next
 * one) and music started in any player. Whether they count is up to [HabitTracker] and its settings.
 */
class RoutineSources(
    private val context: Context,
    private val scope: CoroutineScope,
    private val media: MediaRepository,
    private val habits: HabitTracker,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private var nextAlarm: Long? = null

    fun start() {
        nextAlarm = runCatching { alarms.nextAlarmClock?.triggerTime }.getOrNull()
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = onAlarmChanged()
            },
            IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch { media.media.collect(::onMedia) }
    }

    /**
     * The next alarm changed. Only an earlier one than before, while the previous one isn't ringing, is
     * "set an alarm": dismissing or snoozing moves the next alarm too, and deleting one makes it later.
     */
    private fun onAlarmChanged() {
        val now = System.currentTimeMillis()
        // An alarm long gone without an update since is as good as none.
        val prev = nextAlarm?.takeIf { it > now - RINGING_WINDOW_MS }
        val next = runCatching { alarms.nextAlarmClock?.triggerTime }.getOrNull()
        nextAlarm = next
        if (next == null || next == prev) return
        val why = when {
            prev != null && prev <= now + RINGING_MARGIN_MS -> "previous one rang, was snoozed or dismissed"
            prev != null && next > prev -> "later than before: one was deleted or switched off"
            else -> null
        }
        if (why != null) {
            Log.d(TAG, "next alarm changed, not counted: $why")
            return
        }
        habits.onAlarmSet(next, now)
    }

    private var playingPkg: String? = null
    private var stoppedAt = 0L
    private var firstState = true
    private var pending: Job? = null

    /** A player starting after a break; the artist is read a moment later, once the track's metadata is in. */
    private fun onMedia(info: MediaInfo?) {
        val playing = info?.isPlaying == true
        if (firstState) {
            // Already playing when we started (an app update, a restart): nobody just pressed play.
            firstState = false
            if (playing) playingPkg = info?.packageName
            return
        }
        when {
            playing && playingPkg == null -> {
                playingPkg = info!!.packageName
                val afterBreak = stoppedAt == 0L || SystemClock.elapsedRealtime() - stoppedAt > MUSIC_BREAK_MS
                if (afterBreak) {
                    pending?.cancel()
                    pending = scope.launch {
                        delay(METADATA_DELAY_MS)
                        val now = media.media.value?.takeIf { it.isPlaying } ?: return@launch
                        habits.onMusicStarted(now.packageName, now.artist)
                    }
                }
            }
            !playing && playingPkg != null -> {
                playingPkg = null
                stoppedAt = SystemClock.elapsedRealtime()
            }
        }
    }

    private companion object {
        const val TAG = "RoutineSources"
        const val RINGING_WINDOW_MS = 30 * 60_000L
        const val RINGING_MARGIN_MS = 2 * 60_000L
        /** Paused for less than this and playing again: the same listening session. */
        const val MUSIC_BREAK_MS = 15 * 60_000L
        const val METADATA_DELAY_MS = 3_000L
    }
}
