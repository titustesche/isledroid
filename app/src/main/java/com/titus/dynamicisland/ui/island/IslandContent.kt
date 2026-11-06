package com.titus.dynamicisland.ui.island

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import com.titus.dynamicisland.data.media.MediaCustomAction
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import com.titus.dynamicisland.data.media.RepeatMode
import com.titus.dynamicisland.data.settings.SeekBarStyle
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.titus.dynamicisland.data.notifications.NotificationActionItem
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.foundation.shape.CircleShape
import com.titus.dynamicisland.data.notifications.CallRole
import com.titus.dynamicisland.data.system.BluetoothDeviceInfo
import com.titus.dynamicisland.data.system.BluetoothKind
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titus.dynamicisland.data.notifications.NotificationEntry
import com.titus.dynamicisland.data.settings.ActionStyle
import com.titus.dynamicisland.data.settings.IslandSettings
import com.titus.dynamicisland.domain.IslandActions
import com.titus.dynamicisland.domain.IslandActivity
import com.titus.dynamicisland.domain.IslandMode
import com.titus.dynamicisland.domain.IslandUiState


/** The retro visualizer's look, or null for the smooth bars. */
private fun IslandSettings.retroStyle() = if (retroVisualizer) RetroBars(retroSegments, retroPeak) else null

/** Layout constraints the content needs to respect. */
class ContentLayout(
    val cutout: CutoutGeometry? = null,
    /** Top padding for expanded layouts so text starts below the camera. */
    val topInset: Dp = ExpandedTopPadding,
    val maxCompactWidth: Dp = 320.dp,
    val onCompactWidthNeeded: (Dp) -> Unit = {},
)

@Composable
fun IslandContent(state: IslandUiState, settings: IslandSettings, actions: IslandActions, layout: ContentLayout = ContentLayout()) {
    val primary = state.primary ?: return
    val h = settings.heightDp.dp
    when (state.mode) {
        IslandMode.HIDDEN -> Unit
        IslandMode.IDLE -> if (state.idleBatteries.isNotEmpty()) {
            CompactRow(settings, layout, { IdleBatteryLeft(state.idleBatteries) }, { IdleBatteryRight(state.idleBatteries) })
        }
        IslandMode.ALERT -> (primary as? IslandActivity.Alert)?.let { a ->
            CompactRow(settings, layout, { AlertPreviewLeft(a.entry, settings, h) }, { AlertPreviewRight(a.entry, settings) })
        }
        IslandMode.COMPACT -> when (primary) {
            is IslandActivity.Media -> CompactRow(settings, layout, { MediaCompactLeft(primary, settings, h) }, { MediaCompactRight(primary, settings, h) })
            is IslandActivity.Timer -> CompactRow(settings, layout, { TimerGlyph(primary, settings, h - 12.dp) }, { TimerCompactTime(primary, settings) })
            is IslandActivity.Progress -> CompactRow(settings, layout, { AppIcon(primary.entry.appIcon, h - 12.dp) }, { ProgressGlyph(primary, settings, h - 14.dp) })
            IslandActivity.Torch -> CompactRow(settings, layout, { TorchLeft(h) }, { TorchRight() })
            is IslandActivity.Map -> CompactRow(settings, layout, { MapCompactLeft(primary, settings, h - 12.dp) }, { MapCompactRight(primary) })
            is IslandActivity.Navigation -> CompactRow(settings, layout, { NavManeuver(primary, h - 10.dp) }, { NavCompactRight(primary) })
            is IslandActivity.Assistant -> CompactRow(
                settings, layout,
                { WorkingIndicator(settings.controlIndicator, LocalIslandPalette.current.assistant, h - 14.dp) },
                { if (settings.controlShowSteps && primary.ui.steps > 0) WorkingSteps(primary.ui.steps, LocalIslandPalette.current.assistant) },
            )
            is IslandActivity.Alert, is IslandActivity.Event, IslandActivity.Tools,
            is IslandActivity.Notifications -> Unit
        }
        IslandMode.ASSISTANT -> (primary as? IslandActivity.Assistant)?.let {
            AssistantContent(
                it.ui, layout.topInset, LocalIslandPalette.current.assistant,
                waves = !settings.assistantGlowSpill,
                ascii = if (!settings.assistantAscii) null else AsciiStyle(
                    opacity = settings.asciiOpacity,
                    size = settings.asciiSizeSp.sp,
                    grain = settings.asciiGrainDp.dp,
                    speed = settings.asciiSpeed,
                    accent = settings.asciiAccent,
                    fadeStart = settings.assistantFadeStart.takeIf { settings.assistantSoftEdge },
                ),
                detailed = settings.assistantActionStyle == ActionStyle.DETAILED,
            )
        }
        IslandMode.EVENT -> (primary as? IslandActivity.Event)?.let { e ->
            CompactRow(settings, layout, { EventLeft(e.event, h) }, { EventRight(e.event, actions) })
        }
        IslandMode.EXPANDED -> when (primary) {
            is IslandActivity.Media -> MediaExpanded(
                primary, settings, actions, layout.topInset,
                headphones = state.system.bluetooth.lastOrNull { it.kind == BluetoothKind.HEADPHONES && it.battery != null }
                    ?.takeIf { settings.headphoneBatteryWithMusic },
            )
            is IslandActivity.Timer ->
                if (primary.entry.isCall) CallExpanded(primary, settings, actions, layout.topInset)
                else TimerExpanded(primary, settings, actions, layout.topInset)
            is IslandActivity.Progress -> ProgressExpanded(primary, settings, actions, layout.topInset)
            is IslandActivity.Alert -> AlertExpanded(primary.entry, settings, actions, layout.topInset, state.replying)
            IslandActivity.Torch -> TorchExpanded(state.system, actions, layout.topInset)
            IslandActivity.Tools -> ToolsPanel(settings, state.system, actions, layout.topInset)
            is IslandActivity.Notifications -> NotificationPanel(primary, actions, layout.topInset)
            is IslandActivity.Map -> MapContent(primary, settings, layout.topInset)
            is IslandActivity.Navigation -> NavExpanded(primary, settings, layout.topInset)
            is IslandActivity.Event, is IslandActivity.Assistant -> Unit
        }
    }
}

