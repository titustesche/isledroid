package com.titus.dynamicisland.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "island_settings")

/**
 * Settings live in memory first so the overlay and preview react on every slider tick;
 * changes are persisted to DataStore with a short debounce.
 */
class SettingsRepository(private val context: Context, scope: CoroutineScope) {

    private val _settings = MutableStateFlow(IslandSettings())
    val settings: StateFlow<IslandSettings> = _settings.asStateFlow()

    init {
        scope.launch {
            _settings.value = context.dataStore.data.first().toSettings()
            persistChanges()
        }
    }

    fun update(transform: (IslandSettings) -> IslandSettings) = _settings.update(transform)

    fun reset() = _settings.update { IslandSettings() }

    @OptIn(FlowPreview::class)
    private suspend fun persistChanges() {
        _settings.drop(1).debounce(300).collect { s -> context.dataStore.edit { it.write(s) } }
    }

    private object K {
        val enabled = booleanPreferencesKey("enabled")
        val idleWidth = intPreferencesKey("idle_width")
        val schema = intPreferencesKey("schema_version")
        val compactWidth = intPreferencesKey("compact_width")
        val compactMaxWidth = intPreferencesKey("compact_max_width")
        val autoCollapse = intPreferencesKey("auto_collapse")
        val hideForeground = booleanPreferencesKey("hide_foreground")
        val height = intPreferencesKey("height")
        val expandedWidth = intPreferencesKey("expanded_width")
        val roundness = intPreferencesKey("roundness")
        val expandedCorner = intPreferencesKey("expanded_corner")
        val offsetX = intPreferencesKey("offset_x")
        val offsetY = intPreferencesKey("offset_y")
        val autoFit = booleanPreferencesKey("auto_fit")
        val bg = longPreferencesKey("bg_color")
        val outline = booleanPreferencesKey("outline")
        val outlineColor = longPreferencesKey("outline_color")
        val accentFromArt = booleanPreferencesKey("accent_from_art")
        val accent = longPreferencesKey("accent")
        val timerColor = longPreferencesKey("timer_color")
        val progressColor = longPreferencesKey("progress_color")
        val textAuto = booleanPreferencesKey("text_color_auto")
        val textColor = longPreferencesKey("text_color")
        val chargingColor = longPreferencesKey("charging_color")
        val lowBatteryColor = longPreferencesKey("low_battery_color")
        val silentColor = longPreferencesKey("silent_color")
        val dndColor = longPreferencesKey("dnd_color")
        val bluetoothColor = longPreferencesKey("bluetooth_color")
        val torchColor = longPreferencesKey("torch_color")
        val volumeColor = longPreferencesKey("volume_color")
        val quickToolsColor = longPreferencesKey("quick_tools_color")
        val notificationColor = longPreferencesKey("notification_color")
        val assistantColor = longPreferencesKey("assistant_color")
        val assistantSoftEdge = booleanPreferencesKey("assistant_soft_edge")
        val assistantGlowSpill = booleanPreferencesKey("assistant_glow_spill")
        val assistantFeather = booleanPreferencesKey("assistant_feather")
        val featherSpread = intPreferencesKey("assistant_feather_spread_dp")
        val featherBottom = intPreferencesKey("assistant_feather_bottom_dp")
        val featherBlur = intPreferencesKey("assistant_feather_blur_dp")
        val featherOpacity = floatPreferencesKey("assistant_feather_opacity")
        val actionStyle = stringPreferencesKey("assistant_action_style")
        val artFade = floatPreferencesKey("art_fade_end")
        val secondaryBubble = booleanPreferencesKey("secondary_bubble")
        val idleMode = stringPreferencesKey("idle_mode")
        val hideFullscreen = booleanPreferencesKey("hide_fullscreen")
        val hideLandscape = booleanPreferencesKey("hide_landscape")
        val hiddenApps = stringSetPreferencesKey("hidden_apps")
        val openCalls = booleanPreferencesKey("open_calls")
        val retroVisualizer = booleanPreferencesKey("retro_visualizer")
        val assistantAscii = booleanPreferencesKey("assistant_ascii")
        val retroSegments = intPreferencesKey("retro_segments")
        val retroPeak = booleanPreferencesKey("retro_peak")
        val asciiOpacity = floatPreferencesKey("ascii_opacity")
        val asciiSize = intPreferencesKey("ascii_size_sp")
        val asciiSpeed = floatPreferencesKey("ascii_speed")
        val asciiGrain = intPreferencesKey("ascii_grain_dp")
        val asciiAccent = booleanPreferencesKey("ascii_accent")
        val fadeStart = floatPreferencesKey("assistant_fade_start")
        val glowReach = intPreferencesKey("assistant_glow_reach_dp")
        val controlConfirm = stringPreferencesKey("control_confirm")
        val controlMaxSteps = intPreferencesKey("control_max_steps")
        val controlStepDelayMs = intPreferencesKey("control_step_delay_ms")
        val assistantControl = booleanPreferencesKey("assistant_control")
        val controlTapAnywhere = booleanPreferencesKey("control_tap_anywhere")
        val controlLongPressMs = intPreferencesKey("control_long_press_ms")
        val controlCompact = booleanPreferencesKey("control_compact")
        val controlIndicator = stringPreferencesKey("control_indicator")
        val controlShowSteps = booleanPreferencesKey("control_show_steps")
        val screenReadMaxChars = intPreferencesKey("screen_read_max_chars")
        val assistantReadScreen = booleanPreferencesKey("assistant_read_screen")
        val navShowInstruction = booleanPreferencesKey("nav_show_instruction")
        val navExpandOpensApp = booleanPreferencesKey("nav_expand_opens_app")
        val navFromApps = booleanPreferencesKey("nav_from_apps")
        val assistantMemory = booleanPreferencesKey("assistant_memory")
        val memoryInPrompt = intPreferencesKey("memory_in_prompt")
        val callsViaTelecom = booleanPreferencesKey("calls_via_telecom")
        val callsUseAudioState = booleanPreferencesKey("calls_use_audio_state")
        val callShowDuration = booleanPreferencesKey("call_show_duration")
        val assistantSelfEnd = booleanPreferencesKey("assistant_self_end")
        val assistantDoneVisibleMs = intPreferencesKey("assistant_done_visible_ms")
        val assistantEndDelayMs = intPreferencesKey("assistant_end_delay_ms")
        val assistantEndOnThanks = booleanPreferencesKey("assistant_end_on_thanks")
        val assistantScreenshots = booleanPreferencesKey("assistant_screenshots")
        val screenshotMaxEdge = intPreferencesKey("screenshot_max_edge")
        val messagesConfirm = booleanPreferencesKey("messages_confirm")
        val whatsappAutoSend = booleanPreferencesKey("whatsapp_auto_send")
        val whatsappSendWaitMs = intPreferencesKey("whatsapp_send_wait_ms")
        val messageReturnToApp = booleanPreferencesKey("message_return_to_app")
        val wolRepeats = intPreferencesKey("wol_repeats")
        val httpTimeoutSec = intPreferencesKey("http_timeout_sec")
        val habitsEnabled = booleanPreferencesKey("habits_enabled")
        val habitsSuggest = booleanPreferencesKey("habits_suggest")
        val habitsFromApps = booleanPreferencesKey("habits_from_apps")
        val habitsFromRoutes = booleanPreferencesKey("habits_from_routes")
        val habitWindowMin = intPreferencesKey("habit_window_min")
        val habitMinDays = intPreferencesKey("habit_min_days")
        val habitLookbackDays = intPreferencesKey("habit_lookback_days")
        val habitSplitWeekends = booleanPreferencesKey("habit_split_weekends")
        val habitsInPrompt = intPreferencesKey("habits_in_prompt")
        val wolDevices = stringPreferencesKey("wol_devices")
        val httpActions = stringPreferencesKey("http_actions")
        val mapBuildings3d = booleanPreferencesKey("map_buildings_3d")
        val map3dStrength = floatPreferencesKey("map_3d_strength")
        val mapLine = booleanPreferencesKey("map_line")
        val mapLineColor = longPreferencesKey("map_line_color")
        val mapHeadingUp = booleanPreferencesKey("map_heading_up")
        val mapCompass = booleanPreferencesKey("map_compass")
        val mapDistanceLabel = booleanPreferencesKey("map_distance_label")
        val mapSpanM = intPreferencesKey("map_span_m")
        val mapStreetWidth = floatPreferencesKey("map_street_width")
        val mapBlurStartDp = intPreferencesKey("map_blur_start_dp")
        val mapBlurMax = floatPreferencesKey("map_blur_max")
        val mapBlurCurve = floatPreferencesKey("map_blur_curve")
        val mapFadeStartDp = intPreferencesKey("map_fade_start_dp")
        val mapPulse = booleanPreferencesKey("map_pulse")
        val mapPulseIntervalS = floatPreferencesKey("map_pulse_interval_s")
        val mapCloseOnArrival = booleanPreferencesKey("map_close_on_arrival")
        val mapArrivalRadiusM = intPreferencesKey("map_arrival_radius_m")
        val batteryDevices = stringSetPreferencesKey("battery_devices")
        val headphoneBattery = booleanPreferencesKey("headphone_battery_with_music")
        val animSpeed = floatPreferencesKey("anim_speed")
        val bounciness = floatPreferencesKey("bounciness")
        val haptics = booleanPreferencesKey("haptics")
        val pressSwell = floatPreferencesKey("press_swell")
        val swipeReach = intPreferencesKey("swipe_reach")
        val elasticity = floatPreferencesKey("elasticity")
        val longPressMs = intPreferencesKey("long_press_ms")
        val pausedTimeout = intPreferencesKey("paused_timeout")
        val music = booleanPreferencesKey("music")
        val albumArt = booleanPreferencesKey("album_art")
        val visualizer = booleanPreferencesKey("visualizer")
        val seekBar = booleanPreferencesKey("seek_bar")
        val blurredArt = booleanPreferencesKey("blurred_art")
        val seekStyle = stringPreferencesKey("seek_style")
        val showPaused = booleanPreferencesKey("show_paused")
        val blockedPlayers = stringSetPreferencesKey("blocked_players")
        val notifications = booleanPreferencesKey("notifications")
        val alertDuration = intPreferencesKey("alert_duration")
        val filterMode = stringPreferencesKey("filter_mode")
        val notificationApps = stringSetPreferencesKey("notification_apps")
        val privacy = booleanPreferencesKey("privacy")
        val swipeDeletes = booleanPreferencesKey("swipe_deletes_notifications")
        val replacePopups = booleanPreferencesKey("replace_system_popups")
        val timers = booleanPreferencesKey("timers")
        val liveProgress = booleanPreferencesKey("live_progress")
        val liveOverMusic = booleanPreferencesKey("live_over_music")
        val evCharging = booleanPreferencesKey("ev_charging")
        val evLowBattery = booleanPreferencesKey("ev_low_battery")
        val evRinger = booleanPreferencesKey("ev_ringer")
        val evBluetooth = booleanPreferencesKey("ev_bluetooth")
        val evDnd = booleanPreferencesKey("ev_dnd")
        val evTorch = booleanPreferencesKey("ev_torch")
        val evDuration = intPreferencesKey("ev_duration")
        val volumeHud = booleanPreferencesKey("volume_hud")
        val toolsTrigger = stringPreferencesKey("tools_trigger") // v3 only; replaced by the two below
        val idleTap = stringPreferencesKey("idle_tap")
        val idleLongPress = stringPreferencesKey("idle_long_press")
        val tools = stringPreferencesKey("tools") // ordered, comma-separated
        val favorites = stringSetPreferencesKey("favorite_apps")
        val asBackend = stringPreferencesKey("assistant_backend")
        val claudeModel = stringPreferencesKey("claude_model")
        val openAiBase = stringPreferencesKey("openai_base")
        val openAiModel = stringPreferencesKey("openai_model")
        val speechRate = floatPreferencesKey("speech_rate")
        val asVoice = stringPreferencesKey("assistant_voice")
        val phoneEngine = stringPreferencesKey("phone_tts_engine")
        val phoneVoice = stringPreferencesKey("phone_voice")
        val piperVoice = stringPreferencesKey("piper_voice")
        val googleVoice = stringPreferencesKey("google_voice")
        val elevenVoiceId = stringPreferencesKey("eleven_voice_id")
        val elevenVoiceName = stringPreferencesKey("eleven_voice_name")
        val elevenModel = stringPreferencesKey("eleven_model")
        val navigationApp = stringPreferencesKey("navigation_app")
        fun gesture(g: Gesture) = stringPreferencesKey("gesture_${g.name.lowercase()}")
    }

