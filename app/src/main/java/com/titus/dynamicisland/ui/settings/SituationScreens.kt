package com.titus.dynamicisland.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titus.dynamicisland.appGraph
import com.titus.dynamicisland.data.situations.BtRef
import com.titus.dynamicisland.data.situations.Condition
import com.titus.dynamicisland.data.situations.ScreenState
import com.titus.dynamicisland.data.situations.SignalSnapshot
import com.titus.dynamicisland.data.situations.Situation
import com.titus.dynamicisland.data.situations.SituationText
import com.titus.dynamicisland.domain.PreviewSample
import com.titus.dynamicisland.domain.SituationEvaluator
import com.titus.dynamicisland.domain.SituationUsers
import com.titus.dynamicisland.ui.theme.Term
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.UUID

private val ActiveGreen = Color(0xFF30D158)

/** All situations: live state, what each needs, on/off; and whether the assistant hears about them. */
@Composable
fun SituationsScreen(s: SettingsHandle, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val v = s.value
    val active by LocalContext.current.appGraph.situations.active.collectAsStateWithLifecycle()
    SettingsScaffold("Situations", onBack, v, preview = null) {
        note("A situation is active while all its conditions hold. Features switch on in the situations you pick for them.")
        v.situations.forEach { sit ->
            item("situation-${sit.id}") {
                val on = sit.id in active
                SettingRow(
                    sit.name.ifBlank { "Unnamed" },
                    SituationText.summary(sit),
                    onClick = { onOpen(sit.id) },
                    leading = { Text("●", fontSize = 14.sp, color = if (on) ActiveGreen else Term.Faint) },
                ) {
                    TermToggle(sit.enabled) { enabled -> s.update { st -> st.copy(situations = st.situations.map { if (it.id == sit.id) it.copy(enabled = enabled) else it }) } }
                }
            }
        }
        item("situation-add") {
            TermButton("[ add situation ]", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
                val new = Situation(UUID.randomUUID().toString(), "Situation ${v.situations.size + 1}")
                s.update { it.copy(situations = it.situations + new) }
                onOpen(new.id)
            }
        }
        header("Assistant")
        item {
            SwitchSetting("Tell the assistant", v.situationsTellAssistant, { on -> s.update { it.copy(situationsTellAssistant = on) } },
                summary = "It knows which situations are active (\"Driving\") and can take them into account")
        }
    }
}

/** One situation: name, conditions with their live state, hold time, who uses it, delete. */
@Composable
fun SituationEditScreen(s: SettingsHandle, id: String, onBack: () -> Unit) {
    val v = s.value
    val graph = LocalContext.current.appGraph
    val snapshot by graph.signals.snapshot.collectAsStateWithLifecycle()
    val active by graph.situations.active.collectAsStateWithLifecycle()
    val sit = v.situations.firstOrNull { it.id == id }
    // Condition in the dialog: its index, -1 while picking the type of a new one, null when closed.
    var editing by remember { mutableStateOf<Int?>(null) }
    var draft by remember { mutableStateOf<Condition?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun change(transform: (Situation) -> Situation) =
        s.update { st -> st.copy(situations = st.situations.map { if (it.id == id) transform(it) else it }) }

    SettingsScaffold(sit?.name?.ifBlank { "Unnamed" } ?: "Situation", onBack, v, preview = null) {
        if (sit == null) {
            note("This situation was deleted.")
            return@SettingsScaffold
        }
        item("name") {
            // Typed text lives here: the stored value comes back asynchronously and would jump the cursor.
            var name by remember(id) { mutableStateOf(sit.name) }
            TermField("Name", name, { typed -> name = typed; change { it.copy(name = typed) } }, placeholder = "Driving")
        }
        note(if (sit.id in active) "Active right now." else if (!sit.enabled) "Switched off." else "Not active right now.")
        header("Conditions (all must hold)")
        sit.conditions.forEachIndexed { index, c ->
            item("condition-$index") {
                val ok = SituationEvaluator.holds(c, snapshot, LocalDateTime.now())
                val why = unreadable(c, snapshot)
                SettingRow(
                    SituationText.describe(c),
                    why ?: if (ok) "Holds right now" else "Doesn't hold right now",
                    onClick = { draft = c; editing = index },
                    leading = { Text(if (ok) "✓" else "✗", fontSize = 15.sp, color = if (ok) ActiveGreen else Term.Faint) },
                ) {
                    TermButton("remove", danger = true) { change { it.copy(conditions = it.conditions.filterIndexed { i, _ -> i != index }) } }
                }
            }
        }
        item("condition-add") {
            TermButton("[ add condition ]", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) { draft = null; editing = -1 }
        }
        header("Timing")
        item {
            IntSliderSetting("Stays active after it ends", sit.holdMinutes, 0..Situation.MAX_HOLD_MINUTES, "min") { n -> change { it.copy(holdMinutes = n) } }
        }
        note("Bridges short gaps, like the car dropping out for a moment at the petrol station.")
        header("Used by")
        val users = SituationUsers.usersOf(id, v)
        note(if (users.isEmpty()) "No feature uses it yet." else users.joinToString())
        item("delete") {
            TermButton("delete situation", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), danger = true) {
                if (users.isEmpty()) {
                    s.update { st -> st.copy(situations = st.situations.filterNot { it.id == id }) }
                    onBack()
                } else confirmDelete = true
            }
        }
    }

    if (sit != null && editing != null) {
        val index = editing!!
        val current = draft
        if (current == null) {
            ConditionTypeDialog(onDismiss = { editing = null }) { picked -> draft = picked }
        } else {
            ConditionDialog(current, isNew = index < 0, onDismiss = { editing = null; draft = null }) { done ->
                change { st -> st.copy(conditions = if (index < 0) st.conditions + done else st.conditions.mapIndexed { i, old -> if (i == index) done else old }) }
                editing = null
                draft = null
            }
        }
    }
    if (confirmDelete && sit != null) {
        TermDialog("Delete ${sit.name}?", onDismiss = { confirmDelete = false }, buttons = {
            TermButton("cancel") { confirmDelete = false }
            TermButton("delete", filled = true, danger = true) {
                confirmDelete = false
                s.update { st -> st.copy(situations = st.situations.filterNot { it.id == id }) }
                onBack()
            }
        }) {
            Hint("Used by ${SituationUsers.usersOf(id, v).joinToString()}. Those features won't switch on by it any more.")
        }
    }
}

