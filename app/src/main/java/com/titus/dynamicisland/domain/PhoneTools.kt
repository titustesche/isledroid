package com.titus.dynamicisland.domain

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.titus.dynamicisland.data.assistant.AssistantCard
import com.titus.dynamicisland.data.assistant.AssistantTool
import com.titus.dynamicisland.data.assistant.CardAction
import com.titus.dynamicisland.data.assistant.CardIcon
import com.titus.dynamicisland.data.assistant.CardLive
import com.titus.dynamicisland.data.assistant.ToolException
import com.titus.dynamicisland.data.assistant.ToolInput
import com.titus.dynamicisland.data.assistant.ToolParam
import com.titus.dynamicisland.data.media.MediaInfo
import com.titus.dynamicisland.data.media.MediaRepository
import com.titus.dynamicisland.data.system.RingerMode
import com.titus.dynamicisland.data.system.SystemMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the assistant can do on the phone: clock, flashlight, sound, music, apps, status. All of it
 * harmless and easy to undo, so it runs without asking first. Results are short, factual strings
 * for the model to phrase.
 */
class PhoneTools(
    private val context: Context,
    private val system: SystemMonitor,
    private val media: MediaRepository,
) {
    fun all(): List<AssistantTool> = listOf(
        setTimer, setAlarm, flashlight, doNotDisturb, ringerMode, setVolume, phoneStatus, music, playMusic, openApp,
    )

    private fun tool(name: String, description: String, vararg params: ToolParam, run: (ToolInput) -> String) =
        assistantTool(name, description, *params, run = run)

    // ---------------- Clock ----------------

    private val setTimer = tool(
        "set_timer",
        "Start a countdown timer in the phone's clock app. While it runs it also shows in the island.",
        ToolParam("seconds", "integer", "Length in seconds, e.g. 300 for five minutes.", minimum = 1, maximum = 86_399),
        ToolParam("label", "string", "Optional short name shown with the timer, e.g. \"Pasta\".", required = false),
    ) { i ->
        val seconds = i.int("seconds")
        val label = i.optString("label")
        startClock(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .apply { label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } },
        )
        i.show(
            AssistantCard(
                key = "timer-${System.currentTimeMillis()}", icon = CardIcon.TIMER, title = duration(seconds),
                subtitle = label ?: "Timer · ${duration(seconds)}",
                label = "Timer · ${label ?: "running"}",
                countdownTo = System.currentTimeMillis() + seconds * 1000L,
                countdownTotalMs = seconds * 1000L,
                action = CardAction.Launch(Intent(AlarmClock.ACTION_SHOW_TIMERS)),
            ),
        )
        "Timer started: ${duration(seconds)}."
    }

    private val setAlarm = tool(
        "set_alarm",
        "Set an alarm in the phone's clock app (24-hour time).",
        ToolParam("hour", "integer", "Hour, 0 to 23.", minimum = 0, maximum = 23),
        ToolParam("minute", "integer", "Minute, 0 to 59.", minimum = 0, maximum = 59),
        ToolParam("label", "string", "Optional short name for the alarm.", required = false),
        ToolParam(
            "repeat_days", "string",
            "For a repeating alarm: comma-separated English weekday names, e.g. \"monday,tuesday,friday\". Omit for a one-time alarm.",
            required = false,
        ),
    ) { i ->
        val hour = i.int("hour")
        val minute = i.int("minute")
        val days = i.optString("repeat_days")?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }?.filter { it.isNotEmpty() }
            ?.map { WEEKDAYS[it] ?: throw ToolException("Unknown weekday \"$it\".") }
        startClock(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .apply {
                    i.optString("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                    if (!days.isNullOrEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.distinct()))
                },
        )
        val label = i.optString("label")
        i.show(
            AssistantCard(
                key = "alarm-$hour-$minute", icon = CardIcon.ALARM, title = clockTime(hour, minute),
                subtitle = listOfNotNull(label, repeatLabel(days) ?: nextDayLabel(hour, minute)).joinToString(" · "),
                label = "Alarm · " + (repeatLabel(days) ?: nextDayLabel(hour, minute)),
                detail = label,
                action = CardAction.Launch(Intent(AlarmClock.ACTION_SHOW_ALARMS)),
            ),
        )
        "Alarm set for %02d:%02d%s.".format(Locale.ROOT, hour, minute, if (days.isNullOrEmpty()) "" else ", repeating")
    }

    private fun clockTime(hour: Int, minute: Int): String {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute) }
        return android.text.format.DateFormat.getTimeFormat(context).format(c.time)
    }

    private fun repeatLabel(days: List<Int>?): String? {
        if (days.isNullOrEmpty()) return null
        if (days.distinct().size == 7) return "Every day"
        val names = java.text.DateFormatSymbols.getInstance().shortWeekdays
        val order = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
        return order.filter { it in days }.joinToString(", ") { names[it] }
    }

    private fun nextDayLabel(hour: Int, minute: Int): String {
        val now = Calendar.getInstance()
        val passed = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE) >= hour * 60 + minute
        return if (passed) "Tomorrow" else "Today"
    }

    private fun startClock(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), backgroundStartOptions())
        } catch (e: ActivityNotFoundException) {
            throw ToolException("No clock app on this phone handles that.")
        } catch (e: SecurityException) {
            throw ToolException("The clock app refused the request.")
        }
    }

    // ---------------- Device ----------------

    private val flashlight = tool(
        "flashlight",
        "Turn the flashlight on or off, optionally at a brightness.",
        ToolParam("on", "boolean", "true to turn it on, false to turn it off."),
        ToolParam(
            "brightness", "number", "Optional, 0.0 (dimmest) to 1.0 (brightest). Only some phones can dim it.",
            required = false, minimum = 0, maximum = 1,
        ),
    ) { i ->
        val on = i.bool("on")
        val brightness = i.optNumber("brightness")
        val st = system.state.value
        when {
            !on -> system.setTorch(false)
            brightness != null && st.torchDimmable -> system.setTorchLevel(brightness.toFloat())
            else -> system.setTorch(true)
        }
        i.show(AssistantCard("torch", CardIcon.FLASHLIGHT, "Flashlight", live = CardLive.TORCH, action = CardAction.ToggleTorch))
        when {
            !on -> "Flashlight off."
            brightness != null && !st.torchDimmable -> "Flashlight on. This phone can't dim it, so it's at full brightness."
            brightness != null -> "Flashlight on at ${(brightness * 100).roundToInt()} percent."
            else -> "Flashlight on."
        }
    }

    private val doNotDisturb = tool(
        "do_not_disturb",
        "Turn Do Not Disturb on or off.",
        ToolParam("on", "boolean", "true to turn it on, false to turn it off."),
    ) { i ->
        val on = i.bool("on")
        if (!system.setDnd(on)) throw ToolException(NO_DND_ACCESS)
        i.show(AssistantCard("dnd", CardIcon.DND, "Do Not Disturb", live = CardLive.DND, action = CardAction.ToggleDnd))
        if (on) "Do Not Disturb on." else "Do Not Disturb off."
    }

    private val ringerMode = tool(
        "ringer_mode",
        "Switch the ringer between ring, vibrate and silent.",
        ToolParam("mode", "string", "The new mode.", enum = listOf("ring", "vibrate", "silent")),
    ) { i ->
        val mode = when (i.string("mode")) {
            "vibrate" -> RingerMode.VIBRATE
            "silent" -> RingerMode.SILENT
            else -> RingerMode.NORMAL
        }
        if (!system.setRingerMode(mode)) {
            throw ToolException(if (mode == RingerMode.SILENT) NO_DND_ACCESS else "Android refused to change the ringer.")
        }
        i.show(
            AssistantCard(
                "ringer",
                when (mode) { RingerMode.VIBRATE -> CardIcon.VIBRATE; RingerMode.SILENT -> CardIcon.SILENT; else -> CardIcon.RING },
                i.string("mode").replaceFirstChar { it.uppercase() }, "Sound mode",
                action = CardAction.Launch(Intent(Settings.ACTION_SOUND_SETTINGS)),
            ),
        )
        "Ringer set to ${i.string("mode")}."
    }

    private val setVolume = tool(
        "set_volume",
        "Set a volume. To change it relative to now (louder, quieter), read the current level with phone_status first.",
        ToolParam("stream", "string", "Which volume.", enum = STREAMS.keys.toList()),
        ToolParam("percent", "integer", "New volume, 0 to 100.", minimum = 0, maximum = 100),
    ) { i ->
        val name = i.string("stream")
        val stream = STREAMS.getValue(name)
        system.setVolume(stream, i.int("percent") / 100f)
        val label = "${name.replaceFirstChar { it.uppercase() }} volume"
        i.show(AssistantCard("volume-$name", CardIcon.VOLUME, "${system.volumePercent(stream)} %", label,
            level = system.volumePercent(stream) / 100f, action = CardAction.ShowVolume(stream)))
        "$label now ${system.volumePercent(stream)} percent."
    }

    private val phoneStatus = tool(
        "phone_status",
        "Battery, charging, ringer, Do Not Disturb, flashlight and volume levels.",
    ) { i ->
        val st = system.state.value
        i.show(
            AssistantCard(
                "battery", CardIcon.BATTERY, "${st.batteryLevel} %", if (st.charging) "Battery · charging" else "Battery",
                level = st.batteryLevel / 100f,
                action = CardAction.Launch(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)),
            ),
        )
        buildString {
            append("Battery ${st.batteryLevel} percent, ${if (st.charging) "charging" else "not charging"}. ")
            append("Ringer: ${st.ringer.name.lowercase(Locale.ROOT).replace("normal", "ring")}. ")
            append("Do Not Disturb: ${if (st.dndOn) "on" else "off"}. ")
            append("Flashlight: ${if (st.torchOn) "on" else "off"}. ")
            append(STREAMS.entries.joinToString(", ") { (n, s) -> "$n volume ${system.volumePercent(s)} percent" })
            append(".")
        }
    }

    // ---------------- Music ----------------

    private val music = tool(
        "music",
        "Control music or other media playing on the phone, or find out what's playing.",
        ToolParam("action", "string", "What to do.", enum = listOf("status", "play", "pause", "next", "previous")),
    ) { i ->
        val info = media.media.value
        val action = i.string("action")
        if (info != null || action == "play") {
            i.show(AssistantCard("media", CardIcon.MUSIC, "Music", live = CardLive.MEDIA, action = CardAction.OpenPlayer))
        }
        when (action) {
            "status" -> when {
                info == null -> "Nothing is playing."
                else -> "${if (info.isPlaying) "Playing" else "Paused"}: \"${info.title}\"" +
                    (info.artist?.let { " by $it" } ?: "") + " in ${appLabel(info.packageName)}."
            }
            // Anything that starts sound waits for the answer to end (see ToolInput.atEnd); pausing doesn't.
            "play" -> {
                i.atEnd { media.play() }
                if (info == null) "Asked the last used player to resume." else "Playing \"${info.title}\"."
            }
            "pause" -> if (info == null) "Nothing is playing." else { media.pause(); "Paused." }
            "next" -> if (info == null) throw ToolException("Nothing is playing.") else { i.atEnd { media.next() }; "Skipping to the next track." }
            else -> if (info == null) throw ToolException("Nothing is playing.") else { i.atEnd { media.previous() }; "Going back a track." }
        }
    }

    private val main = Handler(Looper.getMainLooper())

    private val playMusic = tool(
        "play_music",
        "Play a particular song, artist, album, playlist or genre in a music app (Spotify, YouTube Music …). " +
            "To resume, pause or skip, use music instead.",
        ToolParam("name", "string", "What to play as the user said it: the title, artist, album, playlist or genre, e.g. \"Discover Weekly\"."),
        ToolParam("type", "string", "What kind of thing name is. any if unclear.", enum = PLAY_FOCUS.keys.toList()),
        ToolParam("artist", "string", "Optional: the artist, for a song or album.", required = false),
        ToolParam("app", "string", "Optional: the app the user named, e.g. \"Spotify\". Omit to use the current or default player.", required = false),
    ) { i ->
        val name = i.string("name")
        val type = i.string("type")
        val artist = i.optString("artist")?.takeIf { type == "song" || type == "album" }
        val query = listOfNotNull(name, artist).joinToString(" ")
        val extras = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, PLAY_FOCUS.getValue(type))
            PLAY_EXTRA[type]?.let { putString(it, name) }
            artist?.let { putString(MediaStore.EXTRA_MEDIA_ARTIST, it) }
        }
        val pkg = pickPlayer(i.optString("app"))
        val label = appLabel(pkg)
        // After the answer, so the music doesn't play over it.
        i.atEnd {
            val before = media.media.value
            if (media.playFromSearch(pkg, query, extras)) {
                // Plays without opening the app. Some players ignore it from us, so check, and fall back to opening it.
                if (pkg !in media.blockedPackages) {
                    main.postDelayed({ if (!startedPlaying(pkg, before)) launchSearch(pkg, query, extras) }, SEARCH_FALLBACK_MS)
                }
            } else if (!launchSearch(pkg, query, extras)) {
                android.util.Log.w("PhoneTools", "Android didn't let $label start")
            }
        }
        i.show(AssistantCard("media", CardIcon.MUSIC, "Music", live = CardLive.MEDIA, action = CardAction.OpenPlayer))
        val what = if (type == "any") "\"$query\"" else "the $type \"$name\"" + (artist?.let { " by $it" } ?: "")
        "Asked $label to play $what."
    }

    /**
     * The player for [appName], else the current one, the most recently used, the system default,
     * or the only one. Only apps that handle a play search count: other apps' media sessions
     * (Signal's voice notes, KDE Connect's remote player) may claim search support but aren't players.
     */
    private fun pickPlayer(appName: String?): String {
        val pm = context.packageManager
        val search = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        val handlers = pm.queryIntentActivities(search, 0).map { it.activityInfo.packageName }.distinct()
        if (handlers.isEmpty()) throw ToolException("No app on this phone can play music by search.")
        if (appName != null) {
            return matchApp(appName, handlers.map { appLabel(it) to it })?.second
                ?: throw ToolException("\"$appName\" isn't installed or can't play music by search.")
        }
        val running = media.searchablePlayers().filter { it in handlers }
        return media.media.value?.packageName?.takeIf { it in handlers }
            ?: running.firstOrNull()
            ?: pm.resolveActivity(search, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.takeIf { it in handlers }
            ?: handlers.singleOrNull()
            ?: throw ToolException("Several apps can play this: ${handlers.joinToString { appLabel(it) }}. Ask which one.")
    }

    private fun launchSearch(pkg: String, query: String, extras: Bundle) = context.startFromBackground(
        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
            .putExtra(SearchManager.QUERY, query).putExtras(extras),
    )

    /** Whether [pkg] is now playing something new (or resumed from a pause) since [before]. */
    private fun startedPlaying(pkg: String, before: MediaInfo?): Boolean {
        val now = media.media.value ?: return false
        return now.packageName == pkg && now.isPlaying && (before?.isPlaying != true || now.title != before.title)
    }

    // ---------------- Apps ----------------

    private val openApp = tool(
        "open_app",
        "Open an installed app by its name.",
        ToolParam("name", "string", "The app's name as the user said it, e.g. \"Spotify\" or \"Kamera\"."),
    ) { i ->
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
        val (label, pkg) = matchApp(i.string("name"), apps)
            ?: throw ToolException("No app called \"${i.string("name")}\" is installed.")
        val launch = pm.getLaunchIntentForPackage(pkg) ?: throw ToolException("$label can't be opened.")
        if (!context.startFromBackground(launch)) throw ToolException("Android didn't let $label open.")
        i.show(AssistantCard("app-$pkg", CardIcon.APP, label, "Opened", label = "Opened", appPackage = pkg, action = CardAction.Launch(launch)))
        "Opened $label."
    }

    /**
     * The (label, package) in [apps] whose name best matches what the user said, or null for none.
     * Several equally loose matches throw, so the model asks which one.
     */
    private fun matchApp(name: String, apps: List<Pair<String, String>>): Pair<String, String>? {
        val query = name.lowercase()
        val ranked = apps.mapNotNull { (label, pkg) ->
            val l = label.lowercase()
            val score = when {
                l == query -> 0
                l.startsWith(query) -> 1
                query in l -> 2
                l in query -> 3
                else -> return@mapNotNull null
            }
            Triple(score, label, pkg)
        }.sortedWith(compareBy({ it.first }, { it.second.length }))
        val best = ranked.firstOrNull() ?: return null
        val tied = ranked.filter { it.first == best.first }
        if (best.first > 0 && tied.size > 1) {
            throw ToolException("Several apps match: ${tied.take(5).joinToString { it.second }}. Ask which one.")
        }
        return best.second to best.third
    }

    private fun appLabel(pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private companion object {
        const val NO_DND_ACCESS =
            "Dynamic Island has no Do Not Disturb access. It can be granted in Android settings under \"Do Not Disturb access\"."

        const val SEARCH_FALLBACK_MS = 4_000L

        /** MediaStore focus types (the platform constants, some deprecated, have these values). */
        val PLAY_FOCUS = linkedMapOf(
            "any" to "vnd.android.cursor.item/*",
            "song" to "vnd.android.cursor.item/audio",
            "artist" to "vnd.android.cursor.item/artist",
            "album" to "vnd.android.cursor.item/album",
            "playlist" to "vnd.android.cursor.item/playlist",
            "genre" to "vnd.android.cursor.item/genre",
        )

        /** Which extra carries the name for each focus. */
        val PLAY_EXTRA = mapOf(
            "song" to MediaStore.EXTRA_MEDIA_TITLE,
            "artist" to MediaStore.EXTRA_MEDIA_ARTIST,
            "album" to MediaStore.EXTRA_MEDIA_ALBUM,
            "playlist" to MediaStore.EXTRA_MEDIA_PLAYLIST,
            "genre" to MediaStore.EXTRA_MEDIA_GENRE,
        )

        val STREAMS = linkedMapOf(
            "media" to AudioManager.STREAM_MUSIC,
            "ring" to AudioManager.STREAM_RING,
            "alarm" to AudioManager.STREAM_ALARM,
        )

        val WEEKDAYS = mapOf(
            "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY, "wednesday" to Calendar.WEDNESDAY,
            "thursday" to Calendar.THURSDAY, "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY,
            "sunday" to Calendar.SUNDAY,
        )

        fun duration(seconds: Int): String {
            val h = seconds / 3600
            val m = seconds % 3600 / 60
            val s = seconds % 60
            return listOfNotNull(
                h.takeIf { it > 0 }?.let { "$it h" },
                m.takeIf { it > 0 }?.let { "$it min" },
                s.takeIf { it > 0 }?.let { "$it s" },
            ).joinToString(" ")
        }
    }
}

/**
 * A tool whose [run] by default happens on the main thread, where Android state lives (the backend
 * calls tools from its network thread). Network tools pass [onMain] = false.
 */
fun assistantTool(
    name: String,
    description: String,
    vararg params: ToolParam,
    onMain: Boolean = true,
    run: (ToolInput) -> String,
) = AssistantTool(name, description, params.toList()) { input ->
    if (!onMain || Looper.myLooper() == Looper.getMainLooper()) run(input) else runBlocking(Dispatchers.Main) { run(input) }
}
