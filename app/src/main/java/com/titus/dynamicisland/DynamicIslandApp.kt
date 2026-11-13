package com.titus.dynamicisland

import android.app.Application
import android.content.Context
import com.titus.dynamicisland.data.AppIconLoader
import com.titus.dynamicisland.data.assistant.AssistantMemory
import com.titus.dynamicisland.data.assistant.AssistantSecrets
import com.titus.dynamicisland.data.assistant.HabitLog
import com.titus.dynamicisland.data.notifications.NotificationKind
import com.titus.dynamicisland.domain.HabitTracker
import com.titus.dynamicisland.domain.MapController
import com.titus.dynamicisland.data.assistant.PiperStore
import com.titus.dynamicisland.domain.AssistantController
import com.titus.dynamicisland.data.media.MediaRepository
import com.titus.dynamicisland.data.notifications.NotificationRepository
import com.titus.dynamicisland.data.settings.SettingsRepository
import com.titus.dynamicisland.data.system.SystemMonitor
import com.titus.dynamicisland.domain.IslandController
import com.titus.dynamicisland.service.PopupSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class DynamicIslandApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** Manual dependency container; everything lives on the main thread in one process. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsRepository(app, scope)
    val media = MediaRepository(app, scope)
    val notifications = NotificationRepository()
    val icons = AppIconLoader(app)
    val system = SystemMonitor(app)
    val assistantSecrets = AssistantSecrets(app)
    val piper = PiperStore(app)
    val memory = AssistantMemory(app)
    val map = MapController(app, scope)
    val habitLog = HabitLog(app)
    val habits = HabitTracker(habitLog, { settings.settings.value }, icons::label)
    val assistant = AssistantController(app, scope, settings, assistantSecrets, piper, system, media, notifications, memory, map, habits)
    val controller = IslandController(app, scope, settings, media, notifications, system, assistant, map)
    val popups = PopupSuppressor(app, scope)

    init {
        scope.launch {
            // A route appearing (not each update of it) is "started a route".
            var routes = emptySet<String>()
            notifications.live.collect { live ->
                val now = live.filter { it.kind == NotificationKind.NAVIGATION }.map { it.packageName }.toSet()
                (now - routes).forEach(habits::onRouteStarted)
                routes = now
            }
        }
        scope.launch {
            settings.settings.map { it.blockedPlayers }.distinctUntilChanged().collect { media.blockedPackages = it }
        }
        scope.launch {
            settings.settings.map { it.mapArrivalRadiusM to it.mapCloseOnArrival }.distinctUntilChanged()
                .collect { (radius, close) -> map.arrivalRadiusM = radius; map.closeOnArrival = close }
        }
        // Got to the parked car: the note that led there has done its job.
        map.onArrival = { target ->
            if (settings.settings.value.mapForgetOnArrival) target.memoryId?.let(memory::remove)
        }
        scope.launch {
            // Only what decides whose pop-ups are replaced; a full sync touches every app's channels.
            settings.settings
                .distinctUntilChanged { a, b ->
                    a.replaceSystemPopups == b.replaceSystemPopups && a.notificationsEnabled == b.notificationsEnabled &&
                        a.notificationFilterMode == b.notificationFilterMode && a.notificationApps == b.notificationApps
                }
                .collect { popups.sync(it) }
        }
    }
}

val Context.appGraph: AppGraph get() = (applicationContext as DynamicIslandApp).graph
