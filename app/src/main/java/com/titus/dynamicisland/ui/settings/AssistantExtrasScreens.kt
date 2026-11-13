package com.titus.dynamicisland.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titus.dynamicisland.appGraph
import com.titus.dynamicisland.data.assistant.ToolException
import com.titus.dynamicisland.data.settings.HttpAction
import com.titus.dynamicisland.data.settings.WolDevice
import com.titus.dynamicisland.domain.NetworkTools
import com.titus.dynamicisland.domain.PreviewSample
import com.titus.dynamicisland.ui.theme.Term
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------- Devices & web actions ----------------

/** Computers to wake and web requests to fire, each by a name the assistant hears. */
@Composable
fun NetworkScreen(s: SettingsHandle, onBack: () -> Unit) {
    val v = s.value
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    // Device in the dialog: its index, -1 for a new one, null when the dialog is closed.
    var deviceDialog by remember { mutableStateOf<Int?>(null) }
    var actionDialog by remember { mutableStateOf<Int?>(null) }

    fun test(what: String, block: () -> String) {
        status = "$what …"
        scope.launch {
            status = withContext(Dispatchers.IO) {
                try { block() } catch (e: ToolException) { e.message } catch (e: Exception) { e.message ?: "Failed" }
            }
        }
    }

    SettingsScaffold("Devices & actions", onBack, v, preview = PreviewSample.ASSISTANT) {
        header("Wake-on-LAN")
        note("\"Wake my PC\": the assistant sends a magic packet. The device needs Wake-on-LAN switched on (BIOS and network card) and the phone must be on the same network.")
        v.wolDevices.forEachIndexed { index, d ->
            item("wol-$index-${d.name}") {
                SettingRow(d.name, "${d.mac} → ${d.broadcast}" + (if (d.port != 9) ":${d.port}" else ""), onClick = { deviceDialog = index }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TermButton("wake") { test("Waking ${d.name}") { NetworkTools.wake(d, v.wolRepeats); "Sent to ${d.name}." } }
                        TermButton("remove", danger = true) {
                            s.update { it.copy(wolDevices = it.wolDevices.filterIndexed { i, _ -> i != index }) }
                        }
                    }
                }
            }
        }
        item("wol-add") { TermButton("[ add device ]", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) { deviceDialog = -1 } }
        item { IntSliderSetting("Packets per wake", v.wolRepeats, 1..10, "") { n -> s.update { it.copy(wolRepeats = n) } } }

        header("Web actions")
        note(
            "\"Lights on\", \"open the garage\": a web request by name. Works with Home Assistant (REST API or webhooks), " +
                "IFTTT, Shelly and other smart plugs with a local API, routers … Google Home itself has no API apps may use; " +
                "with \"Use apps for you\" on, the assistant switches those devices in the Google Home app instead. " +
                "Links into apps work too: \"kdeconnect://runcommand/<device>/<command>\" runs a KDE Connect command.",
        )
        v.httpActions.forEachIndexed { index, a ->
            item("http-$index-${a.name}") {
                SettingRow(a.name, (if (a.isWeb) "${a.method} ${a.url}" else a.url) + a.description.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty(),
                    onClick = { actionDialog = index }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TermButton("run") {
                            test("Running ${a.name}") {
                                if (!a.isWeb) { NetworkTools.open(context, a); "Opened." }
                                else NetworkTools.request(a, v.httpTimeoutSec).let { (code, body) -> "HTTP $code ${body.take(120)}" }
                            }
                        }
                        TermButton("remove", danger = true) {
                            s.update { it.copy(httpActions = it.httpActions.filterIndexed { i, _ -> i != index }) }
                        }
                    }
                }
            }
        }
        item("http-add") { TermButton("[ add web action ]", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) { actionDialog = -1 } }
        item { IntSliderSetting("Give up after", v.httpTimeoutSec, 2..30, "s") { n -> s.update { it.copy(httpTimeoutSec = n) } } }
        status?.let { msg -> note(msg) }
        note("Names are what you say. Headers are one \"Name: value\" per line, e.g. \"Authorization: Bearer …\" for Home Assistant. Stored on this phone; the assistant sees names and descriptions only.")
    }

    deviceDialog?.let { index ->
        val editing = v.wolDevices.getOrNull(index)
        DeviceDialog(editing, onDismiss = { deviceDialog = null }) { d ->
            s.update { st ->
                st.copy(wolDevices = if (editing != null) st.wolDevices.mapIndexed { i, old -> if (i == index) d else old } else st.wolDevices + d)
            }
            deviceDialog = null
        }
    }
    actionDialog?.let { index ->
        val editing = v.httpActions.getOrNull(index)
        ActionDialog(editing, onDismiss = { actionDialog = null }) { a ->
            s.update { st ->
                st.copy(httpActions = if (editing != null) st.httpActions.mapIndexed { i, old -> if (i == index) a else old } else st.httpActions + a)
            }
            actionDialog = null
        }
    }
}

