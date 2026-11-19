package com.titus.dynamicisland.data.situations

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.titus.dynamicisland.data.system.SystemMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What situations depend on, live: Bluetooth, charging and Do Not Disturb from [SystemMonitor], plus the screen
 * and the Wi-Fi network, which only situations need. New kinds of signals are added here, not in features.
 */
class Signals(private val context: Context, private val scope: CoroutineScope, private val system: SystemMonitor) {

    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val prefs = context.getSharedPreferences("situations", Context.MODE_PRIVATE)

    private val _snapshot = MutableStateFlow(SignalSnapshot(ready = false))
    private val wifi = WifiState()
    private var readyJob: kotlinx.coroutines.Job? = null
    val snapshot: StateFlow<SignalSnapshot> = _snapshot.asStateFlow()

    private val _recheck = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** Emits on screen-on, a set clock and a time-zone change: moments to look at time conditions again. */
    val recheck: SharedFlow<Unit> = _recheck.asSharedFlow()

    private var started = false
    private var systemJob: kotlinx.coroutines.Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT -> {
                    readScreen()
                    // Bluetooth access may have been granted in the meantime.
                    _snapshot.update { it.copy(bluetoothReadable = bluetoothAllowed()) }
                    if (intent.action == Intent.ACTION_SCREEN_ON) _recheck.tryEmit(Unit)
                }
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> _recheck.tryEmit(Unit)
            }
        }
    }

    private val wifiCallback: ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    onWifi(network, (caps.transportInfo as? WifiInfo)?.ssid)
                override fun onLost(network: Network) = onWifiLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                @Suppress("DEPRECATION")
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    onWifi(network, context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.ssid)
                override fun onLost(network: Network) = onWifiLost(network)
            }
        }

    fun start() {
        if (started) return
        started = true
        readScreen()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                wifiCallback,
            )
        }
        systemJob = scope.launch {
            system.state.collect { st ->
                _snapshot.update {
                    it.copy(bluetooth = st.bluetooth, charging = st.charging, dndOn = st.dndOn, bluetoothReadable = bluetoothAllowed())
                }
            }
        }
        // Battery, paired devices and the Wi-Fi callback all report asynchronously right after starting;
        // until then the snapshot holds placeholders that would switch "not …" situations on by mistake.
        readyJob = scope.launch {
            kotlinx.coroutines.delay(SETTLE_MS)
            _snapshot.update { it.copy(ready = true, bluetoothReadable = bluetoothAllowed()) }
        }
    }

    private fun bluetoothAllowed() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { connectivity.unregisterNetworkCallback(wifiCallback) }
        systemJob?.cancel()
        readyJob?.cancel()
        _snapshot.update { it.copy(ready = false) }
    }

    /** Registers the Wi-Fi callback again, e.g. after location access was granted, so the name is read anew. */
    fun refreshWifi() {
        if (!started) return
        runCatching { connectivity.unregisterNetworkCallback(wifiCallback) }
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                wifiCallback,
            )
        }
    }

    /** Networks seen connected, newest first, for the Wi-Fi picker. */
    fun seenSsids(): List<String> = prefs.getString(KEY_SEEN, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private fun readScreen() {
        _snapshot.update { it.copy(screenOn = power.isInteractive, locked = keyguard.isKeyguardLocked) }
    }

    private fun onWifi(network: Network, rawSsid: String?) {
        synchronized(wifi) { wifi.onCapabilities(network, rawSsid); wifi.ssid to wifi.readable }.let { (ssid, readable) ->
            _snapshot.update { it.copy(wifiSsid = ssid, wifiReadable = readable) }
            if (ssid != null) remember(ssid)
        }
    }

    private fun onWifiLost(network: Network) {
        synchronized(wifi) { wifi.onLost(network); wifi.ssid to wifi.readable }.let { (ssid, readable) ->
            _snapshot.update { it.copy(wifiSsid = ssid, wifiReadable = readable) }
        }
    }

    private fun remember(ssid: String) {
        val list = (listOf(ssid) + seenSsids().filter { it != ssid }).take(MAX_SEEN)
        prefs.edit { putString(KEY_SEEN, list.joinToString("\n")) }
    }

    private companion object {
        const val KEY_SEEN = "seen_ssids"
        const val MAX_SEEN = 20
        /** How long after a start the sources get to report before situations are evaluated. */
        const val SETTLE_MS = 3_000L
    }
}
