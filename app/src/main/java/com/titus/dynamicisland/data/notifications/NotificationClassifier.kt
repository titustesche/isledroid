package com.titus.dynamicisland.data.notifications

import com.titus.dynamicisland.data.settings.IslandSettings

/** Decides what, if anything, a notification becomes in the island. Pure logic. */
object NotificationClassifier {

    // NotificationManager.IMPORTANCE_DEFAULT; duplicated to keep this file free of Android framework calls.
    private const val IMPORTANCE_DEFAULT = 3

    private const val TEMPLATE_PROGRESS_STYLE = "android.app.Notification\$ProgressStyle"
    private val TIMER_CATEGORIES = setOf("alarm", "stopwatch", "call")
    val CLOCK_PACKAGES = setOf(
        "com.google.android.deskclock",
        "com.android.deskclock",
        "com.sec.android.app.clockpackage",
        "com.oneplus.deskclock",
        "com.miui.clock",
        "com.coloros.alarmclock",
        "com.huawei.deskclock",
    )

    /**
     * @param ownPackage our own package, always ignored.
     * @param previous the previously seen version of the same notification key, if any.
     */
    fun classify(
        n: NotificationSnapshot,
        settings: IslandSettings,
        ownPackage: String,
        previous: NotificationSnapshot? = null,
    ): NotificationKind? {
        if (n.packageName == ownPackage) return null
        if (n.isMediaStyle) return null // shown via MediaRepository
        if (n.isGroupSummary) return null

        if (n.isOngoing || n.isPromotedOngoing) {
            if (isNavigation(n)) return NotificationKind.NAVIGATION.takeIf { settings.navFromApps }
            if (isTimer(n)) return NotificationKind.TIMER.takeIf { settings.timersEnabled }
            if (isProgress(n)) return NotificationKind.PROGRESS.takeIf { settings.liveProgressEnabled }
            return null
        }

        if (!settings.notificationsEnabled) return null
        if (!settings.allowsNotificationsFrom(n.packageName)) return null
        if (n.importance in 0 until IMPORTANCE_DEFAULT) return null // silent / minimised
        if (n.category == "progress" || n.category == "service" || n.category == "transport") return null
        if (n.title.isNullOrBlank() && n.text.isNullOrBlank()) return null
        if (previous != null) {
            if (n.onlyAlertOnce) return null
            if (previous.title == n.title && previous.text == n.text) return null
        }
        return NotificationKind.ALERT
    }

    fun isTimer(n: NotificationSnapshot): Boolean =
        n.showChronometer || n.hasRemoteChronometer || n.category in TIMER_CATEGORIES ||
            (n.packageName in CLOCK_PACKAGES && !n.title.isNullOrBlank())

    fun isProgress(n: NotificationSnapshot): Boolean =
        n.template == TEMPLATE_PROGRESS_STYLE || n.progressMax > 0 || n.progressIndeterminate

    /** A route running in a maps app: its ongoing notification has the next turn (title: distance, text: what to do). */
    fun isNavigation(n: NotificationSnapshot): Boolean =
        n.category == "navigation" || (n.packageName in NAVIGATION_PACKAGES && !n.title.isNullOrBlank())

    val NAVIGATION_PACKAGES = setOf(
        "com.google.android.apps.maps", "com.waze", "net.osmand", "net.osmand.plus", "com.generalmagic.magicearth",
        "com.here.app.maps", "com.sygic.aura", "de.komoot.android", "app.organicmaps", "com.mapswithme.maps.pro",
    )

    fun isCall(n: NotificationSnapshot): Boolean = n.category == "call" || n.template == TEMPLATE_CALL_STYLE

    /**
     * What a call button does, from its label. Phone apps rarely say so otherwise (only Android's
     * CallStyle does, see the notification listener), and the label is in the phone's language.
     */
    fun callRole(title: String): CallRole? = CALL_ROLES.firstOrNull { (_, re) -> re.containsMatchIn(title) }?.first

    private const val TEMPLATE_CALL_STYLE = "android.app.Notification\$CallStyle"

    private fun words(vararg w: String) = Regex("\\b(${w.joinToString("|")})\\b", RegexOption.IGNORE_CASE)

    // Order matters: "End call" must be a hang-up, not an answer.
    private val CALL_ROLES = listOf(
        CallRole.HANG_UP to words(
            "hang ?up", "end", "end call", "beenden", "auflegen", "raccrocher", "colgar", "terminar", "termina", "riaggancia",
            "encerrar", "desligar", "ophangen", "beëindigen",
        ),
        CallRole.DECLINE to words(
            "decline", "reject", "dismiss", "ablehnen", "abweisen", "refuser", "rechazar", "rifiuta", "recusar", "rejeitar", "weigeren",
        ),
        CallRole.ANSWER to words(
            "answer", "accept", "pick ?up", "annehmen", "abnehmen", "répondre", "repondre", "accepter", "contestar", "aceptar",
            "rispondi", "atender", "opnemen", "aannemen",
        ),
        CallRole.SPEAKER to Regex("speaker|lautsprecher|haut-parleur|altavoz|altoparlante|alto-falante|luidspreker", RegexOption.IGNORE_CASE),
        CallRole.MUTE to Regex("mute|stumm|muet|silenciar|silenzia|mudo|dempen", RegexOption.IGNORE_CASE),
    )
}
