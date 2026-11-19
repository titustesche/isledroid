package com.titus.dynamicisland.data.situations

/**
 * The Wi-Fi network the phone is on, from network callbacks. Only the loss of the current network clears it:
 * when switching networks, the new one can report before the old one is lost. Pure, for tests.
 */
class WifiState {
    private var current: Any? = null

    /** Without quotes; null when not on Wi-Fi or when the name is hidden. */
    var ssid: String? = null
        private set

    /** False when Android hides the name (location permission or location services off). */
    var readable: Boolean = true
        private set

    fun onCapabilities(network: Any, rawSsid: String?) {
        current = network
        // Android answers "<unknown ssid>" without location permission or with location off.
        readable = rawSsid != null && rawSsid != UNKNOWN_SSID
        ssid = rawSsid?.takeIf { readable }?.removeSurrounding("\"")
    }

    fun onLost(network: Any) {
        if (network != current) return
        current = null
        ssid = null
        // Not on Wi-Fi at all is known for certain.
        readable = true
    }

    private companion object {
        /** WifiManager.UNKNOWN_SSID, which is only public from Android 11. */
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
