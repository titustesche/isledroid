package com.titus.dynamicisland.ui.island

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import com.titus.dynamicisland.data.settings.Gesture
import com.titus.dynamicisland.data.settings.IslandSettings
import com.titus.dynamicisland.domain.AssistantPhase
import com.titus.dynamicisland.domain.IslandActions
import com.titus.dynamicisland.domain.IslandActivity
import com.titus.dynamicisland.domain.IslandMode
import com.titus.dynamicisland.domain.IslandUiState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.roundToInt

private val BubbleGap = 6.dp
private val TouchSlack = 4.dp

/**
 * The island. Used both in the overlay window and in the settings preview.
 *
 * @param onWindowSize when set (overlay only), receives the pixel size the hosting window must have:
 *   the larger of the animated and the target size, so growth never clips and shrinking follows the animation.
 * @param onTouchSize with [onWindowSize]: the pixel size of the island as currently drawn, centered at the
 *   top of that window. Only this part takes touches; the rest of the window lets them through.
 */
@Composable
fun Island(
    state: IslandUiState,
    settings: IslandSettings,
    actions: IslandActions,
    modifier: Modifier = Modifier,
    cutout: CutoutGeometry? = null,
    onWindowSize: ((IntSize) -> Unit)? = null,
    onTouchSize: ((IntSize) -> Unit)? = null,
) {
    val maxWidth = LocalConfiguration.current.screenWidthDp.dp - 16.dp
    // Width each compact layout reported it needs to keep its content clear of the camera.
    val compactNeeds = remember { mutableStateMapOf<String, Dp>() }
    fun dimsFor(st: IslandUiState) =
        islandDimensions(st, settings, maxWidth, cutout, st.primary?.let { compactNeeds[it.id] })
    val target = dimsFor(state)
    val spring = remember(settings.animationSpeed, settings.bounciness) { islandSpring<Dp>(settings) }
    val floatSpring = remember(settings.animationSpeed, settings.bounciness) { islandSpring<Float>(settings) }
    val motion = rememberIslandMotion()
    val tuning = MotionTuning(settings.swipeReachDp.dp, settings.elasticity)
    val interacting by remember { derivedStateOf { motion.isActive } }

    val width by animateDpAsState(target.width, spring, label = "w")
    val height by animateDpAsState(target.height, spring, label = "h")
    val corner by animateDpAsState(target.corner, spring, label = "r")

    val visible = state.mode != IslandMode.HIDDEN
    val visibility by animateFloatAsState(if (visible) 1f else 0f, tween(220), label = "visible")

    val bubble = state.secondary.takeIf { state.mode == IslandMode.COMPACT || state.mode == IslandMode.ALERT }
    val bubbleTarget = if (bubble != null) target.height else 0.dp
    val bubbleSize by animateDpAsState(bubbleTarget, spring, label = "bubble")
    val sideTarget = if (bubbleTarget > 0.dp) BubbleGap + bubbleTarget else 0.dp
    val side = if (bubbleSize > 0.dp) BubbleGap + bubbleSize else 0.dp

    // The assistant's optional Gemini-like look: a fill fading out at the bottom, a glow spilling past the edges.
    val assistantUi = (state.primary as? IslandActivity.Assistant)?.ui?.takeIf { state.mode == IslandMode.ASSISTANT }
    val spillGlow = assistantUi != null && settings.assistantGlowSpill
    val softEdge by animateFloatAsState(
        if (assistantUi != null && settings.assistantSoftEdge) 1f else 0f, tween(450), label = "softEdge",
    )
    val featherOn = assistantUi != null && settings.assistantFeather
    val feather by animateFloatAsState(if (featherOn) 1f else 0f, tween(450), label = "feather")
    // Window room below the island for the halo; given back once it has faded.
    val featherRoom by animateDpAsState(
        if (featherOn) (settings.featherBottomDp + settings.featherBlurDp * 2).dp else 0.dp,
        if (featherOn) snap() else tween(150, delayMillis = 450),
        label = "featherRoom",
    )
    // The glow fades out after the assistant has already left the state: it keeps the last phase.
    val glowPhase = remember { arrayOf(AssistantPhase.LISTENING) }
    assistantUi?.let { glowPhase[0] = it.phase }
    // Window room below the island for the spilling glow; given back once the glow has faded.
    val glowRoom by animateDpAsState(
        if (spillGlow) glowRoom(settings.assistantGlowReachDp.dp) else 0.dp,
        if (spillGlow) snap() else tween(150, delayMillis = GlowFadeMs),
        label = "glowRoom",
    )

    if (onWindowSize != null) {
        val density = LocalDensity.current
        val tracker = remember { WindowTracker() }
        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        val settled = width == target.width && height == target.height && bubbleSize == bubbleTarget
        val overshoot = overshootFraction(settings)
        // The window's WIDTH never changes while the island is visible: the window is centered, so a
        // width change moves its left edge, and Android may apply that move a frame apart from the
        // redrawn content, making the island twitch sideways. Only the height (anchored at the top,
        // so harmless) follows the island.
        val widest = max(
            min(settings.expandedWidthDp.dp, maxWidth),
            compactMaxWidth(settings, maxWidth) + (BubbleGap + settings.heightDp.dp) * 2,
        )
        // Room for the bounce when growing from the smallest pill to the widest state.
        val fixedW = min(screenWidth, widest + (widest - settings.idleWidthDp.dp) * overshoot + 16.dp)
        val h = max(glowRoom, featherRoom) + when {
            // Touch feedback (press swell, downward pull) needs a little room below the island.
            interacting -> max(tracker.settledH, target.height) * 1.12f + 24.dp
            settled -> target.height.also { tracker.settledH = it }
            // Tall enough for old size, new size and the spring's overshoot for the whole animation.
            else -> max(tracker.settledH, target.height) + abs(target.height - tracker.settledH) * overshoot + 8.dp
        }
        val size = if (!visible && visibility == 0f) IntSize.Zero else with(density) { evenSize(fixedW.toPx(), h.toPx()) }
        // Settled: the island plus a little slack, so it's easy to hit. Animating or pressed: the whole
        // window. Resizing a window costs the main thread a relayout, so the touch area must not follow
        // the animation frame by frame; this way it only changes when one starts and ends.
        val touch = when {
            !visible -> IntSize.Zero
            settled && !interacting -> with(density) {
                evenSize(min(fixedW, width + side * 2 + TouchSlack * 2).toPx(), (height + TouchSlack).toPx())
            }
            else -> size
        }
        SideEffect {
            onWindowSize(size)
            onTouchSize?.invoke(touch)
        }
    }

    val background = Color(settings.backgroundColor.toInt())
    val contentColor = when {
        !settings.textColorAuto -> Color(settings.textColor.toInt())
        background.luminance() > 0.5f -> Color.Black
        else -> Color.White
    }
    val colors = if (background.luminance() <= 0.5f) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides contentColor, LocalIslandPalette provides settings.palette()) {
            Box(
                modifier
                    // required*: never let a (momentarily) smaller window squeeze the island.
                    .requiredWidth(width + side * 2)
                    .requiredHeight(height)
                    .graphicsLayer {
                        alpha = visibility
                        scaleX = 0.6f + 0.4f * visibility
                        scaleY = 0.6f + 0.4f * visibility
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                        applyIslandMotion(motion, tuning)
                    },
            ) {
                val shape = RoundedCornerShape(corner)
                if (feather > 0f) {
                    FeatherHalo(
                        Modifier.align(Alignment.TopCenter).requiredSize(width, height),
                        background, corner, feather * settings.featherOpacity, softEdge, settings.assistantFadeStart,
                        settings.featherSpreadDp.dp, settings.featherBottomDp.dp, settings.featherBlurDp.dp,
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .requiredSize(width, height)
                        .background(fadingFill(background, softEdge, settings.assistantFadeStart), shape)
                        .then(
                            if (settings.outlineEnabled) {
                                Modifier.border(1.dp, fadingFill(Color(settings.outlineColor.toInt()), softEdge, settings.assistantFadeStart), shape)
                            } else Modifier,
                        )
                        .islandGestures(
                            motion = motion,
                            spring = floatSpring,
                            pressScale = 1f + settings.pressSwell * (if (state.mode == IslandMode.EXPANDED) 0.35f else 1f),
                            tuning = tuning,
                            haptics = settings.haptics,
                            longPressMs = settings.longPressMs,
                            onGesture = actions::onGesture,
                        ),
                ) {
                    // Between the fill and the content, and not clipped, so it can reach onto the screen.
                    AnimatedVisibility(
                        visible = spillGlow,
                        enter = fadeIn(tween(300)),
                        exit = fadeOut(tween(GlowFadeMs)),
                        modifier = Modifier.matchParentSize(),
                    ) {
                        AssistantGlow(glowPhase[0], LocalIslandPalette.current.assistant, Modifier.fillMaxSize(), settings.assistantGlowReachDp.dp)
                    }
                    // Only the content is clipped to the island; it may be larger while the island grows.
                    Box(Modifier.matchParentSize().clip(shape)) {
                        AnimatedContent(
                            targetState = state,
                            contentKey = { it.mode to it.primary?.id },
                            transitionSpec = {
                                // The pill itself animates the size; AnimatedContent must not run a second,
                                // differently-timed size animation that would make the content drift.
                                (fadeIn(tween(220, delayMillis = 80)) + scaleIn(tween(260), initialScale = 0.92f))
                                    .togetherWith(fadeOut(tween(90)))
                                    .using(SizeTransform(clip = false) { _, _ -> snap() })
                            },
                            contentAlignment = Alignment.TopCenter,
                            label = "content",
                            modifier = Modifier.wrapContentSize(Alignment.TopCenter, unbounded = true),
                        ) { shown ->
                            val dims = dimsFor(shown)
                            Box(Modifier.requiredSize(dims.width, dims.height)) {
                                IslandContent(
                                    state = shown,
                                    settings = settings,
                                    actions = actions,
                                    layout = ContentLayout(
                                        cutout = cutout,
                                        topInset = dims.topInset,
                                        maxCompactWidth = compactMaxWidth(settings, maxWidth),
                                        onCompactWidthNeeded = { need ->
                                            shown.primary?.let { if (compactNeeds[it.id] != need) compactNeeds[it.id] = need }
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
                if (bubble != null || bubbleSize > 0.dp) {
                    // Music and timers update all the time; restarting the touch handler on each update
                    // would drop a tap in progress. So it only restarts when the bubble shows something else.
                    val currentBubble by rememberUpdatedState(bubble)
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(bubbleSize)
                            .clip(CircleShape)
                            .background(background)
                            // The bubble is a full island of its own: tap expands it, long-press opens its
                            // app (or switches off an indicator such as the flashlight).
                            .pointerInput(bubble?.id, settings.longPressMs) {
                                withLongPressTimeout(settings.longPressMs).detectTapGestures(
                                    onTap = { currentBubble?.let(actions::expand) },
                                    onLongPress = { currentBubble?.let { if (!actions.deactivate(it)) actions.open(it) } },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        bubble?.let { BubbleContent(it, settings, bubbleTarget) }
                    }
                }
            }
        }
    }
}

private const val GlowFadeMs = 250

/**
 * The feathered edge: the island's fill, grown by [spread] (sides and top) and [below], blurred by [blur]
 * and drawn behind the island, so its outline melts into the screen. It fades out with the soft edge.
 * Fills its parent (the island) and reaches past it. Android < 12 can't blur and stacks rings instead.
 */
@Composable
private fun FeatherHalo(
    modifier: Modifier,
    color: Color,
    corner: Dp,
    alpha: Float,
    softEdge: Float,
    fadeStart: Float,
    spread: Dp,
    below: Dp,
    blur: Dp,
) {
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // Room inside the layer for the blur to fade out before its bounds.
    val margin = if (canBlur) blur * 2 else 0.dp
    Canvas(
        modifier
            .layout { measurable, c ->
                val side = (spread + margin).roundToPx()
                val top = (spread + margin).roundToPx()
                val bottom = (below + margin).roundToPx()
                val p = measurable.measure(Constraints.fixed(c.maxWidth + side * 2, c.maxHeight + top + bottom))
                layout(c.maxWidth, c.maxHeight) { p.place(-side, -top) }
            }
            .graphicsLayer { this.alpha = alpha }
            .then(if (canBlur) Modifier.blur(blur, BlurredEdgeTreatment.Unbounded) else Modifier),
    ) {
        val m = margin.toPx()
        val top = m
        val bottom = size.height - m
        val fill = if (softEdge <= 0f) SolidColor(color) else Brush.verticalGradient(
            0f to color,
            fadeStart.coerceIn(0f, 0.95f) to color,
            1f to color.copy(alpha = color.alpha * (1f - softEdge)),
            startY = top, endY = bottom,
        )
        val r = corner.toPx() + spread.toPx()
        if (canBlur) {
            drawRoundRect(fill, Offset(m, top), Size(size.width - m * 2, bottom - top), CornerRadius(r))
        } else {
            // Rings growing outwards, each faint: together they thin out towards the outer edge.
            val reach = blur.toPx()
            val rings = 8
            for (k in 0 until rings) {
                val inset = reach * k / rings
                drawRoundRect(
                    fill, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2),
                    CornerRadius((r - inset).coerceAtLeast(0f)), alpha = 1f / rings,
                )
            }
        }
    }
}

/** The island's fill; with [fade] > 0 it thins out from [start] (of the height) to the bottom, fully transparent there at 1. */
private fun fadingFill(color: Color, fade: Float, start: Float): Brush =
    if (fade <= 0f) SolidColor(color)
    else Brush.verticalGradient(0f to color, start.coerceIn(0f, 0.95f) to color, 1f to color.copy(alpha = color.alpha * (1f - fade)))

private fun dampingRatio(s: IslandSettings) = (1f - 0.65f * s.bounciness).coerceIn(0.3f, 1f)

/** Spring stiffness at 1× speed; the speed setting scales it quadratically (0.4× … 2×). */
private const val BaseStiffness = 260f

private fun <T> islandSpring(s: IslandSettings) = spring<T>(
    dampingRatio = dampingRatio(s),
    stiffness = BaseStiffness * s.animationSpeed * s.animationSpeed,
)

/** Peak overshoot of an underdamped spring, as a fraction of the distance travelled. */
internal fun overshootFraction(s: IslandSettings): Float {
    val z = dampingRatio(s)
    return if (z >= 1f) 0f else exp(-z * PI.toFloat() / sqrt(1f - z * z))
}

private class WindowTracker {
    var settledH: Dp = 0.dp
}

/** Even pixel widths keep the centered island from shifting by half a pixel between window sizes. */
private fun evenSize(w: Float, h: Float): IntSize {
    val wi = w.roundToInt()
    return IntSize(wi + (wi and 1), h.roundToInt())
}

private fun abs(d: Dp) = if (d < 0.dp) -d else d

@Composable
private fun BubbleContent(activity: IslandActivity, settings: IslandSettings, size: Dp) {
    when (activity) {
        is IslandActivity.Media -> Artwork(activity.info.art, size - 8.dp, size, animatedAccent(activity, settings))
        is IslandActivity.Timer -> TimerGlyph(activity, settings, size - 10.dp)
        is IslandActivity.Progress -> ProgressGlyph(activity, settings, size - 10.dp)
        is IslandActivity.Alert -> AppIcon(activity.entry.appIcon, size - 8.dp)
        IslandActivity.Torch -> TorchLeft(size)
        is IslandActivity.Map -> MapCompactLeft(activity, settings, size - 12.dp)
        is IslandActivity.Navigation -> NavManeuver(activity, size - 10.dp)
        is IslandActivity.Event, IslandActivity.Tools, is IslandActivity.Assistant, is IslandActivity.Notifications -> Unit
    }
}

/** [accentFor], but cross-fading when it changes (e.g. between tracks) instead of jumping. */
@Composable
fun animatedAccent(activity: IslandActivity?, s: IslandSettings): Color {
    val color by animateColorAsState(accentFor(activity, s), tween(450), label = "accent")
    return color
}

/**
 * Same hue, but at least [minValue] bright (HSV value), so controls stay visible on dark
 * (e.g. monochrome) artwork while still following the accent.
 */
fun Color.withMinValue(minValue: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    if (hsv[2] >= minValue) return this
    hsv[2] = minValue
    return Color(android.graphics.Color.HSVToColor((alpha * 255).toInt(), hsv))
}

/** The highlight color for an activity: per-type colors from settings, or the artwork's color for music. */
fun accentFor(activity: IslandActivity?, s: IslandSettings): Color = when (activity) {
    is IslandActivity.Timer -> Color(s.timerColor.toInt())
    is IslandActivity.Progress -> Color(s.progressColor.toInt())
    else -> {
        val fromArt = (activity as? IslandActivity.Media)?.info?.accentColor
        if (s.accentFromArtwork && fromArt != null) Color(fromArt) else Color(s.accentColor.toInt())
    }
}