    private fun Preferences.toSettings(): IslandSettings {
        val d = IslandSettings()
        return IslandSettings(
            enabled = this[K.enabled] ?: d.enabled,
            idleWidthDp = this[K.idleWidth] ?: d.idleWidthDp,
            compactWidthDp = this[K.compactWidth] ?: d.compactWidthDp,
            compactMaxWidthDp = this[K.compactMaxWidth] ?: d.compactMaxWidthDp,
            autoCollapseSec = this[K.autoCollapse] ?: d.autoCollapseSec,
            hideForegroundAppActivities = this[K.hideForeground] ?: d.hideForegroundAppActivities,
            heightDp = this[K.height] ?: d.heightDp,
            expandedWidthDp = this[K.expandedWidth] ?: d.expandedWidthDp,
            cornerRoundness = this[K.roundness] ?: d.cornerRoundness,
            expandedCornerDp = this[K.expandedCorner] ?: d.expandedCornerDp,
            offsetXDp = this[K.offsetX] ?: d.offsetXDp,
            offsetYDp = this[K.offsetY] ?: d.offsetYDp,
            autoFitCutout = this[K.autoFit] ?: d.autoFitCutout,
            backgroundColor = this[K.bg] ?: d.backgroundColor,
            outlineEnabled = this[K.outline] ?: d.outlineEnabled,
            outlineColor = this[K.outlineColor] ?: d.outlineColor,
            accentFromArtwork = this[K.accentFromArt] ?: d.accentFromArtwork,
            accentColor = this[K.accent] ?: d.accentColor,
            timerColor = this[K.timerColor] ?: d.timerColor,
            progressColor = this[K.progressColor] ?: d.progressColor,
            textColorAuto = this[K.textAuto] ?: d.textColorAuto,
            textColor = this[K.textColor] ?: d.textColor,
            chargingColor = this[K.chargingColor] ?: d.chargingColor,
            lowBatteryColor = this[K.lowBatteryColor] ?: d.lowBatteryColor,
            silentColor = this[K.silentColor] ?: d.silentColor,
            dndColor = this[K.dndColor] ?: d.dndColor,
            bluetoothColor = this[K.bluetoothColor] ?: d.bluetoothColor,
            torchColor = this[K.torchColor] ?: d.torchColor,
            volumeColor = this[K.volumeColor] ?: d.volumeColor,
            quickToolsColor = this[K.quickToolsColor] ?: d.quickToolsColor,
            notificationColor = this[K.notificationColor] ?: d.notificationColor,
            assistantColor = this[K.assistantColor] ?: d.assistantColor,
            assistantSoftEdge = this[K.assistantSoftEdge] ?: d.assistantSoftEdge,
            assistantGlowSpill = this[K.assistantGlowSpill] ?: d.assistantGlowSpill,
            assistantFeather = this[K.assistantFeather] ?: d.assistantFeather,
            featherSpreadDp = this[K.featherSpread] ?: d.featherSpreadDp,
            featherBottomDp = this[K.featherBottom] ?: d.featherBottomDp,
            featherBlurDp = this[K.featherBlur] ?: d.featherBlurDp,
            featherOpacity = this[K.featherOpacity] ?: d.featherOpacity,
            assistantActionStyle = enumOr(this[K.actionStyle], d.assistantActionStyle),
            artFadeEnd = this[K.artFade] ?: d.artFadeEnd,
            showSecondaryBubble = this[K.secondaryBubble] ?: d.showSecondaryBubble,
            idleMode = enumOr(this[K.idleMode], d.idleMode),
            hideInFullscreen = this[K.hideFullscreen] ?: d.hideInFullscreen,
            hideInLandscape = this[K.hideLandscape] ?: d.hideInLandscape,
            hiddenApps = this[K.hiddenApps] ?: d.hiddenApps,
            openCalls = this[K.openCalls] ?: d.openCalls,
            retroVisualizer = this[K.retroVisualizer] ?: d.retroVisualizer,
            assistantAscii = this[K.assistantAscii] ?: d.assistantAscii,
            retroSegments = this[K.retroSegments] ?: d.retroSegments,
            retroPeak = this[K.retroPeak] ?: d.retroPeak,
            asciiOpacity = this[K.asciiOpacity] ?: d.asciiOpacity,
            asciiSizeSp = this[K.asciiSize] ?: d.asciiSizeSp,
            asciiSpeed = this[K.asciiSpeed] ?: d.asciiSpeed,
            asciiGrainDp = this[K.asciiGrain] ?: d.asciiGrainDp,
            asciiAccent = this[K.asciiAccent] ?: d.asciiAccent,
            assistantFadeStart = this[K.fadeStart] ?: d.assistantFadeStart,
            assistantGlowReachDp = this[K.glowReach] ?: d.assistantGlowReachDp,
            controlConfirm = enumOr(this[K.controlConfirm], d.controlConfirm),
            controlMaxSteps = this[K.controlMaxSteps] ?: d.controlMaxSteps,
            controlStepDelayMs = this[K.controlStepDelayMs] ?: d.controlStepDelayMs,
            assistantControl = this[K.assistantControl] ?: d.assistantControl,
            controlTapAnywhere = this[K.controlTapAnywhere] ?: d.controlTapAnywhere,
            controlLongPressMs = this[K.controlLongPressMs] ?: d.controlLongPressMs,
            controlCompact = this[K.controlCompact] ?: d.controlCompact,
            controlIndicator = enumOr(this[K.controlIndicator], d.controlIndicator),
            controlShowSteps = this[K.controlShowSteps] ?: d.controlShowSteps,
            screenReadMaxChars = this[K.screenReadMaxChars] ?: d.screenReadMaxChars,
            assistantReadScreen = this[K.assistantReadScreen] ?: d.assistantReadScreen,
            navShowInstruction = this[K.navShowInstruction] ?: d.navShowInstruction,
            navExpandOpensApp = this[K.navExpandOpensApp] ?: d.navExpandOpensApp,
            navFromApps = this[K.navFromApps] ?: d.navFromApps,
            assistantMemory = this[K.assistantMemory] ?: d.assistantMemory,
            memoryInPrompt = this[K.memoryInPrompt] ?: d.memoryInPrompt,
            callsViaTelecom = this[K.callsViaTelecom] ?: d.callsViaTelecom,
            callsUseAudioState = this[K.callsUseAudioState] ?: d.callsUseAudioState,
            callShowDuration = this[K.callShowDuration] ?: d.callShowDuration,
            assistantSelfEnd = this[K.assistantSelfEnd] ?: d.assistantSelfEnd,
            assistantDoneVisibleMs = this[K.assistantDoneVisibleMs] ?: d.assistantDoneVisibleMs,
            assistantEndDelayMs = this[K.assistantEndDelayMs] ?: d.assistantEndDelayMs,
            assistantEndOnThanks = this[K.assistantEndOnThanks] ?: d.assistantEndOnThanks,
            assistantScreenshots = this[K.assistantScreenshots] ?: d.assistantScreenshots,
            screenshotMaxEdge = this[K.screenshotMaxEdge] ?: d.screenshotMaxEdge,
            messagesConfirm = this[K.messagesConfirm] ?: d.messagesConfirm,
            whatsappAutoSend = this[K.whatsappAutoSend] ?: d.whatsappAutoSend,
            whatsappSendWaitMs = this[K.whatsappSendWaitMs] ?: d.whatsappSendWaitMs,
            messageReturnToApp = this[K.messageReturnToApp] ?: d.messageReturnToApp,
            wolRepeats = this[K.wolRepeats] ?: d.wolRepeats,
            httpTimeoutSec = this[K.httpTimeoutSec] ?: d.httpTimeoutSec,
            habitsEnabled = this[K.habitsEnabled] ?: d.habitsEnabled,
            habitsSuggest = this[K.habitsSuggest] ?: d.habitsSuggest,
            habitsFromApps = this[K.habitsFromApps] ?: d.habitsFromApps,
            habitsFromRoutes = this[K.habitsFromRoutes] ?: d.habitsFromRoutes,
            habitWindowMin = this[K.habitWindowMin] ?: d.habitWindowMin,
            habitMinDays = this[K.habitMinDays] ?: d.habitMinDays,
            habitLookbackDays = this[K.habitLookbackDays] ?: d.habitLookbackDays,
            habitSplitWeekends = this[K.habitSplitWeekends] ?: d.habitSplitWeekends,
            habitsInPrompt = this[K.habitsInPrompt] ?: d.habitsInPrompt,
            wolDevices = NetworkActionsJson.devicesFromJson(this[K.wolDevices]) ?: d.wolDevices,
            httpActions = NetworkActionsJson.actionsFromJson(this[K.httpActions]) ?: d.httpActions,
            mapBuildings3d = this[K.mapBuildings3d] ?: d.mapBuildings3d,
            map3dStrength = this[K.map3dStrength] ?: d.map3dStrength,
            mapLine = this[K.mapLine] ?: d.mapLine,
            mapLineColor = this[K.mapLineColor] ?: d.mapLineColor,
            mapHeadingUp = this[K.mapHeadingUp] ?: d.mapHeadingUp,
            mapCompass = this[K.mapCompass] ?: d.mapCompass,
            mapDistanceLabel = this[K.mapDistanceLabel] ?: d.mapDistanceLabel,
            mapSpanM = this[K.mapSpanM] ?: d.mapSpanM,
            mapStreetWidth = this[K.mapStreetWidth] ?: d.mapStreetWidth,
            mapBlurStartDp = this[K.mapBlurStartDp] ?: d.mapBlurStartDp,
            mapBlurMax = this[K.mapBlurMax] ?: d.mapBlurMax,
            mapBlurCurve = this[K.mapBlurCurve] ?: d.mapBlurCurve,
            mapFadeStartDp = this[K.mapFadeStartDp] ?: d.mapFadeStartDp,
            mapPulse = this[K.mapPulse] ?: d.mapPulse,
            mapPulseIntervalS = this[K.mapPulseIntervalS] ?: d.mapPulseIntervalS,
            mapCloseOnArrival = this[K.mapCloseOnArrival] ?: d.mapCloseOnArrival,
            mapArrivalRadiusM = this[K.mapArrivalRadiusM] ?: d.mapArrivalRadiusM,
            batteryDevices = this[K.batteryDevices] ?: d.batteryDevices,
            headphoneBatteryWithMusic = this[K.headphoneBattery] ?: d.headphoneBatteryWithMusic,
            animationSpeed = this[K.animSpeed] ?: d.animationSpeed,
            bounciness = this[K.bounciness] ?: d.bounciness,
            haptics = this[K.haptics] ?: d.haptics,
            pressSwell = this[K.pressSwell] ?: d.pressSwell,
            swipeReachDp = this[K.swipeReach] ?: d.swipeReachDp,
            elasticity = this[K.elasticity] ?: d.elasticity,
            longPressMs = this[K.longPressMs] ?: d.longPressMs,
            pausedMusicTimeoutSec = this[K.pausedTimeout] ?: d.pausedMusicTimeoutSec,
            musicEnabled = this[K.music] ?: d.musicEnabled,
            showAlbumArt = this[K.albumArt] ?: d.showAlbumArt,
            showVisualizer = this[K.visualizer] ?: d.showVisualizer,
            showSeekBar = this[K.seekBar] ?: d.showSeekBar,
            blurredArtBackground = this[K.blurredArt] ?: d.blurredArtBackground,
            seekBarStyle = enumOr(this[K.seekStyle], d.seekBarStyle),
            showWhenPaused = this[K.showPaused] ?: d.showWhenPaused,
            blockedPlayers = this[K.blockedPlayers] ?: d.blockedPlayers,
            notificationsEnabled = this[K.notifications] ?: d.notificationsEnabled,
            alertDurationSec = this[K.alertDuration] ?: d.alertDurationSec,
            notificationFilterMode = enumOr(this[K.filterMode], d.notificationFilterMode),
            notificationApps = this[K.notificationApps] ?: d.notificationApps,
            privacyMode = this[K.privacy] ?: d.privacyMode,
            swipeDeletesNotifications = this[K.swipeDeletes] ?: d.swipeDeletesNotifications,
            replaceSystemPopups = this[K.replacePopups] ?: d.replaceSystemPopups,
            timersEnabled = this[K.timers] ?: d.timersEnabled,
            liveProgressEnabled = this[K.liveProgress] ?: d.liveProgressEnabled,
            liveActivitiesOverMusic = this[K.liveOverMusic] ?: d.liveActivitiesOverMusic,
            eventCharging = this[K.evCharging] ?: d.eventCharging,
            eventLowBattery = this[K.evLowBattery] ?: d.eventLowBattery,
            eventRinger = this[K.evRinger] ?: d.eventRinger,
            eventBluetooth = this[K.evBluetooth] ?: d.eventBluetooth,
            eventDnd = this[K.evDnd] ?: d.eventDnd,
            eventTorch = this[K.evTorch] ?: d.eventTorch,
            eventDurationSec = this[K.evDuration] ?: d.eventDurationSec,
            volumeHud = this[K.volumeHud] ?: d.volumeHud,
            idleTapAction = enumOr(this[K.idleTap], if (this[K.toolsTrigger] == "TAP") IdleAction.QUICK_TOOLS else d.idleTapAction),
            idleLongPressAction = enumOr(
                this[K.idleLongPress],
                when (this[K.toolsTrigger]) {
                    "TAP", "OFF" -> IdleAction.NONE
                    else -> d.idleLongPressAction
                },
            ),
            quickTools = this[K.tools]?.split(',')?.mapNotNull { n -> QuickTool.entries.firstOrNull { it.name == n } } ?: d.quickTools,
            favoriteApps = this[K.favorites] ?: d.favoriteApps,
            assistantBackend = enumOr(this[K.asBackend], d.assistantBackend),
            claudeModel = this[K.claudeModel] ?: d.claudeModel,
            openAiBaseUrl = this[K.openAiBase] ?: d.openAiBaseUrl,
            openAiModel = this[K.openAiModel] ?: d.openAiModel,
            speechRate = this[K.speechRate] ?: d.speechRate,
            assistantVoice = enumOr(this[K.asVoice], d.assistantVoice),
            phoneTtsEngine = this[K.phoneEngine] ?: d.phoneTtsEngine,
            phoneVoice = this[K.phoneVoice] ?: d.phoneVoice,
            piperVoice = this[K.piperVoice] ?: d.piperVoice,
            googleVoice = this[K.googleVoice] ?: d.googleVoice,
            elevenVoiceId = this[K.elevenVoiceId] ?: d.elevenVoiceId,
            elevenVoiceName = this[K.elevenVoiceName] ?: d.elevenVoiceName,
            elevenModel = enumOr(this[K.elevenModel], d.elevenModel),
            navigationApp = enumOr(this[K.navigationApp], d.navigationApp),
            gestureActions = Gesture.entries.associateWith { g ->
                enumOr(this[K.gesture(g)], d.actionFor(g))
            },
        ).migrate(this[K.schema] ?: 1)
    }