@Composable
private fun DeviceDialog(editing: WolDevice?, onDismiss: () -> Unit, onSave: (WolDevice) -> Unit) {
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var mac by remember { mutableStateOf(editing?.mac.orEmpty()) }
    var broadcast by remember { mutableStateOf(editing?.broadcast ?: "255.255.255.255") }
    var port by remember { mutableStateOf((editing?.port ?: 9).toString()) }
    val device = WolDevice(name.trim(), mac.trim(), broadcast.trim(), port.toIntOrNull() ?: 9)
    val valid = device.name.isNotEmpty() && device.macBytes() != null && device.port in 1..65535
    TermDialog(
        if (editing != null) "Edit ${editing.name}" else "Add a device",
        onDismiss,
        buttons = {
            TermButton("cancel", onClick = onDismiss)
            TermButton(if (editing != null) "save" else "add", filled = valid) { if (valid) onSave(device) }
        },
    ) {
        TermField("Name", name, { name = it }, placeholder = "PC")
        TermField("MAC address", mac, { mac = it }, placeholder = "AA:BB:CC:DD:EE:FF")
        if (mac.isNotBlank() && device.macBytes() == null) {
            Text("That isn't a MAC address.", fontSize = 13.sp, color = Term.Dim, modifier = Modifier.padding(horizontal = 20.dp))
        }
        TermField("Broadcast address", broadcast, { broadcast = it }, placeholder = "255.255.255.255")
        TermField("UDP port (9 almost always; 7 for some devices)", port, { port = it }, placeholder = "9", keyboard = KeyboardType.Number)
    }
}

