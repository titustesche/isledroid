package com.titus.dynamicisland.ui.island

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The camera cutout in island coordinates: horizontal center relative to the island's center,
 * and top/bottom relative to the island's top edge. Content must stay clear of this area.
 */
data class CutoutGeometry(val centerOffsetX: Dp, val width: Dp, val top: Dp, val bottom: Dp) {
    /** Half-width that must be kept free around the island's center line (symmetric, plus a margin). */
    val clearHalfWidth: Dp get() = abs(centerOffsetX) + width / 2 + 6.dp

    /** How far down expanded content has to start to clear the cutout. */
    val clearTop: Dp get() = bottom + 4.dp

    private fun abs(d: Dp) = if (d < 0.dp) -d else d
}