/**
 * Compact layout: one slot left and one right of the camera. Slots are measured at their natural
 * width (capped so they can never reach the camera at the maximum island width), and the width the
 * pill needs is reported back so the island can grow towards [ContentLayout.maxCompactWidth].
 */
@Composable
private fun CompactRow(
    settings: IslandSettings,
    layout: ContentLayout,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    val pad = compactPadding(settings)
    val clearHalf = layout.cutout?.clearHalfWidth ?: 4.dp
    val report by rememberUpdatedState(layout.onCompactWidthNeeded)
    Layout(contents = listOf(left, right), modifier = Modifier.fillMaxSize()) { (l, r), constraints ->
        val padPx = pad.roundToPx()
        val clearPx = clearHalf.roundToPx()
        val slotMax = (layout.maxCompactWidth.roundToPx() / 2 - clearPx - padPx).coerceAtLeast(0)
        val slot = Constraints(maxWidth = slotMax, maxHeight = constraints.maxHeight)
        val lp = l.firstOrNull()?.measure(slot)
        val rp = r.firstOrNull()?.measure(slot)
        val widest = maxOf(lp?.width ?: 0, rp?.width ?: 0)
        report((2 * (clearPx + padPx + widest)).toDp())
        layout(constraints.maxWidth, constraints.maxHeight) {
            lp?.place(padPx, (constraints.maxHeight - lp.height) / 2)
            rp?.place(constraints.maxWidth - padPx - rp.width, (constraints.maxHeight - rp.height) / 2)
        }
    }
}

// ---------------- Media ----------------

@Composable
private fun MediaCompactLeft(media: IslandActivity.Media, s: IslandSettings, h: Dp) {
    val glyph = h - 12.dp
    val accent = animatedAccent(media, s)
    if (s.showAlbumArt) Artwork(media.info.art, glyph, glyph * 0.3f, accent)
    else Icon(Icons.Rounded.MusicNote, null, Modifier.size(glyph), tint = accent)
}

