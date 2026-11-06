package com.titus.dynamicisland.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.titus.dynamicisland.appGraph
import com.titus.dynamicisland.data.settings.QuickTool
import com.titus.dynamicisland.domain.PreviewSample
import com.titus.dynamicisland.ui.island.MAX_TOOLS

/** BLUETOOTH_CONNECT: granted state, and a way to ask for it (restarting the monitor once granted). */
@Composable
private fun rememberBluetoothPermission(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    fun granted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    var ok by remember { mutableStateOf(granted()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ok = granted() }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { yes ->
        ok = yes
        if (yes) context.appGraph.system.restart()
    }
    return ok to { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ask.launch(Manifest.permission.BLUETOOTH_CONNECT) }
}

@Composable
private fun BluetoothPermissionRow(why: String, ask: () -> Unit) {
    SettingRow("Bluetooth permission needed", why) { TermButton("allow", filled = true, onClick = ask) }
}

// ---------------- System events ----------------

@Composable
fun SystemEventsScreen(s: SettingsHandle, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val v = s.value
    val (bluetoothGranted, askBluetooth) = rememberBluetoothPermission()

    SettingsScaffold("System events", onBack, v, preview = PreviewSample.EVENT) {
        header("Announcements")
        note("Short pills when something changes. Tap a color dot to change its color.")
        item {
            SwitchColorSetting("Charging", v.eventCharging, { on -> s.update { it.copy(eventCharging = on) } },
                v.chargingColor, { c -> s.update { it.copy(chargingColor = c) } })
        }
        item {
            SwitchColorSetting("Low battery", v.eventLowBattery, { on -> s.update { it.copy(eventLowBattery = on) } },
                v.lowBatteryColor, { c -> s.update { it.copy(lowBatteryColor = c) } }, summary = "At 20 %, 10 % and 5 %")
        }
        item {
            SwitchColorSetting("Sound mode", v.eventRinger, { on -> s.update { it.copy(eventRinger = on) } },
                v.silentColor, { c -> s.update { it.copy(silentColor = c) } }, summary = "Ring, vibrate, silent (the color is silent's)")
        }
        item {
            SwitchColorSetting("Do not disturb", v.eventDnd, { on -> s.update { it.copy(eventDnd = on) } },
                v.dndColor, { c -> s.update { it.copy(dndColor = c) } })
        }
        item {
            SwitchColorSetting(
                "Bluetooth devices", v.eventBluetooth,
                { on ->
                    s.update { it.copy(eventBluetooth = on) }
                    if (on && !bluetoothGranted) askBluetooth()
                },
                v.bluetoothColor, { c -> s.update { it.copy(bluetoothColor = c) } },
                summary = "Connected / disconnected, with battery if the device reports it",
            )
        }
        if (v.eventBluetooth && !bluetoothGranted) {
            item { BluetoothPermissionRow("Required to see device names and connections", askBluetooth) }
        }
        item { IntSliderSetting("Show for", v.eventDurationSec, 1..8, "s") { n -> s.update { it.copy(eventDurationSec = n) } } }

        header("Indicators")
        item {
            SwitchColorSetting("Flashlight", v.eventTorch, { on -> s.update { it.copy(eventTorch = on) } },
                v.torchColor, { c -> s.update { it.copy(torchColor = c) } },
                summary = "Stays while it's on. Long-press to turn it off, expand it for the brightness")
        }
        item {
            SwitchColorSetting(
                "Volume bar", v.volumeHud, { on -> s.update { it.copy(volumeHud = on) } },
                v.volumeColor, { c -> s.update { it.copy(volumeColor = c) } },
                summary = "Volume keys show a bar in the island instead of the system panel. Calls keep the normal one",
            )
        }
        item {
            NavRow(
                "Device batteries",
                if (v.batteryDevices.isEmpty()) "Show Bluetooth batteries in the idle pill" else "${v.batteryDevices.size} in the idle pill",
                Icons.Rounded.BatteryFull,
            ) { onNavigate(Routes.BATTERIES) }
        }
    }
}

// ---------------- Device batteries ----------------

@Composable
fun DeviceBatteriesScreen(s: SettingsHandle, onBack: () -> Unit) {
    val v = s.value
    val context = LocalContext.current
    val (granted, ask) = rememberBluetoothPermission()
    SettingsScaffold("Device batteries", onBack, v, preview = PreviewSample.BATTERIES) {
        note("While they're connected, the idle pill shows these devices' battery (at most two at a time).")
        if (!granted) {
            item { BluetoothPermissionRow("To list your devices and read their battery", ask) }
        } else {
            // Picked devices that are no longer paired stay listed, so they can be unpicked.
            val names = (pairedDeviceNames(context) + v.batteryDevices).distinct()
            if (names.isEmpty()) note("No paired Bluetooth devices.")
            names.forEach { name ->
                item("bt:$name") {
                    PickRow(name, name in v.batteryDevices) {
                        s.update { it.copy(batteryDevices = if (name in it.batteryDevices) it.batteryDevices - name else it.batteryDevices + name) }
                    }
                }
            }
            note("Only devices that report their battery to the phone show up. Headphone battery with music is on the Music page.")
        }
    }
}

@SuppressLint("MissingPermission")
private fun pairedDeviceNames(context: Context): List<String> = runCatching {
    context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
        .map { (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.alias else null) ?: it.name ?: it.address }
        .sortedBy { it.lowercase() }
}.getOrDefault(emptyList())

// ---------------- Quick tools ----------------

@Composable
fun QuickToolsScreen(s: SettingsHandle, onBack: () -> Unit, onPickApps: (AppPickerTarget) -> Unit) {
    val v = s.value
    SettingsScaffold("Quick tools", onBack, v, preview = PreviewSample.TOOLS) {
        note("A panel of shortcuts in the island. Open it with a gesture, set under Gestures (by default: long-press the idle pill).")
        item {
            ColorSetting("Color", v.quickToolsColor, { c -> s.update { it.copy(quickToolsColor = c) } },
                summary = "Tools that are switched on")
        }
        item {
            NavRow(
                "Favourite apps",
                if (v.favoriteApps.isEmpty()) "None — pick up to $MAX_TOOLS" else "${v.favoriteApps.size} selected",
                Icons.Rounded.Apps,
            ) { onPickApps(AppPickerTarget.FAVORITES) }
        }
        header("Tools")
        note("Pick up to $MAX_TOOLS. They appear in the order you pick them.")
        QuickTool.entries.forEach { tool ->
            item(tool.name) {
                val index = v.quickTools.indexOf(tool)
                val picked = index >= 0
                PickRow(
                    tool.label, picked,
                    summary = if (picked) "Position ${index + 1}" else null,
                    enabled = picked || v.quickTools.size < MAX_TOOLS,
                ) {
                    s.update {
                        when {
                            tool in it.quickTools -> it.copy(quickTools = it.quickTools - tool)
                            it.quickTools.size < MAX_TOOLS -> it.copy(quickTools = it.quickTools + tool)
                            else -> it
                        }
                    }
                }
            }
        }
    }
}
