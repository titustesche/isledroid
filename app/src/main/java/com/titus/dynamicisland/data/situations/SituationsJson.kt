package com.titus.dynamicisland.data.situations

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

/** Situations as JSON for one DataStore string, the way Wake-on-LAN devices are stored. */
object SituationsJson {
    private const val LAST_MINUTE = 24 * 60 - 1

    fun toJson(list: List<Situation>): String = JSONArray().apply {
        list.forEach { s ->
            put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("enabled", s.enabled)
                put("holdMinutes", s.holdMinutes)
                put("conditions", JSONArray().apply { s.conditions.forEach { put(conditionToJson(it)) } })
            })
        }
    }.toString()

    /** Null when there's nothing stored or it can't be read. */
    fun fromJson(json: String?): List<Situation>? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val array = JSONArray(json)
            // One unreadable situation is dropped; the others survive.
            (0 until array.length()).mapNotNull { i -> runCatching {
                val o = array.getJSONObject(i)
                val conditions = o.optJSONArray("conditions") ?: JSONArray()
                Situation(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    enabled = o.optBoolean("enabled", true),
                    conditions = (0 until conditions.length()).map { conditionFromJson(conditions.getJSONObject(it)) },
                    holdMinutes = o.optInt("holdMinutes", 0).coerceIn(0, Situation.MAX_HOLD_MINUTES),
                )
            }.getOrNull() }
        }.getOrNull()
    }

    private fun conditionToJson(c: Condition): JSONObject = when (c) {
        is Condition.Time -> JSONObject().put("type", "time").put("start", c.startMin).put("end", c.endMin)
            .put("days", JSONArray().apply { c.days.sorted().forEach { put(it.value) } })
        is Condition.Bluetooth -> JSONObject().put("type", "bluetooth").put("anyCar", c.anyCar).put("connected", c.connected)
            .put("devices", JSONArray().apply { c.devices.forEach { put(JSONObject().put("address", it.address).put("name", it.name)) } })
        is Condition.Charging -> JSONObject().put("type", "charging").put("charging", c.charging)
        is Condition.Screen -> JSONObject().put("type", "screen").put("state", c.state.name)
        is Condition.Wifi -> JSONObject().put("type", "wifi").put("connected", c.connected)
            .put("ssids", JSONArray().apply { c.ssids.forEach { put(it) } })
        is Condition.Dnd -> JSONObject().put("type", "dnd").put("on", c.on)
        is Condition.Unknown -> JSONObject(c.json)
    }

    private fun conditionFromJson(o: JSONObject): Condition = runCatching {
        when (o.getString("type")) {
            "time" -> Condition.Time(o.getInt("start").coerceIn(0, LAST_MINUTE), o.getInt("end").coerceIn(0, LAST_MINUTE), o.getJSONArray("days").let { a ->
                (0 until a.length()).map { DayOfWeek.of(a.getInt(it)) }.toSet()
            })
            "bluetooth" -> Condition.Bluetooth(
                devices = o.getJSONArray("devices").let { a ->
                    (0 until a.length()).map { a.getJSONObject(it).let { d -> BtRef(d.getString("address"), d.getString("name")) } }
                },
                anyCar = o.optBoolean("anyCar"),
                connected = o.getBoolean("connected"),
            )
            "charging" -> Condition.Charging(o.getBoolean("charging"))
            "screen" -> Condition.Screen(ScreenState.valueOf(o.getString("state")))
            "wifi" -> Condition.Wifi(o.getJSONArray("ssids").let { a -> (0 until a.length()).map { a.getString(it) } }, o.getBoolean("connected"))
            "dnd" -> Condition.Dnd(o.getBoolean("on"))
            else -> null
        }
    }.getOrNull() ?: Condition.Unknown(o.toString())
}
