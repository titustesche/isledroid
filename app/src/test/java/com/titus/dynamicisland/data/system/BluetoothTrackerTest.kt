package com.titus.dynamicisland.data.system

import com.titus.dynamicisland.data.system.BluetoothTracker.Companion.DEBOUNCE_MS
import com.titus.dynamicisland.data.system.BluetoothTracker.Companion.WINDOW_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothTrackerTest {
    private val t = BluetoothTracker()
    private val watch = "Watch"
    private val buds = "Buds"

    @Test fun shortDropOutIsNeverAnnounced() {
        assertNotNull(t.onConnected(watch, "w", BluetoothKind.WATCH, 0))
        t.onDisconnected(watch, "w", 1_000)
        assertEquals(listOf(watch), t.connected().map { it.name }) // still counts while unconfirmed
        assertNull(t.onConnected(watch, "w", BluetoothKind.WATCH, 4_000))
        assertNull(t.confirmDisconnect(watch, 1_000 + DEBOUNCE_MS))
    }

    @Test fun realDisconnectIsAnnouncedAfterTheDebounce() {
        t.onConnected(watch, "w", BluetoothKind.WATCH, 0)
        t.onDisconnected(watch, "w", 60_000)
        assertNull(t.confirmDisconnect(watch, 60_000 + DEBOUNCE_MS - 1))
        assertEquals(false, t.confirmDisconnect(watch, 60_000 + DEBOUNCE_MS)?.connected)
        assertEquals(emptyList<BluetoothDeviceInfo>(), t.connected())
    }

    @Test fun secondEarbudIsTheSameDevice() {
        assertNotNull(t.onConnected(buds, "left", BluetoothKind.HEADPHONES, 0))
        assertNull(t.onConnected(buds, "right", BluetoothKind.HEADPHONES, 500))
        t.onDisconnected(buds, "left", 60_000)
        assertNull(t.confirmDisconnect(buds, 60_000 + DEBOUNCE_MS)) // the right one is still there
    }

    @Test fun slowFlappingIsRateLimited() {
        assertNotNull(t.onConnected(watch, "w", BluetoothKind.WATCH, 0))
        t.onDisconnected(watch, "w", 1_000)
        assertNotNull(t.confirmDisconnect(watch, 1_000 + DEBOUNCE_MS))
        assertNull(t.onConnected(watch, "w", BluetoothKind.WATCH, 30_000)) // third within the window
        assertEquals(listOf(watch), t.connected().map { it.name }) // but still tracked as connected
        t.onDisconnected(watch, "w", 40_000)
        t.confirmDisconnect(watch, 40_000 + DEBOUNCE_MS)
        assertNotNull(t.onConnected(watch, "w", BluetoothKind.WATCH, WINDOW_MS + 60_000))
    }

    @Test fun batteryOnlyFollowsTheConnectionOrWarns() {
        t.onConnected(buds, "b", BluetoothKind.HEADPHONES, 0)
        assertEquals(80, t.onBattery(buds, 80, 2_000)?.battery) // fills in the "connected" pill
        assertNull(t.onBattery(buds, 70, 600_000)) // ordinary drain: quiet
        assertEquals(70, t.connected().single().battery)
        assertNull(t.onBattery(buds, 21, 700_000))
        assertEquals(20, t.onBattery(buds, 20, 800_000)?.battery) // got low
    }

    @Test fun seededDevicesAreKnownButNotNews() {
        t.seed(buds, "b", BluetoothKind.HEADPHONES, battery = 55)
        assertEquals(BluetoothDeviceInfo(buds, BluetoothKind.HEADPHONES, 55), t.connected().single())
        assertNull(t.onConnected(buds, "b2", BluetoothKind.HEADPHONES, 1_000))
    }
}
