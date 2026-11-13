package com.titus.dynamicisland.ui.island

import android.media.AudioManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.unit.TextUnit
import com.titus.dynamicisland.data.system.BluetoothKind
import com.titus.dynamicisland.data.system.BluetoothDeviceInfo
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titus.dynamicisland.appGraph
import com.titus.dynamicisland.data.settings.IslandSettings
import com.titus.dynamicisland.data.settings.QuickTool
import com.titus.dynamicisland.data.system.RingerMode
import com.titus.dynamicisland.data.system.SystemEvent
import com.titus.dynamicisland.data.system.SystemState
import com.titus.dynamicisland.domain.IslandActions

/** Max entries per row in the quick-tools panel, so it never gets cluttered. */
const val MAX_TOOLS = 5

// ---------------- Events (compact pill) ----------------

fun eventIcon(e: SystemEvent, p: IslandPalette): Pair<ImageVector, Color?> = when (e) {
    is SystemEvent.Charging -> Icons.Rounded.Bolt to p.charging
    is SystemEvent.LowBattery -> Icons.Rounded.BatteryAlert to p.lowBattery
    is SystemEvent.Ringer -> ringerIcon(e.mode) to (if (e.mode == RingerMode.SILENT) p.silent else null)
    is SystemEvent.Dnd -> Icons.Rounded.DoNotDisturbOn to (if (e.on) p.dnd else null)
    is SystemEvent.Bluetooth -> (if (e.connected) Icons.Rounded.Bluetooth else Icons.Rounded.BluetoothDisabled) to p.bluetooth
    is SystemEvent.Volume -> when {
        e.level == 0 -> Icons.AutoMirrored.Rounded.VolumeOff
        e.fraction < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
        else -> Icons.AutoMirrored.Rounded.VolumeUp
    } to null
}

private fun ringerIcon(mode: RingerMode) = when (mode) {
    RingerMode.NORMAL -> Icons.Rounded.NotificationsActive
    RingerMode.VIBRATE -> Icons.Rounded.Vibration
    RingerMode.SILENT -> Icons.Rounded.NotificationsOff
}

private fun ringerLabel(mode: RingerMode) = when (mode) {
    RingerMode.NORMAL -> "Ring"
    RingerMode.VIBRATE -> "Vibrate"
    RingerMode.SILENT -> "Silent"
}

private fun eventLabel(e: SystemEvent): String? = when (e) {
    is SystemEvent.Charging -> "Charging"
    is SystemEvent.LowBattery -> "Low battery"
    is SystemEvent.Ringer -> null
    is SystemEvent.Dnd -> "Do not disturb"
    is SystemEvent.Bluetooth -> e.name
    is SystemEvent.Volume -> if (e.stream == AudioManager.STREAM_MUSIC) null else "Ring"
}

@Composable
fun EventLeft(e: SystemEvent, h: Dp) {
    val (icon, tint) = eventIcon(e, LocalIslandPalette.current)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(h * 0.55f), tint = tint ?: LocalContentColor.current)
        eventLabel(e)?.let {
            Spacer(Modifier.width(6.dp))
            Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun EventRight(e: SystemEvent, actions: IslandActions) {
    val dim = LocalContentColor.current.copy(alpha = 0.6f)
    val p = LocalIslandPalette.current
    when (e) {
        is SystemEvent.Charging -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${e.level}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.charging)
            Spacer(Modifier.width(6.dp))
            BatteryGlyph(e.level, p.charging, animateFill = true)
        }
        is SystemEvent.LowBattery -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${e.level}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.lowBattery)
            Spacer(Modifier.width(6.dp))
            BatteryGlyph(e.level, p.lowBattery, animateFill = false)
        }
        is SystemEvent.Ringer -> Text(
            ringerLabel(e.mode), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (e.mode == RingerMode.SILENT) p.silent else LocalContentColor.current,
        )
        is SystemEvent.Dnd -> Text(if (e.on) "On" else "Off", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (e.on) p.dnd else dim)
        is SystemEvent.Bluetooth -> when {
            e.battery != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${e.battery}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                BatteryGlyph(e.battery, LocalContentColor.current, animateFill = false)
            }
            else -> Text(if (e.connected) "Connected" else "Disconnected", fontSize = 13.sp, color = dim)
        }
        is SystemEvent.Volume -> VolumeBar(e.fraction, actions)
    }
}

