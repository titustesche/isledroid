package com.titus.dynamicisland.data.system

enum class RingerMode { NORMAL, VIBRATE, SILENT }

/** The audio mode as far as calls go: ringing, talking, or neither. */
enum class CallAudio { NONE, RINGING, ACTIVE }

/** Short-lived system events the idle island announces. */
sealed interface SystemEvent {
    /** Same kind replaces the one on screen (e.g. repeated volume presses) instead of queueing. */
    val kind: String

    data class Charging(val level: Int) : SystemEvent { override val kind get() = "charging" }
    data class LowBattery(val level: Int) : SystemEvent { override val kind get() = "low_battery" }
    data class Ringer(val mode: RingerMode) : SystemEvent { override val kind get() = "ringer" }
    data class Dnd(val on: Boolean) : SystemEvent { override val kind get() = "dnd" }
    data class Bluetooth(val name: String, val connected: Boolean, val battery: Int? = null) : SystemEvent {
        override val kind get() = "bluetooth"
    }
    /** [stream] is an AudioManager.STREAM_* constant. */
    data class Volume(val stream: Int, val level: Int, val max: Int) : SystemEvent {
        override val kind get() = "volume"
        val fraction get() = if (max <= 0) 0f else level.toFloat() / max
    }
}

/** Current device state, for indicators (flashlight) and the quick-tools toggles. */
data class SystemState(
    val torchOn: Boolean = false,
    /** Current flashlight brightness, 1..[torchMaxLevel]. */
    val torchLevel: Int = 1,
    /** Highest brightness the flashlight supports; 1 means it can't be dimmed (or Android < 13). */
    val torchMaxLevel: Int = 1,
    val ringer: RingerMode = RingerMode.NORMAL,
    val dndOn: Boolean = false,
    val batteryLevel: Int = -1,
    val charging: Boolean = false,
    /** Connected Bluetooth devices, with battery where they report it. */
    val bluetooth: List<BluetoothDeviceInfo> = emptyList(),
    /** What the phone's audio says about calls: the system knows when it rings, the notification only hints. */
    val callAudio: CallAudio = CallAudio.NONE,
) {
    val torchDimmable get() = torchMaxLevel > 1
    val torchFraction get() = if (torchDimmable) (torchLevel - 1f) / (torchMaxLevel - 1) else 1f
}
