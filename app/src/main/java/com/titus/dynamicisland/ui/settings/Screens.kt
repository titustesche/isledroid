package com.titus.dynamicisland.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.titus.dynamicisland.ui.theme.Term
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.titus.dynamicisland.data.settings.AppFilterMode
import com.titus.dynamicisland.data.settings.Gesture
import com.titus.dynamicisland.data.settings.GestureAction
import com.titus.dynamicisland.data.settings.IdleAction
import com.titus.dynamicisland.data.settings.IdleMode
import com.titus.dynamicisland.data.settings.IslandSettings
import com.titus.dynamicisland.data.settings.SeekBarStyle
import com.titus.dynamicisland.domain.PreviewSample
import java.util.Locale
import kotlin.math.roundToInt

// ---------------- Home ----------------

@Composable
fun HomeScreen(s: SettingsHandle, onNavigate: (String) -> Unit, onReset: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(Permissions.status(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = Permissions.status(context) }
    val v = s.value

    SettingsScaffold(
        "Dynamic Island", onBack = null, settings = v, preview = PreviewSample.IDLE,
        subtitle = if (status.ready) "Each page previews what it changes on the island above." else "Grant the permissions below first.",
        trailing = { TermToggle(v.enabled, enabled = status.ready) { on -> s.update { it.copy(enabled = on) } } },
    ) {
        if (!status.ready || !status.batteryUnrestricted) item { PermissionCard(status) }
        header("Island")
        item { NavRow("Appearance", "Size, shape, position, colors", Icons.Rounded.Palette) { onNavigate(Routes.APPEARANCE) } }
        item { NavRow("Behavior & motion", "When it shows, animations, touch feel", Icons.Rounded.Animation) { onNavigate(Routes.BEHAVIOR) } }
        item { NavRow("Gestures", "Tap, long-press and swipe actions", Icons.Rounded.Swipe) { onNavigate(Routes.GESTURES) } }
        header("Features")
        item { NavRow("Music", "Player, artwork, visualizer", Icons.Rounded.MusicNote, Color(0xFF64D2FF)) { onNavigate(Routes.MUSIC) } }
        item {
            NavRow("Notifications", "Pop-ups, apps, privacy, panel", Icons.Rounded.Notifications, Color(0xFFFF6482)) {
                onNavigate(Routes.NOTIFICATIONS)
            }
        }
        item { NavRow("Calls", "Answer and hang up from the island", Icons.Rounded.Call, Color(0xFF30D158)) { onNavigate(Routes.CALLS) } }
        item { NavRow("Navigation", "The map to remembered places", Icons.Rounded.Navigation, Color(0xFF0A84FF)) { onNavigate(Routes.NAVIGATION) } }
        item {
            NavRow("Timers & live updates", "Clock timers, stopwatches, progress", Icons.Rounded.Timer, Color(0xFFFF9F0A)) {
                onNavigate(Routes.LIVE)
            }
        }
        item {
            NavRow("System events", "Charging, sound mode, Bluetooth, volume", Icons.Rounded.BatteryChargingFull, Color(0xFFFFD60A)) {
                onNavigate(Routes.EVENTS)
            }
        }
        item {
            NavRow("Quick tools", "Flashlight, sound mode, favourite apps …", Icons.Rounded.GridView, Color(0xFFC77DFF)) {
                onNavigate(Routes.TOOLS)
            }
        }
        item {
            NavRow("Assistant", "Voice assistant: Claude or OpenAI-compatible", Icons.Rounded.AutoAwesome, Color(0xFF2FD3C9)) {
                onNavigate(Routes.ASSISTANT)
            }
        }
        item {
            TermButton("[ reset all settings ]", Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 30.dp), danger = true, onClick = onReset)
        }
    }
}

