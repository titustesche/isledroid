package com.titus.dynamicisland.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.os.Build
import android.os.UserHandle
import android.util.Log
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.RemoteViews
import com.titus.dynamicisland.appGraph
import com.titus.dynamicisland.data.notifications.CallRole
import com.titus.dynamicisland.data.notifications.NotificationActionItem
import com.titus.dynamicisland.data.notifications.NotificationClassifier
import com.titus.dynamicisland.data.notifications.NotificationEntry
import com.titus.dynamicisland.data.notifications.NotificationSnapshot
import com.titus.dynamicisland.data.notifications.RecentNotification
import androidx.core.app.NotificationCompat

/** Feeds notifications to [com.titus.dynamicisland.data.notifications.NotificationRepository] and authorises media-session access. */
class IslandNotificationListener : NotificationListenerService() {

    private val graph get() = appGraph

    override fun onListenerConnected() {
        instance = this
        graph.notifications.cancel = { key -> runCatching { cancelNotification(key) } }
        graph.media.start(ComponentName(this, IslandNotificationListener::class.java))
        val existing = runCatching { activeNotifications }.getOrNull().orEmpty()
        existing.forEach { handle(it, currentRanking, emitAlerts = false) }
        graph.popups.sync(graph.settings.settings.value)
    }

    override fun onListenerDisconnected() {
        instance = null
        graph.notifications.cancel = null
        graph.media.stop()
        graph.notifications.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        handle(sbn, rankingMap, emitAlerts = true)
        val ranking = Ranking().takeIf { rankingMap?.getRanking(sbn.key, it) == true }
        graph.popups.onPosted(sbn, ranking)
    }

    // Only delivered while a companion device is associated (see PopupSuppressor).
    override fun onNotificationChannelModified(pkg: String, user: UserHandle, channel: NotificationChannel, modificationType: Int) {
        graph.popups.onChannelModified(pkg, channel, modificationType)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        graph.notifications.onRemoved(sbn.key)
    }

    private fun handle(sbn: StatusBarNotification, ranking: RankingMap?, emitAlerts: Boolean) {
        val snapshot = sbn.toSnapshot(ranking)
        val repo = graph.notifications
        recentOf(sbn, snapshot)?.let(repo::onRecent)
        val kind = NotificationClassifier.classify(
            snapshot, graph.settings.settings.value, packageName, repo.previous(snapshot.key),
        )
        if (kind == null) {
            repo.onPosted(snapshot, null)
            return
        }
        val n = sbn.notification
        val icons = graph.icons // cached after first use, so cheap enough for the main thread
        val isCall = NotificationClassifier.isCall(snapshot)
        val buttons = n.actions.orEmpty().mapNotNull { a ->
            val intent = a.actionIntent ?: return@mapNotNull null
            val reply = a.remoteInputs?.firstOrNull { it.allowFreeFormInput }
            // Actions with only choice-type inputs can't be answered with a text field; skip them.
            if (reply == null && !a.remoteInputs.isNullOrEmpty()) return@mapNotNull null
            val title = a.title?.toString().orEmpty()
            NotificationActionItem(title, intent, reply, if (isCall) callRoleOf(n, intent, title) else null)
        }
        val entry = NotificationEntry(
            snapshot = snapshot,
            kind = kind,
            appLabel = icons.label(sbn.packageName),
            appIcon = icons.icon(sbn.packageName),
            largeIcon = icons.load(n.getLargeIcon()),
            contentIntent = n.contentIntent,
            actions = if (isCall) (callStyleButtons(n, buttons) + buttons).take(4) else buttons.take(3),
            chronometerViews = chronometerViews(n),
        )
        if (isCall) {
            // Phone apps differ a lot here; this line is what tells why a call looks or acts wrong.
            Log.d(TAG, "call ${sbn.packageName}: template=${snapshot.template} chrono=${snapshot.showChronometer} " +
                "remoteChrono=${snapshot.hasRemoteChronometer} ringing=${entry.isRinging} buttons=" +
                entry.actions.joinToString { "\"${it.title}\"=${it.callRole}" })
        }
        repo.onPosted(snapshot, entry, emitAlerts)
    }

    /**
     * CallStyle (Google's phone app, many VoIP apps) keeps answer / decline / hang-up out of the
     * actions list, as intents in the extras; they become buttons here unless the app also listed them.
     */
    private fun callStyleButtons(n: Notification, listed: List<NotificationActionItem>): List<NotificationActionItem> =
        CALL_STYLE_INTENTS.mapNotNull { (extra, role) ->
            val intent = pendingIntentExtra(n, extra) ?: return@mapNotNull null
            if (listed.any { it.intent == intent }) return@mapNotNull null
            NotificationActionItem(role.name.lowercase().replace('_', ' '), intent, callRole = role)
        }.sortedBy { it.callRole != CallRole.DECLINE && it.callRole != CallRole.HANG_UP } // declining goes left

    private fun callRoleOf(n: Notification, intent: PendingIntent, title: String): CallRole? =
        CALL_STYLE_INTENTS.firstOrNull { (extra, _) -> pendingIntentExtra(n, extra) == intent }?.second
            ?: NotificationClassifier.callRole(title)

