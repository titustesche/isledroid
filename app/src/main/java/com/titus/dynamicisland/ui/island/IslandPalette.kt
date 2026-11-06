package com.titus.dynamicisland.ui.island

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.titus.dynamicisland.data.settings.IslandSettings

/** The user's status and panel colors (Appearance → Colors), for content deep inside the island. */
data class IslandPalette(
    val charging: Color,
    val lowBattery: Color,
    val silent: Color,
    val dnd: Color,
    val bluetooth: Color,
    val torch: Color,
    val volume: Color,
    val tools: Color,
    val notification: Color,
    val assistant: Color,
)

private fun Long.color() = Color(toInt())

fun IslandSettings.palette() = IslandPalette(
    charging = chargingColor.color(),
    lowBattery = lowBatteryColor.color(),
    silent = silentColor.color(),
    dnd = dndColor.color(),
    bluetooth = bluetoothColor.color(),
    torch = torchColor.color(),
    volume = volumeColor.color(),
    tools = quickToolsColor.color(),
    notification = notificationColor.color(),
    assistant = assistantColor.color(),
)

val LocalIslandPalette = staticCompositionLocalOf { IslandSettings().palette() }
