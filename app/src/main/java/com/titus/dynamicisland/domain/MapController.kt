package com.titus.dynamicisland.domain

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.titus.dynamicisland.data.assistant.GeoPoint
import com.titus.dynamicisland.data.map.MapTile
import com.titus.dynamicisland.data.map.Mercator
import com.titus.dynamicisland.data.map.TileKey
import com.titus.dynamicisland.data.map.TileStore
import com.titus.dynamicisland.data.system.Geo
import com.titus.dynamicisland.data.system.Geo.toPoint
import com.titus.dynamicisland.service.MapLocationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Where the island's map leads: a remembered place, like the car; [memoryId] is the note it came from. */
data class MapTarget(val label: String, val point: GeoPoint, val memoryId: String? = null)

/**
 * The island's map: what it points to, whether it's open, and — only while there's a target —
 * the phone's live position, compass heading and the map tiles around it.
 */
class MapController(private val context: Context, private val scope: CoroutineScope) {
    private val tiles = TileStore(context)

    private val _target = MutableStateFlow<MapTarget?>(null)
    val target: StateFlow<MapTarget?> = _target.asStateFlow()

    /** Shown big (true) or as the pill with arrow and distance (false). */
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    private val _position = MutableStateFlow<GeoPoint?>(null)
    val position: StateFlow<GeoPoint?> = _position.asStateFlow()

    /** Degrees clockwise from true north the top of the phone points to. */
    private val _heading = MutableStateFlow(0f)
    val heading: StateFlow<Float> = _heading.asStateFlow()

    private val _tiles = MutableStateFlow<List<MapTile>>(emptyList())
    /** Tiles covering the area around [position]. */
    val loadedTiles: StateFlow<List<MapTile>> = _tiles.asStateFlow()

    /** Within this many metres of the target counts as arrived. */
    var arrivalRadiusM = 15
    /** Arriving closes the map. */
    var closeOnArrival = true
    /** Called once when you arrive at the target (on the main thread). */
    var onArrival: ((MapTarget) -> Unit)? = null
    private var arrived = false
    private var awayFromTarget = false
    private var loadJob: Job? = null
    private var tracking = false

    /** Any thread (assistant tools run in the background); the work happens on the main thread. */
    fun show(target: MapTarget, open: Boolean = true) {
        scope.launch {
            _target.value = target
            _open.value = open
            awayFromTarget = false
            arrived = false
            startTracking()
        }
    }

    fun setOpen(open: Boolean) { if (_target.value != null) _open.value = open }

    fun close() {
        scope.launch {
            _target.value = null
            _open.value = false
            stopTracking()
        }
    }

    // --- Position & heading ---

    private val lm get() = context.getSystemService(LocationManager::class.java)
    private val sensors get() = context.getSystemService(SensorManager::class.java)

    private val locationListener = LocationListener { onLocation(it) }

    private fun onLocation(loc: Location) {
        val p = loc.toPoint()
        val prev = _position.value
        // A coarse fix right after a precise one is noise.
        if (prev != null && (p.accuracyM ?: 99f) > 50f && (prev.accuracyM ?: 99f) < 20f && Geo.distance(prev, p) < 60) return
        _position.value = p
        declination = GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), loc.time).declination
        loadTilesAround(p)
        val t = _target.value ?: return
        if (arrivalRadiusM <= 0 || arrived) return
        // Arriving needs leaving first: opened right at the spot, the map stays until you've walked away and back.
        val d = Geo.distance(p, t.point)
        if (d > arrivalRadiusM * 2) awayFromTarget = true
        if (awayFromTarget && d < arrivalRadiusM) {
            arrived = true
            Log.i(TAG, "Arrived at ${t.label}")
            onArrival?.invoke(t)
            if (closeOnArrival) close()
        }
    }

    private var declination = 0f
    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotation, e.values)
            // Heading of the phone's top edge, whichever way the screen is turned.
            val (ax, ay) = when (displayRotation()) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            SensorManager.remapCoordinateSystem(rotation, ax, ay, remapped)
            SensorManager.getOrientation(remapped, orientation)
            val raw = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + declination) + 360f) % 360f
            // Smooth along the shortest way round; ignore sub-degree jitter so the map isn't redrawn for nothing.
            val cur = _heading.value
            val delta = ((raw - cur + 540f) % 360f) - 180f
            if (abs(delta) < 0.4f) return
            _heading.value = (cur + delta * 0.25f + 360f) % 360f
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    @Suppress("DEPRECATION")
    private fun displayRotation() = runCatching { context.getSystemService(WindowManager::class.java).defaultDisplay.rotation }.getOrDefault(0)

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        if (tracking) return
        tracking = true
        // A foreground service keeps "while in use" location flowing while the island is all that's open.
        MapLocationService.start(context)
        if (Geo.hasAny(context)) {
            val providers = lm.getProviders(true)
            providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let(::onLocation)
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in providers }.forEach {
                runCatching { lm.requestLocationUpdates(it, 1_000L, 1f, locationListener, Looper.getMainLooper()) }
                    .onFailure { e -> Log.w(TAG, "No updates from $it", e) }
            }
        }
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopTracking() {
        if (!tracking) return
        tracking = false
        runCatching { lm.removeUpdates(locationListener) }
        sensors.unregisterListener(sensorListener)
        MapLocationService.stop(context)
        loadJob?.cancel()
    }

    // --- Tiles ---

    private var loadedFor: Set<TileKey> = emptySet()

    /** The tile under [p] and its neighbours toward the edges of a ~1 km view. */
    private fun loadTilesAround(p: GeoPoint) {
        val z = TileStore.ZOOM
        val n = 1 shl z
        val wx = Mercator.x(p.lon) * n
        val wy = Mercator.y(p.lat) * n
        val margin = 0.35 // of a tile (~0.5 km at z14 in Germany)
        val keys = buildSet {
            for (dx in listOf(-margin, 0.0, margin)) for (dy in listOf(-margin, 0.0, margin)) {
                add(TileKey(z, (wx + dx).toInt().coerceIn(0, n - 1), (wy + dy).toInt().coerceIn(0, n - 1)))
            }
        }
        if (keys == loadedFor && _tiles.value.size == keys.size) return
        loadedFor = keys
        loadJob?.cancel()
        loadJob = scope.launch {
            val got = mutableListOf<MapTile>()
            for (k in keys) tiles.get(k)?.let { got += it; _tiles.value = got.toList() }
        }
    }

    private companion object {
        const val TAG = "MapController"
    }
}
