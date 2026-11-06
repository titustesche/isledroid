package com.titus.dynamicisland.data.assistant

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A place on earth, e.g. where the car is. */
data class GeoPoint(val lat: Double, val lon: Double, val accuracyM: Float? = null)

/** Something the assistant was asked to keep: "the car is on level 3", or a parking spot with its position. */
data class Memory(
    val id: String,
    val text: String,
    val createdAt: Long,
    /** Set when it's about a place; the island's map can lead there. */
    val place: GeoPoint? = null,
)

/**
 * The assistant's memory: a short list of things to know later, stored only on this phone
 * (memories.json in the app's files). All calls are cheap and may come from any thread.
 */
class AssistantMemory(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "memories.json"))
    private val lock = Any()
    private val _memories = MutableStateFlow(load())
    /** Newest first. */
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()

    fun add(text: String, place: GeoPoint? = null): Memory {
        val m = Memory(UUID.randomUUID().toString().take(8), text.trim(), System.currentTimeMillis(), place)
        update { (listOf(m) + it).take(MAX) }
        return m
    }

    fun remove(id: String): Boolean {
        var found = false
        update { list -> list.filterNot { (it.id == id).also { hit -> if (hit) found = true } } }
        return found
    }

    fun clear() = update { emptyList() }

    fun find(id: String): Memory? = _memories.value.firstOrNull { it.id == id }

    private fun update(change: (List<Memory>) -> List<Memory>) = synchronized(lock) {
        val next = change(_memories.value)
        _memories.value = next
        save(next)
    }

    private fun load(): List<Memory> = runCatching {
        val arr = JSONArray(String(file.readFully()))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Memory(
                id = o.getString("id"),
                text = o.getString("text"),
                createdAt = o.getLong("at"),
                place = o.optJSONObject("place")?.let { p ->
                    GeoPoint(p.getDouble("lat"), p.getDouble("lon"), p.optDouble("acc").takeIf { !it.isNaN() }?.toFloat())
                },
            )
        }
    }.getOrElse { if (file.baseFile.exists()) Log.w(TAG, "Couldn't read memories", it); emptyList() }

    private fun save(list: List<Memory>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("text", m.text)
                put("at", m.createdAt)
                m.place?.let { p ->
                    put("place", JSONObject().apply {
                        put("lat", p.lat)
                        put("lon", p.lon)
                        p.accuracyM?.let { put("acc", it.toDouble()) }
                    })
                }
            })
        }
        val out = file.startWrite()
        try {
            out.write(arr.toString().toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.w(TAG, "Couldn't save memories", e)
        }
    }

    companion object {
        private const val TAG = "AssistantMemory"
        /** Plenty for a voice assistant's notes; the oldest go first beyond this. */
        const val MAX = 200
    }
}