@Composable
private fun PermissionCard(status: PermissionStatus) {
    val context = LocalContext.current
    TermCard("Setup") {
        PermissionRow("Accessibility service", "Draws the island over the status bar and camera cutout.", status.accessibility) {
            context.startActivity(Permissions.accessibilitySettings())
        }
        PermissionRow("Notification access", "Reads music sessions, timers and notifications.", status.notificationAccess) {
            context.startActivity(Permissions.notificationAccessSettings())
        }
        PermissionRow("Unrestricted battery (optional)", "Prevents the system from stopping the island.", status.batteryUnrestricted) {
            context.startActivity(Permissions.batterySettings())
        }
        if (!status.accessibility) {
            Text(
                "Installed outside the Play Store? If the accessibility toggle is greyed out, open App info → ⋮ → " +
                    "\"Allow restricted settings\" first.",
                fontSize = 13.sp, lineHeight = 18.sp, color = Term.Dim,
            )
            TermButton("open app info") { context.startActivity(Permissions.appDetails(context)) }
        }
    }
}

@Composable
private fun PermissionRow(title: String, why: String, granted: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(why, fontSize = 13.sp, lineHeight = 18.sp, color = Term.Dim)
        }
        Spacer(Modifier.width(10.dp))
        if (granted) Text("[ok]", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Term.Text)
        else TermButton("grant", filled = true, onClick = onGrant)
    }
}

// ---------------- Island: appearance ----------------

@Composable
fun AppearanceScreen(s: SettingsHandle, onBack: () -> Unit) {
    val v = s.value
    SettingsScaffold("Appearance", onBack, v, preview = PreviewSample.BOTH) {
        header("Size")
        item { IntSliderSetting("Height", v.heightDp, 24..48) { n -> s.update { it.copy(heightDp = n) } } }
        item { IntSliderSetting("Idle width", v.idleWidthDp, 40..240) { n -> s.update { it.copy(idleWidthDp = n) } } }
        item { IntSliderSetting("Preferred width", v.compactWidthDp, 100..360) { n -> s.update { it.copy(compactWidthDp = n) } } }
        item {
            IntSliderSetting("Maximum width", v.compactMaxWidthDp, maxOf(120, v.compactWidthDp)..420) { n ->
                s.update { it.copy(compactMaxWidthDp = n) }
            }
        }
        note("The island grows from the preferred towards the maximum width so text never sits under the camera.")
        item { IntSliderSetting("Expanded width", v.expandedWidthDp, 260..440) { n -> s.update { it.copy(expandedWidthDp = n) } } }
        note("Tap the island to see it expanded.")
        header("Shape")
        item { IntSliderSetting("Roundness", v.cornerRoundness, 0..100, "%") { n -> s.update { it.copy(cornerRoundness = n) } } }
        item { IntSliderSetting("Expanded corner radius", v.expandedCornerDp, 8..60) { n -> s.update { it.copy(expandedCornerDp = n) } } }
        header("Position")
        item {
            SwitchSetting("Center on camera cutout", v.autoFitCutout, { on -> s.update { it.copy(autoFitCutout = on) } },
                summary = "Uses the display cutout reported by the system")
        }
        item { IntSliderSetting("Horizontal offset", v.offsetXDp, -150..150) { n -> s.update { it.copy(offsetXDp = n) } } }
        item { IntSliderSetting("Vertical offset", v.offsetYDp, -20..80) { n -> s.update { it.copy(offsetYDp = n) } } }
        header("Colors")
        item { ColorSetting("Background", v.backgroundColor, { c -> s.update { it.copy(backgroundColor = c) } }) }
        item {
            SwitchSetting("Automatic text color", v.textColorAuto, { on -> s.update { it.copy(textColorAuto = on) } },
                summary = "Black or white, whichever contrasts with the background")
        }
        if (!v.textColorAuto) {
            item { ColorSetting("Text & icons", v.textColor, { c -> s.update { it.copy(textColor = c) } }) }
        }
        item {
            SwitchSetting("Outline", v.outlineEnabled, { on -> s.update { it.copy(outlineEnabled = on) } })
        }
        if (v.outlineEnabled) {
            item { ColorSetting("Outline color", v.outlineColor, { c -> s.update { it.copy(outlineColor = c) } }, allowAlpha = true) }
        }
        note("The colors of music, timers, events and the assistant are on their own pages.")
    }
}