/** Why a condition can't be read right now, or null when it can. */
private fun unreadable(c: Condition, s: SignalSnapshot): String? = when {
    c is Condition.Wifi && !s.wifiReadable -> "Wi-Fi name hidden: allow location and keep location on"
    c is Condition.Bluetooth && !s.bluetoothReadable -> "Bluetooth access not allowed: never holds"
    c is Condition.Unknown -> "Made by a newer version of the app; never holds"
    else -> null
}

private val NEW_CONDITIONS: List<Pair<String, Condition>> = listOf(
    "Time" to Condition.Time(9 * 60, 23 * 60, DayOfWeek.entries.toSet()),
    "Bluetooth device" to Condition.Bluetooth(emptyList(), anyCar = false, connected = true),
    "Charging" to Condition.Charging(true),
    "Screen" to Condition.Screen(ScreenState.UNLOCKED),
    "Wi-Fi network" to Condition.Wifi(emptyList(), connected = true),
    "Do Not Disturb" to Condition.Dnd(true),
)

@Composable
private fun ConditionTypeDialog(onDismiss: () -> Unit, onPick: (Condition) -> Unit) {
    TermDialog("Add a condition", onDismiss, buttons = { TermButton("cancel", onClick = onDismiss) }) {
        NEW_CONDITIONS.forEach { (label, template) -> SettingRow(label, onClick = { onPick(template) }) }
    }
}

