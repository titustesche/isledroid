package com.titus.dynamicisland.data.situations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class SituationsJsonTest {
    @Test fun roundTripAllConditionTypes() {
        val list = listOf(
            Situation("t", "Night", conditions = listOf(Condition.Time(22 * 60, 2 * 60, setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)))),
            Situation("b", "Not driving", enabled = false, holdMinutes = 5,
                conditions = listOf(Condition.Bluetooth(listOf(BtRef("AA:BB", "Golf")), anyCar = false, connected = false))),
            Situation("c", "Charging", conditions = listOf(Condition.Charging(true))),
            Situation("s", "Unlocked", conditions = listOf(Condition.Screen(ScreenState.UNLOCKED))),
            Situation("w", "Home", conditions = listOf(Condition.Wifi(listOf("Home"), connected = true))),
            Situation("d", "Loud", conditions = listOf(Condition.Dnd(false))),
        )
        assertEquals(list, SituationsJson.fromJson(SituationsJson.toJson(list)))
    }

    @Test fun unknownConditionSurvives() {
        val loaded = SituationsJson.fromJson(
            """[{"id":"a","name":"A","enabled":true,"holdMinutes":0,"conditions":[{"type":"geofence","lat":1}]}]""",
        )!!
        assertTrue(loaded.single().conditions.single() is Condition.Unknown)
        assertTrue(SituationsJson.toJson(loaded).contains("geofence"))
    }

    @Test fun garbageGivesNull() {
        assertNull(SituationsJson.fromJson("not json"))
        assertNull(SituationsJson.fromJson(null))
    }

    @Test fun holdIsClamped() {
        val loaded = SituationsJson.fromJson("""[{"id":"a","name":"A","enabled":true,"holdMinutes":99,"conditions":[]}]""")!!
        assertEquals(30, loaded.single().holdMinutes)
    }

    @Test fun oneBadSituationDoesNotLoseTheOthers() {
        val loaded = SituationsJson.fromJson(
            """[{"name":"no id"},{"id":"ok","name":"Fine","enabled":true,"holdMinutes":0,"conditions":[]}]""",
        )!!
        assertEquals(listOf("ok"), loaded.map { it.id })
    }

    @Test fun outOfRangeMinutesAreClamped() {
        val loaded = SituationsJson.fromJson(
            """[{"id":"a","name":"A","enabled":true,"holdMinutes":0,"conditions":[{"type":"time","start":2000,"end":-5,"days":[1]}]}]""",
        )!!
        val c = loaded.single().conditions.single() as Condition.Time
        assertEquals(1439 to 0, c.startMin to c.endMin)
    }
}