// ---------------- Island: behavior & motion ----------------

@Composable
fun BehaviorScreen(s: SettingsHandle, onBack: () -> Unit, onPickApps: (AppPickerTarget) -> Unit) {
    val v = s.value
    SettingsScaffold("Behavior & motion", onBack, v, preview = PreviewSample.BOTH) {
        header("When it shows")
        item {
            SegmentedSetting("When nothing is active", IdleMode.entries, v.idleMode,
                { if (it == IdleMode.PILL) "Show pill" else "Hide" }) { m -> s.update { it.copy(idleMode = m) } }
        }
        item {
            SwitchSetting("Show two activities", v.showSecondaryBubble, { on -> s.update { it.copy(showSecondaryBubble = on) } },
                summary = "A second activity appears as a bubble next to the island")
        }
        item {
            SwitchSetting(
                "Hide activities of the open app", v.hideForegroundAppActivities,
                { on -> s.update { it.copy(hideForegroundAppActivities = on) } },
                summary = "E.g. no music in the island while the music app itself is open",
            )
        }
        item { SwitchSetting("Hide in fullscreen apps", v.hideInFullscreen, { on -> s.update { it.copy(hideInFullscreen = on) } }) }
        item { SwitchSetting("Hide in landscape", v.hideInLandscape, { on -> s.update { it.copy(hideInLandscape = on) } }) }
        item { NavRow("Hide in these apps", countLabel(v.hiddenApps.size), null) { onPickApps(AppPickerTarget.HIDDEN) } }
        item {
            IntSliderSetting("Auto-collapse after", v.autoCollapseSec, 0..30, "s") { n -> s.update { it.copy(autoCollapseSec = n) } }
        }
        note("0 s = never. The island also collapses when you tap outside it or turn the screen off.")
        header("Animation")
        item {
            SliderSetting("Speed", v.animationSpeed, 0.4f..2f, { f -> s.update { it.copy(animationSpeed = f) } },
                format = { String.format(Locale.ROOT, "%.1f×", it) })
        }
        item {
            SliderSetting("Bounciness", v.bounciness, 0f..1f, { f -> s.update { it.copy(bounciness = f) } },
                format = { "${(it * 100).toInt()}%" })
        }
        header("Touch")
        item { SwitchSetting("Haptic feedback", v.haptics, { on -> s.update { it.copy(haptics = on) } }) }
        item {
            SliderSetting("Swell while holding", v.pressSwell, 0f..0.12f, { f -> s.update { it.copy(pressSwell = f) } },
                format = { if (it < 0.005f) "Off" else "${(it * 100).roundToInt()}%" })
        }
        item { IntSliderSetting("Swipe follow distance", v.swipeReachDp, 0..40) { n -> s.update { it.copy(swipeReachDp = n) } } }
        item {
            SliderSetting("Stretch & squash", v.elasticity, 0f..2f, { f -> s.update { it.copy(elasticity = f) } },
                format = { if (it < 0.05f) "Off" else String.format(Locale.ROOT, "%.1f×", it) })
        }
        item {
            SliderSetting("Long-press duration", v.longPressMs.toFloat(), 200f..1000f,
                { f -> s.update { it.copy(longPressMs = (f / 50f).roundToInt() * 50) } },
                format = { "${it.roundToInt()} ms" }, steps = 15)
        }
        note("Try it on the island: hold it, or drag it sideways and down.")
    }
}

// ---------------- Island: gestures ----------------

