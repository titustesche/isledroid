package com.titus.dynamicisland.data.notifications

import android.app.PendingIntent
import android.app.RemoteInput
import android.widget.RemoteViews
import androidx.compose.ui.graphics.ImageBitmap

/** Plain-data view of a notification; contains only what the classifier needs, so it is unit-testable. */
data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val postTime: Long,
    val title: String?,
    val text: String?,
    /** Secondary line, e.g. Samsung's "30 min / 04:26" for a timer. */
    val subText: String? = null,
    val category: String?,
    val template: String?,
    val isOngoing: Boolean,
    val isGroupSummary: Boolean,
    val onlyAlertOnce: Boolean,
    val isMediaStyle: Boolean,
    val isPromotedOngoing: Boolean,
    val showChronometer: Boolean,
    val chronometerCountDown: Boolean,
    /** `Notification.when`, wall-clock ms. Chronometer base for timers. */
    val whenTime: Long,
    val progress: Int,
    val progressMax: Int,
    val progressIndeterminate: Boolean,
    /** `NotificationManager.IMPORTANCE_*` from the ranking. */
    val importance: Int,
    /** The notification ships a custom RemoteViews containing a ticking chronometer (e.g. Samsung Clock). */
    val hasRemoteChronometer: Boolean = false,
)

/** What a call notification's button does, so the island can show it as a symbol. */
enum class CallRole { ANSWER, DECLINE, HANG_UP, SPEAKER, MUTE }

/**
 * A notification button. [remoteInput] is set for inline-reply actions (e.g. WhatsApp "Reply"):
 * the island then shows a text field and delivers the text through the action's intent.
 * [callRole] is set for the recognised buttons of a call notification.
 */
data class NotificationActionItem(
    val title: String,
    val intent: PendingIntent,
    val remoteInput: RemoteInput? = null,
    val callRole: CallRole? = null,
) {
    val isReply get() = remoteInput != null
}

/** A classified notification plus the Android objects needed to render and act on it. */
data class NotificationEntry(
    val snapshot: NotificationSnapshot,
    val kind: NotificationKind,
    val appLabel: String,
    val appIcon: ImageBitmap?,
    val largeIcon: ImageBitmap?,
    val contentIntent: PendingIntent?,
    val actions: List<NotificationActionItem>,
    /** RemoteViews that may contain a Chronometer, most specific first. The island hosts that view directly. */
    val chronometerViews: List<RemoteViews> = emptyList(),
    /** From the phone's audio state (see SystemState.callAudio): beats the guess from the buttons when set. */
    val ringingOverride: Boolean? = null,
) {
    val key get() = snapshot.key
    val packageName get() = snapshot.packageName
    val isCall get() = NotificationClassifier.isCall(snapshot)
    /** An incoming call that hasn't been picked up: it can be answered. */
    val isRinging get() = isCall && (
        ringingOverride ?: (
            actions.any { it.callRole == CallRole.ANSWER } ||
                (actions.none { it.callRole != null } && !snapshot.showChronometer)
            )
        )
}

enum class NotificationKind { ALERT, TIMER, PROGRESS, NAVIGATION }

/**
 * A notification someone might ask the assistant about ("what did Anna write?"): every message-like
 * notification still in the shade, whatever the island's own pop-up filters say.
 */
data class RecentNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    /** Sender, or the group chat's name. */
    val title: String?,
    val text: String?,
    /** For chats: the latest messages, oldest first, as "Name: text". */
    val messages: List<String>,
    val postTime: Long,
    val contentIntent: PendingIntent?,
    /** Inline reply, if the app offers one. */
    val reply: NotificationActionItem?,
    val appIcon: ImageBitmap? = null,
    /** Contact picture or similar. */
    val largeIcon: ImageBitmap? = null,
)
