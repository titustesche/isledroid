package com.titus.dynamicisland.domain

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import com.titus.dynamicisland.data.assistant.AssistantCard
import com.titus.dynamicisland.data.assistant.CardIcon
import com.titus.dynamicisland.data.media.MediaInfo
import com.titus.dynamicisland.data.notifications.CallRole
import com.titus.dynamicisland.data.notifications.NotificationActionItem
import com.titus.dynamicisland.data.notifications.NotificationEntry
import com.titus.dynamicisland.data.notifications.NotificationKind
import com.titus.dynamicisland.data.notifications.NotificationSnapshot
import com.titus.dynamicisland.data.system.BluetoothDeviceInfo
import com.titus.dynamicisland.data.system.BluetoothKind
import com.titus.dynamicisland.data.system.SystemEvent
import com.titus.dynamicisland.data.system.SystemState

/**
 * What the real island shows while a settings page is open, so changes can be seen where they
 * apply. [openByDefault]: shown expanded until a gesture closes it.
 */
enum class PreviewSample(val openByDefault: Boolean = false) {
    IDLE, MUSIC, TIMER, BOTH, ALERT, EVENT, BATTERIES, CALL(openByDefault = true), TOOLS(openByDefault = true), ASSISTANT,
    MAP(openByDefault = true),
}

/** Builds the sample island states. Nothing in them is real: their actions go nowhere. */
class PreviewSamples(private val context: Context) {

    fun state(sample: PreviewSample, open: Boolean): IslandUiState {
        fun live(primary: IslandActivity, secondary: IslandActivity? = null) =
            if (open) IslandUiState(IslandMode.EXPANDED, primary) else IslandUiState(IslandMode.COMPACT, primary, secondary)
        return when (sample) {
            PreviewSample.IDLE -> IslandUiState(IslandMode.IDLE)
            PreviewSample.MUSIC -> live(music)
            PreviewSample.TIMER -> live(timer)
            PreviewSample.BOTH -> live(timer, music)
            PreviewSample.ALERT -> if (open) IslandUiState(IslandMode.EXPANDED, alert) else IslandUiState(IslandMode.ALERT, alert, music)
            PreviewSample.EVENT -> IslandUiState(IslandMode.EVENT, IslandActivity.Event(SystemEvent.Charging(64)), music)
            PreviewSample.BATTERIES -> IslandUiState(
                IslandMode.IDLE,
                idleBatteries = listOf(
                    BluetoothDeviceInfo("Headphones", BluetoothKind.HEADPHONES, 80),
                    BluetoothDeviceInfo("Watch", BluetoothKind.WATCH, 45),
                ),
            )
            PreviewSample.CALL -> live(call)
            PreviewSample.TOOLS ->
                if (open) IslandUiState(IslandMode.EXPANDED, IslandActivity.Tools, system = SystemState(torchOn = true))
                else IslandUiState(IslandMode.IDLE)
            PreviewSample.MAP -> if (open) IslandUiState(IslandMode.EXPANDED, mapSample) else IslandUiState(IslandMode.COMPACT, mapSample)
            PreviewSample.ASSISTANT -> IslandUiState(
                IslandMode.ASSISTANT,
                IslandActivity.Assistant(
                    AssistantUi(
                        AssistantPhase.ANSWERING,
                        question = "Wake me up at seven",
                        answer = "Done. Actually getting up is on you.",
                        speaking = true,
                        cards = listOf(AssistantCard("alarm", CardIcon.ALARM, "07:00", "Tomorrow", label = "Alarm · tomorrow")),
                    ),
                ),
            )
        }
    }

    private val music
        get() = IslandActivity.Media(
            MediaInfo(
                packageName = "preview.music", title = "Midnight Drive", artist = "The Islands",
                art = SampleArt, accentColor = 0xFF64D2FF.toInt(), isPlaying = true,
                durationMs = 215_000, positionMs = 72_000, positionUpdatedAt = SystemClock.elapsedRealtime(),
                playbackSpeed = 1f, sessionActivity = null,
            ),
        )

    private val timer
        get() = IslandActivity.Timer(
            entry(NotificationKind.TIMER, "Timer", "Pasta", chronometer = true, whenTime = System.currentTimeMillis() + 9 * 60_000),
        )

    private val mapSample = IslandActivity.Map(MapTarget("Car", com.titus.dynamicisland.data.assistant.GeoPoint(0.0, 0.0)), preview = true)

    private val alert = IslandActivity.Alert(
        entry(NotificationKind.ALERT, "Messages", "Alex", "Are we still on for tonight? I can pick you up around eight if that works"),
    )

    /** Does nothing; the call buttons need some intent to hold. */
    private val nowhere by lazy {
        PendingIntent.getBroadcast(context, 0, Intent("${context.packageName}.PREVIEW").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
    }

    private val call by lazy {
        IslandActivity.Timer(
            entry(
                NotificationKind.TIMER, "Phone", "Mum", "Incoming call", category = "call",
                actions = listOf(
                    NotificationActionItem("Decline", nowhere, callRole = CallRole.DECLINE),
                    NotificationActionItem("Answer", nowhere, callRole = CallRole.ANSWER),
                ),
            ),
        )
    }

    private fun entry(
        kind: NotificationKind,
        app: String,
        title: String,
        text: String? = null,
        chronometer: Boolean = false,
        whenTime: Long = 0,
        category: String? = null,
        actions: List<NotificationActionItem> = emptyList(),
    ) = NotificationEntry(
        snapshot = NotificationSnapshot(
            key = "preview:$title", packageName = "preview.$app", postTime = 0, title = title, text = text,
            category = category, template = null, isOngoing = kind != NotificationKind.ALERT, isGroupSummary = false,
            onlyAlertOnce = false, isMediaStyle = false, isPromotedOngoing = false, showChronometer = chronometer,
            chronometerCountDown = chronometer, whenTime = whenTime, progress = 0, progressMax = 0,
            progressIndeterminate = false, importance = 3,
        ),
        kind = kind, appLabel = app, appIcon = null, largeIcon = null, contentIntent = null, actions = actions,
    )

    private companion object {
        /** A generated "album cover" so the preview shows artwork and the blurred background. */
        val SampleArt: Bitmap by lazy {
            Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                canvas.drawPaint(Paint().apply {
                    shader = LinearGradient(0f, 0f, 96f, 96f, 0xFFFF375F.toInt(), 0xFF0A84FF.toInt(), Shader.TileMode.CLAMP)
                })
                canvas.drawCircle(64f, 34f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD60A.toInt() })
            }
        }
    }
}
