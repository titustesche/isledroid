package com.titus.dynamicisland.data.settings

import com.titus.dynamicisland.data.assistant.ElevenLabs
/** What the island shows when there is no activity. */
enum class IdleMode { PILL, HIDDEN }

/** Which apps are allowed to pop up notification alerts. */
enum class AppFilterMode { ALL_EXCEPT_SELECTED, ONLY_SELECTED }

enum class SeekBarStyle { ONE_UI, WAVY, FLAT }

/** What tapping / long-pressing the idle pill does: nothing, the tools panel, or one tool directly. */
enum class IdleAction(val label: String, val tool: QuickTool? = null) {
    NONE("Nothing"),
    QUICK_TOOLS("Quick tools panel"),
    FLASHLIGHT("Flashlight", QuickTool.FLASHLIGHT),
    RINGER("Cycle sound mode", QuickTool.RINGER),
    DND("Do not disturb", QuickTool.DND),
    SCREENSHOT("Screenshot", QuickTool.SCREENSHOT),
    CAMERA("Camera", QuickTool.CAMERA),
    LOCK("Lock screen", QuickTool.LOCK),
    QUICK_SETTINGS("Quick settings", QuickTool.QUICK_SETTINGS),
    POWER("Power menu", QuickTool.POWER),
    ASSISTANT("Assistant"),
    NOTIFICATIONS("Notification panel"),
}

enum class QuickTool(val label: String) {
    FLASHLIGHT("Flashlight"),
    RINGER("Sound mode"),
    DND("Do not disturb"),
    SCREENSHOT("Screenshot"),
    CAMERA("Camera"),
    LOCK("Lock"),
    QUICK_SETTINGS("Quick settings"),
    POWER("Power menu"),
}

enum class AssistantBackendType(val label: String) { CLAUDE("Claude"), OPENAI_COMPATIBLE("OpenAI-compatible") }

/** Who speaks the assistant's answers. */
enum class AssistantVoiceType(val label: String) { PHONE("Phone"), PIPER("Piper"), GOOGLE("Google"), ELEVENLABS("ElevenLabs") }

/** Where the assistant's "navigate" tool sends routes. */
enum class NavigationApp(val label: String, val pkg: String?) {
    GOOGLE_MAPS("Google Maps", "com.google.android.apps.maps"),
    WAZE("Waze", "com.waze"),
    SYSTEM("Phone's default", null),
}

enum class ElevenModel(val label: String, val id: String) {
    FAST("Fast", "eleven_flash_v2_5"),
    NATURAL("Most natural", "eleven_multilingual_v2"),
}

/** Which of the assistant's actions in other apps wait for a yes. */
/** What the shrunken island shows while the assistant works in other apps. */
/** How the assistant shows what it did under its answer. */
enum class ActionStyle(val label: String) { DETAILED("Per action"), COMPACT("Compact card") }

enum class WorkIndicator(val label: String) { DOTS("Dots"), PULSE("Pulse"), SPINNER("Spinner") }

enum class ControlConfirm(val label: String) { EVERY("Every action"), IMPORTANT("Only important ones"), NEVER("Never") }

/** Raw gestures the island recognises. */
enum class Gesture { TAP, LONG_PRESS, SWIPE_LEFT, SWIPE_RIGHT, SWIPE_UP, SWIPE_DOWN }

/** Things a gesture can be mapped to. */
enum class GestureAction(val label: String) {
    NONE("Nothing"),
    TOGGLE_EXPAND("Expand / collapse"),
    EXPAND("Expand"),
    COLLAPSE("Collapse"),
    OPEN_APP("Open app"),
    PLAY_PAUSE("Play / pause"),
    NEXT_TRACK("Next track"),
    PREVIOUS_TRACK("Previous track"),
    DISMISS("Dismiss"),
    QUICK_TOOLS("Quick tools"),
    QUICK_SETTINGS("Quick settings"),
    ASSISTANT("Assistant"),
    NOTIFICATIONS("Notification panel"),
}