private val GESTURE_LABELS = mapOf(
    Gesture.TAP to "Tap",
    Gesture.LONG_PRESS to "Long press",
    Gesture.SWIPE_LEFT to "Swipe left",
    Gesture.SWIPE_RIGHT to "Swipe right",
    Gesture.SWIPE_DOWN to "Swipe down",
    Gesture.SWIPE_UP to "Swipe up",
)

@Composable
fun GesturesScreen(s: SettingsHandle, onBack: () -> Unit) {
    val v = s.value
    SettingsScaffold("Gestures", onBack, v, preview = PreviewSample.MUSIC) {
        header("Idle pill")
        item { ChoiceSetting("Tap", IdleAction.entries, v.idleTapAction, { it.label }) { a -> s.update { it.copy(idleTapAction = a) } } }
        item {
            ChoiceSetting("Long press", IdleAction.entries, v.idleLongPressAction, { it.label }) { a ->
                s.update { it.copy(idleLongPressAction = a) }
            }
        }
        header("Active island")
        Gesture.entries.forEach { g ->
            item(g.name) {
                ChoiceSetting(GESTURE_LABELS.getValue(g), GestureAction.entries, v.actionFor(g), { it.label }) { a ->
                    s.update { it.copy(gestureActions = it.gestureActions + (g to a)) }
                }
            }
        }
        note("Music actions only apply while music is the main activity. Try them on the island above.")
        item {
            TermButton("[ restore defaults ]", Modifier.padding(start = 16.dp, top = 16.dp)) {
                val d = IslandSettings()
                s.update { it.copy(gestureActions = d.gestureActions, idleTapAction = d.idleTapAction, idleLongPressAction = d.idleLongPressAction) }
            }
        }
    }
}

// ---------------- Music ----------------

@Composable
fun MusicScreen(s: SettingsHandle, onBack: () -> Unit, onPickApps: (AppPickerTarget) -> Unit) {
    val v = s.value
    SettingsScaffold("Music", onBack, v, preview = PreviewSample.MUSIC) {
        item { SwitchSetting("Show music", v.musicEnabled, { on -> s.update { it.copy(musicEnabled = on) } }) }
        item { NavRow("Ignored players", countLabel(v.blockedPlayers.size), null) { onPickApps(AppPickerTarget.PLAYERS) } }
        header("Look")
        item { SwitchSetting("Album art", v.showAlbumArt, { on -> s.update { it.copy(showAlbumArt = on) } }) }
        item { SwitchSetting("Visualizer bars", v.showVisualizer, { on -> s.update { it.copy(showVisualizer = on) } }) }
        if (v.showVisualizer) {
            item {
                SwitchSetting("Retro segmented bars", v.retroVisualizer, { on -> s.update { it.copy(retroVisualizer = on) } },
                    summary = "Stacked blocks, like an 80s spectrum analyzer")
            }
            if (v.retroVisualizer) {
                item { IntSliderSetting("Blocks per column", v.retroSegments, 3..10, "") { n -> s.update { it.copy(retroSegments = n) } } }
                item {
                    SwitchSetting("Bright peak", v.retroPeak, { on -> s.update { it.copy(retroPeak = on) } },
                        summary = "The top lit block of each column lights up brighter")
                }
            }
        }
        item {
            SwitchSetting("Color from album art", v.accentFromArtwork, { on -> s.update { it.copy(accentFromArtwork = on) } })
        }
        item {
            ColorSetting("Music color", v.accentColor, { c -> s.update { it.copy(accentColor = c) } },
                summary = if (v.accentFromArtwork) "Used when a track has no artwork" else null)
        }
        header("Expanded player")
        item { SwitchSetting("Seek bar", v.showSeekBar, { on -> s.update { it.copy(showSeekBar = on) } }) }
        if (v.showSeekBar) {
            item {
                SegmentedSetting("Seek bar style", SeekBarStyle.entries, v.seekBarStyle,
                    {
                        when (it) {
                            SeekBarStyle.ONE_UI -> "One UI"
                            SeekBarStyle.WAVY -> "Android"
                            SeekBarStyle.FLAT -> "Flat"
                        }
                    }) { st -> s.update { it.copy(seekBarStyle = st) } }
            }
        }
        item {
            SwitchSetting("Blurred artwork background", v.blurredArtBackground, { on -> s.update { it.copy(blurredArtBackground = on) } })
        }
        if (v.blurredArtBackground) {
            item {
                SliderSetting("Artwork fades out at", v.artFadeEnd, 0.2f..1f, { f -> s.update { it.copy(artFadeEnd = f) } },
                    format = { "${(it * 100).toInt()}% of the width" })
            }
        }
        item {
            SwitchSetting(
                "Headphone battery", v.headphoneBatteryWithMusic,
                { on -> s.update { it.copy(headphoneBatteryWithMusic = on) } },
                summary = "How full your connected headphones are",
            )
        }
        note("Tap the island to see the expanded player.")
        header("Paused music")
        item { SwitchSetting("Keep paused music forever", v.showWhenPaused, { on -> s.update { it.copy(showWhenPaused = on) } }) }
        if (!v.showWhenPaused) {
            item {
                IntSliderSetting("Hide paused music after", v.pausedMusicTimeoutSec, 5..300, "s") { n ->
                    s.update { it.copy(pausedMusicTimeoutSec = n) }
                }
            }
            note("While the player is expanded it never disappears, so you can pause and resume from the island.")
        }
    }
}

