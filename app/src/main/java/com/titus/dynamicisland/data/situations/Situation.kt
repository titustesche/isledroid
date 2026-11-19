package com.titus.dynamicisland.data.situations

import java.time.DayOfWeek

/**
 * A named set of conditions ("Driving", "Daytime at home") that features switch on by. It's active while all
 * its [conditions] hold, and [holdMinutes] longer; with no conditions, or switched off, it never is.
 */
data class Situation(
    /** Stable across renames; features store this. */
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val conditions: List<Condition> = emptyList(),
    val holdMinutes: Int = 0,
) {
    companion object {
        const val MAX_HOLD_MINUTES = 30
    }
}

sealed interface Condition {
    /**
     * Minutes after midnight. [startMin] == [endMin] is the whole day; a window crossing midnight belongs to the
     * day it starts on (Friday 22:00 to 02:00 runs into Saturday morning).
     */
    data class Time(val startMin: Int, val endMin: Int, val days: Set<DayOfWeek>) : Condition

    /** One of [devices] (or, with [anyCar], any car) is connected, or with [connected] false, none is. */
    data class Bluetooth(val devices: List<BtRef>, val anyCar: Boolean, val connected: Boolean) : Condition

    data class Charging(val charging: Boolean) : Condition

    data class Screen(val state: ScreenState) : Condition

    /** Connected to one of [ssids], or with [connected] false, to none of them. */
    data class Wifi(val ssids: List<String>, val connected: Boolean) : Condition

    data class Dnd(val on: Boolean) : Condition

    /** Stored by a newer build: kept as it was, never holds. */
    data class Unknown(val json: String) : Condition
}

/** A Bluetooth device as picked: matched by [address] first, by [name] if the address isn't known. */
data class BtRef(val address: String, val name: String)

enum class ScreenState(val label: String) { ON("On"), OFF("Off"), UNLOCKED("Unlocked"), LOCKED("Locked") }
