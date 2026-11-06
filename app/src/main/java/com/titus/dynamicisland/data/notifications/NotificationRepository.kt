package com.titus.dynamicisland.data.notifications

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Holds ongoing "live activities" (timers, progress) and emits transient alerts. Main-thread only. */
class NotificationRepository {

    private val _live = MutableStateFlow<List<NotificationEntry>>(emptyList())
    /** Ongoing timer/progress notifications, oldest first. */
    val live: StateFlow<List<NotificationEntry>> = _live.asStateFlow()

    private val _alerts = MutableSharedFlow<NotificationEntry>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val alerts: SharedFlow<NotificationEntry> = _alerts.asSharedFlow()

    private val _removed = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val removed: SharedFlow<String> = _removed.asSharedFlow()

    private val lastSeen = mutableMapOf<String, NotificationSnapshot>()

    private val _recent = LinkedHashMap<String, RecentNotification>()

    /** Message-like notifications in the shade, newest first. */
    val recent: List<RecentNotification> get() = _shade.value

    private val _shade = MutableStateFlow<List<RecentNotification>>(emptyList())
    /** [recent] as a flow, for the island's notification panel. */
    val shade: StateFlow<List<RecentNotification>> = _shade.asStateFlow()

    private fun publishShade() {
        _shade.value = _recent.values.sortedByDescending { it.postTime }
    }

    fun onRecent(n: RecentNotification) {
        _recent.remove(n.key)
        _recent[n.key] = n
        while (_recent.size > MAX_RECENT) _recent.remove(_recent.keys.first())
        publishShade()
    }

    /** Removes a notification from the shade; set by the listener while it's connected. */
    var cancel: ((key: String) -> Unit)? = null

    fun previous(key: String): NotificationSnapshot? = lastSeen[key]

    /** @param entry null when the notification was classified as irrelevant. */
    fun onPosted(snapshot: NotificationSnapshot, entry: NotificationEntry?, emitAlerts: Boolean = true) {
        lastSeen[snapshot.key] = snapshot
        when (entry?.kind) {
            NotificationKind.TIMER, NotificationKind.PROGRESS, NotificationKind.NAVIGATION -> upsertLive(entry)
            NotificationKind.ALERT -> {
                removeLive(snapshot.key)
                if (emitAlerts) _alerts.tryEmit(entry)
            }
            null -> removeLive(snapshot.key)
        }
    }

    fun onRemoved(key: String) {
        lastSeen.remove(key)
        if (_recent.remove(key) != null) publishShade()
        removeLive(key)
        _removed.tryEmit(key)
    }

    fun clear() {
        lastSeen.clear()
        _recent.clear()
        publishShade()
        _live.value = emptyList()
    }

    private fun upsertLive(entry: NotificationEntry) = _live.update { list ->
        val i = list.indexOfFirst { it.key == entry.key }
        if (i >= 0) list.toMutableList().also { it[i] = entry }
        else (list + entry).sortedBy { it.snapshot.postTime }
    }

    private fun removeLive(key: String) = _live.update { list -> list.filterNot { it.key == key } }

    private companion object {
        const val MAX_RECENT = 60
    }
}
