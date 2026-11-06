package com.titus.dynamicisland.data.assistant

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.abs

/** Something the user did once: started a route, opened an app, had the assistant play music … */
data class HabitEvent(
    /** What counts as "the same thing", e.g. "navigate:work" or "app:com.spotify.music". */
    val key: String,
    /** How to say it: "started a route to Work", "opened Spotify". */
    val label: String,
    val at: Long,
)

/** A routine: [label] happened on [days] different days around [minuteOfDay]. */
data class Habit(
    val key: String,
    val label: String,
    val minuteOfDay: Int,
    val days: Int,
    /** Only on weekdays / only on weekends, or null when it doesn't matter. */
    val weekend: Boolean?,
) {
    val time get() = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}

data class HabitRules(
    val windowMin: Int,
    val minDays: Int,
    val lookbackDays: Int,
    val splitWeekends: Boolean,
)

/** Pure: finds routines in a history of events. */
object Habits {

    /**
     * Routines that fit [now]: done on at least [HabitRules.minDays] different days within
     * [HabitRules.windowMin] minutes of this time of day (on the same kind of day, if split).
     * Most frequent first.
     */
    fun fitting(events: List<HabitEvent>, now: Long, rules: HabitRules, zone: TimeZone = TimeZone.getDefault()): List<Habit> {
        val nowMinute = minuteOfDay(now, zone)
        val nowWeekend = isWeekend(now, zone)
        return all(events, now, rules, zone).filter { h ->
            circularDistance(h.minuteOfDay, nowMinute) <= rules.windowMin && (h.weekend == null || h.weekend == nowWeekend)
        }
    }

    /** Every routine in the history, whatever the time now. */
    fun all(events: List<HabitEvent>, now: Long, rules: HabitRules, zone: TimeZone = TimeZone.getDefault()): List<Habit> {
        val since = now - rules.lookbackDays * DAY_MS
        val out = mutableListOf<Habit>()
        events.filter { it.at in since..now }.groupBy { it.key }.forEach { (key, list) ->
            val groups = if (rules.splitWeekends) list.groupBy { isWeekend(it.at, zone) }.map { (we, l) -> we to l } else listOf(null to list)
            groups.forEach { (weekend, evs) ->
                clusters(evs, rules.windowMin, zone).forEach { cluster ->
                    val days = cluster.map { dayIndex(it.at, zone) }.distinct().size
                    val minutes = cluster.map { minuteOfDay(it.at, zone) }.sorted()
                    // Something done all day long (a chat app) chains into one wide cluster: that's no routine.
                    val spread = spread(minutes)
                    // An app opened every hour has a "cluster" at each hour; a routine is most of what happens.
                    val share = cluster.size.toFloat() / evs.size
                    if (days >= rules.minDays && spread <= rules.windowMin * MAX_SPREAD_WINDOWS && share >= MIN_SHARE) {
                        out += Habit(key, cluster.maxBy { it.at }.label, typical(minutes), days, weekend)
                    }
                }
            }
        }
        // What the user did on purpose (routes, music, actions) before apps that were merely opened.
        return out.sortedWith(compareBy<Habit> { it.key.startsWith("app:") }.thenByDescending { it.days })
    }

    /** Minutes covered, the short way round the clock (a cluster may straddle midnight). */
    private fun spread(sorted: List<Int>): Int {
        if (sorted.size < 2) return 0
        val gaps = sorted.zipWithNext { a, b -> b - a } + (sorted.first() + 24 * 60 - sorted.last())
        return 24 * 60 - gaps.max()
    }

    /** The median time, measured from the start of the cluster so 23:50 and 00:10 average to midnight. */
    private fun typical(sorted: List<Int>): Int {
        if (sorted.size < 2) return sorted.firstOrNull() ?: 0
        val gaps = sorted.zipWithNext { a, b -> b - a } + (sorted.first() + 24 * 60 - sorted.last())
        val startIndex = (gaps.indices.maxBy { gaps[it] } + 1) % sorted.size
        val shifted = sorted.map { (it - sorted[startIndex] + 24 * 60) % (24 * 60) }.sorted()
        return (shifted[shifted.size / 2] + sorted[startIndex]) % (24 * 60)
    }