@Composable
private fun MediaCompactRight(media: IslandActivity.Media, s: IslandSettings, h: Dp) {
    val accent = animatedAccent(media, s)
    if (s.showVisualizer) {
        Visualizer(accent, media.info.isPlaying, Modifier.height((h - 12.dp) * 0.8f), retro = s.retroStyle())
    } else {
        Icon(if (media.info.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = accent)
    }
}

/** Fixed sizes of the expanded player; [mediaExpandedHeight] is derived from them. */
private object Player {
    val Cover = 72.dp
    val SeekHeight = 18.dp
    val ControlsHeight = 48.dp
    val Gap = 10.dp
    val Bottom = 12.dp
}

/** Height of the expanded music player for the given settings (without the camera inset). */
fun mediaExpandedHeight(s: IslandSettings): Dp =
    Player.Cover + Player.Gap + (if (s.showSeekBar) Player.SeekHeight + 4.dp else 0.dp) + Player.ControlsHeight + Player.Bottom

@Composable
private fun MediaExpanded(
    media: IslandActivity.Media,
    s: IslandSettings,
    actions: IslandActions,
    topInset: Dp,
    headphones: BluetoothDeviceInfo? = null,
) {
    val info = media.info
    val accent = animatedAccent(media, s)
    Box(Modifier.fillMaxSize()) {
        if (s.blurredArtBackground && info.art != null) {
            // The pill overshoots past this (final-size) box while bouncing open; the artwork has to
            // reach that far, or a strip of plain background shows beyond it. At most the overshoot of
            // the whole expanded size, which the shorter trip from compact never exceeds.
            val bleed = (s.expandedWidthDp.dp * overshootFraction(s)).coerceAtMost(72.dp) + 2.dp
            BlurredArtBackground(info.art, s.artFadeEnd, bleed)
        }
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = topInset, bottom = Player.Bottom)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.showAlbumArt) {
                    Artwork(info.art, Player.Cover, 14.dp, accent)
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(info.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    info.artist?.let {
                        Text(it, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = LocalContentColor.current.copy(alpha = 0.7f))
                    }
                }
                headphones?.let {
                    Spacer(Modifier.width(8.dp))
                    DeviceBattery(it, fontSize = 12.sp)
                }
                if (s.showVisualizer) {
                    Spacer(Modifier.width(8.dp))
                    Visualizer(accent, info.isPlaying, Modifier.height(20.dp), retro = s.retroStyle())
                }
            }
            Spacer(Modifier.height(Player.Gap))
            if (s.showSeekBar) {
                SeekBar(media, accent.withMinValue(0.75f), actions, s.seekBarStyle)
                Spacer(Modifier.height(4.dp))
            }
            PlayerControls(media, accent, actions)
        }
    }
}

/**
 * Transport row like Android's media controls: the app's own custom actions (shuffle, repeat,
 * favorite …) flank previous / play-pause / next.
 */
@Composable
private fun PlayerControls(media: IslandActivity.Media, accentRaw: Color, actions: IslandActions) {
    val info = media.info
    // Toggles sit on the darkest part of the blurred artwork: keep them bright enough to read.
    val accent = accentRaw.withMinValue(0.8f)
    val playColor = accentRaw.withMinValue(0.45f)
    // Up to two extra buttons per side: shuffle/repeat first, then the app's own actions.
    val extraLeft = buildList<@Composable () -> Unit> {
        info.shuffle?.let { on -> add { ToggleButton(Icons.Rounded.Shuffle, "Shuffle", on, accent, actions::toggleShuffle) } }
    }
    val extraRight = buildList<@Composable () -> Unit> {
        info.repeat?.let { mode ->
            add {
                ToggleButton(
                    if (mode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Repeat", mode != RepeatMode.OFF, accent, actions::cycleRepeat,
                )
            }
        }
    }
    val custom = info.customActions.take(4 - extraLeft.size - extraRight.size)
    val left = extraLeft + custom.take(2 - extraLeft.size.coerceAtMost(2)).map { a -> @Composable { CustomActionButton(a, actions) } }
    val right = custom.drop(left.size - extraLeft.size).map { a -> @Composable { CustomActionButton(a, actions) } } + extraRight
    Row(
        Modifier.fillMaxWidth().height(Player.ControlsHeight),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        left.forEach { it() }
        IconButton(onClick = actions::previous) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(30.dp)) }
        Box(
            Modifier
                .size(width = 64.dp, height = 44.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(playColor)
                .clickable(onClick = actions::playPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (info.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (info.isPlaying) "Pause" else "Play",
                Modifier.size(30.dp),
                tint = if (playColor.luminance() > 0.5f) Color.Black else Color.White,
            )
        }
        IconButton(onClick = actions::next) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(30.dp)) }
        right.forEach { it() }
    }
}

@Composable
private fun ToggleButton(icon: ImageVector, label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, label, Modifier.size(22.dp), tint = if (on) accent else LocalContentColor.current.copy(alpha = 0.55f))
    }
}

