package com.titus.dynamicisland.domain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.titus.dynamicisland.data.settings.IslandSettings
import com.titus.dynamicisland.data.settings.SettingsRepository
import com.titus.dynamicisland.data.situations.SignalSnapshot
import com.titus.dynamicisland.data.situations.Signals
import com.titus.dynamicisland.data.situations.Situation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A situation started ([entered]) or ended. [at] is epoch millis. */
data class SituationChange(val id: String, val name: String, val entered: Boolean, val at: Long)

/** Ids no situation has (deleted ones a feature still holds) count as inactive. */
fun anyActive(active: Set<String>, ids: Set<String>): Boolean = ids.any { it in active }

/** Features that switch on by particular situations, for "Used by …" and the delete warning. */
object SituationUsers {
    /** None yet: the wake word (part 2) registers here. The assistant context reads all situations, not ids. */
    fun usersOf(@Suppress("UNUSED_PARAMETER") id: String, @Suppress("UNUSED_PARAMETER") s: IslandSettings): List<String> = emptyList()
}

/**
 * Keeps track of which situations are active, live. It re-evaluates when the situations, the signals or the
 * clock (screen on, time set) change, and wakes itself only at the next time-window edge or hold expiry.
 */
class SituationEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val signals: Signals,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    /** The Android-free part: evaluates and reports what entered or left. */
    class Core {
        private val state = SituationState()
        private val names = HashMap<String, String>()
        var active: Set<String> = emptySet()
            private set

        fun evaluate(sits: List<Situation>, s: SignalSnapshot, now: ZonedDateTime): List<SituationChange> {
            // Before the sources have reported, "not charging" or "not on Home Wi-Fi" are just placeholders.
            if (!s.ready) return emptyList()
            sits.forEach { names[it.id] = it.name }
            val before = active
            active = state.update(sits, s, now)
            val at = now.toInstant().toEpochMilli()
            return (active - before).map { SituationChange(it, names[it].orEmpty(), entered = true, at = at) } +
                (before - active).map { SituationChange(it, names[it].orEmpty(), entered = false, at = at) }
        }

        fun nextWake(sits: List<Situation>, now: ZonedDateTime): ZonedDateTime? = state.nextWake(sits, now)
    }

    private val core = Core()
    private val trace = ConversationTrace(context, "situations-trace.txt", "Situations")
    private val alarms = context.getSystemService(AlarmManager::class.java)

    private val _active = MutableStateFlow<Set<String>>(emptySet())
    val active: StateFlow<Set<String>> = _active.asStateFlow()

    private val _changes = MutableSharedFlow<SituationChange>(extraBufferCapacity = 16)
    val changes: SharedFlow<SituationChange> = _changes.asSharedFlow()

    /** Bumped by the scheduled wake and by [Signals.recheck]: time may have moved past an edge. */
    private val tick = MutableStateFlow(0L)
    private var nextWake: ZonedDateTime? = null
    private var wakeJob: Job? = null
    private var started = false

    private val wakeIntent by lazy {
        PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_WAKE).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { tick.value++ }
    }

    fun anyActive(ids: Set<String>): Flow<Boolean> = active.map { anyActive(it, ids) }.distinctUntilChanged()

    fun activeNames(): List<String> {
        val ids = active.value
        return settings.settings.value.situations.filter { it.id in ids }.map { it.name }
    }

    fun start() {
        if (started) return
        started = true
        ContextCompat.registerReceiver(context, wakeReceiver, IntentFilter(ACTION_WAKE), ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { signals.recheck.collect { tick.value++ } }
        scope.launch {
            combine(settings.settings.map { it.situations }.distinctUntilChanged(), signals.snapshot, tick) { sits, snap, _ -> sits to snap }
                .collect { (sits, snap) -> evaluate(sits, snap) }
        }
    }

    private fun evaluate(sits: List<Situation>, snap: SignalSnapshot) {
        val now = clock()
        val changes = core.evaluate(sits, snap, now)
        _active.value = core.active
        changes.forEach { c ->
            trace.add(if (c.entered) "entered \"${c.name}\"" else "left \"${c.name}\"")
            _changes.tryEmit(c)
        }
        schedule(core.nextWake(sits, now), now)
    }

    /** One pending wake at a time: a coroutine while the process is awake, an inexact alarm through Doze. */
    private fun schedule(at: ZonedDateTime?, now: ZonedDateTime) {
        if (at == nextWake) return
        nextWake = at
        wakeJob?.cancel()
        runCatching { alarms.cancel(wakeIntent) }
        if (at == null) return
        val millis = at.toInstant().toEpochMilli()
        wakeJob = scope.launch {
            delay(Duration.between(now, at).toMillis().coerceAtLeast(0) + 50)
            tick.value++
        }
        runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, wakeIntent) }
    }

    /** For `dumpsys … situations`: signals, every situation with each condition, the next wake, the log. */
    fun dump(): String = buildString {
        val now = clock()
        val snap = signals.snapshot.value
        val sits = settings.settings.value.situations
        appendLine("Signals:")
        appendLine("  bluetooth: " + snap.bluetooth.joinToString { "${it.name} (${it.kind.name.lowercase()}, ${it.addresses.joinToString("/")})" }.ifEmpty { "none" })
        appendLine("  charging: ${snap.charging}, dnd: ${snap.dndOn}, screen on: ${snap.screenOn}, locked: ${snap.locked}")
        appendLine("  ready: ${snap.ready}, bluetooth readable: ${snap.bluetoothReadable}")
        appendLine("  wifi: " + if (!snap.wifiReadable) "name unreadable (location permission or location off)" else snap.wifiSsid ?: "not connected")
        appendLine("Situations:" + if (sits.isEmpty()) " none defined" else "")
        sits.forEach { s ->
            val state = when {
                !s.enabled -> "off"
                s.id in active.value -> "ACTIVE"
                else -> "inactive"
            }
            appendLine("  ${s.name} [$state]" + if (s.holdMinutes > 0) " hold ${s.holdMinutes} min" else "")
            s.conditions.forEach { c ->
                val ok = SituationEvaluator.holds(c, snap, now.toLocalDateTime())
                appendLine("    ${if (ok) "✓" else "✗"} ${com.titus.dynamicisland.data.situations.SituationText.describe(c)}")
            }
        }
        appendLine("Next wake: " + (nextWake?.format(DateTimeFormatter.ofPattern("EEE dd.MM. HH:mm")) ?: "none"))
        appendLine("Log:")
        append(trace.text().ifEmpty { "(nothing yet)\n" })
    }

    private companion object {
        const val ACTION_WAKE = "com.titus.dynamicisland.SITUATIONS_WAKE"
    }
}
