package com.titus.dynamicisland.data.map

import android.content.Context
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/*
 * The island's map data: OpenStreetMap vector tiles (OpenMapTiles schema) from OpenFreeMap — free,
 * no key — decoded by hand. Only streets and buildings are kept; everything else is drawn by nobody.
 */

/** A street: its class (motorway, primary, minor, path …) and its points as x0,y0,x1,y1… */
class Road(val cls: String, val points: FloatArray)

/** A building's outline (x0,y0,x1,y1…, not closed) and its height in metres. */
class Building(val ring: FloatArray, val heightM: Float)

/** One tile's streets and buildings in tile units (0..[extent]). */
class MapTile(val z: Int, val x: Int, val y: Int, val extent: Int, val roads: List<Road>, val buildings: List<Building>)

data class TileKey(val z: Int, val x: Int, val y: Int)

/** Web Mercator, world coordinates 0..1. */
object Mercator {
    fun x(lon: Double) = (lon + 180.0) / 360.0
    fun y(lat: Double): Double {
        val r = Math.toRadians(lat)
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
    }

    /** Metres per world unit at [lat]. */
    fun metres(lat: Double) = 40_075_016.686 * cos(Math.toRadians(lat))

    fun tile(lat: Double, lon: Double, z: Int): TileKey {
        val n = 1 shl z
        return TileKey(z, floor(x(lon) * n).toInt().coerceIn(0, n - 1), floor(y(lat) * n).toInt().coerceIn(0, n - 1))
    }
}

/** Fetches, caches (on disk and in memory) and decodes tiles. */
class TileStore(context: Context) {
    private val dir = File(context.cacheDir, "map-tiles").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("map_tiles", Context.MODE_PRIVATE)
    private val memory = LruCache<TileKey, MapTile>(24)
    private val mutex = Mutex()

    /** The tile, from memory, disk or the network; null if it can't be had right now. */
    suspend fun get(key: TileKey): MapTile? = memory.get(key) ?: withContext(Dispatchers.IO) {
        mutex.withLock {
            memory.get(key)?.let { return@withLock it }
            val file = File(dir, "${key.z}_${key.x}_${key.y}.pbf")
            val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_MS
            val bytes = (if (fresh) runCatching { file.readBytes() }.getOrNull() else null)
                ?: download(key)?.also { runCatching { file.writeBytes(it) } }
                ?: if (file.exists()) runCatching { file.readBytes() }.getOrNull() else null // stale beats nothing
            bytes?.let { runCatching { Mvt.decode(key, it) }.onFailure { Log.w(TAG, "Bad tile $key", it) }.getOrNull() }
                ?.also { memory.put(key, it) }
        }
    }

    private fun download(key: TileKey): ByteArray? {
        val template = template() ?: return null
        val url = template.replace("{z}", key.z.toString()).replace("{x}", key.x.toString()).replace("{y}", key.y.toString())
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            try {
                if (conn.responseCode != 200) return null
                val raw = conn.inputStream.use { it.readBytes() }
                if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) GZIPInputStream(raw.inputStream()).use { it.readBytes() } else raw
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Log.d(TAG, "Tile $key unavailable: ${e.message}")
            null
        }
    }

    /** The current tile URL (it names the data release, which changes every few weeks). */
    private fun template(): String? {
        val cached = prefs.getString(KEY_TEMPLATE, null)
        if (cached != null && System.currentTimeMillis() - prefs.getLong(KEY_TEMPLATE_AT, 0) < TEMPLATE_MS) return cached
        return try {
            val conn = URL(TILEJSON).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val json = try { JSONObject(conn.inputStream.bufferedReader().use { it.readText() }) } finally { conn.disconnect() }
            json.getJSONArray("tiles").getString(0).also {
                prefs.edit().putString(KEY_TEMPLATE, it).putLong(KEY_TEMPLATE_AT, System.currentTimeMillis()).apply()
            }
        } catch (e: Exception) {
            Log.d(TAG, "No tile index: ${e.message}")
            cached
        }
    }

    companion object {
        private const val TAG = "TileStore"
        private const val TILEJSON = "https://tiles.openfreemap.org/planet"
        private const val USER_AGENT = "DynamicIsland/1.0 (Android; personal use)"
        private const val KEY_TEMPLATE = "template"
        private const val KEY_TEMPLATE_AT = "template_at"
        private const val TEMPLATE_MS = 24 * 3_600_000L
        private const val CACHE_MS = 30 * 24 * 3_600_000L
        /** Highest zoom the data has; closer views scale these tiles up. */
        const val ZOOM = 14
    }
}

