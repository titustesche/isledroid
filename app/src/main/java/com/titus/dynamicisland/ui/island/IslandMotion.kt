package com.titus.dynamicisland.ui.island

import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.ViewConfiguration
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titus.dynamicisland.data.settings.Gesture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Touch feedback that makes the island feel physical, like Apple's: it swells while pressed,
 * pops on long-press, and rubber-bands after the finger when dragged, springing back on release.
 */
@Stable
class IslandMotion(private val scope: CoroutineScope) {
    val press = Animatable(1f)
    val nudgeX = Animatable(0f)
    val nudgeY = Animatable(0f)
    var fingerDown by mutableStateOf(false)
        internal set

    /** True while touched or still springing back; the window adds room for the motion then. */
    val isActive: Boolean
        get() = fingerDown || press.value != 1f || nudgeX.value != 0f || nudgeY.value != 0f

    internal fun springBack(spec: AnimationSpec<Float>) {
        scope.launch { press.animateTo(1f, spec) }
        scope.launch { nudgeX.animateTo(0f, spec) }
        scope.launch { nudgeY.animateTo(0f, spec) }
    }

    internal fun releasePress(spec: AnimationSpec<Float>) {
        scope.launch { press.animateTo(1f, spec) }
    }

    internal fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)
}

@Composable
fun rememberIslandMotion(): IslandMotion {
    val scope = rememberCoroutineScope()
    return remember { IslandMotion(scope) }
}

/** User-tunable feel (Behavior → Touch feel). */
data class MotionTuning(val reach: Dp = 22.dp, val elasticity: Float = 1f) {
    val horizontal get() = reach
    val vertical get() = reach * 0.65f
}

private val SwipeThreshold = 36.dp

/** Asymptotic resistance: follows the finger at first, then stiffens towards [max]. */
private fun rubberBand(distance: Float, max: Float): Float =
    if (max <= 0f) 0f else sign(distance) * max * (1f - exp(-abs(distance) / max))

/**
 * Applies the motion to a layer: press scale, horizontal nudge with a slight stretch, downward pull,
 * and a squash when pushed up (the island is anchored to the top, so it can't move up).
 */
fun GraphicsLayerScope.applyIslandMotion(motion: IslandMotion, tuning: MotionTuning) {
    val maxX = tuning.horizontal.toPx().coerceAtLeast(1f)
    val maxDown = tuning.vertical.toPx().coerceAtLeast(1f)
    val maxUp = maxDown
    val e = tuning.elasticity
    val nx = motion.nudgeX.value
    val ny = motion.nudgeY.value
    val p = motion.press.value
    val stretchX = 1f + 0.04f * e * (abs(nx) / maxX)
    val pull = (ny / maxDown).coerceIn(0f, 1f)
    val push = (-ny / maxUp).coerceIn(0f, 1f)
    translationX = nx
    translationY = ny.coerceAtLeast(0f)
    scaleX *= p * stretchX * (1f + 0.03f * e * push)
    scaleY *= p * (1f + 0.04f * e * pull) * (1f - 0.07f * e * push)
}

/**
 * Tap, long-press and swipe recognition with haptics and the [motion] feedback.
 *
 * @param pressScale how much the island swells while held (smaller when expanded).
 */
@Composable
fun Modifier.islandGestures(
    motion: IslandMotion,
    spring: AnimationSpec<Float>,
    pressScale: Float,
    tuning: MotionTuning,
    haptics: Boolean,
    longPressMs: Int,
    onGesture: (Gesture) -> Unit,
): Modifier {
    val view = LocalView.current
    val density = LocalDensity.current
    val threshold = with(density) { SwipeThreshold.toPx() }
    val maxX = with(density) { tuning.horizontal.toPx() }
    val maxDown = with(density) { tuning.vertical.toPx() }
    val maxUp = maxDown
    val currentSpring by rememberUpdatedState(spring)
    val currentPressScale by rememberUpdatedState(pressScale)
    fun haptic(constant: Int) {
        if (haptics) view.performHapticFeedback(constant)
    }
    val fire by rememberUpdatedState { g: Gesture ->
        haptic(if (g == Gesture.LONG_PRESS) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CONTEXT_CLICK)
        onGesture(g)
    }
    return this
        .pointerInput(longPressMs) {
            var longPressed = false
            withLongPressTimeout(longPressMs).detectTapGestures(
                onPress = {
                    longPressed = false
                    motion.fingerDown = true
                    // Swell slowly over roughly the long-press timeout, so the pop feels earned.
                    val grow = motion.launch {
                        motion.press.animateTo(currentPressScale, tween(longPressMs + 50, easing = LinearOutSlowInEasing))
                    }
                    tryAwaitRelease()
                    grow.cancel()
                    motion.fingerDown = false
                    // Only the press swell; a drag that cancelled this tap restores its own nudge.
                    if (!longPressed) motion.releasePress(currentSpring)
                },
                onTap = { fire(Gesture.TAP) },
                onLongPress = {
                    longPressed = true
                    motion.launch {
                        motion.press.animateTo(currentPressScale + 0.04f, tween(110))
                        fire(Gesture.LONG_PRESS)
                        motion.press.animateTo(1f, currentSpring)
                    }
                },
            )
        }
        .pointerInput(Unit) {
            var local = Offset.Zero // finger travel in the island's (moving) coordinates
            var applied = Offset.Zero // translation currently applied to the island
            var total = Offset.Zero // finger travel on screen
            var armed = false
            fun release() {
                motion.fingerDown = false
                motion.springBack(currentSpring)
            }
            detectDragGestures(
                onDragStart = {
                    local = Offset.Zero
                    applied = Offset.Zero
                    total = Offset.Zero
                    armed = false
                    motion.fingerDown = true
                },
                onDragEnd = {
                    val g = when {
                        abs(total.x) < threshold && abs(total.y) < threshold -> null
                        abs(total.x) > abs(total.y) -> if (total.x < 0) Gesture.SWIPE_LEFT else Gesture.SWIPE_RIGHT
                        else -> if (total.y < 0) Gesture.SWIPE_UP else Gesture.SWIPE_DOWN
                    }
                    release()
                    g?.let(fire)
                },
                onDragCancel = { release() },
                onDrag = { change, delta ->
                    change.consume()
                    // The island moves with the finger, so local deltas miss that movement; add it back.
                    local += delta
                    total = local + applied
                    // Follow the dominant axis only, so a horizontal swipe doesn't also bob up and down.
                    val horizontal = abs(total.x) >= abs(total.y)
                    val x = if (horizontal) rubberBand(total.x, maxX) else 0f
                    val y = if (horizontal) 0f else rubberBand(total.y, if (total.y > 0) maxDown else maxUp)
                    applied = Offset(x, y.coerceAtLeast(0f)) // upward motion is a squash, not a translation
                    motion.launch { motion.nudgeX.snapTo(x) }
                    motion.launch { motion.nudgeY.snapTo(y) }
                    // A light tick when the swipe becomes "real".
                    val past = abs(total.x) >= threshold || abs(total.y) >= threshold
                    if (past && !armed) haptic(HapticFeedbackConstants.CLOCK_TICK)
                    armed = past
                },
            )
        }
}

/** This scope, but a long press fires after [timeoutMs] instead of the system's timeout. */
fun PointerInputScope.withLongPressTimeout(timeoutMs: Int): PointerInputScope {
    val config = object : ViewConfiguration by viewConfiguration {
        override val longPressTimeoutMillis get() = timeoutMs.toLong()
    }
    return object : PointerInputScope by this {
        override val viewConfiguration get() = config
    }
}
