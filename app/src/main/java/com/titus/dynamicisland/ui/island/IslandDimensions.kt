package com.titus.dynamicisland.ui.island

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import com.titus.dynamicisland.data.settings.ActionStyle
import com.titus.dynamicisland.data.settings.IslandSettings
import com.titus.dynamicisland.domain.IslandActivity
import com.titus.dynamicisland.domain.IslandMode
import com.titus.dynamicisland.domain.IslandUiState

data class IslandDimensions(val width: Dp, val height: Dp, val corner: Dp, val topInset: Dp)

/** Expanded notification: header (app · sender · up to 3 lines) plus a row of actions or the reply field. */
val AlertExpandedHeight = 150.dp

/** Default top padding of expanded layouts when nothing needs to be avoided. */
val ExpandedTopPadding = 14.dp

/** Round call buttons, and the expanded call's height around them. */
val CallButtonSize = 44.dp
val CallExpandedHeight = ExpandedTopPadding + CallButtonSize + 14.dp

/** Horizontal padding inside the compact pill. */
fun compactPadding(s: IslandSettings): Dp = s.heightDp.dp * 0.22f

/** Widest the compact pill may get. */
fun compactMaxWidth(s: IslandSettings, screenMax: Dp): Dp = min(maxOf(s.compactMaxWidthDp, s.compactWidthDp).dp, screenMax)

/**
 * Target size of the main pill for a state; the composable springs towards it.
 *
 * @param compactContentWidth width the compact content measured it needs (null until measured).
 * @param cutout camera position, so content can avoid it.
 */
fun islandDimensions(
    state: IslandUiState,
    s: IslandSettings,
    maxWidth: Dp,
    cutout: CutoutGeometry? = null,
    compactContentWidth: Dp? = null,
): IslandDimensions {
    val h = s.heightDp.dp
    val pillCorner = h / 2 * (s.cornerRoundness / 100f)
    // Whatever the mode, the pill should at least cover the camera.
    val minWidth = cutout?.let { it.clearHalfWidth * 2 + 4.dp } ?: 0.dp
    val topInset = max(ExpandedTopPadding, cutout?.clearTop ?: 0.dp)
    val extra = topInset - ExpandedTopPadding

    fun pill(width: Dp) = IslandDimensions(width.coerceIn(min(minWidth, maxWidth), maxWidth), h, pillCorner, 0.dp)
    fun expanded(height: Dp) = IslandDimensions(
        width = min(s.expandedWidthDp.dp, maxWidth),
        height = height + extra,
        corner = min(s.expandedCornerDp.dp, (height + extra) / 2),
        topInset = topInset,
    )
    return when (state.mode) {
        IslandMode.HIDDEN -> pill(s.idleWidthDp.dp)
        // With batteries to show it needs the room of a compact pill.
        IslandMode.IDLE -> pill(if (state.idleBatteries.isEmpty()) s.idleWidthDp.dp else max(s.idleWidthDp.dp, s.compactWidthDp.dp))
        // A notification preview is a compact pill too: sender left of the camera, message right.
        IslandMode.COMPACT, IslandMode.ALERT, IslandMode.EVENT -> pill(
            max(s.compactWidthDp.dp, compactContentWidth ?: 0.dp).coerceIn(0.dp, compactMaxWidth(s, maxWidth)),
        )
        // Semi-expanded and nearly square, centred on the camera.
        IslandMode.ASSISTANT -> IslandDimensions(
            width = min(AssistantWidth, maxWidth),
            height = AssistantContentHeight + extra + ExpandedTopPadding +
                ((state.primary as? IslandActivity.Assistant)?.ui?.let { assistantCardsHeight(it, s.assistantActionStyle == ActionStyle.DETAILED) } ?: 0.dp),
            corner = min(s.expandedCornerDp.dp + 8.dp, 56.dp),
            topInset = topInset,
        )
        IslandMode.EXPANDED -> if (state.primary is IslandActivity.Map) IslandDimensions(
            width = min(MapWidth, maxWidth), height = MapHeight, corner = MapCorner, topInset = topInset,
        ) else expanded(
            when (state.primary) {
                is IslandActivity.Media -> ExpandedTopPadding + mediaExpandedHeight(s)
                is IslandActivity.Timer -> if ((state.primary as IslandActivity.Timer).entry.isCall) CallExpandedHeight else 112.dp
                is IslandActivity.Progress -> 124.dp
                is IslandActivity.Navigation -> NavigationExpandedHeight
                is IslandActivity.Alert -> AlertExpandedHeight
                IslandActivity.Torch -> torchExpandedHeight(state.system)
                IslandActivity.Tools -> toolsPanelHeight(s)
                is IslandActivity.Notifications -> notificationPanelHeight((state.primary as IslandActivity.Notifications).items.size)
                else -> maxOf(h, 76.dp)
            },
        )
    }
}
