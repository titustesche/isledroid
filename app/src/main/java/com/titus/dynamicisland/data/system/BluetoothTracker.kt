package com.titus.dynamicisland.data.system

/** What a Bluetooth device is, for its symbol. */
enum class BluetoothKind { HEADPHONES, CAR, WATCH, OTHER }

/** A connected Bluetooth device; [battery] once it has reported one. */
data class BluetoothDeviceInfo(val name: String, val kind: BluetoothKind, val battery: Int? = null)

/**
 * Keeps track of connected Bluetooth devices and decides which changes are worth announcing.
 * Pure logic (time is passed in), so it's unit-testable.
 *
 * Devices are told apart by name: true-wireless earbuds connect each bud on its own link, under one name.
 *
 * Announcements are calmed down three ways:
 * - A device that drops out and comes back within [DEBOUNCE_MS] (a watch charging from empty, earbuds
 *   switching links) never left: neither the drop nor the return is announced. That's why a
 *   disconnect is only confirmed later, through [confirmDisconnect].
 * - At most [MAX_PER_WINDOW] announcements per device within [WINDOW_MS], for devices that flap slower than that.
 * - Battery reports don't announce anything, except right after the connection (they fill in its
 *   pill) and when the level drops past [LOW_LEVELS].
 */
class BluetoothTracker {

    private class Device(val name: String, var kind: BluetoothKind) {
        val links = mutableSetOf<String>()
        /** The state last shown (or that would have been, if not rate-limited); null = never seen. */
        var shownConnected: Boolean? = null
        var lostAt = 0L
        var connectedShownAt = Long.MIN_VALUE
        var battery: Int? = null
        val announcements = ArrayDeque<Long>()
    }

    private val devices = LinkedHashMap<String, Device>()

    /** Connected devices, in the order they connected; one that just dropped out counts until that's confirmed. */
    fun connected(): List<BluetoothDeviceInfo> =
        devices.values.filter { it.links.isNotEmpty() || it.shownConnected == true }.map { BluetoothDeviceInfo(it.name, it.kind, it.battery) }

    /** A device that was already connected when we started: known, but not news. */
    fun seed(name: String, address: String, kind: BluetoothKind, battery: Int?) {
        val d = device(name, kind)
        d.links += address
        d.shownConnected = true
        if (battery != null) d.battery = battery
    }

    fun onConnected(name: String, address: String, kind: BluetoothKind, now: Long): SystemEvent.Bluetooth? {
        val d = device(name, kind)
        val alreadyConnected = d.links.isNotEmpty()
        d.links += address
        // Another link of a device that's already there (the second earbud), or back from a blip.
        if (alreadyConnected || d.shownConnected == true) return null
        d.shownConnected = true
        return announce(d, now, SystemEvent.Bluetooth(name, connected = true, battery = d.battery))
            ?.also { d.connectedShownAt = now }
    }

    /** Call [confirmDisconnect] [DEBOUNCE_MS] later; only then is it announced, if it's still gone. */
    fun onDisconnected(name: String, address: String, now: Long) {
        val d = devices[name] ?: device(name, BluetoothKind.OTHER)
        d.links -= address
        if (d.links.isEmpty()) d.lostAt = now
    }

    fun confirmDisconnect(name: String, now: Long): SystemEvent.Bluetooth? {
        val d = devices[name] ?: return null
        if (d.links.isNotEmpty() || now - d.lostAt < DEBOUNCE_MS || d.shownConnected == false) return null
        d.shownConnected = false
        d.battery = null // stale by the time it's back
        return announce(d, now, SystemEvent.Bluetooth(name, connected = false))
    }

    fun onBattery(name: String, level: Int, now: Long): SystemEvent.Bluetooth? {
        val d = device(name, BluetoothKind.OTHER)
        val before = d.battery
        d.battery = level
        if (d.links.isEmpty()) return null
        val freshConnection = now - d.connectedShownAt < BATTERY_FOLLOW_UP_MS
        val droppedLow = before != null && LOW_LEVELS.any { level <= it && before > it }
        return if (freshConnection || droppedLow) SystemEvent.Bluetooth(name, connected = true, battery = level) else null
    }

    private fun device(name: String, kind: BluetoothKind): Device =
        devices.getOrPut(name) { Device(name, kind) }.also { if (kind != BluetoothKind.OTHER) it.kind = kind }

    private fun announce(d: Device, now: Long, event: SystemEvent.Bluetooth): SystemEvent.Bluetooth? {
        while (d.announcements.isNotEmpty() && now - d.announcements.first() >= WINDOW_MS) d.announcements.removeFirst()
        if (d.announcements.size >= MAX_PER_WINDOW) return null
        d.announcements.addLast(now)
        return event
    }

    companion object {
        const val DEBOUNCE_MS = 15_000L
        const val WINDOW_MS = 3 * 60_000L
        const val MAX_PER_WINDOW = 2
        const val BATTERY_FOLLOW_UP_MS = 20_000L
        val LOW_LEVELS = listOf(20, 10, 5)
    }
}
