package com.titus.dynamicisland.domain

import com.titus.dynamicisland.data.situations.Condition
import com.titus.dynamicisland.data.situations.SignalSnapshot
import com.titus.dynamicisland.data.situations.Situation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class SituationEngineTest {
    private val t = ZonedDateTime.of(LocalDateTime.of(2026, 11, 16, 12, 0), ZoneId.of("Europe/Berlin"))
    private val charging = Situation("c", "Laden", conditions = listOf(Condition.Charging(true)))

    @Test fun entersAndLeavesWithSnapshot() {
        val core = SituationEngine.Core()
        val entered = core.evaluate(listOf(charging), SignalSnapshot(charging = true), t)
        assertEquals(setOf("c"), core.active)
        assertEquals(listOf(true), entered.map { it.entered })
        assertEquals("Laden", entered.single().name)
        val left = core.evaluate(listOf(charging), SignalSnapshot(charging = false), t.plusMinutes(1))
        assertEquals(emptySet<String>(), core.active)
        assertEquals(listOf(false), left.map { it.entered })
    }

    @Test fun nothingChangedReportsNothing() {
        val core = SituationEngine.Core()
        core.evaluate(listOf(charging), SignalSnapshot(charging = true), t)
        assertEquals(emptyList<SituationChange>(), core.evaluate(listOf(charging), SignalSnapshot(charging = true), t.plusMinutes(1)))
    }

    @Test fun anyActiveIgnoresUnknownIds() {
        assertFalse(anyActive(setOf("a"), setOf("deleted")))
        assertTrue(anyActive(setOf("a"), setOf("a", "deleted")))
        assertFalse(anyActive(setOf("a"), emptySet()))
    }

    @Test fun editWhileActiveAppliesAtOnce() {
        val core = SituationEngine.Core()
        core.evaluate(listOf(charging), SignalSnapshot(charging = true), t)
        val changes = core.evaluate(listOf(charging.copy(enabled = false)), SignalSnapshot(charging = true), t)
        assertEquals(listOf(false), changes.map { it.entered })
        assertEquals(emptySet<String>(), core.active)
    }

    @Test fun deletedWhileActiveLeaves() {
        val core = SituationEngine.Core()
        core.evaluate(listOf(charging), SignalSnapshot(charging = true), t)
        val changes = core.evaluate(emptyList(), SignalSnapshot(charging = true), t)
        assertEquals(listOf("c" to false), changes.map { it.id to it.entered })
    }

    @Test fun nothingIsEvaluatedBeforeTheSignalsAreReady() {
        val core = SituationEngine.Core()
        val notOnHome = Situation("w", "Away", conditions = listOf(Condition.Wifi(listOf("Home"), connected = false)), holdMinutes = 10)
        assertEquals(emptyList<SituationChange>(), core.evaluate(listOf(notOnHome), SignalSnapshot(ready = false), t))
        assertEquals(emptySet<String>(), core.active)
        assertEquals(emptyList<SituationChange>(), core.evaluate(listOf(notOnHome), SignalSnapshot(wifiSsid = "Home"), t.plusSeconds(3)))
        assertEquals(emptySet<String>(), core.active)
    }
}