// ---------------- Notifications ----------------

@Composable
fun NotificationsScreen(s: SettingsHandle, onBack: () -> Unit, onPickApps: (AppPickerTarget) -> Unit) {
    val v = s.value
    SettingsScaffold("Notifications", onBack, v, preview = PreviewSample.ALERT) {
        item { SwitchSetting("Notification pop-ups", v.notificationsEnabled, { on -> s.update { it.copy(notificationsEnabled = on) } }) }
        item { IntSliderSetting("Pop-up duration", v.alertDurationSec, 2..10, "s") { n -> s.update { it.copy(alertDurationSec = n) } } }
        item { PopupReplacementSetting(v.replaceSystemPopups) { on -> s.update { it.copy(replaceSystemPopups = on) } } }
        item {
            SwitchSetting("Privacy mode", v.privacyMode, { on -> s.update { it.copy(privacyMode = on) } },
                summary = "Show only the app name, not the message")
        }
        item {
            SwitchSetting("Swipe to delete", v.swipeDeletesNotifications, { on -> s.update { it.copy(swipeDeletesNotifications = on) } },
                summary = "Swiping a notification off the island also removes it from the shade")
        }
        item {
            ColorSetting("Button color", v.notificationColor, { c -> s.update { it.copy(notificationColor = c) } },
                summary = "Reply, send and the notification panel")
        }
        header("Apps")
        item {
            SegmentedSetting("Show pop-ups from", AppFilterMode.entries, v.notificationFilterMode,
                { if (it == AppFilterMode.ALL_EXCEPT_SELECTED) "All except selected" else "Only selected" }) { m ->
                s.update { it.copy(notificationFilterMode = m) }
            }
        }
        item { NavRow("Select apps", countLabel(v.notificationApps.size), null) { onPickApps(AppPickerTarget.NOTIFICATIONS) } }
        note("Silent and low-priority notifications are never shown.")
        header("Notification panel")
        item {
            Hint(
                "Lists everything in the shade inside the island. Open it with a gesture: under Gestures, pick " +
                    "\"Notification panel\" for the idle pill or the active island.",
            )
        }
    }
}

// ---------------- Calls ----------------