    private fun pendingIntentExtra(n: Notification, key: String): PendingIntent? =
        if (Build.VERSION.SDK_INT >= 33) n.extras.getParcelable(key, PendingIntent::class.java)
        else @Suppress("DEPRECATION") (n.extras.getParcelable(key) as? PendingIntent)

    /** What the assistant may read out and answer; ongoing, media and our own notifications aren't. */
    private fun recentOf(sbn: StatusBarNotification, s: NotificationSnapshot): RecentNotification? {
        if (s.packageName == packageName || s.isOngoing || s.isGroupSummary || s.isMediaStyle) return null
        if (s.title.isNullOrBlank() && s.text.isNullOrBlank()) return null
        val n = sbn.notification
        val chat = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        val messages = chat?.messages.orEmpty().takeLast(6).mapNotNull { m ->
            val text = m.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            m.person?.name?.let { "$it: $text" } ?: text
        }
        val reply = n.actions.orEmpty().firstNotNullOfOrNull { a ->
            val input = a.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: return@firstNotNullOfOrNull null
            NotificationActionItem(a.title?.toString().orEmpty(), a.actionIntent ?: return@firstNotNullOfOrNull null, input)
        }
        return RecentNotification(
            key = s.key,
            packageName = s.packageName,
            appLabel = graph.icons.label(s.packageName),
            title = chat?.conversationTitle?.toString() ?: s.title,
            text = s.text,
            messages = messages,
            postTime = s.postTime,
            contentIntent = n.contentIntent,
            reply = reply,
            appIcon = graph.icons.icon(s.packageName),
            largeIcon = graph.icons.load(n.getLargeIcon()),
        )
    }

    private fun StatusBarNotification.toSnapshot(rankingMap: RankingMap?): NotificationSnapshot {
        val n = notification
        val e = n.extras
        val template = e.getString(Notification.EXTRA_TEMPLATE)
        val importance = rankingMap?.let { map ->
            Ranking().takeIf { map.getRanking(key, it) }?.importance
        } ?: NotificationManager.IMPORTANCE_DEFAULT
        val text = e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT)
        return NotificationSnapshot(
            key = key,
            packageName = packageName,
            postTime = postTime,
            title = (e.getCharSequence(Notification.EXTRA_TITLE) ?: e.getCharSequence(Notification.EXTRA_TITLE_BIG))
                ?.toString(),
            text = text?.toString(),
            category = n.category,
            template = template,
            isOngoing = isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            onlyAlertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
            isMediaStyle = e.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
                template?.endsWith("MediaStyle") == true,
            isPromotedOngoing = Build.VERSION.SDK_INT >= 36 && n.flags and Notification.FLAG_PROMOTED_ONGOING != 0,
            showChronometer = e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
            chronometerCountDown = e.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            whenTime = n.`when`,
            progress = e.getInt(Notification.EXTRA_PROGRESS),
            progressMax = e.getInt(Notification.EXTRA_PROGRESS_MAX),
            progressIndeterminate = e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
            importance = importance,
            // One UI often sets its own field to an empty string; then the standard one is the real line.
            subText = listOf(SAMSUNG_SECONDARY_INFO, Notification.EXTRA_SUB_TEXT)
                .firstNotNullOfOrNull { k -> e.getCharSequence(k)?.toString()?.takeIf { it.isNotBlank() } },
            hasRemoteChronometer = remoteViewsExtra(n, SAMSUNG_CHRONOMETER_VIEW) != null,
        )
    }

    @Suppress("DEPRECATION")
    private fun chronometerViews(n: Notification): List<RemoteViews> = listOfNotNull(
        remoteViewsExtra(n, SAMSUNG_CHRONOMETER_VIEW),
        n.contentView,
        n.bigContentView,
    )

    private fun remoteViewsExtra(n: Notification, key: String): RemoteViews? =
        if (Build.VERSION.SDK_INT >= 33) n.extras.getParcelable(key, RemoteViews::class.java)
        else @Suppress("DEPRECATION") (n.extras.getParcelable(key) as? RemoteViews)

    companion object {
        private const val TAG = "IslandNotifications"

        /** The connected listener, for calls only a listener may make (other apps' channels). */
        @Volatile var instance: IslandNotificationListener? = null
            private set

        // Samsung One UI "ongoing activity" (Now Bar) extras. Their Clock app puts the live time only here.
        private const val SAMSUNG_CHRONOMETER_VIEW = "android.ongoingActivityNoti.chronometerRemoteView"
        private const val SAMSUNG_SECONDARY_INFO = "android.ongoingActivityNoti.secondaryInfo"

        // Notification.EXTRA_*_INTENT (API 31), spelled out so older phones can read them too.
        private val CALL_STYLE_INTENTS = listOf(
            "android.declineIntent" to CallRole.DECLINE,
            "android.hangUpIntent" to CallRole.HANG_UP,
            "android.answerIntent" to CallRole.ANSWER,
        )
    }
}
