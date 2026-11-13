package com.titus.dynamicisland.ui.settings

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import com.titus.dynamicisland.ui.theme.Term
import com.titus.dynamicisland.appGraph
import com.titus.dynamicisland.data.settings.AppFilterMode
import com.titus.dynamicisland.data.settings.IslandSettings
import com.titus.dynamicisland.ui.island.MAX_TOOLS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AppPickerTarget { HIDDEN, NOTIFICATIONS, PLAYERS, FAVORITES, HABITS }

private data class AppRow(val pkg: String, val label: String, val icon: ImageBitmap?)

@Composable
fun AppPickerScreen(target: AppPickerTarget, s: SettingsHandle, onBack: () -> Unit) {
    val context = LocalContext.current
    val icons = context.appGraph.icons
    val apps by produceState<List<AppRow>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != context.packageName }
                .map { AppRow(it, icons.label(it), icons.icon(it)) }
                .sortedBy { it.label.lowercase() }
        }
    }
    var query by remember { mutableStateOf("") }
    val selected = target.selection(s.value)
    val title = when (target) {
        AppPickerTarget.HIDDEN -> "Hide island in"
        AppPickerTarget.PLAYERS -> "Ignored players"
        AppPickerTarget.FAVORITES -> "Favourite apps"
        AppPickerTarget.HABITS -> "Never a routine"
        AppPickerTarget.NOTIFICATIONS ->
            if (s.value.notificationFilterMode == AppFilterMode.ONLY_SELECTED) "Show pop-ups from" else "Block pop-ups from"
    }

    SettingsScaffold(title, onBack, s.value, preview = null) {
        item { TermField("Search", "", { query = it }, placeholder = "app name or package") }
        val list = apps
        if (list == null) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp) } }
        } else {
            val filtered = list.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                .sortedByDescending { it.pkg in selected }
            items(filtered, key = { it.pkg }) { app ->
                val checked = app.pkg in selected
                val toggle = { s.update { target.toggle(it, app.pkg) } }
                SettingRow(
                    app.label, app.pkg, onClick = toggle,
                    leading = { app.icon?.let { Image(it, null, Modifier.size(32.dp)) } ?: Box(Modifier.size(32.dp)) },
                ) {
                    Text(
                        if (checked) "[x]" else "[ ]", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = if (checked) Color.White else Term.Faint, modifier = Modifier.padding(end = 10.dp),
                    )
                }
            }
        }
    }
}

private fun AppPickerTarget.selection(s: IslandSettings): Set<String> = when (this) {
    AppPickerTarget.HIDDEN -> s.hiddenApps
    AppPickerTarget.NOTIFICATIONS -> s.notificationApps
    AppPickerTarget.PLAYERS -> s.blockedPlayers
    AppPickerTarget.FAVORITES -> s.favoriteApps
    AppPickerTarget.HABITS -> s.habitsIgnoredApps
}

private fun AppPickerTarget.toggle(s: IslandSettings, pkg: String): IslandSettings {
    fun Set<String>.flip() = if (pkg in this) this - pkg else this + pkg
    return when (this) {
        AppPickerTarget.HIDDEN -> s.copy(hiddenApps = s.hiddenApps.flip())
        AppPickerTarget.NOTIFICATIONS -> s.copy(notificationApps = s.notificationApps.flip())
        AppPickerTarget.PLAYERS -> s.copy(blockedPlayers = s.blockedPlayers.flip())
        AppPickerTarget.HABITS -> s.copy(habitsIgnoredApps = s.habitsIgnoredApps.flip())
        // The tools panel has room for one row.
        AppPickerTarget.FAVORITES ->
            if (pkg !in s.favoriteApps && s.favoriteApps.size >= MAX_TOOLS) s else s.copy(favoriteApps = s.favoriteApps.flip())
    }
}
