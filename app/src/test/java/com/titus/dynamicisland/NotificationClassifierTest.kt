package com.titus.dynamicisland

import com.titus.dynamicisland.data.notifications.CallRole
import com.titus.dynamicisland.data.notifications.NotificationClassifier
import com.titus.dynamicisland.data.notifications.NotificationClassifier.classify
import com.titus.dynamicisland.data.notifications.NotificationKind
import com.titus.dynamicisland.data.settings.AppFilterMode
import com.titus.dynamicisland.data.settings.IslandSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationClassifierTest {
    private val s = IslandSettings()
    private val own = "com.titus.dynamicisland"

    @Test fun regularNotificationIsAlert() =
        assertEquals(NotificationKind.ALERT, classify(snapshot(), s, own))

    @Test fun ownAppMediaAndSummariesIgnored() {
        assertNull(classify(snapshot(pkg = own), s, own))
        assertNull(classify(snapshot(media = true), s, own))
        assertNull(classify(snapshot(groupSummary = true), s, own))
    }

    @Test fun silentNotificationsIgnored() {
        assertNull(classify(snapshot(importance = 2), s, own))
        assertEquals(NotificationKind.ALERT, classify(snapshot(importance = -1000), s, own)) // unspecified
    }

    @Test fun emptyNotificationIgnored() = assertNull(classify(snapshot(title = null, text = " "), s, own))

    @Test fun chronometerIsTimer() =
        assertEquals(NotificationKind.TIMER, classify(snapshot(ongoing = true, chronometer = true, countDown = true), s, own))

    @Test fun pausedClockTimerStillTimer() = assertEquals(
        NotificationKind.TIMER,
        classify(snapshot(pkg = "com.google.android.deskclock", ongoing = true, title = "Timer"), s, own),
    )

    @Test fun samsungNowBarChronometerIsTimer() = assertEquals(
        NotificationKind.TIMER,
        classify(snapshot(pkg = "com.vendor.clock", ongoing = true, title = "Stopwatch", text = "00:00:00", remoteChronometer = true), s, own),
    )

    @Test fun samsungTimerGroupSummaryIgnored() = assertNull(
        classify(snapshot(pkg = "com.sec.android.app.clockpackage", ongoing = true, groupSummary = true, title = "Timer"), s, own),
    )

    @Test fun ongoingProgressIsProgress() {
        assertEquals(NotificationKind.PROGRESS, classify(snapshot(ongoing = true, progressMax = 100), s, own))
        assertEquals(
            NotificationKind.PROGRESS,
            classify(snapshot(promoted = true, template = "android.app.Notification\$ProgressStyle"), s, own),
        )
    }

    @Test fun plainOngoingIgnored() = assertNull(classify(snapshot(ongoing = true), s, own))

    @Test fun disabledFeaturesIgnored() {
        val off = s.copy(timersEnabled = false, liveProgressEnabled = false, notificationsEnabled = false)
        assertNull(classify(snapshot(ongoing = true, chronometer = true), off, own))
        assertNull(classify(snapshot(ongoing = true, progressMax = 10), off, own))
        assertNull(classify(snapshot(), off, own))
    }

    @Test fun appFilterModes() {
        val blocked = s.copy(notificationApps = setOf("com.example.chat"))
        assertNull(classify(snapshot(), blocked, own))
        val only = blocked.copy(notificationFilterMode = AppFilterMode.ONLY_SELECTED)
        assertEquals(NotificationKind.ALERT, classify(snapshot(), only, own))
        assertNull(classify(snapshot(pkg = "com.other"), only, own))
    }

    @Test fun silentUpdatesDoNotRealert() {
        val first = snapshot()
        assertNull(classify(snapshot(), s, own, previous = first)) // same content
        assertNull(classify(snapshot(text = "new", onlyAlertOnce = true), s, own, previous = first))
        assertEquals(NotificationKind.ALERT, classify(snapshot(text = "new"), s, own, previous = first))
    }

    @Test fun callButtonsByLabel() {
        val c = NotificationClassifier
        assertEquals(CallRole.ANSWER, c.callRole("Annehmen"))
        assertEquals(CallRole.ANSWER, c.callRole("Answer"))
        assertEquals(CallRole.DECLINE, c.callRole("Ablehnen"))
        assertEquals(CallRole.DECLINE, c.callRole("Decline"))
        assertEquals(CallRole.HANG_UP, c.callRole("Beenden"))
        assertEquals(CallRole.HANG_UP, c.callRole("End call"))
        assertEquals(CallRole.HANG_UP, c.callRole("Auflegen"))
        assertEquals(CallRole.SPEAKER, c.callRole("Lautsprecher"))
        assertEquals(CallRole.MUTE, c.callRole("Stummschalten"))
        assertEquals(CallRole.MUTE, c.callRole("Unmute"))
        assertEquals(null, c.callRole("Send message"))
    }

    @Test fun callsAreRecognised() {
        assertTrue(NotificationClassifier.isCall(snapshot(category = "call")))
        assertTrue(NotificationClassifier.isCall(snapshot(template = "android.app.Notification\$CallStyle")))
        assertFalse(NotificationClassifier.isCall(snapshot(category = "msg")))
    }

    @Test fun routesFromMapsApps() {
        val s = IslandSettings()
        val own = "com.titus.dynamicisland"
        val maps = snapshot(pkg = "com.google.android.apps.maps", title = "350 m", text = "Turn right", category = "navigation", ongoing = true)
        assertEquals(NotificationKind.NAVIGATION, classify(maps, s, own))
        val waze = snapshot(pkg = "com.waze", title = "In 200 m", ongoing = true)
        assertEquals(NotificationKind.NAVIGATION, classify(waze, s, own))
        assertNull(classify(maps, IslandSettings(navFromApps = false), own))
    }
}
