package com.titus.dynamicisland.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.titus.dynamicisland.AppGraph
import com.titus.dynamicisland.data.settings.IslandSettings
import com.titus.dynamicisland.ui.island.CutoutGeometry
import com.titus.dynamicisland.ui.island.Island
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The accessibility-overlay windows hosting the island.
 *
 * The drawing window is wider than the island (see Island's window sizing) and normally not
 * touchable. Touches go to a second, invisible window sized to the island itself, which forwards
 * them to the drawing window. So everything beside the island, like a pull on the status bar,
 * reaches the system and apps underneath. Resizing the invisible window every frame is harmless,
 * since there's nothing on it that could twitch.
 */
class OverlayWindow(private val context: Context, private val graph: AppGraph) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayLifecycleOwner()
    private val density = context.resources.displayMetrics.density
    private var attached = false
    private var lastSize = IntSize(-1, -1)
    private var lastTouch = IntSize(-1, -1)
    /** While a reply is typed, the drawing window takes all touches and focus itself, as it always used to. */
    private var replying = false

    private fun overlayParams(title: String) = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        this.title = title
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private val params = overlayParams("DynamicIsland")
    private val touchParams = overlayParams("DynamicIsland touch")

    private val cutout = MutableStateFlow<CutoutGeometry?>(null)

    private val compose = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val state by graph.controller.uiState.collectAsStateWithLifecycle()
            val settings by graph.settings.settings.collectAsStateWithLifecycle()
            val cutout by cutout.collectAsStateWithLifecycle()
            // The window is wider than the island (see Island's window sizing). Its transparent sides only
            // take touches while a reply is typed (otherwise they pass through, see touchView). Then they
            // count as "outside": a tap collapses, a swipe down still opens the notification shade.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { graph.controller.onOutsideTouch() } }
                    .pointerInput(Unit) {
                        var pulled = 0f
                        detectVerticalDragGestures(onDragStart = { pulled = 0f }) { change, dy ->
                            pulled += dy
                            if (pulled > 24.dp.toPx()) {
                                change.consume()
                                pulled = Float.NEGATIVE_INFINITY // once per gesture
                                (context as? AccessibilityService)?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                            }
                        }
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                val actions = if (state.isPreview) graph.controller.previewActions else graph.controller
                Island(state, settings, actions, cutout = cutout, onWindowSize = ::resize, onTouchSize = ::resizeTouch)
            }
        }
    }

    /** Root view; catches ACTION_OUTSIDE (a tap anywhere else on screen) to collapse the island. */
    private val view = object : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            // Only reachable while replying (the window is otherwise not focusable): back cancels the reply.
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) graph.controller.cancelReply()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                graph.controller.onOutsideTouch()
                return false
            }
            return super.dispatchTouchEvent(ev)
        }
    }.apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Inset changes are only a *trigger*: this overlay's own insets never contain the status bar
            // (it sits above it), so the actual check asks the display-level metrics.
            setOnApplyWindowInsetsListener { v, insets ->
                recheckFullscreen()
                v.onApplyWindowInsets(insets)
            }
        }
    }

    /**
     * The invisible window over the island; hands each touch to the drawing window at the same spot
     * on screen. Measured from where both windows actually are, so a resize in flight (applied a
     * frame later by the window manager) can't shift a drag.
     */
    private val touchView = object : android.view.View(context) {
        private val at = IntArray(2)

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                graph.controller.onOutsideTouch()
                return false
            }
            view.getLocationOnScreen(at)
            val forwarded = MotionEvent.obtain(ev)
            forwarded.offsetLocation(ev.rawX - ev.x - at[0], ev.rawY - ev.y - at[1])
            return try { view.dispatchTouchEvent(forwarded) } finally { forwarded.recycle() }
        }
    }

    private var fullscreen = false

    /** Status bar hidden for the app in front => something is fullscreen (video, game). */
    private fun checkFullscreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !attached) return
        val statusVisible = wm.currentWindowMetrics.windowInsets.isVisible(WindowInsets.Type.statusBars())
        fullscreen = !statusVisible
        graph.controller.setFullscreen(fullscreen)
    }

    private val checkRunnable = Runnable { checkFullscreen() }

    /** Checks now and again shortly after, since the status bar animates in and out. */
    fun recheckFullscreen() {
        checkFullscreen()
        view.removeCallbacks(checkRunnable)
        view.postDelayed(checkRunnable, 400)
        view.postDelayed({ checkFullscreen() }, 1200)
    }

    fun debugState(): String = buildString {
        appendLine("window=${params.width}x${params.height} @(${params.x},${params.y}) flags=0x${Integer.toHexString(params.flags)}")
        appendLine("touch=${touchParams.width}x${touchParams.height} flags=0x${Integer.toHexString(touchParams.flags)}")
        val insets = view.rootWindowInsets
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets?.let {
                appendLine("rootInsets statusVisible=${it.isVisible(WindowInsets.Type.statusBars())} " +
                    "statusTop=${it.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top} cutout=${it.displayCutout?.boundingRectTop}")
            } ?: appendLine("rootInsets=null")
            val m = wm.currentWindowMetrics.windowInsets
            appendLine("metrics statusVisible=${m.isVisible(WindowInsets.Type.statusBars())} " +
                "statusTop=${m.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top} cutout=${m.displayCutout?.boundingRectTop}")
            val max = wm.maximumWindowMetrics.windowInsets
            appendLine("maxMetrics cutout=${max.displayCutout?.boundingRectTop} statusVisible=${max.isVisible(WindowInsets.Type.statusBars())}")
        }
    }

    fun attach() {
        if (attached) return
        owner.create()
        reposition(graph.settings.settings.value)
        wm.addView(view, params)
        wm.addView(touchView, touchParams)
        attached = true
        graph.controller.overlayAttached.value = true
        owner.resume()
        recheckFullscreen()
        owner.lifecycleScope.launch {
            // Leaving fullscreen doesn't always produce an event we see; poll only while hidden for it.
            while (true) {
                delay(1500)
                if (fullscreen) checkFullscreen()
            }
        }
        owner.lifecycleScope.launch {
            graph.controller.uiState.map { it.replying != null }.distinctUntilChanged().collect(::setTextInput)
        }
        owner.lifecycleScope.launch {
            graph.settings.settings
                .map { Triple(it.offsetXDp, it.offsetYDp, it.autoFitCutout to it.heightDp) }
                .distinctUntilChanged()
                .collect { reposition(graph.settings.settings.value) }
        }
    }

    fun detach() {
        if (!attached) return
        attached = false
        graph.controller.overlayAttached.value = false
        owner.destroy()
        runCatching { wm.removeView(touchView) }
        runCatching { wm.removeView(view) }
    }

    // Window metrics update after the config callback, so wait a frame.
    fun onConfigurationChanged() { view.post { reposition(graph.settings.settings.value) } }

    private fun reposition(s: IslandSettings) {
        val heightPx = (s.heightDp * density).roundToInt()
        var centerXOffset = 0
        var centerY: Int? = null
        var statusBarHeight = statusBarHeightFallback()
        var screenWidth = context.resources.displayMetrics.widthPixels
        var cutoutRect: Rect? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            screenWidth = metrics.bounds.width()
            statusBarHeight = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
            cutoutRect = metrics.windowInsets.displayCutout?.boundingRectTop?.takeUnless { it.isEmpty }
            if (s.autoFitCutout && cutoutRect != null) {
                centerXOffset = cutoutRect.centerX() - screenWidth / 2
                centerY = cutoutRect.centerY()
            }
        }
        val anchorY = centerY ?: (statusBarHeight / 2)
        params.x = centerXOffset + (s.offsetXDp * density).roundToInt()
        params.y = (anchorY - heightPx / 2 + (s.offsetYDp * density).roundToInt()).coerceAtLeast(0)
        cutout.value = cutoutRect?.let { r ->
            val islandCenterX = screenWidth / 2 + params.x
            CutoutGeometry(
                centerOffsetX = ((r.centerX() - islandCenterX) / density).dp,
                width = (r.width() / density).dp,
                top = ((r.top - params.y) / density).dp,
                bottom = ((r.bottom - params.y) / density).dp,
            ).takeIf { it.bottom > 0.dp } // cutout entirely above the island: nothing to avoid
        }
        touchParams.x = params.x
        touchParams.y = params.y
        if (attached) {
            wm.updateViewLayout(view, params)
            wm.updateViewLayout(touchView, touchParams)
        }
    }

    /**
     * The overlay is normally not focusable, so it never steals keys or the keyboard from apps.
     * Only while a reply is being typed does it take focus so the IME can attach.
     */
    private fun setTextInput(active: Boolean) {
        if (!attached) return
        params.flags = if (active) {
            (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        params.softInputMode = if (active) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        if (!active) {
            context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
        replying = active
        updateTouchable()
        wm.updateViewLayout(view, params)
        wm.updateViewLayout(touchView, touchParams)
    }

    private fun resize(size: IntSize) {
        if (size == lastSize || !attached) return
        lastSize = size
        val hidden = size.width <= 0 || size.height <= 0
        params.width = if (hidden) 1 else size.width
        params.height = if (hidden) 1 else size.height
        updateTouchable()
        wm.updateViewLayout(view, params)
    }

    private fun resizeTouch(size: IntSize) {
        if (size == lastTouch || !attached) return
        lastTouch = size
        val hidden = size.width <= 0 || size.height <= 0
        touchParams.width = if (hidden) 1 else size.width
        touchParams.height = if (hidden) 1 else size.height
        updateTouchable()
        wm.updateViewLayout(touchView, touchParams)
    }

    /**
     * Exactly one window takes touches (or none, while hidden). Only that one watches for outside
     * touches too, or a tap on the island would count as "outside" for the other and collapse it.
     */
    private fun updateTouchable() {
        val shown = lastSize.width > 0 && lastSize.height > 0
        params.setTouchable(shown && replying)
        touchParams.setTouchable(shown && !replying && lastTouch.width > 0 && lastTouch.height > 0)
    }

    private fun WindowManager.LayoutParams.setTouchable(on: Boolean) {
        flags = flags.withFlag(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, !on)
            .withFlag(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, on)
    }

    private fun Int.withFlag(flag: Int, on: Boolean) = if (on) this or flag else this and flag.inv()

    @Suppress("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeightFallback(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else (24 * density).roundToInt()
    }
}
