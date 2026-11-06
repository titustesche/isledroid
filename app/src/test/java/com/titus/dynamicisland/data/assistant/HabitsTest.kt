package com.titus.dynamicisland.data.assistant

import com.titus.dynamicisland.data.settings.WolDevice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HabitsTest {
    private val zone = TimeZone.getTimeZone("Europe/Berlin")
    private val rules = HabitRules(windowMin = 45, minDays = 3, lookbackDays = 28, splitWeekends = true)

    /** 2026-11-02 is a Monday. */
    private fun at(day: Int, hour: Int, minute: Int): Long = Calendar.getInstance(zone).apply {
        clear()
        set(2026, Calendar.NOVEMBER, day, hour, minute)
    }.timeInMillis

    private fun work(day: Int, hour: Int, minute: Int) = HabitEvent("navigate:work", "started a route to Work", at(day, hour, minute))

    @Test fun weekdayCommuteFitsTheMorning() {
        val events = listOf(work(2, 7, 30), work(3, 7, 45), work(4, 8, 5), work(5, 7, 40))
        val fits = Habits.fitting(events, at(6, 7, 50), rules, zone) // Friday morning
        assertEquals(1, fits.size)
        assertEquals("navigate:work", fits[0].key)
        assertEquals(false, fits[0].weekend)
        assertEquals(4, fits[0].days)
        // Not in the evening, not on Saturday morning.
        assertTrue(Habits.fitting(events, at(6, 18, 0), rules, zone).isEmpty())
        assertTrue(Habits.fitting(events, at(7, 7, 50), rules, zone).isEmpty())
    }

    @Test fun tooFewDaysIsNoRoutine() {
        // Three times, but on two days only.
        val events = listOf(work(2, 7, 30), work(2, 7, 50), work(3, 7, 40))
        assertTrue(Habits.fitting(events, at(4, 7, 40), rules, zone).isEmpty())
    }

    @Test fun somethingDoneAllDayIsIgnored() {
        val chat = (2..6).flatMap { d -> (8..22).map { h -> HabitEvent("app:chat", "opened Chat", at(d, h, 0)) } }
        assertTrue(Habits.all(chat, at(6, 23, 0), rules, zone).isEmpty())
    }

    @Test fun routineAroundMidnightKeepsItsTime() {
        val events = listOf(
            HabitEvent("app:tv", "opened TV", at(2, 23, 50)), HabitEvent("app:tv", "opened TV", at(4, 0, 10)),
            HabitEvent("app:tv", "opened TV", at(5, 23, 55)),
        )
        val h = Habits.all(events, at(6, 12, 0), rules.copy(splitWeekends = false), zone).single()
        assertEquals("23:55", h.time)
    }

    @Test fun deliberateActionsComeBeforeOpenedApps() {
        val app = (2..6).map { HabitEvent("app:news", "opened News", at(it, 7, 35)) }
        val list = Habits.fitting(app + listOf(work(2, 7, 30), work(3, 7, 45), work(4, 8, 5)), at(6, 7, 40), rules, zone)
        assertEquals(listOf("navigate:work", "app:news"), list.map { it.key })
    }

    @Test fun macAddressesParse() {
        assertArrayEquals(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x01, 0x02, 0x03),
            WolDevice("PC", "aa-bb-cc-01-02-03").macBytes(),
        )
        assertNull(WolDevice("PC", "aa:bb:cc").macBytes())
        assertNull(WolDevice("PC", "zz:bb:cc:01:02:03").macBytes())
    }
}
