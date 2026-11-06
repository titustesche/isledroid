package com.titus.dynamicisland.ui.island

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titus.dynamicisland.data.notifications.RecentNotification
import com.titus.dynamicisland.domain.IslandActions
import com.titus.dynamicisland.domain.IslandActivity

private val PanelHeader = 32.dp
private val PanelRow = 60.dp
private const val PanelVisibleRows = 5

/** Grows with the list up to [PanelVisibleRows] rows; more scroll. */
fun notificationPanelHeight(count: Int): Dp =
    ExpandedTopPadding + PanelHeader + (if (count == 0) 56.dp else PanelRow * count.coerceAtMost(PanelVisibleRows)) + 10.dp

/** What's in the notification shade, newest first: tap one to open it, × to clear it. */
@Composable
fun NotificationPanel(panel: IslandActivity.Notifications, actions: IslandActions, topInset: Dp) {
    val dim = LocalContentColor.current.copy(alpha = 0.6f)
    val accent = LocalIslandPalette.current.notification
    Column(Modifier.fillMaxSize().padding(top = topInset, bottom = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().height(PanelHeader).padding(start = 18.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Notifications", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (panel.items.isNotEmpty()) {
                Text(
                    "Clear all",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { actions.clearNotifications() }.padding(8.dp, 4.dp),
                )
            }
        }
        if (panel.items.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No notifications", fontSize = 13.sp, color = dim)
            }
            return@Column
        }
        val list = rememberLazyListState()
        // Reading and scrolling count as using the island: it mustn't close mid-scroll.
        LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress) actions.onInteraction() }
        val now = System.currentTimeMillis()
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list) {
            items(panel.items, key = { it.key }) { n ->
                NotificationRow(n, panel.hideContent, now, dim, actions, Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun NotificationRow(
    n: RecentNotification,
    hideContent: Boolean,
    now: Long,
    dim: Color,
    actions: IslandActions,
    modifier: Modifier,
) {
    Row(
        modifier.fillMaxWidth().height(PanelRow).clickable { actions.openNotification(n) }.padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val person = n.largeIcon.takeUnless { hideContent }
        Box {
            AppIcon(person ?: n.appIcon, 36.dp)
            if (person != null && n.appIcon != null) {
                Box(Modifier.align(Alignment.BottomEnd)) { AppIcon(n.appIcon, 14.dp) }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (hideContent) n.appLabel else n.title ?: n.appLabel,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(" · ${age(now - n.postTime)}", fontSize = 11.sp, color = dim, maxLines = 1)
            }
            val text = if (hideContent) "New notification" else n.text?.replace('\n', ' ').orEmpty()
            if (text.isNotBlank()) {
                Text(text, fontSize = 12.sp, lineHeight = 15.sp, color = dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = { actions.dismissNotification(n.key) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = dim, modifier = Modifier.size(18.dp))
        }
    }
}

private fun age(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 1 -> "now"
        min < 60 -> "${min}m"
        min < 24 * 60 -> "${min / 60}h"
        else -> "${min / (24 * 60)}d"
    }
}
