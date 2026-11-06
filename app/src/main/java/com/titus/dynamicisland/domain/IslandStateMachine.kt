package com.titus.dynamicisland.domain

import com.titus.dynamicisland.data.media.MediaInfo
import com.titus.dynamicisland.data.notifications.NotificationActionItem
import com.titus.dynamicisland.data.notifications.NotificationEntry
import com.titus.dynamicisland.data.notifications.NotificationKind
import com.titus.dynamicisland.data.notifications.RecentNotification
import com.titus.dynamicisland.data.settings.IdleMode
import com.titus.dynamicisland.data.settings.IslandSettings
import com.titus.dynamicisland.data.system.CallAudio
import com.titus.dynamicisland.data.system.SystemEvent
import com.titus.dynamicisland.data.system.SystemState

/** Pure function from inputs to what the island shows. */
object IslandStateMachine {

    /**
     * Phone apps don't say clearly whether a call rings or runs (Samsung's buttons, timers that tick from
     * boot …); the audio mode does. Calls take that instead of the guess while the setting is on.
     */
    private fun withCallAudio(i: Inputs): Inputs {
        if (!i.settings.callsUseAudioState || i.system.callAudio == CallAudio.NONE) return i
        val ringing = i.system.callAudio == CallAudio.RINGING
        if (i.live.none { it.isCall }) return i
        return i.copy(live = i.live.map { if (it.isCall) it.copy(ringingOverride = ringing) else it })
    }

    data class Inputs(
        val settings: IslandSettings,
        val media: MediaInfo?,
        val live: List<NotificationEntry>,
        val alert: NotificationEntry?,
        val expanded: Boolean,
        val environment: IslandEnvironment,
        /** Paused music has been shown long enough (see IslandSettings.pausedMusicTimeoutSec). */
        val pausedMediaExpired: Boolean = false,
        /** Which activity the user expanded (e.g. via the secondary bubble); null = the main one. */
        val expandedTarget: String? = null,
        val replying: NotificationActionItem? = null,
        /** System event currently being announced. */
        val event: SystemEvent? = null,
        val system: SystemState = SystemState(),
        val assistant: AssistantUi? = null,
        /** Notifications in the shade, for the panel. */
        val shade: List<RecentNotification> = emptyList(),
        /** The call the user swiped back to compact (see IslandActivity.callPhaseId). */
        val collapsedCall: String? = null,
        /** The map's target, if it's up, and whether it's shown big. */
        val map: MapTarget? = null,
        val mapOpen: Boolean = false,
    )

