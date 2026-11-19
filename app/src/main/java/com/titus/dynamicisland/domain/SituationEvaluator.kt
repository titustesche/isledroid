package com.titus.dynamicisland.domain

import com.titus.dynamicisland.data.situations.Condition
import com.titus.dynamicisland.data.situations.ScreenState
import com.titus.dynamicisland.data.situations.SignalSnapshot
import com.titus.dynamicisland.data.situations.Situation
import com.titus.dynamicisland.data.system.BluetoothKind
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/** Whether conditions hold. Pure: signals and time are passed in. */
object SituationEvaluator {

    fun holds(c: Condition, s: SignalSnapshot, now: LocalDateTime): Boolean = when (c) {
        is Condition.Time -> timeHolds(c, now)
        is Condition.Bluetooth -> {
            val any = s.bluetooth.any { d ->
                (c.anyCar && d.kind == BluetoothKind.CAR) ||
                    c.devices.any { ref -> ref.address in d.addresses || ref.name == d.name }
            }
            s.bluetoothReadable && any == c.connected
        }
        is Condition.Charging -> s.charging == c.charging
        is Condition.Dnd -> s.dndOn == c.on
        is Condition.Screen -> when (c.state) {
            ScreenState.ON -> s.screenOn
            ScreenState.OFF -> !s.screenOn
            ScreenState.UNLOCKED -> s.screenOn && !s.locked
            ScreenState.LOCKED -> s.locked || !s.screenOn
        }
        is Condition.Wifi -> s.wifiReadable && (s.wifiSsid?.removeSurrounding("\"") in c.ssids) == c.connected
        is Condition.Unknown -> false
    }

    /** All conditions hold right now (no hold applied); empty or switched off never does. */
    fun rawActive(sit: Situation, s: SignalSnapshot, now: LocalDateTime): Boolean =
        sit.enabled && sit.conditions.isNotEmpty() && sit.conditions.all { holds(it, s, now) }

    /** The next moment a time window of an enabled situation starts or ends, or null if none has one. */
    fun nextEdge(sits: List<Situation>, now: ZonedDateTime): ZonedDateTime? =
        sits.filter { it.enabled }.flatMap { it.conditions }.filterIsInstance<Condition.Time>()
            .flatMap { c ->
                (0L..8L).flatMap { offset ->
                    val date = now.toLocalDate().plusDays(offset)
                    val startsToday = date.dayOfWeek in c.days
                    // A window ends on the day it started, unless it crosses midnight (or is a whole day).
                    val endsToday = if (c.startMin < c.endMin) startsToday else date.dayOfWeek.minus(1) in c.days
                    // A whole day (start == end) runs midnight to midnight, whatever time was entered.
                    val (start, end) = if (c.startMin == c.endMin) 0 to 0 else c.startMin to c.endMin
                    listOfNotNull(start.takeIf { startsToday }, end.takeIf { endsToday })
                        .map { m -> ZonedDateTime.of(date, LocalTime.of(m / 60, m % 60), now.zone) }
                }
            }
            .filter { it.isAfter(now) }
            .minOrNull()

    private fun timeHolds(c: Condition.Time, now: LocalDateTime): Boolean {
        val minute = now.hour * 60 + now.minute
        val today = now.dayOfWeek
        val yesterday = today.minus(1)
        return when {
            c.startMin == c.endMin -> today in c.days
            c.startMin < c.endMin -> today in c.days && minute >= c.startMin && minute < c.endMin
            // Crosses midnight: the evening part is today's, the morning part yesterday's.
            else -> (today in c.days && minute >= c.startMin) || (yesterday in c.days && minute < c.endMin)
        }
    }
}

/**
 * Which situations are active, with each one's hold applied: it stays active [Situation.holdMinutes] after the
 * evaluation that first found its conditions no longer holding. Time is passed in, like BluetoothTracker.
 */
class SituationState {
    /** Situations whose conditions held at the last update. */
    private val holding = HashSet<String>()
    /** When each situation's conditions stopped holding, while its hold may still run. */
    private val stoppedAt = HashMap<String, ZonedDateTime>()
    /** Each situation's conditions at the last update: an edit ends its hold instead of starting one. */
    private val lastConditions = HashMap<String, List<com.titus.dynamicisland.data.situations.Condition>>()
    private var active: Set<String> = emptySet()

    fun update(sits: List<Situation>, s: SignalSnapshot, now: ZonedDateTime): Set<String> {
        val local = now.toLocalDateTime()
        val enabled = sits.filter { it.enabled }
        val ids = enabled.map { it.id }.toSet()
        holding.retainAll(ids)
        stoppedAt.keys.retainAll(ids)
        lastConditions.keys.retainAll(ids)
        enabled.forEach { sit ->
            val before = lastConditions.put(sit.id, sit.conditions)
            if (before != null && before != sit.conditions) {
                holding -= sit.id
                stoppedAt -= sit.id
            }
        }
        active = enabled.filter { sit ->
            if (SituationEvaluator.rawActive(sit, s, local)) {
                holding += sit.id
                stoppedAt -= sit.id
                true
            } else {
                if (holding.remove(sit.id)) stoppedAt[sit.id] = now
                val stopped = stoppedAt[sit.id]
                val held = stopped != null && now.isBefore(stopped.plusMinutes(sit.holdMinutes.toLong()))
                // An expired hold is over for good: raising the hold later mustn't bring it back.
                if (!held) stoppedAt -= sit.id
                held
            }
        }.map { it.id }.toSet()
        return active
    }

    /** The earliest time something may change by itself: a time-window edge or a hold running out. */
    fun nextWake(sits: List<Situation>, now: ZonedDateTime): ZonedDateTime? {
        val holds = sits.filter { it.enabled && it.id in active }
            .mapNotNull { sit -> stoppedAt[sit.id]?.plusMinutes(sit.holdMinutes.toLong()) }
            .filter { it.isAfter(now) }
        return (holds + listOfNotNull(SituationEvaluator.nextEdge(sits, now))).minOrNull()
    }
}