/**
 * Every user-customisable option. Sizes are in dp, colors are ARGB.
 * Kept as a single immutable snapshot so the overlay and the settings preview render from the same source.
 */
data class IslandSettings(
    val enabled: Boolean = true,

    // Appearance
    val idleWidthDp: Int = 110,
    /** Preferred compact width; the island grows past it (up to [compactMaxWidthDp]) to keep content clear of the camera. */
    val compactWidthDp: Int = 200,
    val compactMaxWidthDp: Int = 320,
    val heightDp: Int = 34,
    val expandedWidthDp: Int = 370,
    val cornerRoundness: Int = 100, // 0..100 % of a full pill
    val expandedCornerDp: Int = 36,
    val offsetXDp: Int = 0,
    val offsetYDp: Int = 0,
    val autoFitCutout: Boolean = true,
    val backgroundColor: Long = 0xFF000000,
    val outlineEnabled: Boolean = false,
    val outlineColor: Long = 0x40FFFFFF,
    val accentFromArtwork: Boolean = true,
    /** Music accent when not taken from the artwork. */
    val accentColor: Long = 0xFF34C759,
    val timerColor: Long = 0xFFFF9F0A,
    val progressColor: Long = 0xFF0A84FF,
    /** Text/icon color; automatic picks black or white for contrast with the background. */
    val textColorAuto: Boolean = true,
    val textColor: Long = 0xFFFFFFFF,
    // Status and panel colors
    val chargingColor: Long = 0xFF30D158,
    val lowBatteryColor: Long = 0xFFFF453A,
    val silentColor: Long = 0xFFFF453A,
    val dndColor: Long = 0xFF8E8CFF,
    val bluetoothColor: Long = 0xFF0A84FF,
    val torchColor: Long = 0xFFFFD60A,
    val volumeColor: Long = 0xFFFFFFFF,
    val quickToolsColor: Long = 0xFF34C759,
    val notificationColor: Long = 0xFF34C759,
    val assistantColor: Long = 0xFF34C759,
    val showSecondaryBubble: Boolean = true,

    // Behavior
    val idleMode: IdleMode = IdleMode.PILL,
    val hideInFullscreen: Boolean = true,
    val hideInLandscape: Boolean = true,
    val hiddenApps: Set<String> = emptySet(),
    val animationSpeed: Float = 1f, // 0.4..2
    val bounciness: Float = 0.4f, // 0..1
    val haptics: Boolean = true,
    /** How much the island swells while held (0.06 = 6 %). */
    val pressSwell: Float = 0.06f,
    /** How far (dp) the island follows a swipe. */
    val swipeReachDp: Int = 22,
    /** Strength of the stretch/squash while dragging (1 = default). */
    val elasticity: Float = 1f,
    /** How long the island must be held for a long press. */
    val longPressMs: Int = 400,
    /** Collapse an expanded island after this many seconds without interaction; 0 = never. */
    val autoCollapseSec: Int = 6,
    /** Don't show an app's music/timer/notifications while that app is open. */
    val hideForegroundAppActivities: Boolean = true,

    // Music
    val musicEnabled: Boolean = true,
    val showAlbumArt: Boolean = true,
    val showVisualizer: Boolean = true,
    /** The visualizer as stacked blocks, like an 80s spectrum analyzer. */
    val retroVisualizer: Boolean = false,
    /** Blocks per column of the retro visualizer. */
    val retroSegments: Int = 6,
    /** The top lit block of each column is drawn brighter. */
    val retroPeak: Boolean = true,
    val showSeekBar: Boolean = true,
    val seekBarStyle: SeekBarStyle = SeekBarStyle.ONE_UI,
    val blurredArtBackground: Boolean = true,
    /** Where (0..1 of the width) the blurred artwork has faded out completely. */
    val artFadeEnd: Float = 0.6f,
    /** Keep paused music in the island forever; otherwise it is hidden after [pausedMusicTimeoutSec]. */
    val showWhenPaused: Boolean = false,
    val pausedMusicTimeoutSec: Int = 30,
    val blockedPlayers: Set<String> = emptySet(),

    // Notifications
    val notificationsEnabled: Boolean = true,
    val alertDurationSec: Int = 4,
    val notificationFilterMode: AppFilterMode = AppFilterMode.ALL_EXCEPT_SELECTED,
    val notificationApps: Set<String> = emptySet(),
    val privacyMode: Boolean = false,
    /** Swiping a notification off the island (left, right or up) also deletes it from the shade. */
    val swipeDeletesNotifications: Boolean = true,
    /** Lower the channels of apps the island shows, so One UI doesn't pop them up as well (see PopupSuppressor). */
    val replaceSystemPopups: Boolean = false,

    // Calls
    /** Ringing and running calls open the island with their buttons. */
    val openCalls: Boolean = true,
    /** Answer / decline / hang up of the phone's own calls go through Android (needs ANSWER_PHONE_CALLS). */
    val callsViaTelecom: Boolean = true,
    /** Ringing vs. talking is taken from the phone's audio state rather than guessed from the notification. */
    val callsUseAudioState: Boolean = true,
    /** A running call shows how long it's been going. */
    val callShowDuration: Boolean = true,

    // Timers & live activities
    val timersEnabled: Boolean = true,
    val liveProgressEnabled: Boolean = true,
    val liveActivitiesOverMusic: Boolean = true,

    // Idle & system events
    val eventCharging: Boolean = true,
    val eventLowBattery: Boolean = true,
    val eventRinger: Boolean = true,
    val eventBluetooth: Boolean = true,
    val eventDnd: Boolean = true,
    val eventTorch: Boolean = true,
    val eventDurationSec: Int = 3,
    /** Bluetooth devices (by name) whose battery the idle pill shows while they're connected. */
    val batteryDevices: Set<String> = emptySet(),
    /** The expanded music player shows the connected headphones' battery. */
    val headphoneBatteryWithMusic: Boolean = false,
    /** Volume keys show a bar in the island instead of the system volume panel. */
    val volumeHud: Boolean = false,
    val idleTapAction: IdleAction = IdleAction.NONE,
    val idleLongPressAction: IdleAction = IdleAction.QUICK_TOOLS,
    val quickTools: List<QuickTool> = DEFAULT_TOOLS,
    val favoriteApps: Set<String> = emptySet(),

    // Assistant (API keys are stored separately, see AssistantSecrets)
    val assistantBackend: AssistantBackendType = AssistantBackendType.CLAUDE,
    val claudeModel: String = "claude-opus-5-5",
    val openAiBaseUrl: String = "https://api.openai.com/v1",
    val openAiModel: String = "",
    val speechRate: Float = 1f,
    val assistantVoice: AssistantVoiceType = AssistantVoiceType.PHONE,
    /** Speech engine package; blank = Google's if installed, else the system default. */
    val phoneTtsEngine: String = "",
    /** Voice name within that engine; blank = the best one for the phone's language. */
    val phoneVoice: String = "",
    /** Piper voice id, e.g. "de_DE-thorsten-medium"; blank = a downloaded one for the phone's language. */
    val piperVoice: String = "",
    /** Google Cloud voice name, e.g. "de-DE-Chirp3-HD-Charon"; blank = the default for the phone's language. */
    val googleVoice: String = "",
    val elevenVoiceId: String = ElevenLabs.DEFAULT_VOICE_ID,
    val elevenVoiceName: String = ElevenLabs.DEFAULT_VOICE_NAME,
    val elevenModel: ElevenModel = ElevenModel.FAST,
    /** Waze only drives; walking, cycling and transit still go to Google Maps. */
    val navigationApp: NavigationApp = NavigationApp.GOOGLE_MAPS,
    /** The island's fill fades to transparent towards its bottom while the assistant is open. */
    val assistantSoftEdge: Boolean = false,
    /** The assistant's glow spills past the island's edges onto the screen. */
    val assistantGlowSpill: Boolean = false,
    /** A blurred halo of the island's fill around the assistant, so its outline melts into the screen. */
    val assistantFeather: Boolean = true,
    /** How far (dp) the halo reaches past the island's sides. */
    val featherSpreadDp: Int = 14,
    /** How far (dp) it reaches below the island. */
    val featherBottomDp: Int = 8,
    /** How soft (dp of blur) the halo's edge is. */
    val featherBlurDp: Int = 14,
    val featherOpacity: Float = 0.85f,
    /** Results under the answer: laid out per action (alarm, message, weather …) or as uniform cards. */
    val assistantActionStyle: ActionStyle = ActionStyle.DETAILED,
    /** Drifting ASCII noise behind the assistant's text. */
    val assistantAscii: Boolean = false,
    val asciiOpacity: Float = 0.35f,
    /** Character size in sp. */
    val asciiSizeSp: Int = 9,
    /** How fast the noise drifts (0 = still). */
    val asciiSpeed: Float = 0.15f,
    /** Size of the noise's blobs in dp: small = busy, large = calm. */
    val asciiGrainDp: Int = 220,
    /** Characters in the assistant's color instead of white. */
    val asciiAccent: Boolean = false,
    /** Where (0..1 of the height) the soft bottom edge starts to fade. */
    val assistantFadeStart: Float = 0.45f,
    /** How far (dp) the glow reaches past the island when it spills. */
    val assistantGlowReachDp: Int = 14,
    /** Turn-by-turn from maps apps (Google Maps, Waze …) in the island while a route runs. */
    val navFromApps: Boolean = true,
    /** Expanding the route opens the maps app instead of the island's instruction card. */
    val navExpandOpensApp: Boolean = false,
    /** The next instruction ("Turn right onto …") under the distance in the card. */
    val navShowInstruction: Boolean = true,
    /** The assistant may use other apps (tap, type, scroll) for the user. */
    val assistantControl: Boolean = false,
    val controlConfirm: ControlConfirm = ControlConfirm.EVERY,
    /** Pause after each action before looking at the screen again (apps need a moment). */
    val controlStepDelayMs: Int = 800,
    /**
     * Taps by screen position too, for apps that don't show their buttons to accessibility: the screen
     * reading then carries every element's position, which costs some of its characters.
     */
    val controlTapAnywhere: Boolean = true,
    /** How long a long press at a position holds. */
    val controlLongPressMs: Int = 600,
    /** While it works in other apps the island shrinks to a pill with [controlIndicator], so it covers less. */
    val controlCompact: Boolean = true,
    val controlIndicator: WorkIndicator = WorkIndicator.DOTS,
    /** The pill counts the steps taken. */
    val controlShowSteps: Boolean = true,
    /** Tool steps per question while using apps. */
    val controlMaxSteps: Int = 15,
    /** The assistant may read the screen's text when asked about what's on it (see ScreenReader). */
    val assistantReadScreen: Boolean = true,
    /** At most this much screen text goes to the AI provider per look. */
    val screenReadMaxChars: Int = 6000,
    /** The assistant can remember things (see AssistantMemory). */
    val assistantMemory: Boolean = true,
    /** How many of the newest memories go along with every question. */
    val memoryInPrompt: Int = 30,
    /** It may end the conversation itself when nothing more is expected, instead of listening again. */
    val assistantSelfEnd: Boolean = true,
    /** How long the last answer stays on screen once the conversation is over. */
    val assistantDoneVisibleMs: Int = 4000,
    /** When it ends on purpose (task done), how long after the spoken answer the island closes. */
    val assistantEndDelayMs: Int = 500,
    /** "Danke", "das war's" and the like close the conversation at once. */
    val assistantEndOnThanks: Boolean = true,
    /** Messages (texts, replies, WhatsApp) wait for a yes before they go out. */
    val messagesConfirm: Boolean = true,
    /** WhatsApp messages are sent by pressing WhatsApp's send button; off: the chat opens with the text typed in. */
    val whatsappAutoSend: Boolean = true,
    /** How long to wait for WhatsApp's chat to come up before giving up on pressing send. */
    val whatsappSendWaitMs: Int = 6000,
    /** After sending, the app that was open before comes back to the front. */
    val messageReturnToApp: Boolean = true,
    /** Devices the assistant can wake with Wake-on-LAN. */
    val wolDevices: List<WolDevice> = emptyList(),
    /** Magic packets per wake; more get through flaky Wi-Fi. */
    val wolRepeats: Int = 3,
    /** Web requests the assistant can fire by name (smart home, webhooks). */
    val httpActions: List<HttpAction> = emptyList(),
    val httpTimeoutSec: Int = 10,
    /** Learns routines (what you do at which time) from the assistant, routes and the apps you open. */
    val habitsEnabled: Boolean = true,
    /** The assistant offers a routine that fits the moment. */
    val habitsSuggest: Boolean = true,
    val habitsFromApps: Boolean = true,
    val habitsFromRoutes: Boolean = true,
    /** Minutes either side of the usual time that still count as "around then". */
    val habitWindowMin: Int = 45,
    /** On how many different days it must have happened at about that time. */
    val habitMinDays: Int = 3,
    /** How far back it looks. */
    val habitLookbackDays: Int = 28,
    /** Weekdays and weekends are told apart (a work commute isn't a Sunday thing). */
    val habitSplitWeekends: Boolean = true,
    /** At most this many fitting routines go along with a question. */
    val habitsInPrompt: Int = 3,
    /** Buildings stand up from the streets. */
    val mapBuildings3d: Boolean = true,
    /** How far roofs lean out (1 = like the design). */
    val map3dStrength: Float = 1f,
    /** A straight line from you to the target. */
    val mapLine: Boolean = true,
    /** The line's color, the only color on the map. */
    val mapLineColor: Long = 0xFF0A84FF,
    /** The map turns with you; otherwise north is up. */
    val mapHeadingUp: Boolean = true,
    /** A compass strip at the top of the map. */
    val mapCompass: Boolean = true,
    /** Distance to the target at the bottom of the map. */
    val mapDistanceLabel: Boolean = true,
    /** How many metres the map shows across. */
    val mapSpanM: Int = 300,
    /** Street width multiplier. */
    val mapStreetWidth: Float = 1f,
    /** How far in from the edge the blur begins. */
    val mapBlurStartDp: Int = 40,
    /** Blur right at the edge. */
    val mapBlurMax: Float = 14f,
    /** How long the blur stays low before ramping up at the rim. */
    val mapBlurCurve: Float = 3.7f,
    /** How far in from the edge the fade to black begins. */
    val mapFadeStartDp: Int = 100,
    /** Rings pulse out from your position. */
    val mapPulse: Boolean = true,
    /** Seconds between pulse rings. */
    val mapPulseIntervalS: Float = 1.5f,
    /** The map closes once you've arrived. */
    val mapCloseOnArrival: Boolean = true,
    /** How close counts as arrived. */
    val mapArrivalRadiusM: Int = 15,

    // Gestures
    val gestureActions: Map<Gesture, GestureAction> = DEFAULT_GESTURES,
) {
    fun actionFor(gesture: Gesture): GestureAction = gestureActions[gesture] ?: GestureAction.NONE

    fun allowsNotificationsFrom(pkg: String): Boolean = when (notificationFilterMode) {
        AppFilterMode.ALL_EXCEPT_SELECTED -> pkg !in notificationApps
        AppFilterMode.ONLY_SELECTED -> pkg in notificationApps
    }

    companion object {
        val DEFAULT_TOOLS = listOf(QuickTool.FLASHLIGHT, QuickTool.RINGER, QuickTool.DND, QuickTool.SCREENSHOT, QuickTool.CAMERA)
        val DEFAULT_GESTURES = mapOf(
            Gesture.TAP to GestureAction.EXPAND,
            Gesture.LONG_PRESS to GestureAction.OPEN_APP,
            Gesture.SWIPE_LEFT to GestureAction.NEXT_TRACK,
            Gesture.SWIPE_RIGHT to GestureAction.PREVIOUS_TRACK,
            Gesture.SWIPE_DOWN to GestureAction.EXPAND,
            Gesture.SWIPE_UP to GestureAction.COLLAPSE,
        )
    }
}
