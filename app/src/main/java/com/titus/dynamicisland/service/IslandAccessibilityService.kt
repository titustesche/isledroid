package com.titus.dynamicisland.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.PowerManager
import java.io.FileDescriptor
import java.io.PrintWriter
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.titus.dynamicisland.appGraph
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hosts the island overlay. An accessibility overlay can draw over the status bar and display
 * cutout, and the service is kept alive by the system more reliably than a foreground service.
 * It also tracks which app is open and the screen/lock state.
 */
class IslandAccessibilityService : AccessibilityService() {

    private var overlay: OverlayWindow? = null
    private var ignoredPackages: Set<String> = emptySet()
    private val isAppCache = mutableMapOf<String, Boolean>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val controller = appGraph.controller
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> controller.onScreenOff()
                Intent.ACTION_SCREEN_ON -> controller.setLocked(isKeyguardLocked())
                Intent.ACTION_USER_PRESENT -> controller.setLocked(false)
            }
        }
    }

    override fun onServiceConnected() {
        ScreenReader.service = this
        val ime = getSystemService(InputMethodManager::class.java)
        ignoredPackages = ime.enabledInputMethodList.map { it.packageName }.toSet() + SYSTEM_UI + packageName
        updateOrientation(resources.configuration)
        appGraph.controller.setLocked(isKeyguardLocked())
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
        overlay = OverlayWindow(this, appGraph).also { it.attach() }
        appGraph.system.start()
        appGraph.controller.globalAction = { action -> performGlobalAction(action) }
        settingsJob = appGraph.scope.launch {
            appGraph.settings.settings.map { it.enabled && it.volumeHud }.distinctUntilChanged().collect(::setKeyFiltering)
        }
    }

    private var settingsJob: Job? = null

    /** Only ask for key events while the island volume bar is on; otherwise keys never pass through us. */
    private fun setKeyFiltering(on: Boolean) {
        val info = serviceInfo ?: return
        info.flags = if (on) info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        else info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        serviceInfo = info
    }

    /** Volume keys drive the island's volume bar instead of the system panel (calls are left alone). */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val s = appGraph.settings.settings.value
        if (!s.enabled || !s.volumeHud) return false
        val raise = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> return false
        }
        val system = appGraph.system
        if (system.inCall) return false
        // Screen off and nothing playing: the system ignores the keys too, keep its behaviour.
        if (!getSystemService(PowerManager::class.java).isInteractive && !system.isMusicActive) return false
        if (event.action == KeyEvent.ACTION_DOWN) appGraph.controller.adjustVolume(raise)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Any window change (app switch, video going fullscreen, shade …) may change status bar visibility.
        overlay?.recheckFullscreen()
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignoredPackages || !isUserFacingApp(pkg)) return
        appGraph.controller.setForegroundPackage(pkg)
        if (pkg != homePackage) appGraph.habits.onAppOpened(pkg)
    }

    /**
     * Only launchable apps and the home screen count as "open"; this skips system overlays,
     * edge panels and similar windows that would otherwise flicker the foreground app.
     */
    private fun isUserFacingApp(pkg: String): Boolean = isAppCache.getOrPut(pkg) {
        packageManager.getLaunchIntentForPackage(pkg) != null || pkg == homePackage()
    }

    private val homePackage by lazy { homePackage() }

    private fun homePackage(): String? = packageManager
        .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName

    private fun isKeyguardLocked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    override fun onInterrupt() = Unit

    private var lastDebugAsk = "" to 0L
    private var lastDebugPopups = "" to 0L

    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        // Debug builds: `dumpsys activity service …IslandAccessibilityService ask <question>` runs a
        // question through the assistant without speaking it (for testing answers and voices).
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && args?.firstOrNull() == "ask") {
            val question = args.drop(1).joinToString(" ")
            // dumpsys can call dump() twice for one command; ask once.
            val now = android.os.SystemClock.elapsedRealtime()
            if (question != lastDebugAsk.first || now - lastDebugAsk.second > 2_000) {
                lastDebugAsk = question to now
                android.os.Handler(mainLooper).post { appGraph.assistant.debugAsk(question) }
            }
            writer.println("asking: $question")
            return
        }
        if (debuggable && args?.firstOrNull() == "type") {
            // `… type <n> <text>` types into element n of the last `screen` reading, the way type_text does.
            val n = args.getOrNull(1)?.toIntOrNull() ?: run { writer.println("usage: type <n> <text>"); return }
            val error = runCatching { ScreenReader.type(n, args.drop(2).joinToString(" "), submit = false) }.exceptionOrNull()
            writer.println(error?.message ?: "typed")
            return
        }
        if (debuggable && args?.firstOrNull() == "rounds") {
            // `… rounds <n>` caps the assistant's tool rounds (to test running out of steps); `… rounds off` undoes it.
            com.titus.dynamicisland.data.assistant.AssistantLimits.debugRounds = args.getOrNull(1)?.toIntOrNull()
            writer.println("rounds: ${com.titus.dynamicisland.data.assistant.AssistantLimits.debugRounds ?: "from settings"}")
            return
        }
        if (debuggable && args?.firstOrNull() == "tap") {
            // `… tap <x> <y> [holdMs]` taps there the way tap_at does (what's under it, then the gesture).
            val x = args.getOrNull(1)?.toIntOrNull()
            val y = args.getOrNull(2)?.toIntOrNull()
            if (x == null || y == null) { writer.println("usage: tap <x> <y> [holdMs]"); return }
            writer.println("under it: ${ScreenReader.describeAt(x, y)}")
            val error = runCatching { ScreenReader.tapAt(x, y, args.getOrNull(3)?.toLongOrNull() ?: 60) }.exceptionOrNull()
            writer.println(error?.message ?: "tapped $x,$y")
            return
        }
        if (debuggable && args?.firstOrNull() == "screen") {
            // `… screen` prints the screen as the assistant reads it, numbers and positions included.
            val (w, h) = ScreenReader.screenSize() ?: (0 to 0)
            val screen = ScreenReader.read(20_000, numbered = true, positions = true)
            writer.println("${screen?.packageName} ${w}x$h")
            writer.println(screen?.text ?: "not connected")
            return
        }
        if (debuggable && args?.firstOrNull() == "map") {
            // `… map <lat> <lon> [label]` opens the island's map there; `… map close` closes it.
            val a = args.drop(1)
            if (a.firstOrNull() == "close") appGraph.map.close()
            else {
                val lat = a.getOrNull(0)?.toDoubleOrNull()
                val lon = a.getOrNull(1)?.toDoubleOrNull()
                if (lat == null || lon == null) { writer.println("usage: map <lat> <lon> [label] | map close"); return }
                appGraph.map.show(com.titus.dynamicisland.domain.MapTarget(a.drop(2).joinToString(" ").ifBlank { "Target" },
                    com.titus.dynamicisland.data.assistant.GeoPoint(lat, lon)))
            }
            writer.println("map: ${a.joinToString(" ")}")
            return
        }
        if (debuggable && args?.firstOrNull() == "popups") {
            // Same double-call guard as "ask": a second identical command within 2 s is ignored.
            val command = args.drop(1)
            val now = android.os.SystemClock.elapsedRealtime()
            if (command.joinToString(" ") != lastDebugPopups.first || now - lastDebugPopups.second > 2_000) {
                lastDebugPopups = command.joinToString(" ") to now
                writer.println(PopupExperiment.run(this, command))
            } else {
                writer.println("(repeat ignored)")
            }
            return
        }
        writer.print(appGraph.controller.debugState())
        writer.print(overlay?.debugState() ?: "overlay=null\n")
        writer.println("orientation=${resources.configuration.orientation}")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOrientation(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun onDestroy() {
        ScreenReader.service = null
        settingsJob?.cancel()
        appGraph.controller.globalAction = null
        appGraph.system.stop()
        runCatching { unregisterReceiver(screenReceiver) }
        overlay?.detach()
        overlay = null
        super.onDestroy()
    }

    private fun updateOrientation(config: Configuration) =
        appGraph.controller.setLandscape(config.orientation == Configuration.ORIENTATION_LANDSCAPE)

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
    }
}