@Composable
private fun ConditionDialog(initial: Condition, isNew: Boolean, onDismiss: () -> Unit, onSave: (Condition) -> Unit) {
    var c by remember(initial) { mutableStateOf(initial) }
    val valid = when (val x = c) {
        is Condition.Bluetooth -> x.anyCar || x.devices.isNotEmpty()
        is Condition.Wifi -> x.ssids.isNotEmpty()
        is Condition.Time -> x.days.isNotEmpty()
        else -> true
    }
    TermDialog(SituationText.describe(c), onDismiss, buttons = {
        TermButton("cancel", onClick = onDismiss)
        TermButton(if (isNew) "add" else "save", filled = valid) { if (valid) onSave(c) }
    }) {
        when (val x = c) {
            is Condition.Time -> TimeEditor(x) { c = it }
            is Condition.Bluetooth -> BluetoothEditor(x) { c = it }
            is Condition.Wifi -> WifiEditor(x) { c = it }
            is Condition.Charging -> SegmentedSetting("Phone", listOf(true, false), x.charging, { if (it) "charging" else "not charging" }) { c = Condition.Charging(it) }
            is Condition.Dnd -> SegmentedSetting("Do Not Disturb", listOf(true, false), x.on, { if (it) "on" else "off" }) { c = Condition.Dnd(it) }
            is Condition.Screen -> SegmentedSetting("Screen", ScreenState.entries, x.state, { it.label.lowercase() }) { c = Condition.Screen(it) }
            is Condition.Unknown -> Hint("This condition comes from a newer version of the app and can't be edited here.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeEditor(c: Condition.Time, onChange: (Condition.Time) -> Unit) {
    val context = LocalContext.current
    SettingRow("From", SituationText.clock(c.startMin), onClick = { pickTime(context, c.startMin) { onChange(c.copy(startMin = it)) } })
    SettingRow("Until", SituationText.clock(c.endMin), onClick = { pickTime(context, c.endMin) { onChange(c.copy(endMin = it)) } })
    Hint(if (c.startMin == c.endMin) "Same start and end: the whole day." else if (c.endMin < c.startMin) "Runs past midnight into the next morning." else "")
    val accent = MaterialTheme.colorScheme.primary
    FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        DayOfWeek.entries.forEach { d ->
            val on = d in c.days
            Text(
                (if (on) "[x] " else "[ ] ") + SituationText.day(d),
                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (on) accent else Term.Dim,
                modifier = Modifier.padding(vertical = 10.dp).clickable { onChange(c.copy(days = if (on) c.days - d else c.days + d)) },
            )
        }
    }
}

private fun pickTime(context: Context, minutes: Int, onPicked: (Int) -> Unit) {
    TimePickerDialog(context, { _, h, m -> onPicked(h * 60 + m) }, minutes / 60, minutes % 60, true).show()
}

@SuppressLint("MissingPermission") // only listed once BLUETOOTH_CONNECT is granted
@Composable
private fun BluetoothEditor(c: Condition.Bluetooth, onChange: (Condition.Bluetooth) -> Unit) {
    val context = LocalContext.current
    val (granted, ask) = rememberBluetoothPermission()
    SegmentedSetting("When", listOf(true, false), c.connected, { if (it) "connected" else "not connected" }) { onChange(c.copy(connected = it)) }
    PickRow("Any car", c.anyCar, "Any device Android knows as a car kit") { onChange(c.copy(anyCar = !c.anyCar)) }
    if (!granted) {
        SettingRow("Allow Bluetooth access", "To list your paired devices", onClick = ask)
        return
    }
    val bonded = remember {
        runCatching { context.getSystemService(BluetoothManager::class.java).adapter.bondedDevices.map { BtRef(it.address, it.name ?: it.address) } }
            .getOrDefault(emptyList()).sortedBy { it.name.lowercase() }
    }
    // Picked devices that are no longer paired stay listed, so they can be removed.
    (bonded + c.devices.filter { p -> bonded.none { it.address == p.address } }).forEach { ref ->
        val picked = c.devices.any { it.address == ref.address }
        PickRow(ref.name, picked) {
            onChange(c.copy(devices = if (picked) c.devices.filterNot { it.address == ref.address } else c.devices + ref))
        }
    }
}

@Composable
private fun WifiEditor(c: Condition.Wifi, onChange: (Condition.Wifi) -> Unit) {
    val graph = LocalContext.current.appGraph
    val snapshot by graph.signals.snapshot.collectAsStateWithLifecycle()
    var typed by remember { mutableStateOf("") }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { graph.signals.refreshWifi() }
    SegmentedSetting("When", listOf(true, false), c.connected, { if (it) "connected" else "not connected" }) { onChange(c.copy(connected = it)) }
    if (!snapshot.wifiReadable) {
        SettingRow("Allow location", "Android only shows Wi-Fi names to apps with location access, and with location on", onClick = {
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        })
    }
    val known = (listOfNotNull(snapshot.wifiSsid) + graph.signals.seenSsids() + c.ssids).distinct()
    known.forEach { ssid ->
        val picked = ssid in c.ssids
        PickRow(ssid, picked, if (ssid == snapshot.wifiSsid) "connected now" else null) {
            onChange(c.copy(ssids = if (picked) c.ssids - ssid else c.ssids + ssid))
        }
    }
    TermField("Other network name", typed, { typed = it }, placeholder = "Home")
    if (typed.isNotBlank()) {
        Row(Modifier.padding(horizontal = 20.dp)) {
            TermButton("add \"${typed.trim()}\"") { onChange(c.copy(ssids = (c.ssids + typed.trim()).distinct())); typed = "" }
        }
    }
}
