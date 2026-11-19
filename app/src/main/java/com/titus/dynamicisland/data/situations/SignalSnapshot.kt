package com.titus.dynamicisland.data.situations

import com.titus.dynamicisland.data.system.BluetoothDeviceInfo

/** Everything situations can depend on, at one moment (time comes separately). */
data class SignalSnapshot(
    val bluetooth: List<BluetoothDeviceInfo> = emptyList(),
    val charging: Boolean = false,
    val dndOn: Boolean = false,
    val screenOn: Boolean = true,
    val locked: Boolean = false,
    /** Connected network's name as Android reports it (may be quoted); null when not on Wi-Fi. */
    val wifiSsid: String? = null,
    /** False when Android hides the network name (location permission or location services off). */
    val wifiReadable: Boolean = true,
    /** False without Bluetooth permission: nothing about connections is known then. */
    val bluetoothReadable: Boolean = true,
    /** False until the sources have reported once after a start; nothing is evaluated before that. */
    val ready: Boolean = true,
)
