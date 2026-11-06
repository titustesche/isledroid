package com.titus.dynamicisland.ui.island

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

/** The retro visualizer: [segments] blocks per column; [peak] draws the top lit block brighter. */
data class RetroBars(val segments: Int, val peak: Boolean)

/**
 * Animated equalizer bars; settle flat when [playing] is false. With [retro]: stacked blocks like
 * an 80s spectrum analyzer, one more column.
 */
@Composable
fun Visualizer(color: Color, playing: Boolean, modifier: Modifier = Modifier, bars: Int = 4, retro: RetroBars? = null) {
    val transition = rememberInfiniteTransition(label = "visualizer")
    val amplitude by animateFloatAsState(if (playing) 1f else 0f, tween(300), label = "amp")
    if (retro != null) {
        val segments = retro.segments.coerceIn(2, 12)
        val columns = bars + 1
        val levels = (0 until columns).map { i ->
            transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 340 + ((i * 7) % 5) * 90, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "seg$i",
            )
        }
        val peak = if (retro.peak) lerp(color, Color.White, 0.6f) else color
        Canvas(modifier.width(SegmentWidth * columns + SegmentGap * (columns - 1))) {
            val w = SegmentWidth.toPx()
            val gap = SegmentGap.toPx()
            // Blocks and gaps share each step, so any count fits the height.
            // n blocks + (n - 1) gaps = height, with each gap 28 % of a step.
            val pitch = size.height / (segments - 0.28f)
            val rowGap = pitch * 0.28f
            val cell = pitch - rowGap
            levels.forEachIndexed { c, level ->
                val fraction = 0.2f + (level.value - 0.2f) * amplitude
                val lit = kotlin.math.ceil(fraction * segments).toInt().coerceIn(1, segments)
                val x = c * (w + gap)
                for (i in 0 until segments) {
                    val y = size.height - (i + 1) * cell - i * rowGap
                    val tint = when {
                        i == lit - 1 -> peak
                        i < lit -> color
                        else -> color.copy(alpha = 0.14f)
                    }
                    drawRect(tint, Offset(x, y), Size(w, cell))
                }
            }
        }
        return
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(bars) { i ->
            val level by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(durationMillis = 380 + i * 110, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
                label = "bar$i",
            )
            val fraction = 0.2f + (level - 0.2f) * amplitude
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

private val SegmentWidth = 4.dp
private val SegmentGap = 2.dp

@Composable
fun Artwork(art: Bitmap?, size: Dp, corner: Dp, fallbackTint: Color) {
    val image = remember(art) { art?.asImageBitmap() }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(Color.White.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, null, Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Rounded.MusicNote, null, Modifier.size(size * 0.6f), tint = fallbackTint)
        }
    }
}

@Composable
fun AppIcon(icon: ImageBitmap?, size: Dp, corner: Dp = size / 2) {
    Box(Modifier.size(size).clip(RoundedCornerShape(corner)).background(Color.White.copy(alpha = 0.12f))) {
        if (icon != null) Image(icon, null, Modifier.size(size))
    }
}

/**
 * Android-style media background: the album cover scaled up and blurred, fully visible on the
 * left and fading out to the right, with a light scrim so text stays readable.
 *
 * @param bleed how far it reaches past the box to the sides and below (not above: the island is
 *   anchored at the top), for a pill that overshoots its final size while bouncing open. The fade
 *   stays where it'd be without it.
 */
@Composable
fun BoxScope.BlurredArtBackground(art: Bitmap, fadeEnd: Float = 0.6f, bleed: Dp = 0.dp) {
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // Without RenderEffect, a tiny bilinear-upscaled copy gives a soft, blur-like look.
    val image = remember(art, canBlur) {
        (if (canBlur) art else art.scale(12, 12)).asImageBitmap()
    }
    Box(
        Modifier
            .matchParentSize()
            .layout { measurable, constraints ->
                val b = bleed.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth + 2 * b, constraints.maxHeight + b))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-b, 0) }
            }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val b = bleed.toPx()
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Black,
                        fadeEnd.coerceIn(0.1f, 1f) * 0.35f to Color.Black,
                        fadeEnd.coerceIn(0.1f, 1f) to Color.Transparent,
                        startX = b,
                        endX = size.width - b,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { scaleX = 1.35f; scaleY = 1.35f }
                .then(if (canBlur) Modifier.blur(28.dp, BlurredEdgeTreatment.Rectangle) else Modifier),
        )
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.32f)))
    }
}

/** Wall-clock time, ticking every [intervalMs]. */
@Composable
fun rememberWallClock(intervalMs: Long = 250): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(intervalMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/** Elapsed-realtime clock, ticking only while [active]. */
@Composable
fun rememberElapsedClock(active: Boolean, intervalMs: Long = 500): Long {
    val now by produceState(SystemClock.elapsedRealtime(), active) {
        value = SystemClock.elapsedRealtime()
        while (active) {
            delay(intervalMs)
            value = SystemClock.elapsedRealtime()
        }
    }
    return now
}

fun formatDuration(ms: Long): String {
    val negative = ms < 0
    val total = abs(ms) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val body = if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%d:%02d", m, s)
    return if (negative) "-$body" else body
}