@Composable
fun CallsScreen(s: SettingsHandle, onBack: () -> Unit) {
    val v = s.value
    SettingsScaffold("Calls", onBack, v, preview = PreviewSample.CALL) {
        item {
            SwitchSetting("Open calls in the island", v.openCalls, { on -> s.update { it.copy(openCalls = on) } },
                summary = "Ringing and running calls show the caller with answer, decline and hang-up buttons")
        }
        if (v.openCalls) {
            item {
                SwitchSetting("Show how long it's running", v.callShowDuration, { on -> s.update { it.copy(callShowDuration = on) } })
            }
        }
        item {
            SwitchSetting("Answer through Android", v.callsViaTelecom, { on -> s.update { it.copy(callsViaTelecom = on) } },
                summary = "The buttons answer, decline and hang up directly instead of using the phone app's own buttons, " +
                    "which on some phones (Samsung) only open the call screen. Phone calls only; WhatsApp & co. keep theirs")
        }
        if (v.callsViaTelecom) item { CallPermissionRow() }
        item {
            SwitchSetting("Ringing from the phone's audio", v.callsUseAudioState, { on -> s.update { it.copy(callsUseAudioState = on) } },
                summary = "Tells ringing from talking by what the phone's audio is doing, not by guessing from the notification")
        }
        item {
            Hint(
                "Swipe a call up to shrink it to the pill; it opens again when it's answered. Buttons the island " +
                    "doesn't recognise keep their text. Off: calls show in the pill like a timer.",
            )
        }
    }
}

// ---------------- Timers & live updates ----------------

@Composable
fun LiveActivitiesScreen(s: SettingsHandle, onBack: () -> Unit) {
    val v = s.value
    SettingsScaffold("Timers & live updates", onBack, v, preview = PreviewSample.TIMER) {
        item {
            SwitchColorSetting(
                "Timers & stopwatches", v.timersEnabled, { on -> s.update { it.copy(timersEnabled = on) } },
                v.timerColor, { c -> s.update { it.copy(timerColor = c) } },
                summary = "From the Clock app and any notification with a running clock",
            )
        }
        item {
            SwitchColorSetting(
                "Progress & live updates", v.liveProgressEnabled, { on -> s.update { it.copy(liveProgressEnabled = on) } },
                v.progressColor, { c -> s.update { it.copy(progressColor = c) } },
                summary = "Downloads, deliveries, rides, Android 16 Live Updates",
            )
        }
        item {
            SwitchSetting("Prefer over music", v.liveActivitiesOverMusic, { on -> s.update { it.copy(liveActivitiesOverMusic = on) } },
                summary = "When both are active, the timer takes the main spot")
        }
        note("Tap a color dot to change it.")
    }
}

// ---------------- Navigation: the island's map ----------------