/** A small battery outline whose fill can animate up to [level], like the charging animation on iPhone. */
@Composable
internal fun BatteryGlyph(level: Int, color: Color, animateFill: Boolean) {
    val fill = remember { Animatable(if (animateFill) 0f else level / 100f) }
    LaunchedEffect(level) { fill.animateTo(level / 100f, tween(if (animateFill) 900 else 200, easing = FastOutSlowInEasing)) }
    Canvas(Modifier.size(width = 24.dp, height = 12.dp)) {
        val stroke = 1.5.dp.toPx()
        val nub = 2.dp.toPx()
        val body = Size(size.width - nub - 1.dp.toPx(), size.height)
        val r = CornerRadius(3.dp.toPx())
        drawRoundRect(color.copy(alpha = 0.5f), size = body, cornerRadius = r, style = Stroke(stroke))
        drawRoundRect(color.copy(alpha = 0.5f), topLeft = Offset(body.width + 1.dp.toPx(), size.height * 0.3f),
            size = Size(nub, size.height * 0.4f), cornerRadius = CornerRadius(1.dp.toPx()))
        val inset = stroke + 1.dp.toPx()
        drawRoundRect(color, topLeft = Offset(inset, inset),
            size = Size((body.width - inset * 2) * fill.value.coerceIn(0f, 1f), body.height - inset * 2),
            cornerRadius = CornerRadius(1.5.dp.toPx()))
    }
}

