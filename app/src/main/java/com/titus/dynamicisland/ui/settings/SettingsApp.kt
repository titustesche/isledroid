package com.titus.dynamicisland.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.style.TextDecoration
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.titus.dynamicisland.ui.theme.Term
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.titus.dynamicisland.appGraph
import com.titus.dynamicisland.data.settings.IslandSettings
import com.titus.dynamicisland.domain.PreviewSample
import com.titus.dynamicisland.ui.island.islandDimensions

object Routes {
    const val HOME = "home"
    const val APPEARANCE = "appearance"
    const val BEHAVIOR = "behavior"
    const val GESTURES = "gestures"
    const val MUSIC = "music"
    const val NOTIFICATIONS = "notifications"
    const val CALLS = "calls"
    const val LIVE = "live"
    const val EVENTS = "events"
    const val BATTERIES = "batteries"
    const val TOOLS = "tools"
    const val ASSISTANT = "assistant"
    const val ASSISTANT_TOOLS = "assistant/tools"
    const val MEMORY = "assistant/memory"
    const val NETWORK = "assistant/devices"
    const val HABITS = "assistant/habits"
    const val ASSISTANT_AI = "assistant/ai"
    const val ASSISTANT_VOICE = "assistant/voice"
    const val ASSISTANT_SCREEN = "assistant/screen"
    const val ASSISTANT_APPS = "assistant/apps"
    const val ASSISTANT_MESSAGES = "assistant/messages"
    const val ASSISTANT_CONVERSATION = "assistant/conversation"
    const val ASSISTANT_LOOK = "assistant/look"
    const val NAVIGATION = "navigation"
    const val APPS = "apps/{target}"
    fun apps(target: AppPickerTarget) = "apps/${target.name}"
}

/** Read + write access to settings for screens. */
class SettingsHandle(val value: IslandSettings, val update: ((IslandSettings) -> IslandSettings) -> Unit)

@Composable
fun SettingsApp() {
    val graph = LocalContext.current.appGraph
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val handle = SettingsHandle(settings, graph.settings::update)
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    val go: (String) -> Unit = { nav.navigate(it) }
    val pickApps: (AppPickerTarget) -> Unit = { nav.navigate(Routes.apps(it)) }

    CompositionLocalProvider(LocalSettingsNav provides nav) {
        NavHost(nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) { HomeScreen(handle, onNavigate = go, onReset = graph.settings::reset) }
            composable(Routes.APPEARANCE) { AppearanceScreen(handle, back) }
            composable(Routes.BEHAVIOR) { BehaviorScreen(handle, back, pickApps) }
            composable(Routes.GESTURES) { GesturesScreen(handle, back) }
            composable(Routes.MUSIC) { MusicScreen(handle, back, pickApps) }
            composable(Routes.NOTIFICATIONS) { NotificationsScreen(handle, back, pickApps) }
            composable(Routes.CALLS) { CallsScreen(handle, back) }
            composable(Routes.LIVE) { LiveActivitiesScreen(handle, back) }
            composable(Routes.EVENTS) { SystemEventsScreen(handle, back, go) }
            composable(Routes.BATTERIES) { DeviceBatteriesScreen(handle, back) }
            composable(Routes.TOOLS) { QuickToolsScreen(handle, back, pickApps) }
            composable(Routes.ASSISTANT) { AssistantScreen(handle, back, go) }
            composable(Routes.ASSISTANT_TOOLS) { AssistantToolsScreen(handle, back) }
            composable(Routes.MEMORY) { MemoryScreen(handle, back) }
            composable(Routes.NETWORK) { NetworkScreen(handle, back) }
            composable(Routes.HABITS) { HabitsScreen(handle, back, pickApps) }
            composable(Routes.ASSISTANT_AI) { AssistantAiScreen(handle, back) }
            composable(Routes.ASSISTANT_VOICE) { AssistantVoiceScreen(handle, back) }
            composable(Routes.ASSISTANT_SCREEN) { ReadScreenScreen(handle, back) }
            composable(Routes.ASSISTANT_APPS) { UseAppsScreen(handle, back) }
            composable(Routes.ASSISTANT_MESSAGES) { MessagesScreen(handle, back) }
            composable(Routes.ASSISTANT_CONVERSATION) { ConversationScreen(handle, back) }
            composable(Routes.ASSISTANT_LOOK) { AssistantLookScreen(handle, back) }
            composable(Routes.NAVIGATION) { NavigationScreen(handle, back) }
            composable(Routes.APPS) { entry ->
                val target = AppPickerTarget.valueOf(entry.arguments?.getString("target") ?: AppPickerTarget.HIDDEN.name)
                AppPickerScreen(target, handle, back)
            }
        }
    }
}