@Composable
private fun ActionDialog(editing: HttpAction?, onDismiss: () -> Unit, onSave: (HttpAction) -> Unit) {
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var description by remember { mutableStateOf(editing?.description.orEmpty()) }
    var url by remember { mutableStateOf(editing?.url.orEmpty()) }
    var method by remember { mutableStateOf(editing?.method ?: "POST") }
    var body by remember { mutableStateOf(editing?.body.orEmpty()) }
    var headers by remember { mutableStateOf(editing?.headers.orEmpty()) }
    val action = HttpAction(name.trim(), url.trim(), method, body, headers, description.trim())
    val valid = action.name.isNotEmpty() && action.hasValidUrl
    TermDialog(
        if (editing != null) "Edit ${editing.name}" else "Add a web action",
        onDismiss,
        buttons = {
            TermButton("cancel", onClick = onDismiss)
            TermButton(if (editing != null) "save" else "add", filled = valid) { if (valid) onSave(action) }
        },
    ) {
        TermField("Name", name, { name = it }, placeholder = "Living room lights on")
        TermField("What it does (for the assistant)", description, { description = it }, placeholder = "optional")
        TermField("URL or app link", url, { url = it }, placeholder = "http://homeassistant.local:8123/api/…", keyboard = KeyboardType.Uri)
        if (action.isWeb || url.isBlank()) {
            ChoiceSetting("Method", HttpAction.METHODS, method, { it }) { method = it }
            TermField("Body", body, { body = it }, placeholder = "{\"entity_id\": \"light.living_room\"}")
            TermField("Headers", headers, { headers = it }, placeholder = "Authorization: Bearer …")
        } else if (action.hasValidUrl) {
            Text("An app link: the phone opens it, like tapping it. Method, body and headers don't apply.",
                fontSize = 13.sp, color = Term.Dim, modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}

// ---------------- Habits ----------------

/** What the assistant learned about the user's routines, and how it learns. */
@Composable
fun HabitsScreen(s: SettingsHandle, onBack: () -> Unit, onPickApps: (AppPickerTarget) -> Unit) {
    val v = s.value
    val graph = LocalContext.current.appGraph
    val events by graph.habitLog.events.collectAsStateWithLifecycle()
    val routines = remember(events, v.habitWindowMin, v.habitMinDays, v.habitLookbackDays, v.habitSplitWeekends, v.habitsIgnored, v.habitsIgnoredApps) {
        graph.habits.all(events, v)
    }
    SettingsScaffold("Habits", onBack, v, preview = PreviewSample.ASSISTANT) {
        item {
            SwitchSetting("Learn routines", v.habitsEnabled, { on -> s.update { it.copy(habitsEnabled = on) } },
                summary = "Notices what you do at which time, like a route to work every weekday morning")
        }
        if (v.habitsEnabled) {
            item {
                SwitchSetting("Suggest them", v.habitsSuggest, { on -> s.update { it.copy(habitsSuggest = on) } },
                    summary = "When you talk to the assistant around that time, it offers the routine")
            }
            if (v.habitsSuggest) {
                item { IntSliderSetting("Routines sent along", v.habitsInPrompt, 1..6, "") { n -> s.update { it.copy(habitsInPrompt = n) } } }
            }
            header("Learns from")
            note("What the assistant does for you always counts (routes, music, messages, actions …).")
            item {
                SwitchSetting("Routes in maps apps", v.habitsFromRoutes, { on -> s.update { it.copy(habitsFromRoutes = on) } },
                    summary = "A route you start in Google Maps or Waze yourself")
            }
            item {
                SwitchSetting("Alarms you set", v.habitsFromAlarms, { on -> s.update { it.copy(habitsFromAlarms = on) } },
                    summary = "In any clock app, with the time they ring: \"weekdays around 17:30, an alarm for 6:00 the next morning\"")
            }
            item {
                SwitchSetting("Music you start", v.habitsFromMusic, { on -> s.update { it.copy(habitsFromMusic = on) } },
                    summary = "The artist you put on after a break, in any player")
            }
            item {
                SwitchSetting("Apps you open", v.habitsFromApps, { on -> s.update { it.copy(habitsFromApps = on) } },
                    summary = "Only ones you open at a steady time; apps used all day are ignored")
            }
            item {
                NavRow("Never a routine", countLabel(v.habitsIgnoredApps.size) + " · the clock you open to stop the alarm, …", null) {
                    onPickApps(AppPickerTarget.HABITS)
                }
            }
            header("What counts as a routine")
            item { IntSliderSetting("Around the same time, give or take", v.habitWindowMin, 10..120, "min") { n -> s.update { it.copy(habitWindowMin = (n / 5) * 5) } } }
            item { IntSliderSetting("On at least", v.habitMinDays, 2..10, "days") { n -> s.update { it.copy(habitMinDays = n) } } }
            item { IntSliderSetting("Looking back", v.habitLookbackDays, 7..90, "days") { n -> s.update { it.copy(habitLookbackDays = n) } } }
            item {
                SwitchSetting("Weekdays and weekends apart", v.habitSplitWeekends, { on -> s.update { it.copy(habitSplitWeekends = on) } },
                    summary = "A commute on weekdays doesn't make it a Sunday thing")
            }
        }
        header("Learned")
        if (routines.isEmpty()) note(if (events.isEmpty()) "Nothing yet." else "Nothing regular yet (${events.size} things noted).")
        routines.forEach { h ->
            item("habit-${h.key}-${h.weekend}-${h.minuteOfDay}") {
                val days = when (h.weekend) { true -> "Weekends"; false -> "Weekdays"; null -> "Most days" }
                SettingRow(h.label.replaceFirstChar { it.uppercase() }, "$days around ${h.time} · ${h.days} days") {
                    TermButton("forget") { graph.habitLog.forget(h.key) }
                    // Forgotten it comes back if you keep doing it; ignored it never does.
                    TermButton("ignore", danger = true) {
                        s.update { it.copy(habitsIgnored = it.habitsIgnored + h.key) }
                        graph.habitLog.forget(h.key)
                    }
                }
            }
        }
        if (events.isNotEmpty()) {
            item { TermButton("[ forget everything ]", Modifier.padding(start = 16.dp, top = 16.dp), danger = true) { graph.habitLog.clear() } }
        }
        if (v.habitsIgnored.isNotEmpty()) {
            item {
                SettingRow("Ignored routines", "${v.habitsIgnored.size} never learned again") {
                    TermButton("allow again") { s.update { it.copy(habitsIgnored = emptySet()) } }
                }
            }
        }
        note("\"forget\" clears what was noted, so it comes back if you keep doing it; \"ignore\" never learns it again.")
        note("Stored only on this phone. The routines that fit the moment go to your AI provider with a question.")
    }
}

// ---------------- Calls ----------------

/** Asks for the permission that lets the island answer and end calls through Android. */
@Composable
fun CallPermissionRow() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        if (!granted) context.startActivity(Permissions.appDetails(context))
    }
    key(refresh) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        SettingRow("Permission to answer calls", if (granted) "Allowed" else "Needed, or the phone app's own buttons are used") {
            if (granted) Text("[ok]", fontSize = 14.sp, color = Term.Text, modifier = Modifier.padding(end = 8.dp))
            else TermButton("allow", filled = true) { ask.launch(Manifest.permission.ANSWER_PHONE_CALLS) }
        }
    }
}