@Composable
private fun CustomActionButton(action: MediaCustomAction, actions: IslandActions) {
    IconButton(onClick = { actions.customAction(action) }, modifier = Modifier.size(40.dp)) {
        val icon = action.icon
        if (icon != null) Icon(icon, action.name, Modifier.size(22.dp))
        else Text(action.name.take(2), fontSize = 11.sp)
    }
}

/** Slim seek bar: a thin track you can tap or drag, with times on either side. */
@Composable
private fun SeekBar(media: IslandActivity.Media, accent: Color, actions: IslandActions, style: SeekBarStyle) {
    val info = media.info
    if (info.durationMs <= 0) return
    val now = rememberElapsedClock(info.isPlaying)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: (info.positionAt(now).toFloat() / info.durationMs).coerceIn(0f, 1f)
    val dim = LocalContentColor.current.copy(alpha = 0.7f)
    val seek by rememberUpdatedState { f: Float -> actions.seekTo((f * info.durationMs).toLong()) }
    Row(Modifier.height(Player.SeekHeight), verticalAlignment = Alignment.CenterVertically) {
        Text(formatDuration((fraction * info.durationMs).toLong()), fontSize = 11.sp, color = dim, fontFamily = FontFamily.Monospace)
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 10.dp)
                .pointerInput(Unit) {
                    detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { dragging?.let(seek); dragging = null },
                        onDragCancel = { dragging = null },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragging = (change.position.x / size.width).coerceIn(0f, 1f)
                            actions.onInteraction()
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            when (style) {
                SeekBarStyle.ONE_UI -> OneUiTrack(fraction, accent, dim.copy(alpha = 0.3f), waving = info.isPlaying && dragging == null)
                SeekBarStyle.WAVY -> WavyTrack(fraction, accent, dim.copy(alpha = 0.3f), waving = info.isPlaying && dragging == null)
                SeekBarStyle.FLAT -> {
                    val track = if (dragging != null) 6.dp else 4.dp
                    Box(Modifier.fillMaxWidth().height(track).clip(RoundedCornerShape(track)).background(dim.copy(alpha = 0.25f)))
                    Box(Modifier.fillMaxWidth(fraction).height(track).clip(RoundedCornerShape(track)).background(accent))
                }
            }
        }
        Text(formatDuration(info.durationMs), fontSize = 11.sp, color = dim, fontFamily = FontFamily.Monospace)
    }
}

/**
 * Android's media-player progress line: the played part is a travelling sine wave, the rest a flat
 * line. The wave flattens out when paused or while scrubbing.
 */
@Composable
private fun WavyTrack(fraction: Float, color: Color, rest: Color, waving: Boolean) {
    val amplitude by animateFloatAsState(if (waving) 1f else 0f, tween(400), label = "amp")
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "phase",
    )
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val mid = size.height / 2
        val stroke = 3.dp.toPx()
        val split = size.width * fraction
        val wavelength = 22.dp.toPx()
        val amp = 2.5f.dp.toPx() * amplitude
        val path = Path().apply {
            moveTo(0f, mid)
            var x = 0f
            val step = 2f
            while (x <= split) {
                val y = mid + amp * sin(2f * PI.toFloat() * (x / wavelength - phase))
                lineTo(x, y)
                x += step
            }
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
        if (split + stroke < size.width) {
            drawLine(rest, Offset(split + stroke * 2, mid), Offset(size.width, mid), stroke, StrokeCap.Round)
        }
        // Thumb: a short vertical pill, as in Android 14+.
        drawLine(color, Offset(split, mid - 5.dp.toPx()), Offset(split, mid + 5.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
    }
}

/**
 * Samsung One UI style: a thick rounded track with a round thumb, and two soft translucent waves
 * rising above the played part that drift slowly and settle flat when paused.
 */
@Composable
private fun OneUiTrack(fraction: Float, color: Color, rest: Color, waving: Boolean) {
    val amplitude by animateFloatAsState(if (waving) 1f else 0f, tween(600), label = "amp")
    val transition = rememberInfiniteTransition(label = "oneui")
    val phaseA by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "a")
    val phaseB by transition.animateFloat(1f, 0f, infiniteRepeatable(tween(7300, easing = LinearEasing)), label = "b")
    Canvas(Modifier.fillMaxWidth().height(Player.SeekHeight)) {
        val track = 5.dp.toPx()
        val mid = size.height - 7.dp.toPx()
        val split = size.width * fraction
        val maxAmp = 7.dp.toPx() * amplitude

        fun wave(phase: Float, wavelength: Float, alpha: Float) {
            if (split <= 0f || maxAmp <= 0.5f) return
            val path = Path().apply {
                moveTo(0f, mid)
                var x = 0f
                while (x <= split) {
                    // Taper in from the start and towards the thumb so the hills grow out of the track.
                    val envelope = smooth(x / (32.dp.toPx())) * smooth((split - x) / (14.dp.toPx()))
                    val s = 0.5f + 0.5f * sin(2f * PI.toFloat() * (x / wavelength - phase))
                    lineTo(x, mid - maxAmp * envelope * s)
                    x += 2f
                }
                lineTo(split, mid)
                close()
            }
            drawPath(path, color.copy(alpha = alpha))
        }
        drawLine(rest, Offset(track / 2, mid), Offset(size.width - track / 2, mid), track, StrokeCap.Round)
        wave(phaseA, 110.dp.toPx(), 0.35f)
        wave(phaseB, 76.dp.toPx(), 0.25f)
        drawLine(color, Offset(track / 2, mid), Offset(split.coerceAtLeast(track / 2), mid), track, StrokeCap.Round)
        drawCircle(color, 7.dp.toPx(), Offset(split.coerceIn(7.dp.toPx(), size.width - 7.dp.toPx()), mid))
    }
}