/**
 * Common screen chrome: back arrow and title, then the settings, all scrolling
 * together on black. While the page is open, the real island shows [preview], the sample the
 * page's settings apply to, and the page moves down whenever that sample is open, so the
 * island never covers it.
 */
@Composable
fun SettingsScaffold(
    title: String,
    onBack: (() -> Unit)?,
    settings: IslandSettings,
    preview: PreviewSample? = PreviewSample.BOTH,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: SettingsListScope.() -> Unit,
) {
    val controller = LocalContext.current.appGraph.controller
    val page = remember { Any() }
    LifecycleResumeEffect(preview) {
        if (preview != null) controller.showPreview(page, preview) else controller.clearPreview(page)
        onPauseOrDispose { controller.clearPreview(page) }
    }
    val state by controller.uiState.collectAsStateWithLifecycle()
    val attached by controller.overlayAttached.collectAsStateWithLifecycle()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val maxWidth = LocalConfiguration.current.screenWidthDp.dp - 16.dp
    val pill = settings.heightDp.dp
    val height = if (attached && state.isPreview) islandDimensions(state, settings, maxWidth).height else 0.dp
    // Only an open island reaches into the page; the pill stays within the status bar.
    val room = if (height > pill) {
        val bottom = statusBar / 2 - pill / 2 + settings.offsetYDp.dp + height
        max(0.dp, bottom - statusBar + 8.dp)
    } else 0.dp
    val reserved by animateDpAsState(room, spring(stiffness = Spring.StiffnessMediumLow), label = "previewRoom")

    val crumbs = breadcrumbs(LocalLifecycleOwner.current as? NavBackStackEntry)
    val nav = LocalSettingsNav.current
    // Text without its own color must still show on black (outside a Surface it would default to black).
    CompositionLocalProvider(LocalContentColor provides Term.Text) {
        LazyColumn(
            Modifier.fillMaxSize().background(Term.Bg),
            contentPadding = PaddingValues(top = statusBar + reserved, bottom = navBar + 32.dp),
        ) {
            item(contentType = "title") {
                PageTitle(title, onBack, trailing)
                Breadcrumbs(crumbs, start = if (onBack != null) 58.dp else 20.dp) { route ->
                    // Back to that page if it's behind this one (it normally is), otherwise straight to it.
                    if (nav != null && !nav.popBackStack(route, inclusive = false)) nav.navigate(route)
                }
                if (subtitle != null) {
                    Text(subtitle, fontSize = 14.sp, lineHeight = 20.sp, color = Term.Dim, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp))
                }
            }
            SettingsListScope(this).content()
        }
    }
}

@Composable
private fun PageTitle(title: String, onBack: (() -> Unit)?, trailing: (@Composable () -> Unit)?) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.size(24.dp), tint = Term.Text)
                }
                Spacer(Modifier.width(6.dp))
            }
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, maxLines = 1,
                modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
    }
}

/** The NavController, for the breadcrumbs. */
private val LocalSettingsNav = staticCompositionLocalOf<NavHostController?> { null }

private data class Crumb(val label: String, val route: String)