/** Mapbox Vector Tile decoding (protobuf, by hand): just the "transportation" and "building" layers. */
internal object Mvt {
    private class Reader(val buf: ByteArray, var pos: Int = 0, val end: Int = buf.size) {
        fun more() = pos < end
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        fun sub(): Reader { val len = varint().toInt(); val r = Reader(buf, pos, pos + len); pos += len; return r }
        fun string(): String { val len = varint().toInt(); val s = String(buf, pos, len); pos += len; return s }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> pos += varint().toInt()
                5 -> pos += 4
                else -> throw IllegalStateException("wire type $wire")
            }
        }
        fun double(): Double { val v = java.nio.ByteBuffer.wrap(buf, pos, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).double; pos += 8; return v }
        fun float(): Float { val v = java.nio.ByteBuffer.wrap(buf, pos, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float; pos += 4; return v }
    }

    fun decode(key: TileKey, bytes: ByteArray): MapTile {
        val roads = mutableListOf<Road>()
        val buildings = mutableListOf<Building>()
        var extent = 4096
        val r = Reader(bytes)
        while (r.more()) {
            val tag = r.varint().toInt()
            if (tag ushr 3 == 3 && tag and 7 == 2) {
                val layer = r.sub()
                extent = decodeLayer(layer, roads, buildings) ?: extent
            } else r.skip(tag and 7)
        }
        return MapTile(key.z, key.x, key.y, extent, roads, buildings)
    }

    /** Returns the layer's extent if it was one we keep. */
    private fun decodeLayer(r: Reader, roads: MutableList<Road>, buildings: MutableList<Building>): Int? {
        // Fields can come in any order: collect first, interpret after.
        var name = ""
        var extent = 4096
        val keys = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        val features = mutableListOf<Reader>()
        while (r.more()) {
            val tag = r.varint().toInt()
            when (tag ushr 3) {
                1 -> name = r.string()
                2 -> features += r.sub()
                3 -> keys += r.string()
                4 -> values += value(r.sub())
                5 -> extent = r.varint().toInt()
                else -> r.skip(tag and 7)
            }
        }
        if (name != "transportation" && name != "building") return null
        for (f in features) {
            var type = 0
            var tags = LongArray(0)
            var geometry = IntArray(0)
            while (f.more()) {
                val tag = f.varint().toInt()
                when (tag ushr 3) {
                    2 -> tags = packed(f.sub()).toLongArray()
                    3 -> type = f.varint().toInt()
                    4 -> geometry = packed(f.sub()).map { it.toInt() }.toIntArray()
                    else -> f.skip(tag and 7)
                }
            }
            fun tag(k: String): Any? {
                for (i in 0 until tags.size - 1 step 2) if (keys.getOrNull(tags[i].toInt()) == k) return values.getOrNull(tags[i + 1].toInt())
                return null
            }
            val parts = geometry(geometry)
            if (name == "transportation" && type == 2) {
                val cls = tag("class") as? String ?: continue
                if (cls in SKIPPED_ROADS) continue
                parts.forEach { roads += Road(cls, it) }
            } else if (name == "building" && type == 3) {
                val h = (tag("render_height") as? Number)?.toFloat() ?: 8f
                // Polygons: outer rings are clockwise in tile space; holes (courtyards) are dropped.
                parts.filter { signedArea(it) > 0 }.forEach { buildings += Building(it, h) }
            }
        }
        return extent
    }

    private val SKIPPED_ROADS = setOf("ferry", "aerialway", "transit", "rail", "bus_guideway")

    private fun value(r: Reader): Any? {
        var v: Any? = null
        while (r.more()) {
            val tag = r.varint().toInt()
            v = when (tag ushr 3) {
                1 -> r.string()
                2 -> r.float()
                3 -> r.double()
                4 -> r.varint()
                5 -> r.varint()
                6 -> r.varint().let { (it ushr 1) xor -(it and 1) }
                7 -> r.varint() != 0L
                else -> { r.skip(tag and 7); v }
            }
        }
        return v
    }

    private fun packed(r: Reader): List<Long> = buildList { while (r.more()) add(r.varint()) }

    /** Geometry commands → one point array per line / ring (ClosePath doesn't repeat the first point). */
    private fun geometry(cmds: IntArray): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        var cur = ArrayList<Float>()
        var x = 0
        var y = 0
        var i = 0
        fun flush() { if (cur.size >= 4) out += cur.toFloatArray(); cur = ArrayList() }
        while (i < cmds.size) {
            val c = cmds[i++]
            val id = c and 7
            val count = c ushr 3
            when (id) {
                1, 2 -> repeat(count) {
                    if (i + 1 >= cmds.size) return@repeat // malformed: not enough parameters
                    if (id == 1) flush()
                    x += zigzag(cmds[i++]); y += zigzag(cmds[i++])
                    cur += x.toFloat(); cur += y.toFloat()
                }
                7 -> flush()
            }
        }
        flush()
        return out
    }

    private fun zigzag(n: Int) = (n ushr 1) xor -(n and 1)

    /** Positive for clockwise rings in tile space (y down). */
    private fun signedArea(p: FloatArray): Float {
        var a = 0f
        var j = p.size - 2
        for (i in 0 until p.size step 2) {
            a += (p[j] - p[i]) * (p[j + 1] + p[i + 1])
            j = i
        }
        return a
    }
}