@Composable
fun NavigationScreen(s: SettingsHandle, onBack: () -> Unit) {
    val v = s.value
    SettingsScaffold("Navigation", onBack, v, preview = PreviewSample.MAP) {
        note("Ask the assistant \"where did I park?\" and the map opens in the island, pointing the way until you close it.")
        note("Tap it for a walking route in your maps app, swipe it up to shrink it to the pill, long-press to close it.")
        item { NavigationAppSetting(s) }
        header("From maps apps")
        item {
            SwitchSetting("Routes from maps apps", v.navFromApps, { on -> s.update { it.copy(navFromApps = on) } },
                summary = "While Google Maps, Waze & co. guide you, the island shows the next turn and how far it is")
        }
        if (v.navFromApps) {
            item {
                SwitchSetting("Expanding opens the app", v.navExpandOpensApp, { on -> s.update { it.copy(navExpandOpensApp = on) } },
                    summary = "Otherwise the island opens a card with the turn; tapping that opens the app")
            }
            item {
                SwitchSetting("Instruction in the card", v.navShowInstruction, { on -> s.update { it.copy(navShowInstruction = on) } },
                    summary = "\"Turn right onto …\" under the distance")
            }
            note("The route stays with the maps app: Android doesn't tell other apps where it leads, so the island's own map is for remembered places.")
        }
        header("Island map")
        item { IntSliderSetting("Shows across", v.mapSpanM, 100..1000, "m") { n -> s.update { it.copy(mapSpanM = n) } } }
        item {
            SegmentedSetting("Orientation", listOf(true, false), v.mapHeadingUp, { if (it) "Heading up" else "North up" }) { up ->
                s.update { it.copy(mapHeadingUp = up) }
            }
        }
        item {
            SliderSetting("Street width", v.mapStreetWidth, 0.4f..2f, { f -> s.update { it.copy(mapStreetWidth = f) } },
                format = { String.format(Locale.ROOT, "%.1f×", it) })
        }
        item {
            SwitchSetting("Buildings in 3D", v.mapBuildings3d, { on -> s.update { it.copy(mapBuildings3d = on) } },
                summary = "Houses stand up from the streets")
        }
        if (v.mapBuildings3d) {
            item {
                SliderSetting("Height", v.map3dStrength, 0.2f..3f, { f -> s.update { it.copy(map3dStrength = f) } },
                    format = { String.format(Locale.ROOT, "%.1f×", it) })
            }
        }
        item {
            SwitchColorSetting(
                "Line to the target", v.mapLine, { on -> s.update { it.copy(mapLine = on) } },
                v.mapLineColor, { c -> s.update { it.copy(mapLineColor = c) } },
                summary = "Straight, the only color on the map",
            )
        }
        item { SwitchSetting("Compass", v.mapCompass, { on -> s.update { it.copy(mapCompass = on) } }, summary = "A heading strip at the top") }
        item { SwitchSetting("Distance", v.mapDistanceLabel, { on -> s.update { it.copy(mapDistanceLabel = on) } }, summary = "What it is and how far, at the bottom") }
        header("Edges")
        item { IntSliderSetting("Blur starts", v.mapBlurStartDp, 10..100) { n -> s.update { it.copy(mapBlurStartDp = n) } } }
        item {
            SliderSetting("Blur at the edge", v.mapBlurMax, 0f..24f, { f -> s.update { it.copy(mapBlurMax = f) } },
                format = { if (it < 0.25f) "Off" else String.format(Locale.ROOT, "%.1f dp", it) })
        }
        item {
            SliderSetting("Blur curve", v.mapBlurCurve, 1f..8f, { f -> s.update { it.copy(mapBlurCurve = f) } },
                format = { String.format(Locale.ROOT, "%.1f", it) })
        }
        note("A higher curve keeps the map sharp longer and blurs it hard right at the rim.")
        item { IntSliderSetting("Fade starts", v.mapFadeStartDp, 10..120) { n -> s.update { it.copy(mapFadeStartDp = n) } } }
        header("You")
        item { SwitchSetting("Pulse", v.mapPulse, { on -> s.update { it.copy(mapPulse = on) } }, summary = "Rings pulse out from your position") }
        if (v.mapPulse) {
            item {
                SliderSetting("Pulse every", v.mapPulseIntervalS, 0.2f..3f, { f -> s.update { it.copy(mapPulseIntervalS = f) } },
                    format = { String.format(Locale.ROOT, "%.1f s", it) })
            }
        }
        item {
            SwitchSetting("Close on arrival", v.mapCloseOnArrival, { on -> s.update { it.copy(mapCloseOnArrival = on) } },
                summary = "The map goes away once you're there")
        }
        item {
            SwitchSetting("Forget the note on arrival", v.mapForgetOnArrival, { on -> s.update { it.copy(mapForgetOnArrival = on) } },
                summary = "Got to the parked car: the note that led you there is deleted")
        }
        if (v.mapCloseOnArrival || v.mapForgetOnArrival) {
            item { IntSliderSetting("Arrived within", v.mapArrivalRadiusM, 5..60, "m") { n -> s.update { it.copy(mapArrivalRadiusM = n) } } }
        }
        note("Streets and buildings only, from OpenStreetMap (© OpenStreetMap contributors, via OpenFreeMap). Tiles are kept on the phone for a month.")
    }
}
