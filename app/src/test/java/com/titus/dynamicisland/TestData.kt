package com.titus.dynamicisland

import com.titus.dynamicisland.data.media.MediaInfo
import com.titus.dynamicisland.data.notifications.NotificationEntry
import com.titus.dynamicisland.data.notifications.NotificationKind
import com.titus.dynamicisland.data.notifications.NotificationSnapshot

fun snapshot(
    key: String = "k",
    pkg: String = "com.example.chat",
    title: String? = "Alex",
    text: String? = "Hello",
    category: String? = null,
    template: String? = null,
    ongoing: Boolean = false,
    groupSummary: Boolean = false,
    onlyAlertOnce: Boolean = false,
    media: Boolean = false,
    promoted: Boolean = false,
    chronometer: Boolean = false,
    countDown: Boolean = false,
    progressMax: Int = 0,
    indeterminate: Boolean = false,
    importance: Int = 3,
    postTime: Long = 0,
    remoteChronometer: Boolean = false,
) = NotificationSnapshot(
    key = key, packageName = pkg, postTime = postTime, title = title, text = text, category = category,
    template = template, isOngoing = ongoing, isGroupSummary = groupSummary, onlyAlertOnce = onlyAlertOnce,
    isMediaStyle = media, isPromotedOngoing = promoted, showChronometer = chronometer,
    chronometerCountDown = countDown, whenTime = 0, progress = 0, progressMax = progressMax,
    progressIndeterminate = indeterminate, importance = importance, hasRemoteChronometer = remoteChronometer,
)

fun entry(s: NotificationSnapshot, kind: NotificationKind) =
    NotificationEntry(s, kind, s.packageName, null, null, null, emptyList())

fun media(pkg: String = "com.example.music", playing: Boolean = true) = MediaInfo(
    packageName = pkg, title = "Song", artist = "Artist", art = null, accentColor = null, isPlaying = playing,
    durationMs = 200_000, positionMs = 10_000, positionUpdatedAt = 1_000, playbackSpeed = 1f, sessionActivity = null,
)
