package com.titus.dynamicisland.data.situations

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiStateTest {
    @Test fun anotherNetworksLossKeepsTheCurrentOne() {
        val w = WifiState()
        w.onCapabilities("old", "\"Home\"")
        w.onCapabilities("new", "\"Work\"")
        w.onLost("old")
        assertEquals("Work" to true, w.ssid to w.readable)
    }

    @Test fun leavingWifiMakesItReadableAgain() {
        val w = WifiState()
        w.onCapabilities("n", "<unknown ssid>")
        assertEquals(null to false, w.ssid to w.readable)
        w.onLost("n")
        assertEquals(null to true, w.ssid to w.readable)
    }
}
