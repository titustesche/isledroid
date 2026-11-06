package com.titus.dynamicisland.domain

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.titus.dynamicisland.AssistActivity
import com.titus.dynamicisland.data.media.MediaCustomAction
import com.titus.dynamicisland.data.media.MediaInfo
import com.titus.dynamicisland.data.media.MediaRepository
import com.titus.dynamicisland.data.notifications.NotificationActionItem
import com.titus.dynamicisland.data.notifications.RecentNotification
import com.titus.dynamicisland.data.notifications.NotificationEntry
import com.titus.dynamicisland.data.notifications.NotificationRepository
import com.titus.dynamicisland.data.settings.Gesture
import com.titus.dynamicisland.data.settings.GestureAction
import com.titus.dynamicisland.data.settings.IslandSettings
import com.titus.dynamicisland.data.settings.QuickTool
import com.titus.dynamicisland.data.settings.IdleAction
import com.titus.dynamicisland.data.settings.SettingsRepository
import com.titus.dynamicisland.data.system.RingerMode
import com.titus.dynamicisland.data.system.SystemEvent
import com.titus.dynamicisland.data.system.SystemMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Actions the island UI can trigger. Implemented by [IslandController]; a no-op version drives the preview. */
interface IslandActions {
    fun onGesture(gesture: Gesture) {}
    fun playPause() {}
    fun next() {}
    fun previous() {}
    fun seekTo(positionMs: Long) {}
    fun customAction(action: MediaCustomAction) {}
    fun toggleShuffle() {}
    fun cycleRepeat() {}
    fun open(activity: IslandActivity) {}
    /** Expand a specific activity, e.g. the one in the secondary bubble. */
    fun expand(activity: IslandActivity) {}
    fun sendAction(action: NotificationActionItem) {}
    /** Switch the expanded notification into a text field for this reply action. */
    fun startReply(action: NotificationActionItem) {}
    fun cancelReply() {}
    fun sendReply(text: String) {}
    /** The user is interacting (e.g. dragging the seek bar, typing); postpones auto-collapse. */
    fun onInteraction() {}
    fun runTool(tool: QuickTool) {}
    fun launchFavorite(pkg: String) {}
    fun setVolume(fraction: Float) {}
    fun setTorchLevel(fraction: Float) {}
    /**
     * Switches off a two-state indicator (flashlight, Do Not Disturb, silent/vibrate): its long-press,
     * since that's the only real action it has. False if [activity] isn't one that's currently on.
     */
    fun deactivate(activity: IslandActivity): Boolean = false
    /** Notification panel: open one, swipe one away, clear them all. */
    fun openNotification(item: RecentNotification) {}
    fun dismissNotification(key: String) {}
    fun clearNotifications() {}
}