    private fun IslandSettings.migrate(from: Int): IslandSettings {
        var s = this
        // v2: tapping no longer collapses (tap outside does), so move the old toggle default to expand.
        if (from < 2 && s.actionFor(Gesture.TAP) == GestureAction.TOGGLE_EXPAND) {
            s = s.copy(gestureActions = s.gestureActions + (Gesture.TAP to GestureAction.EXPAND))
        }
        // v3: One UI seek bar became the default; move users off the previous default.
        if (from < 3 && s.seekBarStyle == SeekBarStyle.WAVY) s = s.copy(seekBarStyle = SeekBarStyle.ONE_UI)
        return s
    }

    private fun MutablePreferences.write(s: IslandSettings) {
        this[K.schema] = SCHEMA_VERSION
        this[K.enabled] = s.enabled
        this[K.compactMaxWidth] = s.compactMaxWidthDp
        this[K.autoCollapse] = s.autoCollapseSec
        this[K.hideForeground] = s.hideForegroundAppActivities
        this[K.idleWidth] = s.idleWidthDp
        this[K.compactWidth] = s.compactWidthDp
        this[K.height] = s.heightDp
        this[K.expandedWidth] = s.expandedWidthDp
        this[K.roundness] = s.cornerRoundness
        this[K.expandedCorner] = s.expandedCornerDp
        this[K.offsetX] = s.offsetXDp
        this[K.offsetY] = s.offsetYDp
        this[K.autoFit] = s.autoFitCutout
        this[K.bg] = s.backgroundColor
        this[K.outline] = s.outlineEnabled
        this[K.outlineColor] = s.outlineColor
        this[K.accentFromArt] = s.accentFromArtwork
        this[K.accent] = s.accentColor
        this[K.timerColor] = s.timerColor
        this[K.progressColor] = s.progressColor
        this[K.textAuto] = s.textColorAuto
        this[K.textColor] = s.textColor
        this[K.chargingColor] = s.chargingColor
        this[K.lowBatteryColor] = s.lowBatteryColor
        this[K.silentColor] = s.silentColor
        this[K.dndColor] = s.dndColor
        this[K.bluetoothColor] = s.bluetoothColor
        this[K.torchColor] = s.torchColor
        this[K.volumeColor] = s.volumeColor
        this[K.quickToolsColor] = s.quickToolsColor
        this[K.notificationColor] = s.notificationColor
        this[K.assistantColor] = s.assistantColor
        this[K.assistantSoftEdge] = s.assistantSoftEdge
        this[K.assistantGlowSpill] = s.assistantGlowSpill
        this[K.assistantFeather] = s.assistantFeather
        this[K.featherSpread] = s.featherSpreadDp
        this[K.featherBottom] = s.featherBottomDp
        this[K.featherBlur] = s.featherBlurDp
        this[K.featherOpacity] = s.featherOpacity
        this[K.actionStyle] = s.assistantActionStyle.name
        this[K.artFade] = s.artFadeEnd
        this[K.secondaryBubble] = s.showSecondaryBubble
        this[K.idleMode] = s.idleMode.name
        this[K.hideFullscreen] = s.hideInFullscreen
        this[K.hideLandscape] = s.hideInLandscape
        this[K.hiddenApps] = s.hiddenApps
        this[K.openCalls] = s.openCalls
        this[K.retroVisualizer] = s.retroVisualizer
        this[K.assistantAscii] = s.assistantAscii
        this[K.retroSegments] = s.retroSegments
        this[K.retroPeak] = s.retroPeak
        this[K.asciiOpacity] = s.asciiOpacity
        this[K.asciiSize] = s.asciiSizeSp
        this[K.asciiSpeed] = s.asciiSpeed
        this[K.asciiGrain] = s.asciiGrainDp
        this[K.asciiAccent] = s.asciiAccent
        this[K.fadeStart] = s.assistantFadeStart
        this[K.glowReach] = s.assistantGlowReachDp
        this[K.controlConfirm] = s.controlConfirm.name
        this[K.controlMaxSteps] = s.controlMaxSteps
        this[K.controlStepDelayMs] = s.controlStepDelayMs
        this[K.assistantControl] = s.assistantControl
        this[K.controlTapAnywhere] = s.controlTapAnywhere
        this[K.controlLongPressMs] = s.controlLongPressMs
        this[K.controlCompact] = s.controlCompact
        this[K.controlIndicator] = s.controlIndicator.name
        this[K.controlShowSteps] = s.controlShowSteps
        this[K.screenReadMaxChars] = s.screenReadMaxChars
        this[K.assistantReadScreen] = s.assistantReadScreen
        this[K.navShowInstruction] = s.navShowInstruction
        this[K.navExpandOpensApp] = s.navExpandOpensApp
        this[K.navFromApps] = s.navFromApps
        this[K.assistantMemory] = s.assistantMemory
        this[K.memoryInPrompt] = s.memoryInPrompt
        this[K.callsViaTelecom] = s.callsViaTelecom
        this[K.callsUseAudioState] = s.callsUseAudioState
        this[K.callShowDuration] = s.callShowDuration
        this[K.assistantSelfEnd] = s.assistantSelfEnd
        this[K.assistantDoneVisibleMs] = s.assistantDoneVisibleMs
        this[K.assistantEndDelayMs] = s.assistantEndDelayMs
        this[K.assistantEndOnThanks] = s.assistantEndOnThanks
        this[K.assistantScreenshots] = s.assistantScreenshots
        this[K.screenshotMaxEdge] = s.screenshotMaxEdge
        this[K.messagesConfirm] = s.messagesConfirm
        this[K.whatsappAutoSend] = s.whatsappAutoSend
        this[K.whatsappSendWaitMs] = s.whatsappSendWaitMs
        this[K.messageReturnToApp] = s.messageReturnToApp
        this[K.wolRepeats] = s.wolRepeats
        this[K.httpTimeoutSec] = s.httpTimeoutSec
        this[K.habitsEnabled] = s.habitsEnabled
        this[K.habitsSuggest] = s.habitsSuggest
        this[K.habitsFromApps] = s.habitsFromApps
        this[K.habitsFromRoutes] = s.habitsFromRoutes
        this[K.habitWindowMin] = s.habitWindowMin
        this[K.habitMinDays] = s.habitMinDays
        this[K.habitLookbackDays] = s.habitLookbackDays
        this[K.habitSplitWeekends] = s.habitSplitWeekends
        this[K.habitsInPrompt] = s.habitsInPrompt
        this[K.wolDevices] = NetworkActionsJson.devicesToJson(s.wolDevices)
        this[K.httpActions] = NetworkActionsJson.actionsToJson(s.httpActions)
        this[K.mapBuildings3d] = s.mapBuildings3d
        this[K.map3dStrength] = s.map3dStrength
        this[K.mapLine] = s.mapLine
        this[K.mapLineColor] = s.mapLineColor
        this[K.mapHeadingUp] = s.mapHeadingUp
        this[K.mapCompass] = s.mapCompass
        this[K.mapDistanceLabel] = s.mapDistanceLabel
        this[K.mapSpanM] = s.mapSpanM
        this[K.mapStreetWidth] = s.mapStreetWidth
        this[K.mapBlurStartDp] = s.mapBlurStartDp
        this[K.mapBlurMax] = s.mapBlurMax
        this[K.mapBlurCurve] = s.mapBlurCurve
        this[K.mapFadeStartDp] = s.mapFadeStartDp
        this[K.mapPulse] = s.mapPulse
        this[K.mapPulseIntervalS] = s.mapPulseIntervalS
        this[K.mapCloseOnArrival] = s.mapCloseOnArrival
        this[K.mapArrivalRadiusM] = s.mapArrivalRadiusM
        this[K.batteryDevices] = s.batteryDevices
        this[K.headphoneBattery] = s.headphoneBatteryWithMusic
        this[K.animSpeed] = s.animationSpeed
        this[K.bounciness] = s.bounciness
        this[K.haptics] = s.haptics
        this[K.pressSwell] = s.pressSwell
        this[K.swipeReach] = s.swipeReachDp
        this[K.elasticity] = s.elasticity
        this[K.longPressMs] = s.longPressMs
        this[K.pausedTimeout] = s.pausedMusicTimeoutSec
        this[K.music] = s.musicEnabled
        this[K.albumArt] = s.showAlbumArt
        this[K.visualizer] = s.showVisualizer
        this[K.seekBar] = s.showSeekBar
        this[K.blurredArt] = s.blurredArtBackground
        this[K.seekStyle] = s.seekBarStyle.name
        this[K.showPaused] = s.showWhenPaused
        this[K.blockedPlayers] = s.blockedPlayers
        this[K.notifications] = s.notificationsEnabled
        this[K.alertDuration] = s.alertDurationSec
        this[K.filterMode] = s.notificationFilterMode.name
        this[K.notificationApps] = s.notificationApps
        this[K.privacy] = s.privacyMode
        this[K.swipeDeletes] = s.swipeDeletesNotifications
        this[K.replacePopups] = s.replaceSystemPopups
        this[K.timers] = s.timersEnabled
        this[K.liveProgress] = s.liveProgressEnabled
        this[K.liveOverMusic] = s.liveActivitiesOverMusic
        this[K.evCharging] = s.eventCharging
        this[K.evLowBattery] = s.eventLowBattery
        this[K.evRinger] = s.eventRinger
        this[K.evBluetooth] = s.eventBluetooth
        this[K.evDnd] = s.eventDnd
        this[K.evTorch] = s.eventTorch
        this[K.evDuration] = s.eventDurationSec
        this[K.volumeHud] = s.volumeHud
        this[K.idleTap] = s.idleTapAction.name
        this[K.idleLongPress] = s.idleLongPressAction.name
        this[K.tools] = s.quickTools.joinToString(",") { it.name }
        this[K.favorites] = s.favoriteApps
        this[K.asBackend] = s.assistantBackend.name
        this[K.claudeModel] = s.claudeModel
        this[K.openAiBase] = s.openAiBaseUrl
        this[K.openAiModel] = s.openAiModel
        this[K.speechRate] = s.speechRate
        this[K.asVoice] = s.assistantVoice.name
        this[K.phoneEngine] = s.phoneTtsEngine
        this[K.phoneVoice] = s.phoneVoice
        this[K.piperVoice] = s.piperVoice
        this[K.googleVoice] = s.googleVoice
        this[K.elevenVoiceId] = s.elevenVoiceId
        this[K.elevenVoiceName] = s.elevenVoiceName
        this[K.elevenModel] = s.elevenModel.name
        this[K.navigationApp] = s.navigationApp.name
        Gesture.entries.forEach { g -> this[K.gesture(g)] = s.actionFor(g).name }
    }

    private companion object {
        const val SCHEMA_VERSION = 3
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
