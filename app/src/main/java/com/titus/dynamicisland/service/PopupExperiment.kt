package com.titus.dynamicisland.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Process
import com.titus.dynamicisland.appGraph

/**
 * Debug-only tools for the pop-up replacement (see PopupSuppressor), run through
 * `dumpsys activity service …IslandAccessibilityService popups …` in IslandAccessibilityService.dump.
 */
object PopupExperiment {

    private const val CHANNEL = "popup_test"

    fun run(context: Context, args: List<String>): String = try {
        command(context, args)
    } catch (e: Exception) {
        "failed: $e"
    }

    private fun command(context: Context, args: List<String>): String {
        return when (args.firstOrNull()) {
            "notify" -> notify(context, args.getOrNull(1) ?: "Test pop-up")
            "channels" -> channels(args.getOrNull(1) ?: return "usage: popups channels <package>")
            "importance" -> setImportance(
                args.getOrNull(1) ?: return "usage: popups importance <package> <channel> <0-4>",
                args.getOrNull(2) ?: return "missing channel",
                args.getOrNull(3)?.toIntOrNull() ?: return "missing importance",
            )
            "status" -> "associated=${context.appGraphPopups().hasAssociation()} listener=${IslandNotificationListener.instance != null}"
            else -> "usage: popups status | notify [text] | channels <package> | importance <package> <channel> <0-4>"
        }
    }

    private fun Context.appGraphPopups() = appGraph.popups

    /** A high-importance notification from us, which would normally pop up. */
    private fun notify(context: Context, text: String): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pop-up test", NotificationManager.IMPORTANCE_HIGH))
        val id = (System.currentTimeMillis() % 100_000).toInt()
        nm.notify(
            id,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Pop-up test")
                .setContentText(text)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build(),
        )
        return "posted $id (notifications enabled: ${nm.areNotificationsEnabled()})"
    }

    /** Other apps' channels, readable and changeable by a listener with a companion-device association. */
    private fun channels(pkg: String): String {
        val listener = IslandNotificationListener.instance ?: return "listener not connected"
        return listener.getNotificationChannels(pkg, Process.myUserHandle())
            .joinToString("\n") { "${it.id}  importance=${it.importance}  \"${it.name}\"" }
            .ifEmpty { "no channels" }
    }

    private fun setImportance(pkg: String, channelId: String, importance: Int): String {
        val listener = IslandNotificationListener.instance ?: return "listener not connected"
        val user = Process.myUserHandle()
        val channel = listener.getNotificationChannels(pkg, user).firstOrNull { it.id == channelId }
            ?: return "no channel $channelId"
        val before = channel.importance
        channel.importance = importance
        listener.updateNotificationChannel(pkg, user, channel)
        val after = listener.getNotificationChannels(pkg, user).first { it.id == channelId }.importance
        return "$pkg/$channelId importance $before -> $after"
    }
}
