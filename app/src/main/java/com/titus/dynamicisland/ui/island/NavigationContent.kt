package com.titus.dynamicisland.ui.island

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titus.dynamicisland.data.settings.IslandSettings
import com.titus.dynamicisland.domain.IslandActivity

/** Height of the open route card. */
val NavigationExpandedHeight = ExpandedTopPadding + 96.dp

/** The next turn's arrow: maps apps put it in the notification's large icon. */
@Composable
fun NavManeuver(nav: IslandActivity.Navigation, size: Dp) {
    val icon = nav.entry.largeIcon
    if (icon != null) Image(icon, null, Modifier.size(size), colorFilter = ColorFilter.tint(LocalContentColor.current))
    else Icon(Icons.Rounded.Navigation, null, Modifier.size(size), tint = LocalContentColor.current)
}

/** The distance to the next turn ("350 m"); before the first turn Google Maps only has the instruction ("Head west"). */
private fun headline(nav: IslandActivity.Navigation) =
    nav.entry.snapshot.title?.takeIf { it.isNotBlank() } ?: nav.entry.snapshot.text.orEmpty()

/** Right of the camera: how far to the next turn ("350 m"). */
@Composable
fun NavCompactRight(nav: IslandActivity.Navigation) {
    Text(
        headline(nav),
        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** The open route: the turn, how far, what to do, and the app's arrival line. Tap opens the app. */
@Composable
fun NavExpanded(nav: IslandActivity.Navigation, s: IslandSettings, topInset: Dp) {
    val n = nav.entry.snapshot
    val dim = LocalContentColor.current.copy(alpha = 0.6f)
    Row(
        Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = topInset, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavManeuver(nav, 56.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(headline(nav), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.navShowInstruction && !n.text.isNullOrBlank() && !n.title.isNullOrBlank()) {
                Text(n.text, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val eta = n.subText ?: nav.entry.appLabel
            Text(eta, fontSize = 12.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
