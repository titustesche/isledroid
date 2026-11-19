package com.titus.dynamicisland.data.situations

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Situations and conditions in a few words, for the editor and the debug dump. */
object SituationText {

    fun summary(s: Situation): String =
        if (s.conditions.isEmpty()) "No conditions yet" else s.conditions.joinToString(" · ", transform = ::describe)

    fun describe(c: Condition): String = when (c) {
        is Condition.Time -> (if (c.startMin == c.endMin) "All day" else "${clock(c.startMin)}–${clock(c.endMin)}") + ", " + days(c.days)
        is Condition.Bluetooth -> {
            val what = listOfNotNull("Any car".takeIf { c.anyCar }) + c.devices.map { it.name }
            if (what.isEmpty()) "No device picked" else what.joinToString(" or ") + if (c.connected) " connected" else " not connected"
        }
        is Condition.Charging -> if (c.charging) "Charging" else "Not charging"
        is Condition.Screen -> "Screen " + c.state.label.lowercase()
        is Condition.Wifi -> (if (c.connected) "On Wi-Fi " else "Not on Wi-Fi ") + c.ssids.joinToString(" or ").ifEmpty { "(none picked)" }
        is Condition.Dnd -> "Do Not Disturb " + if (c.on) "on" else "off"
        is Condition.Unknown -> "Unsupported condition"
    }

    fun clock(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

    fun day(d: DayOfWeek): String = d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    /** "every day", "Mon–Fri" for a run of three or more, otherwise "Mon, Wed". */
    private fun days(days: Set<DayOfWeek>): String {
        if (days.isEmpty()) return "never"
        if (days.size == 7) return "every day"
        val sorted = days.sorted()
        val run = sorted.size >= 3 && sorted.zipWithNext().all { (a, b) -> b.value == a.value + 1 }
        return if (run) "${day(sorted.first())}–${day(sorted.last())}" else sorted.joinToString(transform = ::day)
    }
}
