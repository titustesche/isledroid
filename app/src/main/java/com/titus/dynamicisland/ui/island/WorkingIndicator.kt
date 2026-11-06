package com.titus.dynamicisland.ui.island

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.titus.dynamicisland.data.settings.WorkIndicator
import kotlin.math.PI
import kotlin.math.sin

/** The shrunken assistant's left side while it works in other apps: still busy, out of the way. */
@Composable
fun WorkingIndicator(style: WorkIndicator, color: Color, size: Dp) {
    val t = rememberInfiniteTransition(label = "working")
    when (style) {
        WorkIndicator.DOTS -> {
            // Three dots, a wave running through them.
            val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "dots")
            Canvas(Modifier.size(width = size * 1.5f, height = size)) {
                val r = this.size.height * 0.16f
                val gap = (this.size.width - 6 * r) / 2
                for (i in 0 until 3) {
                    val wave = ((sin((phase - i * 0.18f) * 2 * PI) + 1) / 2).toFloat()
                    val x = r + i * (2 * r + gap)
                    drawCircle(color.copy(alpha = 0.35f + 0.65f * wave), r * (0.8f + 0.25f * wave), Offset(x, center.y - r * 0.9f * wave))
                }
            }
        }
        WorkIndicator.PULSE -> {
            val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
            Canvas(Modifier.size(size)) {
                val r = this.size.minDimension / 2
                drawCircle(color.copy(alpha = 0.25f * (1 - p)), r * (0.6f + 0.4f * p))
                drawCircle(color, r * (0.36f + 0.08f * p))
            }
        }
        WorkIndicator.SPINNER -> {
            val turn by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin")
            Canvas(Modifier.size(size)) {
                val stroke = this.size.minDimension * 0.14f
                drawCircle(color.copy(alpha = 0.2f), this.size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
                drawArc(
                    color, turn, 100f, useCenter = false, topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** How many steps it took so far, on the right. */
@Composable
fun WorkingSteps(steps: Int, color: Color) {
    Text("$steps", color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}
