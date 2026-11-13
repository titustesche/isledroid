package com.titus.dynamicisland.data.settings

import org.json.JSONArray
import org.json.JSONObject

/** A computer (or anything else) the assistant can wake with a Wake-on-LAN magic packet. */
data class WolDevice(
    val name: String,
    /** "AA:BB:CC:DD:EE:FF"; dashes or no separators work too. */
    val mac: String,
    /** Where the packet goes: the network's broadcast address, or the device's own address. */
    val broadcast: String = "255.255.255.255",
    val port: Int = 9,
) {
    /** The six bytes of [mac], or null if it isn't one. */
    fun macBytes(): ByteArray? {
        val hex = mac.filter { it.isLetterOrDigit() }
        if (hex.length != 12 || hex.any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * A web request the assistant can fire by name: a Home Assistant service or webhook, IFTTT, a smart
 * plug's local API, a router … [headers] is one "Name: value" per line. Any other scheme
 * ("kdeconnect://runcommand/…", "intent:…") is an app link: opened on the phone, method, body and headers unused.
 */
data class HttpAction(
    val name: String,
    val url: String,
    val method: String = "POST",
    val body: String = "",
    val headers: String = "",
    /** Short note for the assistant: what it does, when to use it ("turns the living room lights on"). */
    val description: String = "",
) {
    /** An http(s) request; otherwise a link an app on the phone opens. */
    val isWeb: Boolean get() = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /** http(s) with a host, or any other "scheme:something". */
    val hasValidUrl: Boolean
        get() = if (isWeb) url.substringAfter("://").isNotBlank() else SCHEME.matches(url)

    fun headerPairs(): List<Pair<String, String>> = headers.lines().mapNotNull { line ->
        val i = line.indexOf(':')
        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
    }.filter { it.first.isNotEmpty() }

    companion object {
        val METHODS = listOf("GET", "POST", "PUT", "DELETE")
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:.+")
    }
}

/** Settings store both lists as JSON strings. */
internal object NetworkActionsJson {
    fun devicesToJson(list: List<WolDevice>): String = JSONArray().apply {
        list.forEach { d -> put(JSONObject().put("name", d.name).put("mac", d.mac).put("broadcast", d.broadcast).put("port", d.port)) }
    }.toString()

    fun devicesFromJson(json: String?): List<WolDevice>? = json?.let {
        runCatching {
            val arr = JSONArray(it)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WolDevice(o.getString("name"), o.getString("mac"), o.optString("broadcast", "255.255.255.255"), o.optInt("port", 9))
            }
        }.getOrNull()
    }

    fun actionsToJson(list: List<HttpAction>): String = JSONArray().apply {
        list.forEach { a ->
            put(
                JSONObject().put("name", a.name).put("url", a.url).put("method", a.method).put("body", a.body)
                    .put("headers", a.headers).put("description", a.description),
            )
        }
    }.toString()

    fun actionsFromJson(json: String?): List<HttpAction>? = json?.let {
        runCatching {
            val arr = JSONArray(it)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HttpAction(
                    o.getString("name"), o.getString("url"), o.optString("method", "POST"), o.optString("body"),
                    o.optString("headers"), o.optString("description"),
                )
            }
        }.getOrNull()
    }
}
