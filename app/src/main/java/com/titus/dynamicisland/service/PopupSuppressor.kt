package com.titus.dynamicisland.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.util.Log
import com.titus.dynamicisland.data.notifications.NotificationClassifier
import com.titus.dynamicisland.data.settings.IslandSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "Replace system pop-ups": lowers the notification channels of apps the island shows from high
 * importance (pops up) to default (doesn't, but still sounds, vibrates and lands in the shade),
 * so a notification shows once, in the island, instead of in One UI's pop-up as well.
 *
 * Android has no switch for another app's pop-ups, only per channel, and only a notification
 * listener with a companion-device association may change other apps' channels. Every original
 * importance is stored, and put back when the feature is turned off or an app leaves the island.
 *
 * Calls and alarms keep their pop-ups: a call channel at default importance can't show the
 * incoming-call screen over another app. A channel the user raises back to high is theirs: it's
 * never lowered again.
 */
class PopupSuppressor(private val context: Context, private val scope: CoroutineScope) {

    private val prefs = context.getSharedPreferences("popup_suppressor", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val user: UserHandle = Process.myUserHandle()
    private var settings: IslandSettings? = null

    private val listener get() = IslandNotificationListener.instance

    /** Whether Android lets us change other apps' channels (a companion device is associated). */
    fun hasAssociation(): Boolean = runCatching {
        context.getSystemService(CompanionDeviceManager::class.java).myAssociations.isNotEmpty()
    }.getOrDefault(false)

    /** Settings changed, or the listener (re)connected: bring every channel in line. */
    fun sync(s: IslandSettings? = settings) {
        settings = s ?: return
        scope.launch(Dispatchers.IO) { lock.withLock { syncLocked(s) } }
    }

    /** A notification arrived. One that still pops up means a channel was missed: sweep its app. */
    fun onPosted(sbn: StatusBarNotification, ranking: NotificationListenerService.Ranking?) {
        val s = settings ?: return
        val channel = ranking?.channel ?: return
        val pkg = sbn.packageName
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                val key = key(pkg, channel.id)
                val n = sbn.notification
                // A call or alarm on a channel we lowered: give it its pop-up back, for good.
                if (n.fullScreenIntent != null || n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_ALARM) {
                    if (originals().containsKey(key)) {
                        restoreChannel(pkg, channel.id)
                        exempt(key)
                        Log.i(TAG, "${label(key)} carries calls or alarms: pop-ups restored")
                    }
                    return@withLock
                }
                if (active(s, pkg) && channel.importance >= NotificationManager.IMPORTANCE_HIGH && key !in exempted()) {
                    lowerPackage(pkg)
                }
            }
        }
    }

    /** An app added or changed a channel (or the user changed it in settings). */
    fun onChannelModified(pkg: String, channel: NotificationChannel, modificationType: Int) {
        val s = settings ?: return
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                val key = key(pkg, channel.id)
                when (modificationType) {
                    NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_DELETED -> forget(key)
                    else -> when {
                        // Only the user can raise a channel's importance: they want this one's pop-ups.
                        originals().containsKey(key) && channel.importance >= NotificationManager.IMPORTANCE_HIGH -> {
                            forget(key)
                            exempt(key)
                            Log.i(TAG, "${label(key)} raised by the user: leaving it alone")
                        }
                        active(s, pkg) && shouldLower(pkg, channel) -> lower(pkg, channel)
                    }
                }
            }
        }
    }

    private fun syncLocked(s: IslandSettings) {
        val l = listener ?: return
        if (!hasAssociation()) return
        // Restore first: apps that left the island (or everything, when the feature is off).
        originals().keys.map { it.substringBefore(SEP) }.distinct()
            .filterNot { active(s, it) }
            .forEach(::restorePackage)
        if (!s.replaceSystemPopups || !s.notificationsEnabled) return
        launcherPackages().filter { active(s, it) }.forEach { pkg -> runCatching { lowerPackage(pkg) } }
        Log.i(TAG, "synced: ${originals().size} channels lowered (listener=${l.hashCode()})")
    }

    private fun active(s: IslandSettings, pkg: String) =
        s.replaceSystemPopups && s.notificationsEnabled && s.allowsNotificationsFrom(pkg) && pkg !in untouchablePackages()

    private fun lowerPackage(pkg: String) {
        channels(pkg).filter { shouldLower(pkg, it) }.forEach { lower(pkg, it) }
    }

    private fun shouldLower(pkg: String, channel: NotificationChannel) =
        channel.importance >= NotificationManager.IMPORTANCE_HIGH &&
            key(pkg, channel.id) !in exempted() &&
            !CALL_OR_ALARM.containsMatchIn("${channel.id} ${channel.name}")

    private fun lower(pkg: String, channel: NotificationChannel) {
        val l = listener ?: return
        val key = key(pkg, channel.id)
        val original = channel.importance
        channel.importance = NotificationManager.IMPORTANCE_DEFAULT
        try {
            l.updateNotificationChannel(pkg, user, channel)
            if (!originals().containsKey(key)) prefs.edit().putInt(ORIGINAL + key, original).apply()
            Log.d(TAG, "lowered ${label(key)} ($original -> default)")
        } catch (e: Exception) {
            Log.w(TAG, "couldn't lower ${label(key)}", e)
        }
    }

    private fun restorePackage(pkg: String) {
        originals().keys.filter { it.substringBefore(SEP) == pkg }.forEach { restoreChannel(pkg, it.substringAfter(SEP)) }
    }

    private fun restoreChannel(pkg: String, channelId: String) {
        val key = key(pkg, channelId)
        val original = prefs.getInt(ORIGINAL + key, -1)
        forget(key)
        if (original < 0) return
        val channel = channels(pkg).firstOrNull { it.id == channelId } ?: return
        // Changed since (by the user): theirs now, not ours to put back.
        if (channel.importance != NotificationManager.IMPORTANCE_DEFAULT) return
        channel.importance = original
        try {
            listener?.updateNotificationChannel(pkg, user, channel)
            Log.d(TAG, "restored ${label(key)} (importance $original)")
        } catch (e: Exception) {
            Log.w(TAG, "couldn't restore ${label(key)}", e)
        }
    }

    private fun channels(pkg: String): List<NotificationChannel> =
        runCatching { listener?.getNotificationChannels(pkg, user).orEmpty() }.getOrDefault(emptyList())

    private fun launcherPackages(): List<String> =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct()

    /** Never touched: us, the phone app (incoming calls) and clock apps (alarms). */
    private fun untouchablePackages(): Set<String> = buildSet {
        add(context.packageName)
        runCatching { context.getSystemService(TelecomManager::class.java).defaultDialerPackage }.getOrNull()?.let(::add)
        addAll(NotificationClassifier.CLOCK_PACKAGES)
        addAll(SYSTEM_PACKAGES)
    }

    private fun originals(): Map<String, Int> =
        prefs.all.filterKeys { it.startsWith(ORIGINAL) }.mapKeys { it.key.removePrefix(ORIGINAL) }.mapValues { it.value as Int }

    private fun forget(key: String) = prefs.edit().remove(ORIGINAL + key).apply()

    private fun exempted(): Set<String> = prefs.getStringSet(EXEMPT, emptySet()).orEmpty()

    private fun exempt(key: String) = prefs.edit().putStringSet(EXEMPT, exempted() + key).apply()

    private fun key(pkg: String, channelId: String) = pkg + SEP + channelId

    /** A stored key as readable text, for the log. */
    private fun label(key: String) = key.replace(SEP, "/")

    private companion object {
        const val TAG = "PopupSuppressor"
        const val SEP = "\u0000"
        const val ORIGINAL = "original:"
        const val EXEMPT = "exempt"
        val CALL_OR_ALARM = Regex("call|anruf|ring|incoming|voip|alarm|wecker", RegexOption.IGNORE_CASE)
        val SYSTEM_PACKAGES = setOf("android", "com.android.systemui", "com.android.server.telecom", "com.samsung.android.incallui")
    }
}
