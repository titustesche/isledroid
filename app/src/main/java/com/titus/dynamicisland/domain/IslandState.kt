package com.titus.dynamicisland.domain

import com.titus.dynamicisland.data.media.MediaInfo
import com.titus.dynamicisland.data.notifications.NotificationActionItem
import com.titus.dynamicisland.data.notifications.NotificationEntry
import com.titus.dynamicisland.data.notifications.RecentNotification
import com.titus.dynamicisland.data.notifications.NotificationKind
import com.titus.dynamicisland.data.system.BluetoothDeviceInfo
import com.titus.dynamicisland.data.system.SystemEvent
import com.titus.dynamicisland.data.system.SystemState

/** Something the island can display. */
sealed interface IslandActivity {
    val id: String
    val packageName: String

    data class Media(val info: MediaInfo) : IslandActivity {
        override val id get() = "media:${info.packageName}"
        override val packageName get() = info.packageName
    }

    data class Timer(val entry: NotificationEntry) : IslandActivity {
        override val id get() = entry.key
        override val packageName get() = entry.packageName
    }

    data class Progress(val entry: NotificationEntry) : IslandActivity {
        override val id get() = entry.key
        override val packageName get() = entry.packageName
    }

    data class Alert(val entry: NotificationEntry) : IslandActivity {
        override val id get() = "alert:${entry.key}"
        override val packageName get() = entry.packageName
    }

    /** A short system announcement (charging, silent mode, volume …). */
    data class Event(val event: SystemEvent) : IslandActivity {
        override val id get() = "event:${event.kind}"
        override val packageName get() = ""
    }

    /** Flashlight on: an indicator that stays until it's turned off. */
    data object Torch : IslandActivity {
        override val id get() = "torch"
        override val packageName get() = ""
    }

    /** The voice assistant session. */
    data class Assistant(val ui: AssistantUi) : IslandActivity {
        override val id get() = "assistant"
        override val packageName get() = ""
    }

    /** Turn-by-turn from a maps app's route notification. */
    data class Navigation(val entry: NotificationEntry) : IslandActivity {
        override val id get() = entry.key
        override val packageName get() = entry.packageName
    }

    /** The island's map, pointing to a remembered place. [preview]: the settings' sample, not real. */
    data class Map(val target: MapTarget, val preview: Boolean = false) : IslandActivity {
        override val id get() = MAP_ID
        override val packageName get() = ""
    }

    /** The quick-tools panel opened from the idle pill. */
    data object Tools : IslandActivity {
        override val id get() = TOOLS_ID
        override val packageName get() = ""
    }

    /** The notifications in the shade, as a list in the island. [hideContent]: privacy mode on the lock screen. */
    data class Notifications(val items: List<RecentNotification>, val hideContent: Boolean) : IslandActivity {
        override val id get() = NOTIFICATIONS_ID
        override val packageName get() = ""
    }

    companion object {
        const val TOOLS_ID = "tools"
        const val NOTIFICATIONS_ID = "notifications"
        const val MAP_ID = "map"

        /** Identifies a call and whether it's ringing, so swiping away the ringing call doesn't hide the answered one. */
        fun callPhaseId(entry: NotificationEntry) = (if (entry.isRinging) "ringing:" else "active:") + entry.key

        fun live(entry: NotificationEntry): IslandActivity = when (entry.kind) {
            NotificationKind.TIMER -> Timer(entry)
            NotificationKind.PROGRESS -> Progress(entry)
            NotificationKind.NAVIGATION -> Navigation(entry)
            NotificationKind.ALERT -> Alert(entry)
        }
    }
}

enum class IslandMode { HIDDEN, IDLE, COMPACT, EXPANDED, ALERT, EVENT, ASSISTANT }

data class IslandUiState(
    val mode: IslandMode = IslandMode.HIDDEN,
    val primary: IslandActivity? = null,
    /** Second activity shown as a small bubble next to the compact island. */
    val secondary: IslandActivity? = null,
    /** Inline reply in progress for the expanded notification. */
    val replying: NotificationActionItem? = null,
    /** Device state for the quick-tools toggles. */
    val system: SystemState = SystemState(),
    /** Batteries the idle pill shows (see IslandSettings.batteryDevices). */
    val idleBatteries: List<BluetoothDeviceInfo> = emptyList(),
    /** A settings page's sample (see PreviewSample), not what's really going on. */
    val isPreview: Boolean = false,
)

/** Device/foreground conditions reported by the accessibility service. */
data class IslandEnvironment(
    val foregroundPackage: String? = null,
    val isFullscreen: Boolean = false,
    val isLandscape: Boolean = false,
    /** Lock screen showing: no app counts as "open" then. */
    val isLocked: Boolean = false,
) {
    val openApp: String? get() = foregroundPackage.takeUnless { isLocked }
}
