package com.titus.dynamicisland.domain

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.CalendarContract
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import com.titus.dynamicisland.data.assistant.AssistantCard
import com.titus.dynamicisland.data.assistant.AssistantTool
import com.titus.dynamicisland.data.assistant.CardAction
import com.titus.dynamicisland.data.assistant.CardIcon
import com.titus.dynamicisland.data.assistant.ToolException
import com.titus.dynamicisland.data.assistant.ToolParam
import com.titus.dynamicisland.data.settings.NavigationApp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Weather (Open-Meteo, no account needed), the calendar, and navigation. */
class InfoTools(private val context: Context, private val navigationApp: () -> NavigationApp) {

    fun all(): List<AssistantTool> = listOf(weather, calendarEvents, createEvent, navigate)

    // ---------------- Weather ----------------

    private data class Place(val name: String, val lat: Double, val lon: Double)

    private val weather = assistantTool(
        "weather",
        "Current weather and forecast. Without a place it uses the phone's location. Prefer this over web search for weather.",
        ToolParam("place", "string", "Optional city or place, e.g. \"Berlin\". Omit for where the user is.", required = false),
        ToolParam("days", "integer", "Days of daily forecast to include, 1 (today) to 7. Default 2.", required = false, minimum = 1, maximum = 7),
        onMain = false,
    ) { i ->
        val place = i.optString("place")?.let(::geocode) ?: here()
        val days = i.optInt("days") ?: 2
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation" +
            "&hourly=temperature_2m,precipitation_probability,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
            "&timezone=auto&forecast_days=$days&forecast_hours=12"
        val json = getJson(url)
        val cur = json.getJSONObject("current")
        val code = cur.getInt("weather_code")
        val daily = json.getJSONObject("daily")
        val hi = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt()
        val lo = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt()
        i.show(
            AssistantCard(
                "weather-${place.name}", weatherIcon(code), "${cur.getDouble("temperature_2m").roundToInt()}° · ${weatherLabel(code)}",
                "${place.name} · ↑$hi° ↓$lo°",
                label = "Now · ${weatherLabel(code)}",
                detail = "H $hi° · L $lo°\n${place.name}",
                action = CardAction.Launch(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + enc("weather ${place.name}"))),
                ),
            ),
        )
        buildString {
            append("${place.name}, now: ${cur.getDouble("temperature_2m")} °C (feels ${cur.getDouble("apparent_temperature")}), ")
            append("${weatherLabel(code)}, wind ${cur.getDouble("wind_speed_10m")} km/h, precipitation ${cur.getDouble("precipitation")} mm.\n")
            val hourly = json.getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            append("Next hours: ")
            append(
                (0 until times.length() step 3).joinToString("; ") { h ->
                    "${times.getString(h).substringAfter('T')} ${hourly.getJSONArray("temperature_2m").getDouble(h)}°, " +
                        "${weatherLabel(hourly.getJSONArray("weather_code").getInt(h))}, rain ${hourly.getJSONArray("precipitation_probability").optInt(h)}%"
                },
            )
            append(".\nDays: ")
            val dates = daily.getJSONArray("time")
            append(
                (0 until dates.length()).joinToString("; ") { d ->
                    "${dates.getString(d)}: ${weatherLabel(daily.getJSONArray("weather_code").getInt(d))}, " +
                        "${daily.getJSONArray("temperature_2m_min").getDouble(d)} to ${daily.getJSONArray("temperature_2m_max").getDouble(d)}°, " +
                        "rain ${daily.getJSONArray("precipitation_probability_max").optInt(d)}%, " +
                        "sunrise ${daily.getJSONArray("sunrise").getString(d).substringAfter('T')}, sunset ${daily.getJSONArray("sunset").getString(d).substringAfter('T')}"
                },
            )
        }
    }

    private fun geocode(name: String): Place {
        val lang = Locale.getDefault().language
        val json = getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&format=json&language=$lang&name=" + enc(name))
        val r = json.optJSONArray("results")?.optJSONObject(0) ?: throw ToolException("Couldn't find a place called \"$name\".")
        return Place(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"))
    }

    /** Where the phone is (coarse is plenty for weather). */
    @SuppressLint("MissingPermission")
    private fun here(): Place {
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            throw ToolException(
                "No location access. Ask the user for a place, or tell them to allow location in Dynamic Island → Assistant → Tool permissions.",
            )
        }
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = lm.getProviders(true)
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000L }
        val loc = recent ?: fresh(lm, providers) ?: throw ToolException("The phone couldn't find its location right now. Ask for a place.")
        val name = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let { it.locality ?: it.subAdminArea }
        }.getOrNull() ?: "Here"
        return Place(name, loc.latitude, loc.longitude)
    }

    @SuppressLint("MissingPermission")
    private fun fresh(lm: LocationManager, providers: List<String>): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in providers } ?: return null
        val latch = CountDownLatch(1)
        var result: Location? = null
        val cancel = CancellationSignal()
        val executor = Executors.newSingleThreadExecutor()
        try {
            lm.getCurrentLocation(provider, cancel, executor) { result = it; latch.countDown() }
            if (!latch.await(LOCATION_TIMEOUT_S, TimeUnit.SECONDS)) cancel.cancel()
        } finally {
            executor.shutdown()
        }
        return result
    }

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        return try {
            if (conn.responseCode !in 200..299) throw ToolException("The weather service answered ${conn.responseCode}.")
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: java.io.IOException) {
            throw ToolException("The weather service can't be reached.")
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- Calendar ----------------

    private val calendarEvents = assistantTool(
        "calendar_events",
        "Events in the user's calendars. Without arguments: the next seven days from now.",
        ToolParam("from", "string", "Optional start, \"YYYY-MM-DD\" or \"YYYY-MM-DDTHH:MM\". Default: now.", required = false),
        ToolParam("days", "integer", "Optional number of days to look ahead, 1 to 60. Default 7.", required = false, minimum = 1, maximum = 60),
        ToolParam("search", "string", "Optional word to look for in the title or location.", required = false),
        onMain = false,
    ) { i ->
        if (!granted(Manifest.permission.READ_CALENDAR)) throw ToolException(missing("the calendar"))
        val from = i.optString("from")?.let { parseStart(it) } ?: System.currentTimeMillis()
        val to = from + (i.optInt("days") ?: 7) * DateUtils.DAY_IN_MILLIS
        val search = i.optString("search")?.lowercase()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        data class Event(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String?)
        val events = mutableListOf<Event>()
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            ),
            "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val e = Event(c.getLong(0), c.getString(1).orEmpty().ifBlank { "(no title)" }, c.getLong(2), c.getLong(3), c.getInt(4) != 0, c.getString(5))
                // All-day events are stored in UTC; one that ended before now isn't "upcoming".
                if (e.end <= from) continue
                if (search != null && search !in e.title.lowercase() && search !in e.location.orEmpty().lowercase()) continue
                events += e
            }
        }
        if (events.isEmpty()) return@assistantTool "No events in that time."
        events.take(2).forEach { e ->
            i.show(
                AssistantCard(
                    "event-${e.id}-${e.begin}", CardIcon.CALENDAR, e.title,
                    listOfNotNull(whenLabel(e.begin, e.allDay), e.location?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    label = "Event",
                    action = viewEvent(e.id, e.begin, e.end),
                ),
            )
        }
        events.take(MAX_EVENTS).joinToString("\n") { e ->
            val time = if (e.allDay) "${isoDay(e.begin, utc = true)} all day" else "${isoTime(e.begin)} to ${isoTime(e.end).substringAfter(' ')}"
            "$time: ${e.title}" + (e.location?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        } + if (events.size > MAX_EVENTS) "\n… and ${events.size - MAX_EVENTS} more." else ""
    }

    private val createEvent = assistantTool(
        "create_event",
        "Add an event to the user's main calendar.",
        ToolParam("title", "string", "What it is, e.g. \"Zahnarzt\"."),
        ToolParam("start", "string", "\"YYYY-MM-DDTHH:MM\", or \"YYYY-MM-DD\" for an all-day event."),
        ToolParam("end", "string", "Optional end, same format. Default: one hour after the start.", required = false),
        ToolParam("location", "string", "Optional place.", required = false),
        onMain = false,
    ) { i ->
        if (!granted(Manifest.permission.WRITE_CALENDAR) || !granted(Manifest.permission.READ_CALENDAR)) {
            throw ToolException(missing("the calendar"))
        }
        val title = i.string("title")
        val startText = i.string("start")
        val allDay = !startText.contains('T')
        val tz = if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()
        val start = parse(startText, tz)
        val end = i.optString("end")?.let { parse(it, tz) }?.takeIf { it > start }
            ?: (start + if (allDay) DateUtils.DAY_IN_MILLIS else DateUtils.HOUR_IN_MILLIS)
        val calendarId = mainCalendar() ?: throw ToolException("No calendar on this phone can take new events.")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else ZoneId.systemDefault().id)
            i.optString("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        val uri = runCatching { context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }.getOrNull()
            ?: throw ToolException("The calendar refused the event.")
        val id = ContentUris.parseId(uri)
        i.show(
            AssistantCard(
                "event-$id-$start", CardIcon.CALENDAR, title,
                listOfNotNull(whenLabel(start, allDay), i.optString("location")).joinToString(" · "),
                label = "Event added",
                action = viewEvent(id, start, end),
            ),
        )
        "Added \"$title\" on " + (if (allDay) "${isoDay(start, utc = true)}, all day" else isoTime(start)) + "."
    }

    /** The primary writable, visible calendar (usually the Google account's own). */
    private fun mainCalendar(): Long? = context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.ACCOUNT_TYPE),
        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
        arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null,
    )?.use { c ->
        val all = buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getInt(1) == 1, c.getString(2))) }
        (all.firstOrNull { it.second && it.third == "com.google" } ?: all.firstOrNull { it.second } ?: all.firstOrNull())?.first
    }

    private fun viewEvent(id: Long, begin: Long, end: Long) = CardAction.Launch(
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end),
    )

    private fun whenLabel(millis: Long, allDay: Boolean): String {
        if (allDay) {
            return DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_UTC or DateUtils.FORMAT_SHOW_DATE)
        }
        val flags = DateUtils.FORMAT_SHOW_TIME or if (DateUtils.isToday(millis)) 0 else DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
        return DateUtils.formatDateTime(context, millis, flags)
    }

    // ---------------- Navigation ----------------

    private val navigate = assistantTool(
        "navigate",
        "Start turn-by-turn navigation to a place in the user's navigation app (Google Maps or Waze, as they chose).",
        ToolParam("destination", "string", "Address or place, e.g. \"Lidl\" or \"Hauptbahnhof Hamburg\"."),
        ToolParam("mode", "string", "How to get there. Default driving.", required = false, enum = listOf("driving", "walking", "bicycling", "transit")),
    ) { i ->
        val destination = i.string("destination")
        val mode = i.optString("mode") ?: "driving"
        val hasGoogleMaps = installed(GOOGLE_MAPS)
        val app = navigationApp()
        val useWaze = app == NavigationApp.WAZE && mode == "driving" && installed(WAZE)
        val intent = when {
            useWaze ->
                // Waze searches the text and drives to the best match.
                Intent(Intent.ACTION_VIEW, Uri.parse("waze://?q=${Uri.encode(destination)}&navigate=yes")).setPackage(WAZE)
            app == NavigationApp.SYSTEM && mode == "driving" ->
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}"))
            hasGoogleMaps && mode != "transit" ->
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${enc(destination)}&mode=${mode.first()}")).setPackage(GOOGLE_MAPS)
            hasGoogleMaps ->
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&travelmode=transit&destination=${enc(destination)}"))
                    .setPackage(GOOGLE_MAPS)
            else -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${enc(destination)}"))
        }
        if (!context.startFromBackground(intent)) throw ToolException("No maps app could open that.")
        val where = if (useWaze) "Waze" else "Navigation"
        i.show(AssistantCard(
            "navigate", CardIcon.NAVIGATION, destination, "$where · $mode",
            label = mode, detail = if (useWaze) "in Waze" else "route started", action = CardAction.Launch(intent),
        ))
        if (useWaze) "Waze is routing to its best match for \"$destination\" ($mode)." else "Navigation to $destination started ($mode)."
    }

    // ---------------- Helpers ----------------

    private fun installed(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun parseStart(text: String): Long = parse(text, ZoneId.systemDefault())

    private fun parse(text: String, zone: ZoneId): Long = runCatching {
        val t = text.trim()
        if (t.contains('T')) LocalDateTime.parse(t.take(16)).atZone(zone).toInstant().toEpochMilli()
        else LocalDate.parse(t).atStartOfDay(zone).toInstant().toEpochMilli()
    }.getOrElse { throw ToolException("\"$text\" isn't a date like 2026-11-02 or 2026-11-02T14:30.") }

    private fun isoTime(millis: Long) =
        DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm", Locale.ENGLISH).format(java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private fun isoDay(millis: Long, utc: Boolean) =
        DateTimeFormatter.ofPattern("EEE yyyy-MM-dd", Locale.ENGLISH)
            .format(java.time.Instant.ofEpochMilli(millis).atZone(if (utc) ZoneOffset.UTC else ZoneId.systemDefault()))

    private companion object {
        const val GOOGLE_MAPS = "com.google.android.apps.maps"
        const val WAZE = "com.waze"
        const val LOCATION_TIMEOUT_S = 6L
        const val MAX_EVENTS = 12

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        fun missing(what: String) =
            "Dynamic Island isn't allowed to use $what. It can be allowed in Dynamic Island → Assistant → Tool permissions."

        /** WMO weather codes, as Open-Meteo reports them. */
        fun weatherLabel(code: Int) = when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55, 56, 57 -> "Drizzle"
            61, 63, 66 -> "Rain"
            65, 67 -> "Heavy rain"
            71, 73, 75, 77 -> "Snow"
            80, 81 -> "Showers"
            82 -> "Heavy showers"
            85, 86 -> "Snow showers"
            95, 96, 99 -> "Thunderstorm"
            else -> "Unknown"
        }

        fun weatherIcon(code: Int) = when (code) {
            0, 1 -> CardIcon.SUN
            2 -> CardIcon.PARTLY_CLOUDY
            3 -> CardIcon.CLOUD
            45, 48 -> CardIcon.FOG
            71, 73, 75, 77, 85, 86 -> CardIcon.SNOW
            95, 96, 99 -> CardIcon.STORM
            else -> CardIcon.RAIN
        }
    }
}
