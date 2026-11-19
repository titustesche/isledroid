package com.titus.dynamicisland.domain

import com.titus.dynamicisland.data.situations.BtRef
import com.titus.dynamicisland.data.situations.Condition
import com.titus.dynamicisland.data.situations.ScreenState
import com.titus.dynamicisland.data.situations.SignalSnapshot
import com.titus.dynamicisland.data.situations.Situation
import com.titus.dynamicisland.data.system.BluetoothDeviceInfo
import com.titus.dynamicisland.data.system.BluetoothKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class SituationEvaluatorTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val none = SignalSnapshot()

    // 2026-11-16 is a Monday, 2026-11-20 a Friday.
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, day, h, m)
    private fun zoned(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(at(day, h, m), berlin)
    private fun time(start: String, end: String, vararg days: DayOfWeek) =
        Condition.Time(minutes(start), minutes(end), days.toSet().ifEmpty { DayOfWeek.entries.toSet() })
    private fun minutes(hhmm: String) = hhmm.substringBefore(':').toInt() * 60 + hhmm.substringAfter(':').toInt()
    private fun holds(c: Condition, s: SignalSnapshot = none, now: LocalDateTime = at(16, 12)) = SituationEvaluator.holds(c, s, now)
    private fun sit(vararg c: Condition, hold: Int = 0, enabled: Boolean = true) =
        Situation("id", "Test", enabled = enabled, conditions = c.toList(), holdMinutes = hold)

    @Test fun timeWindowSameDay() {
        val c = time("09:00", "23:00", MONDAY)
        assertFalse(holds(c, now = at(16, 8, 59)))
        assertTrue(holds(c, now = at(16, 9, 0)))
        assertTrue(holds(c, now = at(16, 22, 59)))
        assertFalse(holds(c, now = at(16, 23, 0)))
        assertFalse(holds(c, now = at(17, 12, 0))) // Tuesday
    }

    @Test fun timeWindowCrossesMidnight() {
        val c = time("22:00", "02:00", FRIDAY)
        assertTrue(holds(c, now = at(20, 23, 0)))   // Friday night
        assertTrue(holds(c, now = at(21, 1, 59)))   // Saturday morning, started Friday
        assertFalse(holds(c, now = at(21, 23, 0)))  // Saturday night
        assertFalse(holds(c, now = at(20, 1, 0)))   // Friday morning belongs to Thursday's window
    }

    @Test fun wholeDayWhenStartEqualsEnd() {
        val c = time("00:00", "00:00", MONDAY)
        assertTrue(holds(c, now = at(16, 0, 0)))
        assertTrue(holds(c, now = at(16, 23, 59)))
        assertFalse(holds(c, now = at(17, 0, 0)))
    }

    @Test fun bluetoothByAddressSurvivesRename() {
        val c = Condition.Bluetooth(listOf(BtRef("AA", "Golf")), anyCar = false, connected = true)
        val renamed = SignalSnapshot(bluetooth = listOf(BluetoothDeviceInfo("VW Golf", BluetoothKind.OTHER, addresses = setOf("AA"))))
        assertTrue(holds(c, renamed))
        val byName = SignalSnapshot(bluetooth = listOf(BluetoothDeviceInfo("Golf", BluetoothKind.OTHER)))
        assertTrue(holds(c, byName))
        assertFalse(holds(c, none))
    }

    @Test fun bluetoothAnyCarAndNotConnected() {
        val car = SignalSnapshot(bluetooth = listOf(BluetoothDeviceInfo("Golf", BluetoothKind.CAR)))
        val buds = SignalSnapshot(bluetooth = listOf(BluetoothDeviceInfo("Buds", BluetoothKind.HEADPHONES)))
        assertTrue(holds(Condition.Bluetooth(emptyList(), anyCar = true, connected = true), car))
        assertFalse(holds(Condition.Bluetooth(emptyList(), anyCar = true, connected = true), buds))
        assertFalse(holds(Condition.Bluetooth(emptyList(), anyCar = true, connected = false), car))
        assertTrue(holds(Condition.Bluetooth(emptyList(), anyCar = true, connected = false), buds))
        assertTrue(holds(Condition.Bluetooth(listOf(BtRef("x", "Golf")), anyCar = false, connected = false), buds))
    }

    @Test fun screenStates() {
        val onLocked = SignalSnapshot(screenOn = true, locked = true)
        val onUnlocked = SignalSnapshot(screenOn = true, locked = false)
        val off = SignalSnapshot(screenOn = false, locked = true)
        assertTrue(holds(Condition.Screen(ScreenState.ON), onLocked))
        assertTrue(holds(Condition.Screen(ScreenState.ON), onUnlocked))
        assertFalse(holds(Condition.Screen(ScreenState.ON), off))
        assertTrue(holds(Condition.Screen(ScreenState.OFF), off))
        assertTrue(holds(Condition.Screen(ScreenState.UNLOCKED), onUnlocked))
        assertFalse(holds(Condition.Screen(ScreenState.UNLOCKED), onLocked))
        assertTrue(holds(Condition.Screen(ScreenState.LOCKED), onLocked))
        assertTrue(holds(Condition.Screen(ScreenState.LOCKED), off))
        assertFalse(holds(Condition.Screen(ScreenState.LOCKED), onUnlocked))
    }

    @Test fun wifiMatchesWithoutQuotes() {
        val home = SignalSnapshot(wifiSsid = "\"Home\"")
        assertTrue(holds(Condition.Wifi(listOf("Home"), connected = true), home))
        assertFalse(holds(Condition.Wifi(listOf("Home"), connected = false), home))
        assertTrue(holds(Condition.Wifi(listOf("Home"), connected = false), SignalSnapshot(wifiSsid = null)))
    }

    @Test fun wifiUnreadableIsFalseEitherWay() {
        val hidden = SignalSnapshot(wifiSsid = null, wifiReadable = false)
        assertFalse(holds(Condition.Wifi(listOf("Home"), connected = true), hidden))
        assertFalse(holds(Condition.Wifi(listOf("Home"), connected = false), hidden))
    }

    @Test fun chargingAndDnd() {
        assertTrue(holds(Condition.Charging(true), SignalSnapshot(charging = true)))
        assertFalse(holds(Condition.Charging(true), none))
        assertTrue(holds(Condition.Dnd(false), none))
    }

    @Test fun allConditionsMustHold() {
        val s = sit(Condition.Charging(true), Condition.Dnd(true))
        assertFalse(SituationEvaluator.rawActive(s, SignalSnapshot(charging = true), at(16, 12)))
        assertTrue(SituationEvaluator.rawActive(s, SignalSnapshot(charging = true, dndOn = true), at(16, 12)))
    }

    @Test fun emptyOrDisabledNeverActive() {
        assertFalse(SituationEvaluator.rawActive(sit(), none, at(16, 12)))
        assertFalse(SituationEvaluator.rawActive(sit(Condition.Dnd(false), enabled = false), none, at(16, 12)))
    }

    @Test fun unknownConditionIsFalse() {
        assertFalse(holds(Condition.Unknown("{\"type\":\"geofence\"}")))
    }

    @Test fun holdKeepsActiveThenExpires() {
        val state = SituationState()
        val s = listOf(sit(Condition.Charging(true), hold = 5))
        val t = zoned(16, 12)
        assertEquals(setOf("id"), state.update(s, SignalSnapshot(charging = true), t))
        assertEquals(setOf("id"), state.update(s, none, t.plusMinutes(1)))
        assertEquals(setOf("id"), state.update(s, none, t.plusMinutes(5).plusSeconds(59)))
        assertEquals(emptySet<String>(), state.update(s, none, t.plusMinutes(6)))
    }

    @Test fun holdWakesAtExpiry() {
        val state = SituationState()
        val s = listOf(sit(Condition.Charging(true), hold = 5))
        val t = zoned(16, 12)
        state.update(s, SignalSnapshot(charging = true), t)
        state.update(s, none, t.plusMinutes(1))
        assertEquals(t.plusMinutes(6), state.nextWake(s, t.plusMinutes(1)))
    }

    @Test fun disablingCancelsHold() {
        val state = SituationState()
        val t = zoned(16, 12)
        state.update(listOf(sit(Condition.Charging(true), hold = 5)), SignalSnapshot(charging = true), t)
        assertEquals(emptySet<String>(), state.update(listOf(sit(Condition.Charging(true), hold = 5, enabled = false)), none, t.plusMinutes(1)))
    }

    @Test fun nextEdgeFindsStartAndEnd() {
        val s = listOf(sit(time("09:00", "23:00")))
        assertEquals(zoned(16, 9), SituationEvaluator.nextEdge(s, zoned(16, 8)))
        assertEquals(zoned(16, 23), SituationEvaluator.nextEdge(s, zoned(16, 9)))
        assertEquals(zoned(17, 9), SituationEvaluator.nextEdge(s, zoned(16, 23)))
    }

    @Test fun nextEdgeIgnoresDisabledAndOtherDays() {
        assertEquals(null, SituationEvaluator.nextEdge(listOf(sit(time("09:00", "23:00"), enabled = false)), zoned(16, 8)))
        val friday = listOf(sit(time("09:00", "10:00", FRIDAY)))
        assertEquals(zoned(20, 9), SituationEvaluator.nextEdge(friday, zoned(16, 8)))
    }

    @Test fun nextEdgeAcrossDst() {
        // Europe/Berlin skips 02:00 to 03:00 on 2026-03-29.
        val s = listOf(sit(time("02:30", "03:30")))
        val before = ZonedDateTime.of(LocalDateTime.of(2026, 3, 29, 1, 0), berlin)
        val edge = SituationEvaluator.nextEdge(s, before)
        assertNotNull(edge)
        assertTrue(edge!!.isAfter(before))
    }

    @Test fun wholeDayEdgesAreAtMidnight() {
        val s = listOf(sit(time("08:00", "08:00", MONDAY)))
        assertEquals(zoned(16, 0), SituationEvaluator.nextEdge(s, zoned(15, 12))) // Sunday noon → Monday 00:00
        assertEquals(zoned(17, 0), SituationEvaluator.nextEdge(s, zoned(16, 1)))  // Monday 01:00 → Tuesday 00:00
    }

    @Test fun bluetoothUnreadableIsFalseEitherWay() {
        val noPermission = SignalSnapshot(bluetoothReadable = false)
        assertFalse(holds(Condition.Bluetooth(emptyList(), anyCar = true, connected = false), noPermission))
        assertFalse(holds(Condition.Bluetooth(listOf(BtRef("a", "Golf")), anyCar = false, connected = true), noPermission))
    }

    @Test fun editingConditionsEndsTheHoldAtOnce() {
        val state = SituationState()
        val t = zoned(16, 12)
        state.update(listOf(sit(Condition.Charging(true), hold = 5)), SignalSnapshot(charging = true), t)
        val edited = listOf(sit(Condition.Dnd(true), hold = 5))
        assertEquals(emptySet<String>(), state.update(edited, SignalSnapshot(charging = true), t.plusSeconds(10)))
    }

    @Test fun raisingTheHoldDoesNotReviveAnOldStop() {
        val state = SituationState()
        val t = zoned(16, 10)
        state.update(listOf(sit(Condition.Charging(true), hold = 0)), SignalSnapshot(charging = true), t)
        state.update(listOf(sit(Condition.Charging(true), hold = 0)), none, t) // stopped at 10:00, no hold
        assertEquals(emptySet<String>(), state.update(listOf(sit(Condition.Charging(true), hold = 30)), none, t.plusMinutes(5)))
    }
}