    fun reduce(inputs: Inputs): IslandUiState {
        val i = withCallAudio(inputs)
        val s = i.settings
        if (!s.enabled) return IslandUiState(IslandMode.HIDDEN)
        // Called up on purpose: shows over everything, fullscreen included.
        i.assistant?.let { ui ->
            // Working in other apps: a small pill out of the way, unless something waits for a yes.
            val small = ui.acting && s.controlCompact && ui.cards.none { it.awaitingConfirmation }
            return IslandUiState(if (small) IslandMode.COMPACT else IslandMode.ASSISTANT, IslandActivity.Assistant(ui))
        }
        if (isSuppressed(i)) {
            // The volume bar replaces the system panel, so it must show even over fullscreen video.
            return if (i.event is SystemEvent.Volume) IslandUiState(IslandMode.EVENT, IslandActivity.Event(i.event))
            else IslandUiState(IslandMode.HIDDEN)
        }

        val ongoing = orderedActivities(i)
        // A call is shown open, with its buttons, unless the user swiped it back to the pill.
        val call = (ongoing.firstOrNull() as? IslandActivity.Timer)
            ?.takeIf { s.openCalls && it.entry.isCall && IslandActivity.callPhaseId(it.entry) != i.collapsedCall }

        // Someone calling beats everything the user might be looking at.
        if (call != null && call.entry.isRinging) return IslandUiState(IslandMode.EXPANDED, call)

        if (i.alert != null && !isFromOpenApp(i, i.alert.packageName)) {
            val alert = IslandActivity.Alert(i.alert)
            return if (i.expanded) {
                IslandUiState(IslandMode.EXPANDED, alert, replying = i.replying)
            } else {
                IslandUiState(IslandMode.ALERT, alert, ongoing.firstOrNull()?.takeIf { s.showSecondaryBubble })
            }
        }

        if (i.expanded && i.expandedTarget == IslandActivity.TOOLS_ID) {
            return IslandUiState(IslandMode.EXPANDED, IslandActivity.Tools, system = i.system)
        }
        if (i.expanded && i.expandedTarget == IslandActivity.NOTIFICATIONS_ID) {
            val hide = s.privacyMode && i.environment.isLocked
            return IslandUiState(IslandMode.EXPANDED, IslandActivity.Notifications(i.shade, hide))
        }

        // A system event briefly takes over the pill; what was running stays reachable as the bubble.
        if (i.event != null && !i.expanded) {
            return IslandUiState(IslandMode.EVENT, IslandActivity.Event(i.event), ongoing.firstOrNull()?.takeIf { s.showSecondaryBubble })
        }

        // A running call stays open, below alerts and announcements (the volume bar adjusts the call).
        if (call != null && !i.expanded) return IslandUiState(IslandMode.EXPANDED, call)

        // The map stays open until it's swiped to the pill or closed.
        if (i.map != null && i.mapOpen && !i.expanded) return IslandUiState(IslandMode.EXPANDED, IslandActivity.Map(i.map))

        val primary = ongoing.firstOrNull() ?: run {
            // Picked devices' batteries keep the pill on screen, even if it's otherwise hidden when idle.
            val batteries = i.system.bluetooth.filter { it.name in s.batteryDevices && it.battery != null }
            return when {
                batteries.isNotEmpty() -> IslandUiState(IslandMode.IDLE, idleBatteries = batteries.take(2))
                s.idleMode == IdleMode.HIDDEN -> IslandUiState(IslandMode.HIDDEN)
                else -> IslandUiState(IslandMode.IDLE)
            }
        }

        return if (i.expanded) {
            IslandUiState(IslandMode.EXPANDED, ongoing.firstOrNull { it.id == i.expandedTarget } ?: primary, system = i.system)
        } else {
            IslandUiState(
                IslandMode.COMPACT,
                primary,
                ongoing.getOrNull(1)?.takeIf { s.showSecondaryBubble },
            )
        }
    }

    fun isSuppressed(i: Inputs): Boolean {
        val s = i.settings
        val env = i.environment
        return (s.hideInFullscreen && env.isFullscreen) ||
            (s.hideInLandscape && env.isLandscape) ||
            (env.openApp != null && env.openApp in s.hiddenApps)
    }

    /** The user is looking at this app already, so the island shouldn't duplicate it. */
    private fun isFromOpenApp(i: Inputs, pkg: String) =
        i.settings.hideForegroundAppActivities && pkg == i.environment.openApp

    /** Ongoing activities in display priority order. */
    fun orderedActivities(i: Inputs): List<IslandActivity> {
        val s = i.settings
        val media = i.media
            ?.takeIf { s.musicEnabled && it.packageName !in s.blockedPlayers }
            ?.takeIf { it.isPlaying || s.showWhenPaused || !i.pausedMediaExpired }
            ?.takeUnless { isFromOpenApp(i, it.packageName) }
            ?.let { IslandActivity.Media(it) }
        val live = i.live
            .filter {
                when (it.kind) {
                    NotificationKind.TIMER -> s.timersEnabled
                    NotificationKind.PROGRESS -> s.liveProgressEnabled
                    NotificationKind.NAVIGATION -> s.navFromApps
                    NotificationKind.ALERT -> false
                }
            }
            // Media apps often also post an ongoing progress notification; prefer the media session.
            .filterNot { media != null && it.packageName == media.packageName }
            .map(IslandActivity::live)
            .filterNot { isFromOpenApp(i, it.packageName) }
        // The flashlight indicator is the least important ongoing thing.
        val torch = IslandActivity.Torch.takeIf { s.eventTorch && i.system.torchOn }
        val ordered = if (s.liveActivitiesOverMusic) live + listOfNotNull(media) else listOfNotNull(media) + live
        // A call always comes first, then a running route, then the map.
        val (calls, others) = ordered.partition { (it as? IslandActivity.Timer)?.entry?.isCall == true }
        val (routes, rest) = others.partition { it is IslandActivity.Navigation }
        return calls + routes + listOfNotNull(i.map?.let { IslandActivity.Map(it) }) + rest + listOfNotNull(torch)
    }
}