/** Owns transient UI state (expanded, alert queue, environment) and merges all sources into [uiState]. */
class IslandController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val media: MediaRepository,
    private val notifications: NotificationRepository,
    private val system: SystemMonitor,
    private val assistant: AssistantController,
    private val map: MapController,
) : IslandActions {

    /** Accessibility global actions (screenshot, lock …); set by the service while it's connected. */
    var globalAction: ((Int) -> Boolean)? = null

    private val environment = MutableStateFlow(IslandEnvironment())
    private val expanded = MutableStateFlow(false)
    private val expandedTarget = MutableStateFlow<String?>(null)
    private val currentAlert = MutableStateFlow<NotificationEntry?>(null)
    private val replying = MutableStateFlow<NotificationActionItem?>(null)
    private val pausedMediaExpired = MutableStateFlow(false)
    private val alertQueue = ArrayDeque<NotificationEntry>()
    private var alertJob: Job? = null
    private var collapseJob: Job? = null
    private var pauseJob: Job? = null
    private val repliedAt = mutableMapOf<String, Long>()
    private val currentEvent = MutableStateFlow<SystemEvent?>(null)
    private var eventJob: Job? = null
    /** See IslandStateMachine.Inputs.collapsedCall. */
    private val collapsedCall = MutableStateFlow<String?>(null)

    private val inputs = combine(
        combine(settings.settings, media.media, notifications.live, ::Triple),
        combine(currentAlert, expanded, environment, ::Triple),
        combine(pausedMediaExpired, expandedTarget, replying, ::Triple),
        combine(currentEvent, system.state, assistant.state, ::Triple),
        combine(notifications.shade, collapsedCall, map.target, map.open, ::Extras),
    ) { (s, m, live), (alert, exp, env), (pausedExpired, target, reply), (event, sys, assist), x ->
        IslandStateMachine.Inputs(s, m, live, alert, exp, env, pausedExpired, target, reply, event, sys, assist, x.shade, x.call, x.map, x.mapOpen)
    }

    private data class Extras(
        val shade: List<RecentNotification>,
        val call: String?,
        val map: MapTarget?,
        val mapOpen: Boolean,
    )

    // --- Settings preview ---

    private val samples = PreviewSamples(context)
    private val preview = MutableStateFlow<Pair<Any, PreviewSample>?>(null)
    /** Opened or closed by a gesture on the sample; null = the sample's default. */
    private val previewOpen = MutableStateFlow<Boolean?>(null)

    /** True while the island's overlay is on screen, so the settings know whether there's a preview to make room for. */
    val overlayAttached = MutableStateFlow(false)

    /**
     * Shows [sample] on the real island while a settings page is open. [owner] identifies the page,
     * so a page that's leaving can't clear the preview the next page just set.
     */
    fun showPreview(owner: Any, sample: PreviewSample) {
        if (preview.value?.second != sample) previewOpen.value = null
        preview.value = owner to sample
    }

    fun clearPreview(owner: Any) {
        if (preview.value?.first === owner) {
            preview.value = null
            previewOpen.value = null
        }
    }

    val uiState: StateFlow<IslandUiState> = combine(
        inputs.map(IslandStateMachine::reduce), preview, previewOpen, settings.settings,
    ) { real, p, open, s ->
        val sample = p?.second
        // What's really happening still wins if it matters more than a sample: a conversation, a call.
        val realWins = sample == null || !s.enabled || real.primary is IslandActivity.Assistant ||
            (real.primary as? IslandActivity.Timer)?.entry?.isRinging == true
        if (realWins) real else samples.state(sample!!, open ?: sample.openByDefault).copy(isPreview = true)
    }.stateIn(scope, SharingStarted.Eagerly, IslandUiState())

    /** Touches on a sample only open and close it; its buttons do nothing. */
    val previewActions: IslandActions = object : IslandActions {
        override fun onGesture(gesture: Gesture) {
            val sample = preview.value?.second ?: return
            val open = previewOpen.value ?: sample.openByDefault
            previewOpen.value = when (settings.settings.value.actionFor(gesture)) {
                GestureAction.TOGGLE_EXPAND -> !open
                GestureAction.EXPAND -> true
                GestureAction.COLLAPSE, GestureAction.DISMISS, GestureAction.OPEN_APP -> false
                else -> open
            }
        }

        override fun expand(activity: IslandActivity) { previewOpen.value = true }
        override fun open(activity: IslandActivity) { previewOpen.value = false }
    }

    init {
        scope.launch { notifications.alerts.collect(::enqueueAlert) }
        scope.launch { notifications.removed.collect(::onNotificationRemoved) }
        scope.launch {
            uiState.collect { if (it.primary == null && expanded.value) collapse() }
        }
        scope.launch {
            expanded.collect { if (it) restartCollapseTimer() else collapseJob?.cancel() }
        }
        scope.launch { media.media.collect(::trackPausedMedia) }
        scope.launch { system.events.collect(::showEvent) }
    }

    // --- System events ---

    private fun SystemEvent.enabledIn(s: IslandSettings) = when (this) {
        is SystemEvent.Charging -> s.eventCharging
        is SystemEvent.LowBattery -> s.eventLowBattery
        is SystemEvent.Ringer -> s.eventRinger
        is SystemEvent.Dnd -> s.eventDnd
        is SystemEvent.Bluetooth -> s.eventBluetooth
        is SystemEvent.Volume -> s.volumeHud
    }

    /** Announces an event for a few seconds; a newer one replaces it. Volume stays while keys are pressed. */
    private fun showEvent(event: SystemEvent) {
        val s = settings.settings.value
        if (!event.enabledIn(s)) return
        // A battery report for the device just announced updates it in place.
        val current = currentEvent.value
        if (event is SystemEvent.Bluetooth && current is SystemEvent.Bluetooth && current.name == event.name &&
            event.battery == null && current.battery != null
        ) return
        currentEvent.value = event
        eventJob?.cancel()
        eventJob = scope.launch {
            delay(if (event is SystemEvent.Volume) VOLUME_VISIBLE_MS else s.eventDurationSec * 1000L)
            currentEvent.value = null
        }
    }

    fun dismissEvent() {
        eventJob?.cancel()
        currentEvent.value = null
    }

    /** Volume keys, when the island replaces the system volume panel. */
    fun adjustVolume(raise: Boolean) = system.adjustVolume(raise)

    override fun setVolume(fraction: Float) {
        val v = currentEvent.value as? SystemEvent.Volume ?: return
        system.setVolume(v.stream, fraction) // re-announces, which also keeps the bar visible
    }

    override fun setTorchLevel(fraction: Float) {
        onInteraction()
        system.setTorchLevel(fraction)
    }

    override fun deactivate(activity: IslandActivity): Boolean {
        when (activity) {
            is IslandActivity.Map -> map.close()
            IslandActivity.Torch -> {
                system.setTorch(false)
                collapse()
            }
            is IslandActivity.Event -> when (val e = activity.event) {
                // Each re-announces itself as "off", which confirms the change.
                is SystemEvent.Dnd -> if (!e.on || !system.setDnd(false)) return false
                is SystemEvent.Ringer -> if (e.mode == RingerMode.NORMAL) return false else system.setRingerNormal()
                else -> return false
            }
            else -> return false
        }
        return true
    }

    // --- Quick tools ---

    fun openQuickTools() {
        dismissEvent()
        expandTo(IslandActivity.TOOLS_ID)
    }

    override fun runTool(tool: QuickTool) {
        onInteraction()
        val global = globalAction
        when (tool) {
            QuickTool.FLASHLIGHT -> system.setTorch(!system.state.value.torchOn)
            QuickTool.RINGER -> system.cycleRinger()
            QuickTool.DND -> if (!system.toggleDnd()) {
                collapse()
                startSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            }
            QuickTool.CAMERA -> {
                collapse()
                runCatching {
                    context.startActivity(
                        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        backgroundStartOptions(),
                    )
                }
            }
            QuickTool.SCREENSHOT -> {
                collapse()
                // Let the island shrink back first so it doesn't cover the screenshot.
                scope.launch {
                    delay(450)
                    global?.invoke(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                }
            }
            QuickTool.LOCK -> { collapse(); global?.invoke(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }
            QuickTool.QUICK_SETTINGS -> { collapse(); global?.invoke(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) }
            QuickTool.POWER -> { collapse(); global?.invoke(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) }
        }
    }

    override fun launchFavorite(pkg: String) {
        collapse()
        launchApp(pkg)
    }

    private fun startSettings(action: String) {
        runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), backgroundStartOptions()) }
    }

    // --- Expand / collapse ---

    private val alertExpanded get() = expanded.value && currentAlert.value != null

    private fun expandTo(target: String?) {
        expandedTarget.value = target
        expanded.value = true
    }

    /** Collapses; an expanded notification is done with at that point and gets dismissed. */
    fun collapse() {
        val wasAlert = alertExpanded
        replying.value = null
        expanded.value = false
        expandedTarget.value = null
        if (wasAlert) dismissAlert()
        // Alerts that came in while something else was open (see expand) get their turn now.
        else if (currentAlert.value == null && alertQueue.isNotEmpty() && alertJob?.isActive != true) restartAlertLoop(showCurrentFirst = false)
    }

    /** A touch landed outside the island window. */
    fun onOutsideTouch() {
        if (assistant.state.value != null) return assistant.onOutsideTouch()
        if (expanded.value) collapse()
    }

    fun onScreenOff() {
        collapse()
        environment.update { it.copy(isLocked = true) }
    }

    fun setLocked(locked: Boolean) = environment.update { it.copy(isLocked = locked) }

    /** Any interaction with the expanded island postpones auto-collapse. */
    override fun onInteraction() {
        if (expanded.value) restartCollapseTimer()
    }

    private fun restartCollapseTimer() {
        collapseJob?.cancel()
        val seconds = settings.settings.value.autoCollapseSec
        if (seconds <= 0) return
        collapseJob = scope.launch {
            delay(seconds * 1000L)
            // Never yank the text field away while someone is typing a reply.
            while (replying.value != null) delay(1000)
            collapse()
        }
    }

    /** For `adb shell dumpsys activity service …IslandAccessibilityService`. */
    fun debugState(): String = buildString {
        val st = uiState.value
        appendLine("mode=${st.mode} primary=${st.primary?.id} secondary=${st.secondary?.id}")
        appendLine("environment=${environment.value}")
        appendLine("expanded=${expanded.value} target=${expandedTarget.value} alert=${currentAlert.value?.key} event=${currentEvent.value}")
        appendLine("pausedMediaExpired=${pausedMediaExpired.value} media=${media.media.value?.let { it.packageName + " playing=" + it.isPlaying }}")
        appendLine("live=${notifications.live.value.map { it.key }}")
        appendLine("system=${system.state.value}")
    }

    // --- Environment, reported by the accessibility service ---

    fun setForegroundPackage(pkg: String?) = environment.update { it.copy(foregroundPackage = pkg) }
    fun setFullscreen(fullscreen: Boolean) = environment.update { it.copy(isFullscreen = fullscreen) }
    fun setLandscape(landscape: Boolean) = environment.update { it.copy(isLandscape = landscape) }

    // --- Paused music ---

    private var lastMediaPackage: String? = null
    private var lastMediaPlaying = false

    /**
     * Paused music stays for the configured time after it *was paused* (so pause → resume from the
     * island works); music that is already paused when first seen counts as expired. Never expires
     * while the island is expanded.
     */
    private fun trackPausedMedia(m: MediaInfo?) {
        when {
            m == null -> {
                pauseJob?.cancel()
                pausedMediaExpired.value = false
            }
            m.isPlaying -> {
                pauseJob?.cancel()
                pausedMediaExpired.value = false
            }
            m.packageName != lastMediaPackage -> pausedMediaExpired.value = true
            lastMediaPlaying -> {
                pauseJob?.cancel()
                pausedMediaExpired.value = false
                pauseJob = scope.launch {
                    delay(settings.settings.value.pausedMusicTimeoutSec * 1000L)
                    while (expanded.value) delay(1000)
                    pausedMediaExpired.value = true
                }
            }
        }
        lastMediaPackage = m?.packageName
        lastMediaPlaying = m?.isPlaying == true
    }

    // --- Alerts ---

    private fun enqueueAlert(entry: NotificationEntry) {
        // Our own reply usually re-posts the conversation; don't pop it right back up.
        repliedAt[entry.key]?.let { if (SystemClock.elapsedRealtime() - it < REPLY_ECHO_MS) return }
        // The panel is open: the new one shows up in its list, no need to pop it over it.
        if (expanded.value && expandedTarget.value == IslandActivity.NOTIFICATIONS_ID) return
        val current = currentAlert.value
        if (current?.key == entry.key) {
            currentAlert.value = entry
            if (!alertExpanded) restartAlertLoop(showCurrentFirst = true)
            return
        }
        alertQueue.removeAll { it.key == entry.key }
        alertQueue.addLast(entry)
        // Someone is reading/answering a notification: queue, don't interrupt.
        if (alertExpanded) return
        if (alertJob?.isActive != true) restartAlertLoop(showCurrentFirst = false)
    }

    private fun restartAlertLoop(showCurrentFirst: Boolean) {
        alertJob?.cancel()
        val first = if (showCurrentFirst) currentAlert.value else null
        alertJob = scope.launch {
            if (expanded.value) collapse()
            var next = first ?: alertQueue.removeFirstOrNull()
            while (next != null) {
                currentAlert.value = next
                delay(settings.settings.value.alertDurationSec * 1000L)
                next = alertQueue.removeFirstOrNull()
            }
            currentAlert.value = null
        }
    }

    fun dismissAlert() {
        val wasExpanded = alertExpanded
        alertJob?.cancel()
        currentAlert.value = null
        replying.value = null
        if (wasExpanded) {
            expanded.value = false
            expandedTarget.value = null
        }
        if (alertQueue.isNotEmpty()) restartAlertLoop(showCurrentFirst = false)
    }

    /** Tapping the preview expands it: the timeout stops until the user collapses it. */
    private fun expandAlert(alert: IslandActivity.Alert) {
        alertJob?.cancel()
        expandTo(alert.id)
    }

    private fun onNotificationRemoved(key: String) {
        if (collapsedCall.value?.substringAfter(':') == key) collapsedCall.value = null
        alertQueue.removeAll { it.key == key }
        if (currentAlert.value?.key == key) dismissAlert()
    }

    // --- Replies ---

    override fun startReply(action: NotificationActionItem) {
        if (action.remoteInput == null) return
        onInteraction()
        replying.value = action
    }

    override fun cancelReply() {
        replying.value = null
        onInteraction()
    }

    override fun sendReply(text: String) {
        val action = replying.value ?: return
        val input = action.remoteInput ?: return
        if (text.isBlank()) return
        val fillIn = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(arrayOf(input), fillIn, Bundle().apply { putCharSequence(input.resultKey, text) })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }
        currentAlert.value?.key?.let { repliedAt[it] = SystemClock.elapsedRealtime() }
        try {
            action.intent.send(context, 0, fillIn, null, null, null, backgroundStartOptions())
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "Reply intent cancelled", e)
        }
        collapse()
    }

    // --- UI actions ---

    override fun onGesture(gesture: Gesture) {
        onInteraction()
        val state = uiState.value
        // While typing, taps/long-presses belong to the text field (cursor, selection), not the island.
        if (state.replying != null) return
        // The assistant: a tap interrupts it (or ends the conversation while it listens); swiping up ends it.
        if (state.primary is IslandActivity.Assistant) {
            when (gesture) {
                Gesture.TAP -> assistant.onTap()
                Gesture.SWIPE_UP -> assistant.stop("swiped up")
                else -> Unit
            }
            return
        }
        val s = settings.settings.value
        // The map, like a call, is open on its own: collapsing sends it to the pill, a long-press closes it.
        if (state.primary is IslandActivity.Map && !state.isPreview) {
            val action = s.actionFor(gesture)
            val folds = action == GestureAction.COLLAPSE || action == GestureAction.DISMISS || action == GestureAction.TOGGLE_EXPAND
            when {
                gesture == Gesture.LONG_PRESS -> return map.close()
                state.mode == IslandMode.EXPANDED && !expanded.value && folds -> return map.setOpen(false)
                state.mode == IslandMode.COMPACT && (action == GestureAction.EXPAND || action == GestureAction.TOGGLE_EXPAND) ->
                    return map.setOpen(true)
            }
        }
        // A route: with "opens the app" set, expanding the pill goes straight to the maps app.
        if (state.primary is IslandActivity.Navigation && state.mode == IslandMode.COMPACT && s.navExpandOpensApp) {
            val action = s.actionFor(gesture)
            if (action == GestureAction.EXPAND || action == GestureAction.TOGGLE_EXPAND) return open(state.primary)
        }
        // A call opens by itself; whatever would collapse an island sends it back to the pill instead.
        val call = (state.primary as? IslandActivity.Timer)?.entry?.takeIf { it.isCall }
        if (call != null && state.mode == IslandMode.EXPANDED && !expanded.value) {
            val action = s.actionFor(gesture)
            if (action == GestureAction.COLLAPSE || action == GestureAction.DISMISS || action == GestureAction.TOGGLE_EXPAND) {
                collapsedCall.value = IslandActivity.callPhaseId(call)
                return
            }
        }
        // The idle pill has nothing to expand; its tap/long-press run their own configurable actions.
        if (state.mode == IslandMode.IDLE) {
            val idle = when (gesture) {
                Gesture.TAP -> s.idleTapAction
                Gesture.LONG_PRESS -> s.idleLongPressAction
                else -> null
            }
            if (idle != null) {
                when {
                    idle == IdleAction.QUICK_TOOLS -> openQuickTools()
                    idle == IdleAction.ASSISTANT -> startAssistant()
                    idle == IdleAction.NOTIFICATIONS -> openNotificationPanel()
                    idle.tool != null -> runTool(idle.tool)
                }
                return
            }
        }
        // Long-pressing an indicator that's on turns it off. Not while expanded: there the flashlight's
        // brightness slider is held, and the panel has its own off button.
        if (gesture == Gesture.LONG_PRESS && (state.mode == IslandMode.COMPACT || state.mode == IslandMode.EVENT)) {
            if (state.primary?.let(::deactivate) == true) return
        }
        // Tapping an announcement just clears it.
        if (state.mode == IslandMode.EVENT && gesture == Gesture.TAP) return dismissEvent()
        // Swiping a notification away deletes it, like in the shade.
        val alert = state.primary as? IslandActivity.Alert
        if (alert != null && s.swipeDeletesNotifications && gesture in DELETE_SWIPES) {
            notifications.cancel?.invoke(alert.entry.key)
            return dismissAlert()
        }
        val action = s.actionFor(gesture)
        val isMedia = state.primary is IslandActivity.Media
        when (action) {
            GestureAction.NONE -> Unit
            GestureAction.TOGGLE_EXPAND -> when (state.mode) {
                IslandMode.ALERT -> (state.primary as? IslandActivity.Alert)?.let(::expandAlert)
                IslandMode.COMPACT -> expandTo(null)
                IslandMode.EXPANDED -> collapse()
                else -> Unit
            }
            GestureAction.EXPAND -> when (state.mode) {
                IslandMode.COMPACT -> expandTo(null)
                IslandMode.ALERT -> (state.primary as? IslandActivity.Alert)?.let(::expandAlert)
                IslandMode.EXPANDED -> state.primary?.let(::open) // tap on the open island: go to the app
                else -> Unit
            }
            GestureAction.COLLAPSE, GestureAction.DISMISS ->
                if (state.mode == IslandMode.ALERT) dismissAlert() else collapse()
            GestureAction.OPEN_APP -> state.primary?.let(::open)
            GestureAction.PLAY_PAUSE -> if (isMedia) playPause()
            GestureAction.NEXT_TRACK -> if (isMedia) next()
            GestureAction.PREVIOUS_TRACK -> if (isMedia) previous()
            GestureAction.QUICK_TOOLS -> openQuickTools()
            GestureAction.QUICK_SETTINGS -> { collapse(); globalAction?.invoke(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) }
            GestureAction.ASSISTANT -> startAssistant()
            GestureAction.NOTIFICATIONS -> openNotificationPanel()
        }
    }

    private fun openNotificationPanel() = expandTo(IslandActivity.NOTIFICATIONS_ID)

    override fun openNotification(item: RecentNotification) {
        val sent = item.contentIntent?.let(::send) ?: false
        if (!sent) launchApp(item.packageName)
        collapse()
    }

    override fun dismissNotification(key: String) {
        onInteraction()
        notifications.cancel?.invoke(key)
    }

    override fun clearNotifications() {
        notifications.shade.value.forEach { notifications.cancel?.invoke(it.key) }
        collapse()
    }

    /**
     * Talks to the assistant, like the side key does. Goes through the assist activity: Android only
     * lets us open the microphone while something of ours is in front.
     */
    private fun startAssistant() {
        if (!context.startFromBackground(Intent(context, AssistActivity::class.java))) Log.w(TAG, "Couldn't start the assistant")
    }

    /** Hands the target to the phone's maps app for a real walking route (Google Maps if there, else any). */
    private fun openWalkingRoute(target: MapTarget) {
        val p = target.point
        val google = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=${p.lat},${p.lon}&mode=w"))
            .setPackage("com.google.android.apps.maps")
        if (!context.startFromBackground(google)) {
            context.startFromBackground(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${android.net.Uri.encode(target.label)})")))
        }
    }

    override fun expand(activity: IslandActivity) {
        if (activity is IslandActivity.Map) return map.setOpen(true)
        if (activity is IslandActivity.Alert) return expandAlert(activity)
        // The bubble beside a notification preview was tapped: the preview makes way for what they
        // picked (it's still in the shade). Queued alerts wait until the island closes again.
        if (currentAlert.value != null) {
            alertJob?.cancel()
            currentAlert.value = null
            replying.value = null
        }
        expandTo(activity.id)
    }

    override fun playPause() { onInteraction(); media.playPause() }
    override fun next() { onInteraction(); media.next() }
    override fun previous() { onInteraction(); media.previous() }
    override fun seekTo(positionMs: Long) { onInteraction(); media.seekTo(positionMs) }
    override fun customAction(action: MediaCustomAction) { onInteraction(); media.sendCustomAction(action) }
    override fun toggleShuffle() { onInteraction(); media.toggleShuffle() }
    override fun cycleRepeat() { onInteraction(); media.cycleRepeat() }

    override fun open(activity: IslandActivity) {
        val intent: PendingIntent? = when (activity) {
            is IslandActivity.Media -> activity.info.sessionActivity
            is IslandActivity.Timer -> activity.entry.contentIntent
            is IslandActivity.Progress -> activity.entry.contentIntent
            is IslandActivity.Navigation -> activity.entry.contentIntent
            is IslandActivity.Alert -> activity.entry.contentIntent
            // Nothing to open for these: just close.
            is IslandActivity.Event, IslandActivity.Torch, IslandActivity.Tools, is IslandActivity.Notifications -> return collapse()
            is IslandActivity.Map -> return openWalkingRoute(activity.target)
            is IslandActivity.Assistant -> return assistant.stop("opened")
        }
        val sent = intent?.let(::send) ?: false
        if (!sent) launchApp(activity.packageName)
        collapse()
        if (activity is IslandActivity.Alert) dismissAlert()
    }

    private val calls = CallControl(context)

    override fun sendAction(action: NotificationActionItem) {
        if (action.isReply) return startReply(action)
        onInteraction()
        val role = action.callRole
        if (role != null) {
            val call = notifications.live.value.firstOrNull { e -> e.actions.any { it.intent == action.intent } }
            Log.d(TAG, "call button $role (\"${action.title}\") of ${call?.packageName}, activity=${action.intent.isActivityCompat()}")
            if (call != null && settings.settings.value.callsViaTelecom && calls.perform(role, call.packageName)) return
        }
        send(action.intent)
        // A handled notification action (mark as read, snooze …) finishes the notification.
        if (alertExpanded) collapse()
    }

    private fun PendingIntent.isActivityCompat(): Boolean? = if (Build.VERSION.SDK_INT >= 31) isActivity else null

    private fun send(intent: PendingIntent): Boolean = try {
        intent.send(context, 0, null, null, null, null, backgroundStartOptions())
        true
    } catch (e: PendingIntent.CanceledException) {
        Log.w(TAG, "PendingIntent cancelled", e)
        false
    }

    private fun launchApp(pkg: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch, backgroundStartOptions()) }
            .onFailure { Log.w(TAG, "Could not launch $pkg", it) }
    }

    /** Lets the sender's (our) background-activity-start privilege, granted via the a11y service, apply. */
    @Suppress("DEPRECATION")
    private fun backgroundStartOptions() = when {
        Build.VERSION.SDK_INT >= 36 -> ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
            .toBundle()
        Build.VERSION.SDK_INT >= 34 -> ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
        else -> null
    }

    private companion object {
        const val TAG = "IslandController"
        const val REPLY_ECHO_MS = 5_000L
        const val VOLUME_VISIBLE_MS = 1_600L
        val DELETE_SWIPES = setOf(Gesture.SWIPE_LEFT, Gesture.SWIPE_RIGHT, Gesture.SWIPE_UP)
    }
}
