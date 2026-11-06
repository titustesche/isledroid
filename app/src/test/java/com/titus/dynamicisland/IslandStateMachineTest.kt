package com.titus.dynamicisland

import com.titus.dynamicisland.data.notifications.NotificationKind
import com.titus.dynamicisland.data.settings.IdleMode
import com.titus.dynamicisland.data.settings.IslandSettings
import com.titus.dynamicisland.domain.IslandActivity
import com.titus.dynamicisland.domain.IslandEnvironment
import com.titus.dynamicisland.domain.IslandMode
import com.titus.dynamicisland.domain.IslandStateMachine
import com.titus.dynamicisland.domain.IslandStateMachine.Inputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandStateMachineTest {
    private val timer = entry(snapshot(key = "t", pkg = "clock", ongoing = true, chronometer = true), NotificationKind.TIMER)
    private val alert = entry(snapshot(key = "a"), NotificationKind.ALERT)

    private fun inputs(
        settings: IslandSettings = IslandSettings(),
        media: com.titus.dynamicisland.data.media.MediaInfo? = null,
        live: List<com.titus.dynamicisland.data.notifications.NotificationEntry> = emptyList(),
        alert: com.titus.dynamicisland.data.notifications.NotificationEntry? = null,
        expanded: Boolean = false,
        env: IslandEnvironment = IslandEnvironment(),
        pausedExpired: Boolean = false,
        target: String? = null,
        event: com.titus.dynamicisland.data.system.SystemEvent? = null,
        system: com.titus.dynamicisland.data.system.SystemState = com.titus.dynamicisland.data.system.SystemState(),
        assistant: com.titus.dynamicisland.domain.AssistantUi? = null,
        collapsedCall: String? = null,
    ) = Inputs(settings, media, live, alert, expanded, env, pausedExpired, target, null, event, system, assistant, collapsedCall = collapsedCall)

    @Test fun idleOrHiddenWithoutActivity() {
        assertEquals(IslandMode.IDLE, IslandStateMachine.reduce(inputs()).mode)
        assertEquals(IslandMode.HIDDEN, IslandStateMachine.reduce(inputs(IslandSettings(idleMode = IdleMode.HIDDEN))).mode)
    }

    @Test fun timerOutranksMusicByDefaultWithMusicAsBubble() {
        val st = IslandStateMachine.reduce(inputs(media = media(), live = listOf(timer)))
        assertEquals(IslandMode.COMPACT, st.mode)
        assertTrue(st.primary is IslandActivity.Timer)
        assertTrue(st.secondary is IslandActivity.Media)
    }

    @Test fun musicFirstWhenPreferred() {
        val st = IslandStateMachine.reduce(
            inputs(IslandSettings(liveActivitiesOverMusic = false), media = media(), live = listOf(timer)),
        )
        assertTrue(st.primary is IslandActivity.Media)
    }

    @Test fun alertTakesOver() {
        val st = IslandStateMachine.reduce(inputs(media = media(), alert = alert))
        assertEquals(IslandMode.ALERT, st.mode)
        assertTrue(st.primary is IslandActivity.Alert)
    }

    @Test fun expandedHasNoBubble() {
        val st = IslandStateMachine.reduce(inputs(media = media(), live = listOf(timer), expanded = true))
        assertEquals(IslandMode.EXPANDED, st.mode)
        assertNull(st.secondary)
    }

    @Test fun hideRules() {
        val m = media()
        assertEquals(IslandMode.HIDDEN, IslandStateMachine.reduce(inputs(media = m, env = IslandEnvironment(isFullscreen = true))).mode)
        assertEquals(IslandMode.HIDDEN, IslandStateMachine.reduce(inputs(media = m, env = IslandEnvironment(isLandscape = true))).mode)
        val hidden = IslandSettings(hiddenApps = setOf("com.game"))
        assertEquals(
            IslandMode.HIDDEN,
            IslandStateMachine.reduce(inputs(hidden, media = m, env = IslandEnvironment(foregroundPackage = "com.game"))).mode,
        )
        assertEquals(IslandMode.HIDDEN, IslandStateMachine.reduce(inputs(IslandSettings(enabled = false), media = m)).mode)
    }

    @Test fun pausedAndBlockedMedia() {
        // Recently paused: still shown so it can be resumed from the island.
        assertEquals(IslandMode.COMPACT, IslandStateMachine.reduce(inputs(media = media(playing = false))).mode)
        // Pause timeout over: gone, unless "keep forever".
        assertEquals(IslandMode.IDLE, IslandStateMachine.reduce(inputs(media = media(playing = false), pausedExpired = true)).mode)
        assertEquals(
            IslandMode.COMPACT,
            IslandStateMachine.reduce(inputs(IslandSettings(showWhenPaused = true), media = media(playing = false), pausedExpired = true)).mode,
        )
        assertEquals(
            IslandMode.IDLE,
            IslandStateMachine.reduce(inputs(IslandSettings(blockedPlayers = setOf("com.example.music")), media = media())).mode,
        )
    }

    @Test fun progressFromMediaAppDeduplicated() {
        val musicProgress = entry(snapshot(key = "p", pkg = "com.example.music", ongoing = true, progressMax = 5), NotificationKind.PROGRESS)
        val st = IslandStateMachine.reduce(inputs(media = media(), live = listOf(musicProgress)))
        assertTrue(st.primary is IslandActivity.Media)
        assertNull(st.secondary)
    }

    @Test fun openAppsActivitiesAreHidden() {
        val inMusicApp = IslandEnvironment(foregroundPackage = "com.example.music")
        assertEquals(IslandMode.IDLE, IslandStateMachine.reduce(inputs(media = media(), env = inMusicApp)).mode)
        // Other activities still show, alerts from the open app don't.
        val st = IslandStateMachine.reduce(inputs(media = media(), live = listOf(timer), env = inMusicApp))
        assertTrue(st.primary is IslandActivity.Timer)
        assertNull(st.secondary)
        val chatOpen = IslandEnvironment(foregroundPackage = "com.example.chat")
        assertEquals(IslandMode.IDLE, IslandStateMachine.reduce(inputs(alert = alert, env = chatOpen)).mode)
        // Setting off, or on the lock screen: shown again.
        assertEquals(
            IslandMode.COMPACT,
            IslandStateMachine.reduce(inputs(IslandSettings(hideForegroundAppActivities = false), media = media(), env = inMusicApp)).mode,
        )
        assertEquals(IslandMode.COMPACT, IslandStateMachine.reduce(inputs(media = media(), env = inMusicApp.copy(isLocked = true))).mode)
    }

    @Test fun bubbleActivityCanBeExpanded() {
        val m = media()
        val st = IslandStateMachine.reduce(inputs(media = m, live = listOf(timer), expanded = true, target = "media:${m.packageName}"))
        assertEquals(IslandMode.EXPANDED, st.mode)
        assertTrue(st.primary is IslandActivity.Media)
        // Unknown/vanished target falls back to the main activity.
        assertTrue(IslandStateMachine.reduce(inputs(media = m, live = listOf(timer), expanded = true, target = "gone")).primary is IslandActivity.Timer)
    }

    @Test fun notificationPreviewThenExpanded() {
        val preview = IslandStateMachine.reduce(inputs(media = media(), alert = alert))
        assertEquals(IslandMode.ALERT, preview.mode)
        assertTrue(preview.secondary is IslandActivity.Media) // what's running stays reachable as the bubble
        val open = IslandStateMachine.reduce(inputs(media = media(), alert = alert, expanded = true))
        assertEquals(IslandMode.EXPANDED, open.mode)
        assertTrue(open.primary is IslandActivity.Alert)
    }

    @Test fun systemEventsAnnounceOverOngoing() {
        val charging = com.titus.dynamicisland.data.system.SystemEvent.Charging(50)
        val st = IslandStateMachine.reduce(inputs(media = media(), event = charging))
        assertEquals(IslandMode.EVENT, st.mode)
        assertTrue(st.secondary is IslandActivity.Media)
        // Notifications still win.
        assertEquals(IslandMode.ALERT, IslandStateMachine.reduce(inputs(event = charging, alert = alert)).mode)
    }

    @Test fun volumeBarShowsEvenWhenIslandIsHidden() {
        val fullscreen = IslandEnvironment(isFullscreen = true)
        val volume = com.titus.dynamicisland.data.system.SystemEvent.Volume(3, 5, 15)
        assertEquals(IslandMode.EVENT, IslandStateMachine.reduce(inputs(env = fullscreen, event = volume)).mode)
        val ringer = com.titus.dynamicisland.data.system.SystemEvent.Ringer(com.titus.dynamicisland.data.system.RingerMode.SILENT)
        assertEquals(IslandMode.HIDDEN, IslandStateMachine.reduce(inputs(env = fullscreen, event = ringer)).mode)
    }

    @Test fun torchIndicatorAndToolsPanel() {
        val torchOn = com.titus.dynamicisland.data.system.SystemState(torchOn = true)
        assertEquals(IslandActivity.Torch, IslandStateMachine.reduce(inputs(system = torchOn)).primary)
        assertEquals(IslandMode.IDLE, IslandStateMachine.reduce(inputs(IslandSettings(eventTorch = false), system = torchOn)).mode)
        val tools = IslandStateMachine.reduce(inputs(expanded = true, target = IslandActivity.TOOLS_ID))
        assertEquals(IslandMode.EXPANDED, tools.mode)
        assertEquals(IslandActivity.Tools, tools.primary)
    }

    @Test fun assistantTakesOverEvenInFullscreen() {
        val ui = com.titus.dynamicisland.domain.AssistantUi(com.titus.dynamicisland.domain.AssistantPhase.LISTENING)
        val st = IslandStateMachine.reduce(inputs(media = media(), alert = alert, env = IslandEnvironment(isFullscreen = true), assistant = ui))
        assertEquals(IslandMode.ASSISTANT, st.mode)
        assertTrue(st.primary is IslandActivity.Assistant)
        assertEquals(IslandMode.HIDDEN, IslandStateMachine.reduce(inputs(IslandSettings(enabled = false), assistant = ui)).mode)
    }

    private val ringing = entry(snapshot(key = "c", pkg = "dialer", title = "Mum", category = "call", ongoing = true), NotificationKind.TIMER)
    private val talking = entry(snapshot(key = "c", pkg = "dialer", title = "Mum", category = "call", ongoing = true, chronometer = true), NotificationKind.TIMER)

    @Test fun ringingCallOpensOverEverything() {
        val st = IslandStateMachine.reduce(inputs(media = media(), live = listOf(timer, ringing), alert = alert))
        assertEquals(IslandMode.EXPANDED, st.mode)
        assertEquals("c", st.primary?.id)
    }

    @Test fun runningCallStaysOpenUntilSwipedToThePill() {
        assertEquals(IslandMode.EXPANDED, IslandStateMachine.reduce(inputs(media = media(), live = listOf(talking))).mode)
        // An alert still gets through.
        assertEquals(IslandMode.ALERT, IslandStateMachine.reduce(inputs(live = listOf(talking), alert = alert)).mode)
        val collapsed = IslandStateMachine.reduce(
            inputs(media = media(), live = listOf(talking), collapsedCall = IslandActivity.callPhaseId(talking)),
        )
        assertEquals(IslandMode.COMPACT, collapsed.mode)
        assertEquals("c", collapsed.primary?.id) // the call comes first, music goes to the bubble
        assertTrue(collapsed.secondary is IslandActivity.Media)
        // Swiping away the ringing call doesn't hide the call once it's answered.
        val answered = IslandStateMachine.reduce(inputs(live = listOf(talking), collapsedCall = IslandActivity.callPhaseId(ringing)))
        assertEquals(IslandMode.EXPANDED, answered.mode)
    }

    @Test fun audioStateDecidesRingingOverTheNotification() {
        // Samsung-like: the ringing call looks like a running one (chronometer, no answer button).
        val looksTalking = talking
        val ringingAudio = com.titus.dynamicisland.data.system.SystemState(callAudio = com.titus.dynamicisland.data.system.CallAudio.RINGING)
        val st = IslandStateMachine.reduce(inputs(live = listOf(looksTalking), system = ringingAudio))
        assertTrue((st.primary as IslandActivity.Timer).entry.isRinging)
        // Talking per audio: not ringing, even though nothing in the notification says so.
        val activeAudio = com.titus.dynamicisland.data.system.SystemState(callAudio = com.titus.dynamicisland.data.system.CallAudio.ACTIVE)
        val active = IslandStateMachine.reduce(inputs(live = listOf(ringing), system = activeAudio))
        assertTrue(!(active.primary as IslandActivity.Timer).entry.isRinging)
        // Switched off: the guess stays.
        val off = IslandStateMachine.reduce(inputs(IslandSettings(callsUseAudioState = false), live = listOf(looksTalking), system = ringingAudio))
        assertTrue(!(off.primary as IslandActivity.Timer).entry.isRinging)
    }

    @Test fun notificationPanel() {
        val st = IslandStateMachine.reduce(inputs(media = media(), expanded = true, target = IslandActivity.NOTIFICATIONS_ID))
        assertEquals(IslandMode.EXPANDED, st.mode)
        assertTrue(st.primary is IslandActivity.Notifications)
    }

    @Test fun idlePillShowsPickedBatteries() {
        val sys = com.titus.dynamicisland.data.system.SystemState(
            bluetooth = listOf(
                com.titus.dynamicisland.data.system.BluetoothDeviceInfo("Buds", com.titus.dynamicisland.data.system.BluetoothKind.HEADPHONES, 80),
                com.titus.dynamicisland.data.system.BluetoothDeviceInfo("Car", com.titus.dynamicisland.data.system.BluetoothKind.OTHER, 50),
            ),
        )
        val st = IslandStateMachine.reduce(
            inputs(IslandSettings(idleMode = IdleMode.HIDDEN, batteryDevices = setOf("Buds")), system = sys),
        )
        assertEquals(IslandMode.IDLE, st.mode)
        assertEquals(listOf("Buds"), st.idleBatteries.map { it.name })
    }

    @Test fun aRouteComesBeforeMusicAndTimers() {
        val route = entry(snapshot(key = "r", pkg = "com.waze", title = "In 200 m", ongoing = true), NotificationKind.NAVIGATION)
        val st = IslandStateMachine.reduce(inputs(media = media(), live = listOf(timer, route)))
        assertEquals(IslandMode.COMPACT, st.mode)
        assertTrue(st.primary is IslandActivity.Navigation)
    }
}
