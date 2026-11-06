package com.titus.dynamicisland.data.system

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.os.SystemClock
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothClass
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

/**
 * Watches charging, battery, ringer, Do Not Disturb, Bluetooth and the flashlight, and offers the
 * matching controls. Started by the accessibility service so it lives exactly as long as the island.
 */
class SystemMonitor(private val context: Context) {

    private val _events = MutableSharedFlow<SystemEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<SystemEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(SystemState())
    val state: StateFlow<SystemState> = _state.asStateFlow()

    private val audio = context.getSystemService(AudioManager::class.java)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var lastWarned = 101
    private var torchCameraId: String? = null
    private val prefs = context.getSharedPreferences("system_monitor", Context.MODE_PRIVATE)
    private val bluetooth = BluetoothTracker()

    private fun emitBluetooth(event: SystemEvent.Bluetooth?) {
        _state.update { it.copy(bluetooth = bluetooth.connected()) }
        event?.let(_events::tryEmit)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isInitialStickyBroadcast && intent.action != Intent.ACTION_BATTERY_CHANGED) return
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    _state.update { it.copy(charging = true) }
                    _events.tryEmit(SystemEvent.Charging(batteryLevel()))
                }
                Intent.ACTION_POWER_DISCONNECTED -> _state.update { it.copy(charging = false) }
                Intent.ACTION_BATTERY_CHANGED -> onBattery(intent, initial = isInitialStickyBroadcast)
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    val mode = ringerMode()
                    _state.update { it.copy(ringer = mode) }
                    _events.tryEmit(SystemEvent.Ringer(mode))
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                    val on = dndOn()
                    if (on != _state.value.dndOn) {
                        _state.update { it.copy(dndOn = on) }
                        _events.tryEmit(SystemEvent.Dnd(on))
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = device(intent) ?: return
                    emitBluetooth(bluetooth.onConnected(nameOf(device), device.address, kindOf(device), SystemClock.elapsedRealtime()))
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = device(intent) ?: return
                    val name = nameOf(device)
                    bluetooth.onDisconnected(name, device.address, SystemClock.elapsedRealtime())
                    // Only gone if it stays gone (see BluetoothTracker).
                    handler.postDelayed(
                        { emitBluetooth(bluetooth.confirmDisconnect(name, SystemClock.elapsedRealtime())) },
                        BluetoothTracker.DEBOUNCE_MS + 100,
                    )
                }
                ACTION_BT_BATTERY -> {
                    val device = device(intent) ?: return
                    val level = intent.getIntExtra(EXTRA_BT_BATTERY, -1)
                    if (level in 0..100) emitBluetooth(bluetooth.onBattery(nameOf(device), level, SystemClock.elapsedRealtime()))
                }
            }
        }
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != torchCameraId) return
            _state.update { it.copy(torchOn = enabled) }
            if (enabled) torchLevel(cameraId)?.let(::rememberTorchLevel)
        }

        // Android 15+: brightness changed elsewhere (e.g. the system quick settings tile).
        override fun onTorchStrengthLevelChanged(cameraId: String, newStrengthLevel: Int) {
            if (cameraId == torchCameraId && _state.value.torchOn) rememberTorchLevel(newStrengthLevel)
        }
    }

    fun start() {
        if (started) return
        started = true
        torchCameraId = runCatching {
            camera.cameraIdList.firstOrNull {
                camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
        val id = torchCameraId
        val maxLevel = if (id != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { camera.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) }.getOrNull()
        } else null
        val max = maxLevel ?: 1
        val savedLevel = prefs.getInt(KEY_TORCH_LEVEL, max).coerceIn(1, max)
        _state.update { it.copy(ringer = ringerMode(), dndOn = dndOn(), torchMaxLevel = max, torchLevel = savedLevel) }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            if (hasBluetoothPermission()) {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(ACTION_BT_BATTERY)
            }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        runCatching { camera.registerTorchCallback(torchCallback, handler) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audio.addOnModeChangedListener(ContextCompat.getMainExecutor(context), modeListener) }
        }
        onAudioMode(audio.mode)
        if (hasBluetoothPermission()) seedBluetooth()
    }

    /**
     * Headphones that were connected before we started send no broadcast; ask the audio profiles.
     * (Watches and other devices only show up once they reconnect.)
     */
    @Suppress("MissingPermission")
    private fun seedBluetooth() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val profiles = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, LE_AUDIO_PROFILE)
        profiles.forEach { profile ->
            runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        runCatching {
                            proxy.connectedDevices.forEach { d -> bluetooth.seed(nameOf(d), d.address, kindOf(d), batteryOf(d)) }
                        }
                        runCatching { adapter.closeProfileProxy(p, proxy) }
                        handler.post { emitBluetooth(null) }
                    }

                    override fun onServiceDisconnected(p: Int) = Unit
                }, profile)
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { camera.unregisterTorchCallback(torchCallback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { audio.removeOnModeChangedListener(modeListener) }
    }

    // Android 12+ only; older phones keep guessing from the call notification.
    private val modeListener = AudioManager.OnModeChangedListener { mode -> onAudioMode(mode) }

    private fun onAudioMode(mode: Int) {
        val call = when (mode) {
            AudioManager.MODE_RINGTONE -> CallAudio.RINGING
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, MODE_CALL_REDIRECT, MODE_COMMUNICATION_REDIRECT -> CallAudio.ACTIVE
            else -> CallAudio.NONE
        }
        if (call != _state.value.callAudio) Log.d(TAG, "call audio: $call (mode $mode)")
        _state.update { it.copy(callAudio = call) }
    }

    /** Re-registers so newly granted permissions (Bluetooth) take effect. */
    fun restart() {
        if (!started) return
        stop()
        start()
    }

    // --- Controls ---

    /**
     * Turning it on brings back the last brightness: plain [CameraManager.setTorchMode] would use the
     * phone's default level (the dimmest on Samsung), whatever it was set to before.
     */
    fun setTorch(on: Boolean) {
        val id = torchCameraId ?: return
        val st = _state.value
        if (on && st.torchDimmable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val level = st.torchLevel.coerceIn(1, st.torchMaxLevel)
            if (runCatching { camera.turnOnTorchWithStrengthLevel(id, level) }.isSuccess) return
        }
        runCatching { camera.setTorchMode(id, on) }.onFailure { Log.w(TAG, "Torch unavailable", it) }
    }

    /** Sets the flashlight brightness (0 = dimmest, 1 = brightest), turning it on if needed. Android 13+. */
    fun setTorchLevel(fraction: Float) {
        val id = torchCameraId ?: return
        val st = _state.value
        if (!st.torchDimmable || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val level = (1 + fraction.coerceIn(0f, 1f) * (st.torchMaxLevel - 1)).roundToInt()
        if (level == st.torchLevel && st.torchOn) return
        runCatching { camera.turnOnTorchWithStrengthLevel(id, level) }
            .onSuccess { rememberTorchLevel(level) }
            .onFailure { Log.w(TAG, "Torch brightness refused", it) }
    }

    /** Kept across restarts, so the next "on" comes back at the same brightness. */
    private fun rememberTorchLevel(level: Int) {
        _state.update { it.copy(torchLevel = level) }
        prefs.edit().putInt(KEY_TORCH_LEVEL, level).apply()
    }

    private fun torchLevel(id: String): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && _state.value.torchDimmable) {
            runCatching { camera.getTorchStrengthLevel(id) }.getOrNull()
        } else null

    /** False if Android refused (silent needs Do-Not-Disturb access). */
    fun setRingerMode(mode: RingerMode): Boolean = runCatching {
        audio.ringerMode = when (mode) {
            RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
            RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
        }
    }.onFailure { Log.w(TAG, "Ringer change refused", it) }.isSuccess && ringerMode() == mode

    fun volumePercent(stream: Int): Int {
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        return audio.getStreamVolume(stream) * 100 / max
    }

    fun setRingerNormal() {
        runCatching { audio.ringerMode = AudioManager.RINGER_MODE_NORMAL }.onFailure { Log.w(TAG, "Ringer change refused", it) }
    }

    /** Ring → vibrate → silent → ring. Silent needs Do-Not-Disturb access; without it we skip it. */
    fun cycleRinger() {
        val next = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        runCatching { audio.ringerMode = next }.onFailure {
            runCatching { audio.ringerMode = AudioManager.RINGER_MODE_NORMAL }
        }
    }

    val hasDndAccess: Boolean get() = notificationManager.isNotificationPolicyAccessGranted

    fun toggleDnd(): Boolean = setDnd(!dndOn())

    /** False without Do-Not-Disturb access. */
    fun setDnd(on: Boolean): Boolean {
        if (!hasDndAccess) return false
        notificationManager.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        return true
    }

    /** The stream volume keys would change right now: media while something plays, otherwise the ringer. */
    private fun activeStream() = if (audio.isMusicActive) AudioManager.STREAM_MUSIC else AudioManager.STREAM_RING

    val inCall: Boolean
        get() = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audio.mode == AudioManager.MODE_RINGTONE

    val isMusicActive: Boolean get() = audio.isMusicActive

    /** Adjusts without the system volume panel (flags = 0) and announces the new level. */
    fun adjustVolume(raise: Boolean) {
        val stream = activeStream()
        runCatching {
            audio.adjustStreamVolume(stream, if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        }.onFailure { Log.w(TAG, "Volume change refused", it) }
        emitVolume(stream)
    }

    fun setVolume(stream: Int, fraction: Float) {
        val max = audio.getStreamMaxVolume(stream)
        runCatching { audio.setStreamVolume(stream, (fraction * max).toInt().coerceIn(0, max), 0) }
        emitVolume(stream)
    }

    private fun emitVolume(stream: Int) {
        _events.tryEmit(SystemEvent.Volume(stream, audio.getStreamVolume(stream), audio.getStreamMaxVolume(stream)))
    }

    // --- Helpers ---

    private fun onBattery(intent: Intent, initial: Boolean) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = if (level < 0) -1 else level * 100 / scale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        _state.update { it.copy(batteryLevel = pct, charging = plugged) }
        if (plugged) {
            lastWarned = 101
            return
        }
        // Warn once per threshold while discharging.
        val threshold = LOW_THRESHOLDS.firstOrNull { pct in 0..it && it < lastWarned } ?: return
        lastWarned = threshold
        if (!initial) _events.tryEmit(SystemEvent.LowBattery(pct))
    }

    private fun batteryLevel(): Int =
        context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun ringerMode() = when (audio.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
        AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
        else -> RingerMode.NORMAL
    }

    private fun dndOn() = notificationManager.currentInterruptionFilter.let {
        it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    fun hasBluetoothPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun device(intent: Intent): BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

    @Suppress("MissingPermission")
    private fun nameOf(device: BluetoothDevice): String =
        runCatching { device.alias ?: device.name }.getOrNull() ?: "Bluetooth device"

    @Suppress("MissingPermission")
    private fun kindOf(device: BluetoothDevice): BluetoothKind = when (runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()) {
        BluetoothClass.Device.Major.AUDIO_VIDEO -> BluetoothKind.HEADPHONES
        BluetoothClass.Device.Major.WEARABLE -> BluetoothKind.WATCH
        else -> BluetoothKind.OTHER
    }

    /** Not public API, but present on every phone we know of; null if it isn't (or nothing was reported yet). */
    private fun batteryOf(device: BluetoothDevice): Int? = runCatching {
        BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as Int
    }.getOrNull()?.takeIf { it in 0..100 }

    private companion object {
        const val TAG = "SystemMonitor"
        const val KEY_TORCH_LEVEL = "torch_level"
        val LOW_THRESHOLDS = listOf(5, 10, 20)
        // Not in the public SDK, but broadcast by the Bluetooth stack (headsets report their battery).
        const val ACTION_BT_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BT_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"
        /** BluetoothProfile.LE_AUDIO (API 33). */
        const val LE_AUDIO_PROFILE = 22
        /** AudioManager.MODE_CALL_REDIRECT / MODE_COMMUNICATION_REDIRECT (API 33): a call routed elsewhere is still a call. */
        const val MODE_CALL_REDIRECT = 5
        const val MODE_COMMUNICATION_REDIRECT = 6
    }
}