private fun smooth(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3 - 2 * c)
}

// ---------------- Timers ----------------

/**
 * Fallback timer text when the notification has no hostable chronometer view: the standard
 * chronometer extras, else whatever time-like text the notification carries.
 */
@Composable
private fun timerText(entry: NotificationEntry): String {
    val n = entry.snapshot
    if (!n.showChronometer) {
        return listOf(n.text, n.subText).firstOrNull(::looksLikeTime)
            ?: n.text?.takeIf { it.length <= 12 } ?: n.title.orEmpty()
    }
    val now = rememberWallClock()
    return if (n.chronometerCountDown) {
        // Round up so a timer shows 0:01 until it really expires, like the system chronometer.
        val remaining = n.whenTime - now
        formatDuration(if (remaining > 0) remaining + 999 else remaining)
    } else formatDuration(now - n.whenTime)
}

/** The timer's live time, preferring the source app's own chronometer view. */
@Composable
private fun TimerTime(entry: NotificationEntry, color: Color, fontSize: TextUnit, weight: FontWeight) {
    // A ringing call has no time yet; whatever its notification shows there (Samsung: time since boot) is wrong.
    if (entry.isRinging) {
        Text(entry.snapshot.text ?: entry.appLabel, color = color, fontSize = fontSize, fontWeight = weight,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val views = if (entry.snapshot.showChronometer) emptyList() else entry.chronometerViews
    RemoteTimeOr(views, color, fontSize, weight) {
        Text(timerText(entry), color = color, fontSize = fontSize, fontWeight = weight,
            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TimerGlyph(timer: IslandActivity.Timer, s: IslandSettings, size: Dp) {
    val icon = timer.entry.appIcon
    if (icon != null && timer.entry.snapshot.category == "call") AppIcon(icon, size)
    else Icon(Icons.Rounded.Timer, null, Modifier.size(size), tint = accentFor(timer, s))
}

@Composable
private fun TimerCompactTime(timer: IslandActivity.Timer, s: IslandSettings) =
    TimerTime(timer.entry, accentFor(timer, s), 15.sp, FontWeight.SemiBold)

@Composable
private fun TimerExpanded(timer: IslandActivity.Timer, s: IslandSettings, actions: IslandActions, topInset: Dp) {
    val e = timer.entry
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = topInset, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(e.appIcon, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(e.snapshot.title ?: e.appLabel, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.snapshot.subText ?: e.appLabel, fontSize = 12.sp, color = LocalContentColor.current.copy(alpha = 0.6f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            TimerTime(e, accentFor(timer, s), 28.sp, FontWeight.Medium)
        }
        Spacer(Modifier.weight(1f))
        ActionRow(e, actions, accentFor(timer, s))
    }
}

// ---------------- Calls ----------------

private val CallGreen = Color(0xFF34C759)
private val CallRed = Color(0xFFFF3B30)

/** Declining on the left, answering on the right; while talking, hanging up last. */
private fun callOrder(a: NotificationActionItem) = when (a.callRole) {
    CallRole.DECLINE -> 0
    CallRole.MUTE -> 1
    CallRole.SPEAKER -> 2
    null -> 3
    CallRole.HANG_UP -> 4
    CallRole.ANSWER -> 5
}

/** A ringing or running call: who it is, how long it's been going, and round buttons with symbols. */
@Composable
private fun CallExpanded(call: IslandActivity.Timer, s: IslandSettings, actions: IslandActions, topInset: Dp) {
    val e = call.entry
    val ringing = e.isRinging
    val dim = LocalContentColor.current.copy(alpha = 0.6f)
    // One row: who's calling, then the buttons pinned to the right edge, within thumb reach.
    Row(
        Modifier.fillMaxSize().padding(start = 18.dp, end = 16.dp, top = topInset, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(e, s, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(senderOf(e, s), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ringing || !s.callShowDuration) {
                Text(e.snapshot.text ?: e.appLabel, fontSize = 12.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else TimerTime(e, accentFor(call, s), 13.sp, FontWeight.Medium)
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            e.actions.sortedBy(::callOrder).forEach { a -> CallButton(a, actions) }
        }
    }
}

@Composable
private fun CallButton(a: NotificationActionItem, actions: IslandActions) {
    val neutral = LocalContentColor.current
    val (icon, background) = when (a.callRole) {
        CallRole.ANSWER -> Icons.Rounded.Call to CallGreen
        CallRole.DECLINE, CallRole.HANG_UP -> Icons.Rounded.CallEnd to CallRed
        CallRole.SPEAKER -> Icons.AutoMirrored.Rounded.VolumeUp to neutral.copy(alpha = 0.16f)
        CallRole.MUTE -> Icons.Rounded.MicOff to neutral.copy(alpha = 0.16f)
        null -> null to Color.Transparent
    }
    if (icon == null) {
        // Something we don't recognise: keep its label rather than guess a symbol.
        TextButton(onClick = { actions.sendAction(a) }) { Text(a.title, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        return
    }
    val coloured = a.callRole == CallRole.ANSWER || a.callRole == CallRole.DECLINE || a.callRole == CallRole.HANG_UP
    Box(
        Modifier.size(CallButtonSize).clip(CircleShape).background(background).clickable { actions.sendAction(a) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = a.title, modifier = Modifier.size(22.dp), tint = if (coloured) Color.White else neutral)
    }
}

// ---------------- Progress / live updates ----------------

@Composable
fun ProgressGlyph(p: IslandActivity.Progress, s: IslandSettings, size: Dp) {
    val n = p.entry.snapshot
    val accent = accentFor(p, s)
    Box(contentAlignment = Alignment.Center) {
        if (n.progressMax > 0 && !n.progressIndeterminate) {
            CircularProgressIndicator(
                progress = { n.progress.toFloat() / n.progressMax },
                modifier = Modifier.size(size),
                color = accent,
                strokeWidth = 2.5.dp,
                trackColor = accent.copy(alpha = 0.25f),
            )
        } else {
            CircularProgressIndicator(Modifier.size(size), color = accent, strokeWidth = 2.5.dp)
        }
    }
}

@Composable
private fun ProgressExpanded(p: IslandActivity.Progress, s: IslandSettings, actions: IslandActions, topInset: Dp) {
    val e = p.entry
    val n = e.snapshot
    val accent = accentFor(p, s)
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = topInset, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(e.appIcon, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(n.title ?: e.appLabel, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                n.text?.let {
                    Text(it, fontSize = 12.sp, color = LocalContentColor.current.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (n.progressMax > 0 && !n.progressIndeterminate) {
                Text("${n.progress * 100 / n.progressMax}%", fontWeight = FontWeight.SemiBold, color = accent)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (n.progressMax > 0 && !n.progressIndeterminate) {
            LinearProgressIndicator(
                progress = { n.progress.toFloat() / n.progressMax },
                modifier = Modifier.fillMaxWidth(),
                color = accent,
                trackColor = accent.copy(alpha = 0.25f),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent, trackColor = accent.copy(alpha = 0.25f))
        }
        Spacer(Modifier.weight(1f))
        ActionRow(e, actions, accent)
    }
}

@Composable
private fun ActionRow(entry: NotificationEntry, actions: IslandActions, color: Color) {
    if (entry.actions.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        entry.actions.forEach { a ->
            TextButton(onClick = { actions.sendAction(a) }) {
                Text(a.title, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

// ---------------- Alerts ----------------

private fun senderOf(entry: NotificationEntry, s: IslandSettings) =
    if (s.privacyMode) entry.appLabel else entry.snapshot.title ?: entry.appLabel

private fun messageOf(entry: NotificationEntry, s: IslandSettings) =
    if (s.privacyMode) "New notification" else entry.snapshot.text.orEmpty().replace('\n', ' ')

/** Contact picture (or app icon) with a small app badge, like Android's conversation notifications. */
@Composable
private fun Avatar(entry: NotificationEntry, s: IslandSettings, size: Dp) {
    val showPerson = !s.privacyMode && entry.largeIcon != null
    Box {
        AppIcon(if (showPerson) entry.largeIcon else entry.appIcon, size)
        if (showPerson && entry.appIcon != null && size >= 28.dp) {
            Box(Modifier.align(Alignment.BottomEnd)) { AppIcon(entry.appIcon, size * 0.38f) }
        }
    }
}

/** Preview, left of the camera: avatar and sender. */
@Composable
private fun AlertPreviewLeft(entry: NotificationEntry, s: IslandSettings, h: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(entry, s, h - 10.dp)
        Spacer(Modifier.width(6.dp))
        Text(senderOf(entry, s), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Preview, right of the camera: as much of the message as fits. */
@Composable
private fun AlertPreviewRight(entry: NotificationEntry, s: IslandSettings) {
    Text(
        messageOf(entry, s),
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = LocalContentColor.current.copy(alpha = 0.8f),
    )
}

/** Tapped notification: full content plus its own buttons; reply actions turn into a text field. */
@Composable
private fun AlertExpanded(
    entry: NotificationEntry,
    s: IslandSettings,
    actions: IslandActions,
    topInset: Dp,
    replying: NotificationActionItem?,
) {
    val accent = LocalIslandPalette.current.notification
    val dim = LocalContentColor.current.copy(alpha = 0.6f)
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = topInset, bottom = 10.dp)) {
        Row {
            Avatar(entry, s, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.appLabel, fontSize = 11.sp, color = dim, maxLines = 1)
                Text(senderOf(entry, s), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (s.privacyMode) "New notification" else entry.snapshot.text.orEmpty(),
                    fontSize = 13.sp, lineHeight = 16.sp, maxLines = if (replying != null) 2 else 3,
                    overflow = TextOverflow.Ellipsis, color = LocalContentColor.current.copy(alpha = 0.8f),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (replying != null) ReplyField(replying, accent, actions)
        else if (entry.actions.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                entry.actions.forEach { a ->
                    TextButton(onClick = { actions.sendAction(a) }) {
                        if (a.isReply) {
                            Icon(Icons.AutoMirrored.Rounded.Reply, null, Modifier.size(18.dp), tint = accent)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(a.title, color = accent, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Inline reply: the text goes back to the app through the notification's RemoteInput. */
@Composable
private fun ReplyField(action: NotificationActionItem, accent: Color, actions: IslandActions) {
    var text by remember(action) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val content = LocalContentColor.current
    LaunchedEffect(action) {
        // The overlay window only becomes focusable once the reply starts; give it a moment.
        delay(150)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    val label = action.remoteInput?.label?.toString()?.takeIf { it.isNotBlank() } ?: action.title
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions::cancelReply, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Close, "Cancel", Modifier.size(20.dp))
        }
        BasicTextField(
            value = text,
            onValueChange = { text = it; actions.onInteraction() },
            singleLine = true,
            textStyle = TextStyle(color = content, fontSize = 14.sp),
            cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { actions.sendReply(text) }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .clip(RoundedCornerShape(20.dp))
                .background(content.copy(alpha = 0.12f))
                .padding(horizontal = 14.dp, vertical = 9.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text(label, fontSize = 14.sp, color = content.copy(alpha = 0.5f), maxLines = 1)
                    inner()
                }
            },
        )
        IconButton(onClick = { actions.sendReply(text) }, enabled = text.isNotBlank(), modifier = Modifier.size(40.dp)) {
            Icon(Icons.AutoMirrored.Rounded.Send, "Send", Modifier.size(22.dp), tint = if (text.isNotBlank()) accent else content.copy(alpha = 0.35f))
        }
    }
}