/** Parent page and path name of each page. */
private val PAGES: Map<String, Pair<String?, String>> = mapOf(
    Routes.HOME to (null to "~/island"),
    Routes.APPEARANCE to (Routes.HOME to "appearance"),
    Routes.BEHAVIOR to (Routes.HOME to "behavior"),
    Routes.GESTURES to (Routes.HOME to "gestures"),
    Routes.MUSIC to (Routes.HOME to "music"),
    Routes.NOTIFICATIONS to (Routes.HOME to "notifications"),
    Routes.CALLS to (Routes.HOME to "calls"),
    Routes.LIVE to (Routes.HOME to "timers"),
    Routes.EVENTS to (Routes.HOME to "system-events"),
    Routes.BATTERIES to (Routes.EVENTS to "batteries"),
    Routes.TOOLS to (Routes.HOME to "quick-tools"),
    Routes.ASSISTANT to (Routes.HOME to "assistant"),
    Routes.ASSISTANT_TOOLS to (Routes.ASSISTANT to "permissions"),
    Routes.ASSISTANT_AI to (Routes.ASSISTANT to "ai"),
    Routes.ASSISTANT_VOICE to (Routes.ASSISTANT to "voice"),
    Routes.ASSISTANT_SCREEN to (Routes.ASSISTANT to "screen"),
    Routes.ASSISTANT_APPS to (Routes.ASSISTANT to "apps"),
    Routes.ASSISTANT_MESSAGES to (Routes.ASSISTANT to "messages"),
    Routes.ASSISTANT_CONVERSATION to (Routes.ASSISTANT to "conversation"),
    Routes.ASSISTANT_LOOK to (Routes.ASSISTANT to "look"),
    Routes.MEMORY to (Routes.ASSISTANT to "memory"),
    Routes.NETWORK to (Routes.ASSISTANT to "devices"),
    Routes.HABITS to (Routes.ASSISTANT to "habits"),
    Routes.NAVIGATION to (Routes.HOME to "navigation"),
)

/** The app picker lives under the page that opened it. */
private fun AppPickerTarget.parent() = when (this) {
    AppPickerTarget.HIDDEN -> Routes.BEHAVIOR
    AppPickerTarget.NOTIFICATIONS -> Routes.NOTIFICATIONS
    AppPickerTarget.PLAYERS -> Routes.MUSIC
    AppPickerTarget.FAVORITES -> Routes.TOOLS
    AppPickerTarget.HABITS -> Routes.HABITS
}

/** "~/island/system-events/batteries", one crumb per page from the top down to [entry]'s. */
private fun breadcrumbs(entry: NavBackStackEntry?): List<Crumb> {
    val route = entry?.destination?.route ?: return listOf(Crumb("~/island", Routes.HOME))
    val trail = ArrayDeque<Crumb>()
    var parent: String? = if (route == Routes.APPS) {
        val target = runCatching { AppPickerTarget.valueOf(entry.arguments?.getString("target").orEmpty()) }.getOrNull()
        trail.addFirst(Crumb("apps", route))
        target?.parent() ?: Routes.HOME
    } else route
    while (parent != null) {
        val (up, label) = PAGES[parent] ?: break
        trail.addFirst(Crumb(label, parent))
        parent = up
    }
    return trail.toList()
}

/** The path under the title; every part but the last (this page) takes you to that page. */
@Composable
private fun Breadcrumbs(crumbs: List<Crumb>, start: androidx.compose.ui.unit.Dp, onOpen: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(start = start - 6.dp, end = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.Center) {
        crumbs.forEachIndexed { i, crumb ->
            val last = i == crumbs.lastIndex
            if (i > 0) Text("/", fontSize = 13.sp, color = Term.Faint, modifier = Modifier.padding(vertical = 8.dp))
            Text(
                crumb.label,
                fontSize = 13.sp,
                letterSpacing = 0.4.sp,
                fontWeight = if (last) FontWeight.Medium else FontWeight.Normal,
                color = if (last) Term.Text else Term.Dim,
                textDecoration = if (last) null else TextDecoration.Underline,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .then(if (last) Modifier else Modifier.clickable { onOpen(crumb.route) })
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }
}
