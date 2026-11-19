package com.titus.dynamicisland.data.situations

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.WEDNESDAY

class SituationTextTest {
    private fun d(c: Condition) = SituationText.describe(c)

    @Test fun times() {
        assertEquals("09:00–23:00, every day", d(Condition.Time(540, 1380, DayOfWeek.entries.toSet())))
        assertEquals("22:00–02:00, Mon–Fri", d(Condition.Time(1320, 120, (1..5).map(DayOfWeek::of).toSet())))
        assertEquals("All day, Sat, Sun", d(Condition.Time(0, 0, setOf(SATURDAY, SUNDAY))))
        assertEquals("08:30–09:00, Mon, Wed, Fri", d(Condition.Time(510, 540, setOf(MONDAY, WEDNESDAY, FRIDAY))))
        assertEquals("08:30–09:00, never", d(Condition.Time(510, 540, emptySet())))
    }

    @Test fun bluetooth() {
        assertEquals("Golf connected", d(Condition.Bluetooth(listOf(BtRef("a", "Golf")), anyCar = false, connected = true)))
        assertEquals("Any car or Golf not connected", d(Condition.Bluetooth(listOf(BtRef("a", "Golf")), anyCar = true, connected = false)))
        assertEquals("Any car connected", d(Condition.Bluetooth(emptyList(), anyCar = true, connected = true)))
        assertEquals("No device picked", d(Condition.Bluetooth(emptyList(), anyCar = false, connected = true)))
    }

    @Test fun others() {
        assertEquals("Charging", d(Condition.Charging(true)))
        assertEquals("Not charging", d(Condition.Charging(false)))
        assertEquals("Screen unlocked", d(Condition.Screen(ScreenState.UNLOCKED)))
        assertEquals("On Wi-Fi Home or Work", d(Condition.Wifi(listOf("Home", "Work"), connected = true)))
        assertEquals("Not on Wi-Fi Home", d(Condition.Wifi(listOf("Home"), connected = false)))
        assertEquals("Do Not Disturb on", d(Condition.Dnd(true)))
        assertEquals("Unsupported condition", d(Condition.Unknown("{}")))
    }

    @Test fun summaryJoinsConditions() {
        val s = Situation("x", "Day", conditions = listOf(Condition.Charging(true), Condition.Dnd(false)))
        assertEquals("Charging · Do Not Disturb off", SituationText.summary(s))
        assertEquals("No conditions yet", SituationText.summary(s.copy(conditions = emptyList())))
    }
}