/** Volume level; tap or drag it to set the volume directly. */
@Composable
private fun VolumeBar(fraction: Float, actions: IslandActions) {
    val shown by animateFloatAsState(fraction, tween(120), label = "volume")
    val content = LocalContentColor.current
    val fill = LocalIslandPalette.current.volume
    Canvas(
        Modifier
            .size(width = 104.dp, height = 22.dp)
            .pointerInput(Unit) { detectTapGestures { actions.setVolume((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    actions.setVolume((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val track = 6.dp.toPx()
        val y = size.height / 2 - track / 2
        drawRoundRect(content.copy(alpha = 0.25f), Offset(0f, y), Size(size.width, track), CornerRadius(track / 2))
        drawRoundRect(fill, Offset(0f, y), Size(size.width * shown, track), CornerRadius(track / 2))
    }
}

// ---------------- Flashlight ----------------

@Composable
fun TorchLeft(h: Dp) = Icon(Icons.Rounded.FlashlightOn, "Flashlight on", Modifier.size(h * 0.55f), tint = LocalIslandPalette.current.torch)

@Composable
fun TorchRight() = Text("On", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LocalIslandPalette.current.torch)

@Composable
fun TorchExpanded(system: SystemState, actions: IslandActions, topInset: Dp) {
    val torch = LocalIslandPalette.current.torch
    Column(
        Modifier.fillMaxSize().padding(start = 20.dp, end = 16.dp, top = topInset, bottom = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FlashlightOn, null, Modifier.size(30.dp), tint = torch)
            Spacer(Modifier.width(12.dp))
            Text("Flashlight is on", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(torch)
                    .clickable { actions.runTool(QuickTool.FLASHLIGHT) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text("Turn off", color = onColor(torch), fontWeight = FontWeight.SemiBold)
            }
        }
        if (system.torchDimmable) {
            Spacer(Modifier.height(TorchSliderGap))
            BrightnessSlider(system.torchFraction, torch, actions::setTorchLevel)
        }
    }
}

private val TorchSliderGap = 14.dp
private val TorchSliderHeight = 28.dp

/** Height of the expanded flashlight panel without the camera inset; taller when it can be dimmed. */
fun torchExpandedHeight(system: SystemState): Dp =
    84.dp + if (system.torchDimmable) TorchSliderGap + TorchSliderHeight else 0.dp

/** A thick, iOS-style brightness bar with a sun glyph; tap or drag it to set the level. */
@Composable
private fun BrightnessSlider(fraction: Float, color: Color, onChange: (Float) -> Unit) {
    val shown by animateFloatAsState(fraction, tween(120), label = "torchLevel")
    val content = LocalContentColor.current
    val change by rememberUpdatedState(onChange)
    Box(
        Modifier
            .fillMaxWidth()
            .height(TorchSliderHeight)
            .clip(RoundedCornerShape(TorchSliderHeight / 2))
            .pointerInput(Unit) { detectTapGestures { change((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { c, _ ->
                    c.consume()
                    change((c.position.x / size.width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(content.copy(alpha = 0.14f))
            // Never fully empty: the dimmest level is still on.
            val w = size.height + (size.width - size.height) * shown
            drawRoundRect(color, size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2))
        }
        Icon(Icons.Rounded.LightMode, "Brightness", Modifier.padding(start = 6.dp).size(16.dp), tint = onColor(color))
    }
}

// ---------------- Quick tools ----------------

/** Panel height without the camera inset: a row of tools, plus a row of favourite apps if any are chosen. */
fun toolsPanelHeight(s: IslandSettings): Dp =
    ExpandedTopPadding + 66.dp + 12.dp + if (s.favoriteApps.isNotEmpty()) 52.dp else 0.dp

@Composable
fun ToolsPanel(s: IslandSettings, system: SystemState, actions: IslandActions, topInset: Dp) {
    val accent = LocalIslandPalette.current.tools
    Column(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = topInset, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            s.quickTools.take(MAX_TOOLS).forEach { tool ->
                val (icon, label, active) = toolLook(tool, system)
                ToolButton(icon, label, active, accent) { actions.runTool(tool) }
            }
        }
        if (s.favoriteApps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val icons = LocalContext.current.appGraph.icons
            val apps = remember(s.favoriteApps) { s.favoriteApps.sortedBy { icons.label(it).lowercase() }.take(MAX_TOOLS) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                apps.forEach { pkg ->
                    val icon = remember(pkg) { icons.icon(pkg) }
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable { actions.launchFavorite(pkg) }) {
                        if (icon != null) Image(icon, icons.label(pkg), Modifier.size(40.dp))
                    }
                }
            }
        }
    }
}

private data class ToolLook(val icon: ImageVector, val label: String, val active: Boolean)

private fun toolLook(tool: QuickTool, s: SystemState): ToolLook = when (tool) {
    QuickTool.FLASHLIGHT -> ToolLook(if (s.torchOn) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, "Flashlight", s.torchOn)
    QuickTool.RINGER -> ToolLook(ringerIcon(s.ringer), ringerLabel(s.ringer), s.ringer != RingerMode.NORMAL)
    QuickTool.DND -> ToolLook(Icons.Rounded.DoNotDisturbOn, "Do not disturb", s.dndOn)
    QuickTool.SCREENSHOT -> ToolLook(Icons.Rounded.Screenshot, "Screenshot", false)
    QuickTool.CAMERA -> ToolLook(Icons.Rounded.CameraAlt, "Camera", false)
    QuickTool.LOCK -> ToolLook(Icons.Rounded.Lock, "Lock", false)
    QuickTool.QUICK_SETTINGS -> ToolLook(Icons.Rounded.Tune, "Quick settings", false)
    QuickTool.POWER -> ToolLook(Icons.Rounded.PowerSettingsNew, "Power", false)
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    val content = LocalContentColor.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (active) accent else content.copy(alpha = 0.14f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, Modifier.size(24.dp), tint = if (active) onColor(accent) else content)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = content.copy(alpha = 0.8f))
    }
}

/** Black or white, whichever reads better on [background]. */
fun onColor(background: Color): Color = if (background.luminance() > 0.45f) Color.Black else Color.White

fun bluetoothIcon(kind: BluetoothKind): ImageVector = when (kind) {
    BluetoothKind.HEADPHONES -> Icons.Rounded.Headphones
    BluetoothKind.CAR -> Icons.Rounded.DirectionsCar
    BluetoothKind.WATCH -> Icons.Rounded.Watch
    BluetoothKind.OTHER -> Icons.Rounded.Bluetooth
}

/** A device's symbol and battery, e.g. headphones · 80 %. Red once it's low. */
@Composable
fun DeviceBattery(device: BluetoothDeviceInfo, showIcon: Boolean = true, fontSize: TextUnit = 13.sp) {
    val level = device.battery ?: return
    val color = if (level <= 20) LocalIslandPalette.current.lowBattery else LocalContentColor.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showIcon) {
            Icon(bluetoothIcon(device.kind), contentDescription = device.name, modifier = Modifier.size(15.dp), tint = LocalContentColor.current)
            Spacer(Modifier.width(4.dp))
        }
        Text("$level%", fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

/** The idle pill with batteries: the first device left of the camera, the second (or the first's level) right of it. */
@Composable
fun IdleBatteryLeft(devices: List<BluetoothDeviceInfo>) {
    val first = devices.first()
    if (devices.size == 1) Icon(bluetoothIcon(first.kind), first.name, Modifier.size(16.dp), tint = LocalContentColor.current)
    else DeviceBattery(first)
}

@Composable
fun IdleBatteryRight(devices: List<BluetoothDeviceInfo>) {
    val first = devices.first()
    if (devices.size == 1) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceBattery(first, showIcon = false)
            Spacer(Modifier.width(6.dp))
            BatteryGlyph(first.battery ?: 0, if ((first.battery ?: 0) <= 20) LocalIslandPalette.current.lowBattery else LocalContentColor.current, animateFill = false)
        }
    } else DeviceBattery(devices[1])
}
