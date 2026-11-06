package com.titus.dynamicisland.ui.island

import android.content.Context
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Some apps (notably Samsung Clock) put the live time only inside a custom RemoteViews.
 * We inflate it and either host its Chronometer directly — it ticks, pauses and formats itself
 * exactly like the source app intends — or fall back to a time-looking label from it.
 */
private sealed interface RemoteTime {
    data object Live : RemoteTime
    data class Static(val text: String) : RemoteTime
}

private val TIME_PATTERN = Regex("""^-?\d{1,2}(:\d{2}){1,2}$""")

fun looksLikeTime(text: String?): Boolean = text != null && TIME_PATTERN.matches(text.trim())

private fun inflate(context: Context, views: RemoteViews): View? =
    runCatching { views.apply(context, FrameLayout(context)) }.getOrNull()

private fun View.descendants(): Sequence<View> = sequence {
    yield(this@descendants)
    if (this@descendants is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).descendants())
}

private fun View.visibleChronometer(): Chronometer? =
    descendants().filterIsInstance<Chronometer>().firstOrNull { it.visibility == View.VISIBLE && it.isPlausible() }

/**
 * Some apps leave a chronometer in their layout before it means anything: Samsung's ringing call has one
 * based at 0, which counts up from boot ("240:13:07"). Anything claiming more than a day is that.
 */
private fun Chronometer.isPlausible(): Boolean {
    if (isCountDown) return true
    val elapsed = SystemClock.elapsedRealtime() - base
    return base > 0 && elapsed in -MAX_SKEW_MS..MAX_ELAPSED_MS
}

private const val MAX_ELAPSED_MS = 24 * 60 * 60_000L
private const val MAX_SKEW_MS = 5_000L

private fun probe(context: Context, candidates: List<RemoteViews>): Pair<RemoteViews, RemoteTime>? {
    for (rv in candidates) {
        val root = inflate(context, rv) ?: continue
        if (root.visibleChronometer() != null) return rv to RemoteTime.Live
        root.descendants().filterIsInstance<TextView>()
            .filterNot { it is Chronometer } // a live one was taken above; the rest show nonsense
            .map { it.text?.toString().orEmpty() }
            .firstOrNull(::looksLikeTime)
            ?.let { return rv to RemoteTime.Static(it) }
    }
    return null
}

/** Inflates a fresh, parent-less Chronometer from [views], styled to match the island. */
private fun liveChronometer(context: Context, views: RemoteViews): View {
    val chrono = inflate(context, views)?.visibleChronometer() ?: return TextView(context)
    (chrono.parent as? ViewGroup)?.removeView(chrono)
    chrono.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    chrono.setPadding(0, 0, 0, 0)
    chrono.gravity = Gravity.CENTER_VERTICAL
    chrono.includeFontPadding = false
    chrono.maxLines = 1
    chrono.background = null
    return chrono
}

/**
 * Shows the time of a timer/stopwatch notification from its RemoteViews if possible;
 * otherwise renders [fallback].
 */
@Composable
fun RemoteTimeOr(
    views: List<RemoteViews>,
    color: Color,
    fontSize: TextUnit,
    weight: FontWeight,
    fallback: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val found = remember(views) { if (views.isEmpty()) null else probe(context, views) }
    when (val time = found?.second) {
        null -> fallback()
        is RemoteTime.Static -> Text(
            time.text, color = color, fontSize = fontSize, fontWeight = weight, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Clip,
        )
        RemoteTime.Live -> key(found.first) {
            AndroidView(
                factory = { ctx -> liveChronometer(ctx, found.first) },
                update = { v ->
                    (v as? TextView)?.apply {
                        setTextColor(color.toArgb())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.value)
                        typeface = Typeface.create(Typeface.MONOSPACE, if (weight >= FontWeight.SemiBold) Typeface.BOLD else Typeface.NORMAL)
                    }
                },
                modifier = Modifier,
            )
        }
    }
}
