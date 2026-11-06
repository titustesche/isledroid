package com.titus.dynamicisland.ui.island

import android.graphics.Paint
import android.graphics.Typeface
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.titus.dynamicisland.R
import kotlin.math.floor
import kotlin.math.roundToInt

/** Dark to bright. */
private const val RAMP = " .:-=+*#%@"

/**
 * Slowly drifting ASCII noise (the design canvas's "ASCII noise"): three octaves of value noise,
 * each moving through time at its own rate, mapped onto [RAMP]. [fadeStart] fades it out from
 * there to the bottom, like the assistant's soft edge. With animations turned off in the system it stands still.
 */
@Composable
fun AsciiNoise(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    opacity: Float = 0.35f,
    fontSize: TextUnit = 9.sp,
    scale: Dp = 220.dp,
    speed: Float = 0.15f,
    fadeStart: Float? = null,
) {
    val context = LocalContext.current
    val animate = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = runCatching { ResourcesCompat.getFont(context, R.font.geist_mono) }.getOrNull() ?: Typeface.MONOSPACE
        }
    }
    val time = remember { mutableFloatStateOf(0f) }
    if (animate) {
        LaunchedEffect(speed) {
            var last = withFrameNanos { it }
            var acc = 0L
            while (true) {
                withFrameNanos { now ->
                    val dt = now - last
                    last = now
                    acc += dt
                    // ~20 redraws a second is plenty for something drifting this slowly.
                    if (acc >= 50_000_000L) {
                        time.floatValue += (acc / 1e9f).coerceAtMost(0.1f) * speed
                        acc = 0L
                    }
                }
            }
        }
    }
    val line = remember { StringBuilder() }
    Canvas(
        modifier.then(
            if (fadeStart == null) Modifier else Modifier
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val start = fadeStart.coerceIn(0f, 0.95f)
                    drawRect(Brush.verticalGradient(0f to Color.Black, start to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
        ),
    ) {
        val z = time.floatValue
        paint.textSize = fontSize.toPx()
        paint.color = color.copy(alpha = opacity).toArgb()
        val cw = paint.measureText("M").coerceAtLeast(1f)
        val ch = (paint.textSize * 1.2f).roundToInt().coerceAtLeast(1)
        val cols = kotlin.math.ceil(size.width / cw).toInt()
        val rows = kotlin.math.ceil(size.height / ch).toInt()
        val f = 1f / scale.toPx()
        val top = RAMP.length - 1
        val ascent = -paint.ascent()
        drawIntoCanvas { canvas ->
            for (r in 0 until rows) {
                val y = (r + 0.5f) * ch * f
                line.setLength(0)
                for (c in 0 until cols) {
                    val x = (c + 0.5f) * cw * f
                    var n = noise(x, y, z) * 0.5f +
                        noise(x * 2.03f + 17.1f, y * 2.03f + 4.7f, z * 1.6f + 11f) * 0.25f +
                        noise(x * 4.11f + 3.3f, y * 4.11f + 29.9f, z * 2.4f + 23f) * 0.125f
                    n = ((n / 0.875f - 0.2f) / 0.6f).coerceIn(0f, 1f) // stretch to the full 0..1
                    line.append(RAMP[(n * top).roundToInt()])
                }
                canvas.nativeCanvas.drawText(line, 0, line.length, 0f, r * ch + ascent, paint)
            }
        }
    }
}

private fun hash(x: Int, y: Int, z: Int): Float {
    var h = (x * 374761393) xor (y * 668265263) xor (z * 1440662683)
    h = (h xor (h ushr 13)) * 1274126177
    return ((h xor (h ushr 16)).toLong() and 0xFFFFFFFFL) / 4294967296f
}

private fun fade(t: Float) = t * t * t * (t * (t * 6 - 15) + 10)
private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** 3D value noise, 0..1. */
private fun noise(x: Float, y: Float, z: Float): Float {
    val xi = floor(x).toInt()
    val yi = floor(y).toInt()
    val zi = floor(z).toInt()
    val u = fade(x - xi)
    val v = fade(y - yi)
    val w = fade(z - zi)
    return lerp(
        lerp(lerp(hash(xi, yi, zi), hash(xi + 1, yi, zi), u), lerp(hash(xi, yi + 1, zi), hash(xi + 1, yi + 1, zi), u), v),
        lerp(lerp(hash(xi, yi, zi + 1), hash(xi + 1, yi, zi + 1), u), lerp(hash(xi, yi + 1, zi + 1), hash(xi + 1, yi + 1, zi + 1), u), v),
        w,
    )
}