    /** Groups events whose times of day are close (single-link, at most [windowMin] apart), so 7:30 and 18:00 stay apart. */
    private fun clusters(events: List<HabitEvent>, windowMin: Int, zone: TimeZone): List<List<HabitEvent>> {
        val sorted = events.sortedBy { minuteOfDay(it.at, zone) }
        val out = mutableListOf<MutableList<HabitEvent>>()
        for (e in sorted) {
            val last = out.lastOrNull()
            if (last != null && minuteOfDay(e.at, zone) - minuteOfDay(last.last().at, zone) <= windowMin) last += e
            else out += mutableListOf(e)
        }
        // Around midnight: the first and last clusters may be one.
        if (out.size > 1 && circularDistance(minuteOfDay(out.first().first().at, zone), minuteOfDay(out.last().last().at, zone)) <= windowMin) {
            out.first().addAll(out.removeAt(out.lastIndex))
        }
        return out
    }

    private fun calendar(t: Long, zone: TimeZone) = Calendar.getInstance(zone).apply { timeInMillis = t }

    fun minuteOfDay(t: Long, zone: TimeZone): Int = calendar(t, zone).let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    private fun isWeekend(t: Long, zone: TimeZone) = calendar(t, zone).get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }

    private fun dayIndex(t: Long, zone: TimeZone) = (t + zone.getOffset(t)) / DAY_MS

    private fun circularDistance(a: Int, b: Int): Int = abs(a - b).let { minOf(it, 24 * 60 - it) }

    const val DAY_MS = 24 * 60 * 60_000L
    private const val MAX_SPREAD_WINDOWS = 3
    private const val MIN_SHARE = 0.25f
}

/**
 * The history routines are learned from, kept only on this phone (habits.json). The same thing within
 * a few minutes counts once. Cheap; may be called from any thread.
 */
class HabitLog(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "habits.json"))
    private val lock = Any()
    private val _events = MutableStateFlow(load())
    val events: StateFlow<List<HabitEvent>> = _events.asStateFlow()
    /** Records come from the main thread on every app switch; the file is written beside it, in order. */
    private val writer = Executors.newSingleThreadExecutor()

    fun record(key: String, label: String, at: Long = System.currentTimeMillis()) = synchronized(lock) {
        val list = _events.value
        // Opening an app twice in a row, or a route notification coming back: once.
        if (list.any { it.key == key && abs(it.at - at) < DEDUPE_MS }) return
        val next = (list + HabitEvent(key, label, at)).filter { at - it.at < KEEP_MS }.takeLast(MAX)
        _events.value = next
        writer.execute { save(next) }
    }

    fun forget(key: String) = update { list -> list.filterNot { it.key == key } }

    fun clear() = update { emptyList() }

    private fun update(change: (List<HabitEvent>) -> List<HabitEvent>) = synchronized(lock) {
        val next = change(_events.value)
        _events.value = next
        writer.execute { save(next) }
    }

    private fun load(): List<HabitEvent> = runCatching {
        val arr = JSONArray(String(file.readFully()))
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { HabitEvent(it.getString("k"), it.getString("l"), it.getLong("t")) } }
    }.getOrElse { if (file.baseFile.exists()) Log.w(TAG, "Couldn't read habits", it); emptyList() }

    private fun save(list: List<HabitEvent>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("k", it.key).put("l", it.label).put("t", it.at)) }
        val out = file.startWrite()
        try {
            out.write(arr.toString().toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.w(TAG, "Couldn't save habits", e)
        }
    }

    private companion object {
        const val TAG = "HabitLog"
        const val MAX = 3000
        const val KEEP_MS = 90 * Habits.DAY_MS
        const val DEDUPE_MS = 20 * 60_000L
    }
}
