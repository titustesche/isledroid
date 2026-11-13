package com.titus.dynamicisland.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The screen, through the accessibility service — only when the assistant asks, never in the
 * background. Reading gives every element it could act on a number ("[button #3] Send"), and the
 * actions take those numbers; they always refer to the last reading. Our own windows (the island,
 * the assist screen) are never read or touched, and password fields are neither read nor typed into.
 */
object ScreenReader {
    @Volatile var service: AccessibilityService? = null

    class Screen(val packageName: String, val text: String)

    /** Elements from the last reading, by number. */
    @Volatile private var elements: List<AccessibilityNodeInfo> = emptyList()

    /**
     * The app the user is looking at, as text: one line per readable element, roles in brackets, numbers when
     * [numbered], and each element's centre in screen pixels ("@540,1210") when [positions].
     */
    fun read(maxChars: Int = 6000, numbered: Boolean = false, positions: Boolean = false): Screen? {
        val s = service ?: return null
        val root = appRoot(s) ?: return null
        val out = StringBuilder()
        val found = ArrayList<AccessibilityNodeInfo>()
        walk(root, out, maxChars, 0, if (numbered) found else null, positions, merged = false)
        elements = found
        return Screen(root.packageName?.toString().orEmpty(), out.toString().trim())
    }

    /**
     * A picture of the whole display as JPEG, its longer edge at most [maxEdge] pixels: for what apps draw without
     * text (maps, route times, images, games). Android 11 and later; blocks up to a few seconds.
     */
    fun screenshot(maxEdge: Int, quality: Int = 80): ByteArray {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw ActionException("Screenshots need Android 11 or later.")
        val s = service ?: throw ActionException("The island's accessibility service isn't connected.")
        val latch = CountDownLatch(1)
        var result: AccessibilityService.ScreenshotResult? = null
        var error: Int? = null
        // Not the main executor: the island's main thread may be busy (or waiting), and the result would never arrive.
        s.takeScreenshot(Display.DEFAULT_DISPLAY, { callbacks.post(it) }, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) { result = r; latch.countDown() }
            override fun onFailure(code: Int) { error = code; latch.countDown() }
        })
        if (!latch.await(5, TimeUnit.SECONDS)) throw ActionException("The screenshot took too long.")
        val shot = result ?: throw ActionException(
            when (error) {
                AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "This app blocks screenshots."
                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Too soon after the last screenshot; try again."
                else -> "The screenshot failed ($error)."
            },
        )
        val buffer = shot.hardwareBuffer
        try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, shot.colorSpace)
                ?: throw ActionException("The screenshot couldn't be read.")
            val full = hardware.copy(Bitmap.Config.ARGB_8888, false)
            hardware.recycle()
            val scale = (maxEdge.toFloat() / maxOf(full.width, full.height)).coerceAtMost(1f)
            val small = if (scale < 1f) {
                Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true).also { full.recycle() }
            } else full
            return ByteArrayOutputStream().use { out ->
                small.compress(Bitmap.CompressFormat.JPEG, quality, out)
                small.recycle()
                out.toByteArray()
            }
        } finally {
            buffer.close()
        }
    }

    /** The app in front (not this one), or null when unknown. */
    fun foregroundPackage(): String? = service?.let(::appRoot)?.packageName?.toString()

    /** The whole display in pixels, the space positions and [tapAt] use. */
    fun screenSize(): Pair<Int, Int>? {
        val s = service ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = s.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            return b.width() to b.height()
        }
        val m = s.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private fun appRoot(s: AccessibilityService): AccessibilityNodeInfo? {
        val own = s.packageName
        val window = runCatching { s.windows }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .firstOrNull { it.root?.packageName?.toString()?.let { p -> p != own } == true }
        return window?.root ?: s.rootInActiveWindow?.takeIf { it.packageName?.toString() != own }
    }

    /** Own text, else the description. */
    private fun ownLabel(n: AccessibilityNodeInfo): String {
        val text = n.text?.toString()?.trim().orEmpty()
        val desc = n.contentDescription?.toString()?.trim().orEmpty()
        return listOf(text, desc.takeIf { it != text }).filter { !it.isNullOrEmpty() }.joinToString(" — ")
    }

    /**
     * What a button without a label of its own says: the text inside it, the way TalkBack reads it. Many apps
     * (Compose, Flutter, React Native) put the text in children and leave the clickable container blank.
     * Clickable children are skipped; they're buttons of their own.
     */
    private fun innerLabel(n: AccessibilityNodeInfo, max: Int = 120): String {
        val parts = ArrayList<String>()
        fun collect(c: AccessibilityNodeInfo, depth: Int) {
            if (depth > 12 || parts.sumOf { it.length } >= max || !c.isVisibleToUser || c.isPassword) return
            ownLabel(c).takeIf { it.isNotEmpty() && parts.none { p -> it in p } }?.let { new ->
                parts.removeAll { p -> p in new }
                parts += new
            }
            for (i in 0 until c.childCount) c.getChild(i)?.takeIf { !it.isClickable }?.let { collect(it, depth + 1) }
        }
        for (i in 0 until n.childCount) n.getChild(i)?.takeIf { !it.isClickable }?.let { collect(it, 1) }
        return parts.joinToString(" — ").take(max)
    }

    private fun walk(
        n: AccessibilityNodeInfo, out: StringBuilder, max: Int, depth: Int,
        found: MutableList<AccessibilityNodeInfo>?, positions: Boolean, merged: Boolean,
    ) {
        if (out.length >= max || depth > 60 || !n.isVisibleToUser) return
        val actionable = !n.isPassword && (n.isClickable || n.isEditable || n.isCheckable || n.isScrollable)
        var label = ownLabel(n)
        // A blank button takes its children's text; they're then not repeated as lines of their own.
        val absorbs = label.isEmpty() && actionable && n.isClickable && !n.isEditable
        if (absorbs) label = innerLabel(n)
        // A field's placeholder is often a child (Compose) rather than its hint.
        var hint = if (n.isEditable) n.hintText?.toString()?.takeIf { it.isNotBlank() && it != n.text?.toString() } else null
        val placeholder = n.isEditable && !n.isPassword && label.isEmpty() && hint == null
        if (placeholder) hint = innerLabel(n).ifEmpty { null }
        val role = when {
            n.isPassword -> null // never read passwords
            n.isEditable -> "field"
            n.isCheckable -> if (n.isChecked) "on" else "off"
            n.isClickable && label.isNotEmpty() -> "button"
            n.isScrollable -> "list"
            n.isHeading -> "heading"
            else -> ""
        }
        // Inside a button that already said this text, only things that act get a line.
        if (role != null && !(merged && !actionable)) {
            // Fields always get a number, empty ones too: typing is what they're for.
            val number = if (found != null && actionable && (label.isNotEmpty() || hint != null || n.isScrollable || n.isEditable)) {
                found += n
                found.size
            } else null
            if (label.isNotEmpty() || hint != null || number != null) {
                if (role.isNotEmpty() || number != null) {
                    out.append('[').append(role.ifEmpty { "item" })
                    if (number != null) out.append(" #").append(number)
                    out.append("] ")
                }
                out.append(label)
                if (hint != null) out.append(" (").append(hint).append(')')
                if (positions) {
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    out.append(" @").append(r.centerX()).append(',').append(r.centerY())
                }
                out.append('\n')
            }
        }
        val inside = merged || (absorbs && label.isNotEmpty()) || (placeholder && hint != null)
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, out, max, depth + 1, found, positions, inside) }
    }

    // ---------------- Acting ----------------

    /**
     * Waits up to [timeoutMs] for [pkg] to be in front showing a button with one of [viewIds] (or, failing
     * that, labelled one of [labels]) and clicks it. For sending a message an app opened with the text
     * typed in. Blocks; call off the main thread.
     */
    fun clickWhenShown(pkg: String, viewIds: List<String>, labels: List<String>, timeoutMs: Long): Boolean {
        val s = service ?: return false
        val until = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < until) {
            val root = appRoot(s)?.takeIf { it.packageName?.toString() == pkg }
            if (root != null) {
                val byId = viewIds.asSequence().flatMap { id -> root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").asSequence() }
                val byLabel = sequence { collectAll(root, 0) }.filter { n ->
                    val label = n.contentDescription?.toString() ?: n.text?.toString() ?: return@filter false
                    labels.any { it.equals(label.trim(), ignoreCase = true) }
                }
                val target = (byId + byLabel).firstOrNull { it.isVisibleToUser && it.isEnabled }
                if (target != null) {
                    var clickable: AccessibilityNodeInfo? = target
                    while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                    if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
                }
            }
            Thread.sleep(250)
        }
        return false
    }

    private suspend fun SequenceScope<AccessibilityNodeInfo>.collectAll(n: AccessibilityNodeInfo, depth: Int) {
        if (depth > 60) return
        yield(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { collectAll(it, depth + 1) }
    }

    class ActionException(message: String) : Exception(message)

    private fun element(number: Int): AccessibilityNodeInfo {
        val n = elements.getOrNull(number - 1) ?: throw ActionException("There's no element #$number. Read the screen again.")
        if (!n.refresh()) throw ActionException("Element #$number is gone; the screen changed. Read it again.")
        if (n.packageName?.toString() == service?.packageName) throw ActionException("Not allowed in this app.")
        if (n.isPassword) throw ActionException("Password fields are off limits.")
        return n
    }

    /** What the element is, for confirmations: "Send" (button). */
    fun describe(number: Int): String {
        val n = elements.getOrNull(number - 1) ?: return "element #$number"
        val label = ownLabel(n).ifEmpty { if (n.isClickable && !n.isEditable) innerLabel(n) else "" }
            .ifEmpty { n.hintText?.toString().orEmpty() }.ifEmpty { "element #$number" }
        return label.take(40)
    }

    fun tap(number: Int) {
        val n = element(number)
        // The element itself or the nearest parent that takes clicks; if none, a real tap at its centre.
        var target: AccessibilityNodeInfo? = n
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
        val r = Rect().also { n.getBoundsInScreen(it) }
        if (!gesture(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 60)) throw ActionException("The tap didn't go through.")
    }

    /** The deepest element of the app under a point, labelled for confirmations; null when there's nothing readable. */
    fun describeAt(x: Int, y: Int): String? {
        val root = service?.let(::appRoot) ?: return null
        val r = Rect()
        fun deepest(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > 60 || !n.isVisibleToUser) return null
            n.getBoundsInScreen(r)
            if (!r.contains(x, y)) return null
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { deepest(it, depth + 1) }?.let { return it }
            return n
        }
        // The labelled thing the tap lands on: the hit itself, or the nearest parent that says something.
        var n = deepest(root, 0)
        while (n != null) {
            if (n.isPassword) return "a password field"
            val label = ownLabel(n).ifEmpty { if (n.isClickable) innerLabel(n) else "" }
            if (label.isNotEmpty()) return label.take(40)
            n = n.parent
        }
        return null
    }

    /**
     * A real finger on the screen at [x],[y] (pixels), for apps whose buttons the accessibility tree doesn't show.
     * Held for [holdMs]; long enough is a long press. Refused where our own windows (the island) are on top.
     */
    fun tapAt(x: Int, y: Int, holdMs: Long) {
        val s = service ?: throw ActionException("Not connected.")
        val (w, h) = screenSize() ?: throw ActionException("Not connected.")
        if (x !in 0 until w || y !in 0 until h) throw ActionException("$x,$y is off the screen; it is $w × $h pixels.")
        if (islandAt(s, x, y)) throw ActionException("That spot is covered by the island; not allowed.")
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        if (!gesture(path, holdMs.coerceIn(1, 5000))) throw ActionException("The tap didn't go through.")
    }

    /**
     * Whether one of our windows would take a touch there. Only where it's touchable: the island draws in a
     * screen-wide window that lets touches through and takes them in a small one over the pill.
     */
    private fun islandAt(s: AccessibilityService, x: Int, y: Int): Boolean {
        val r = Rect()
        val (w, _) = screenSize() ?: (0 to 0)
        return runCatching { s.windows }.getOrNull().orEmpty()
            .filter { it.root?.packageName?.toString() == s.packageName }
            .any { win ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Region().also(win::getRegionInScreen).contains(x, y)
                } else {
                    // No touchable region before Android 13: a window as wide as the screen is the drawing one.
                    win.getBoundsInScreen(r)
                    r.contains(x, y) && r.width() < w
                }
            }
    }

    fun type(number: Int, text: String, submit: Boolean) {
        val n = element(number).let { if (it.isEditable) it else fieldBehind(it) }
            ?: throw ActionException("#$number isn't a text field, and tapping it didn't open one. Read the screen again.")
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw ActionException("Couldn't type into #$number.")
        if (submit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
    }

    /**
     * Many apps' search bars (Play Store, Lieferando …) are buttons that open the real field. Taps [n] and
     * waits for the field that gets the keyboard focus; null if none comes up.
     */
    private fun fieldBehind(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!n.isClickable && generateSequence(n.parent) { it.parent }.none { it.isClickable }) return null
        tap(elements.indexOf(n) + 1)
        val s = service ?: return null
        repeat(10) {
            Thread.sleep(150)
            val root = appRoot(s) ?: return@repeat
            // The focused field, else the only kind of field a search screen has: the first one.
            val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: firstField(root)
            if (field != null && !field.isPassword) return field
        }
        return null
    }

    private fun firstField(n: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 60 || !n.isVisibleToUser) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { firstField(it, depth + 1) }?.let { return it }
        return null
    }

    /** Scrolls the element (a list) or, without one, the first scrollable thing on screen; else a swipe. */
    fun scroll(number: Int?, down: Boolean) {
        val s = service ?: throw ActionException("Not connected.")
        val n = number?.let { element(it) } ?: firstScrollable(appRoot(s))
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (n?.performAction(action) == true) return
        val m = s.resources.displayMetrics
        val x = m.widthPixels / 2f
        val (from, to) = if (down) m.heightPixels * 0.7f to m.heightPixels * 0.3f else m.heightPixels * 0.3f to m.heightPixels * 0.7f
        if (!gesture(Path().apply { moveTo(x, from); lineTo(x, to) }, 300)) throw ActionException("Couldn't scroll.")
    }

    private fun firstScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) firstScrollable(n.getChild(i))?.let { return it }
        return null
    }

    fun global(action: Int) {
        if (service?.performGlobalAction(action) != true) throw ActionException("The phone didn't do it.")
    }

    /** Gesture results arrive here, off the main thread, for callers that wait on another thread. */
    private val callbacks by lazy { Handler(HandlerThread("gestures").apply { start() }.looper) }

    private fun gesture(path: Path, durationMs: Long): Boolean {
        val s = service ?: return false
        val latch = CountDownLatch(1)
        var ok = false
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val dispatched = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, callbacks)
        if (!dispatched) return false
        // On the main thread, waiting would deadlock: the gesture also reaches the island's own window (as an
        // outside touch), and Android holds the gesture's end until that window, i.e. this thread, has taken it.
        // Accepted is good enough there; the step delay that follows covers the rest.
        if (Looper.myLooper() == Looper.getMainLooper()) return true
        latch.await(durationMs + 2000, TimeUnit.MILLISECONDS)
        return ok
    }
}
