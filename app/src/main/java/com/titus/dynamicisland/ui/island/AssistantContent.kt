package com.titus.dynamicisland.ui.island

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dehaze
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import com.titus.dynamicisland.data.assistant.AssistantCard
import com.titus.dynamicisland.data.assistant.CardIcon
import com.titus.dynamicisland.data.assistant.CardLive
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titus.dynamicisland.appGraph
import com.titus.dynamicisland.domain.AssistantPhase
import com.titus.dynamicisland.domain.AssistantUi
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** The assistant's semi-expanded, almost square island. */
val AssistantWidth = 236.dp
val AssistantContentHeight = 196.dp

private val ErrorPalette = listOf(Color(0xFFFF453A), Color(0xFFFF9F0A), Color(0xFFFF375F), Color(0xFFFF6961))

/**
 * @param waves false when the island draws the glow itself, spilling past its edges (see [AssistantGlow]).
 * @param ascii drifting ASCII noise behind the text, as [AsciiStyle] says.
 * @param detailed results laid out per action (see [ResultBlock]) instead of uniform cards.
 */
@Composable
fun AssistantContent(
    ui: AssistantUi,
    topInset: Dp,
    accent: Color,
    waves: Boolean = true,
    ascii: AsciiStyle? = null,
    detailed: Boolean = true,
) {
    val dim = LocalContentColor.current.copy(alpha = 0.6f)
    Box(Modifier.fillMaxSize()) {
        if (waves) {
            VoiceWaves(
                ui.phase,
                accent,
                LocalContext.current.appGraph.assistant.level,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(WavesHeight),
            )
        }
        if (ascii != null) {
            AsciiNoise(
                Modifier.fillMaxSize(),
                color = if (ascii.accent) accent else Color.White,
                opacity = ascii.opacity, fontSize = ascii.size, scale = ascii.grain, speed = ascii.speed, fadeStart = ascii.fadeStart,
            )
        }
        Column(
            Modifier.fillMaxSize().padding(
                top = topInset + 2.dp,
                bottom = when {
                    ui.cards.isEmpty() -> 44.dp
                    detailed -> BlocksBottomGap
                    else -> CardsBottomGap
                },
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { AssistantText(ui, dim) }
            if (ui.cards.isNotEmpty() && detailed) {
                Spacer(Modifier.height(CardsTopGap))
                Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(BlockGap)) {
                    ui.cards.forEach { card -> key(card.key) { ResultBlock(card, accent) } }
                }
            } else if (ui.cards.isNotEmpty()) {
                Spacer(Modifier.height(CardsTopGap))
                Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(CardGap)) {
                    ui.cards.forEach { card -> key(card.key) { ResultCard(card, accent) } }
                }
            }
        }
    }
}

@Composable
private fun AssistantText(ui: AssistantUi, dim: Color) {
            when (ui.phase) {
                // Waiting for a follow-up: the last answer stays readable until they speak.
                AssistantPhase.LISTENING if ui.followUp && ui.question.isBlank() && ui.previous.isNotBlank() ->
                    AutoScrollingText(ui.previous, color = dim)
                AssistantPhase.LISTENING -> Text(
                    ui.question.ifBlank { "Listening…" },
                    fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    color = if (ui.question.isBlank()) dim else LocalContentColor.current,
                )
                AssistantPhase.THINKING -> Text(
                    ui.question, fontSize = 15.sp, lineHeight = 19.sp, color = dim,
                    maxLines = 4, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                AssistantPhase.ANSWERING, AssistantPhase.DONE, AssistantPhase.ERROR -> {
                    Text(ui.question, fontSize = 11.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    AutoScrollingText(
                        ui.answer,
                        color = if (ui.phase == AssistantPhase.ERROR) Color(0xFFFF6961) else LocalContentColor.current,
                    )
                }
            }
}

private val CardHeight = 46.dp
private val CardGap = 6.dp
private val CardsTopGap = 10.dp
private val CardsBottomGap = 14.dp

private val BlockGap = 14.dp
private val BlocksBottomGap = 24.dp

/** Extra island height for the cards under the answer (they take the place of the glow's bottom strip). */
fun assistantCardsHeight(ui: AssistantUi, detailed: Boolean = true): Dp {
    val n = ui.cards.size
    if (n == 0) return 0.dp
    if (detailed) {
        val blocks = ui.cards.fold(0.dp) { sum, c -> sum + blockHeight(c) }
        return CardsTopGap + blocks + BlockGap * (n - 1) + BlocksBottomGap - 44.dp + 20.dp
    }
    return CardsTopGap + CardHeight * n + CardGap * (n - 1) + CardsBottomGap - 44.dp + 20.dp
}

/** One thing the assistant did or found; tap to open it, toggle it, or say yes to it. */
@Composable
private fun ResultCard(card: AssistantCard, accent: Color) {
    val graph = LocalContext.current.appGraph
    val content = LocalContentColor.current
    val darkIsland = content.luminance() > 0.5f
    val look = liveLook(card)
    val highlight = card.awaitingConfirmation
    val background = when {
        highlight -> accent
        darkIsland -> Color.Black.copy(alpha = 0.5f)
        else -> Color.White.copy(alpha = 0.65f)
    }
    val text = if (highlight) onColor(accent) else content
    Row(
        Modifier
            .fillMaxWidth()
            .height(CardHeight)
            .clip(RoundedCornerShape(15.dp))
            .background(background)
            .then(if (card.action != null) Modifier.clickable { graph.assistant.onCardClick(card) } else Modifier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardGlyph(card, look, if (highlight) text else accent)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(look.title, fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when {
                highlight -> card.subtitle?.let { "\u201C$it\u201D" } ?: "Tap to confirm"
                card.icon == CardIcon.MESSAGE || card.icon == CardIcon.CALL -> listOfNotNull(card.label, look.subtitle).joinToString(" · ")
                else -> look.subtitle
            }
            sub?.let {
                Text(it, fontSize = 11.sp, lineHeight = 13.sp, color = text.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (highlight) {
            Spacer(Modifier.width(6.dp))
            Text(confirmVerb(card), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = text)
            Spacer(Modifier.width(4.dp))
        }
    }
}

internal fun confirmVerb(card: AssistantCard) = when (card.icon) {
    CardIcon.CALL -> "Call"
    CardIcon.APP -> "Do it"
    else -> "Send"
}

internal data class CardLook(val title: String, val subtitle: String?, val art: ImageBitmap? = null, val dimmed: Boolean = false)

/** Live cards follow the phone; countdowns tick; the rest is what the tool said. */
@Composable
internal fun liveLook(card: AssistantCard): CardLook {
    val graph = LocalContext.current.appGraph
    return when (card.live) {
        CardLive.TORCH -> {
            val sys by graph.system.state.collectAsState()
            val title = when {
                !sys.torchOn -> "Off"
                sys.torchDimmable -> "On · ${(sys.torchFraction * 100).roundToInt()} %"
                else -> "On"
            }
            CardLook(title, "Flashlight · tap to switch", dimmed = !sys.torchOn)
        }
        CardLive.DND -> {
            val sys by graph.system.state.collectAsState()
            CardLook(if (sys.dndOn) "On" else "Off", "Do Not Disturb · tap to switch", dimmed = !sys.dndOn)
        }
        CardLive.MEDIA -> {
            val info by graph.media.media.collectAsState()
            val m = info
            if (m == null) CardLook("Nothing playing", null, dimmed = true)
            else CardLook(m.title, listOfNotNull(m.artist, if (m.isPlaying) null else "Paused").joinToString(" · "), m.art?.asImageBitmap())
        }
        null -> {
            val end = card.countdownTo
            if (end == null) return CardLook(card.title, card.subtitle)
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(end) {
                while (now < end) {
                    delay(1_000 - now % 1_000)
                    now = System.currentTimeMillis()
                }
            }
            val left = ((end - now + 999) / 1_000).coerceAtLeast(0)
            CardLook(if (left == 0L) "Done" else countdown(left), card.subtitle)
        }
    }
}

private fun countdown(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

@Composable
private fun CardGlyph(card: AssistantCard, look: CardLook, tint: Color) {
    val icons = LocalContext.current.appGraph.icons
    val appIcon = remember(card.appPackage) { card.appPackage?.let(icons::icon) }
    val shape = RoundedCornerShape(9.dp)
    when {
        look.art != null -> Image(look.art, null, Modifier.size(30.dp).clip(shape), contentScale = ContentScale.Crop)
        appIcon != null -> Image(appIcon, null, Modifier.size(30.dp).clip(shape))
        else -> Box(
            Modifier.size(30.dp).clip(CircleShape).background(tint.copy(alpha = if (look.dimmed) 0.1f else 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(card.icon.vector(), null, Modifier.size(18.dp), tint = if (look.dimmed) tint.copy(alpha = 0.5f) else tint)
        }
    }
}

internal fun CardIcon.vector(): ImageVector = when (this) {
    CardIcon.TIMER -> Icons.Rounded.Timer
    CardIcon.ALARM -> Icons.Rounded.Alarm
    CardIcon.FLASHLIGHT -> Icons.Rounded.FlashlightOn
    CardIcon.DND -> Icons.Rounded.DoNotDisturbOn
    CardIcon.RING -> Icons.Rounded.NotificationsActive
    CardIcon.VIBRATE -> Icons.Rounded.Vibration
    CardIcon.SILENT -> Icons.Rounded.NotificationsOff
    CardIcon.VOLUME -> Icons.AutoMirrored.Rounded.VolumeUp
    CardIcon.MUSIC -> Icons.Rounded.MusicNote
    CardIcon.APP -> Icons.Rounded.Apps
    CardIcon.WEB -> Icons.Rounded.Public
    CardIcon.BATTERY -> Icons.Rounded.BatteryFull
    CardIcon.SUN -> Icons.Rounded.WbSunny
    CardIcon.PARTLY_CLOUDY -> Icons.Rounded.WbCloudy
    CardIcon.CLOUD -> Icons.Rounded.Cloud
    CardIcon.FOG -> Icons.Rounded.Dehaze
    CardIcon.RAIN -> Icons.Rounded.Umbrella
    CardIcon.SNOW -> Icons.Rounded.AcUnit
    CardIcon.STORM -> Icons.Rounded.Thunderstorm
    CardIcon.CALENDAR -> Icons.Rounded.Event
    CardIcon.CONTACT -> Icons.Rounded.Person
    CardIcon.CALL -> Icons.Rounded.Call
    CardIcon.MESSAGE -> Icons.Rounded.Sms
    CardIcon.NAVIGATION -> Icons.Rounded.Navigation
    CardIcon.NOTIFICATION -> Icons.Rounded.Notifications
    CardIcon.LOCATION -> Icons.Rounded.Place
    CardIcon.NOTE -> Icons.Rounded.Bookmark
    CardIcon.POWER -> Icons.Rounded.PowerSettingsNew
    CardIcon.HOME -> Icons.Rounded.Home
}

/** Shows the newest part of a growing text; it follows along on its own (dragging belongs to the island). */
@Composable
private fun AutoScrollingText(text: String, color: Color) {
    val scroll = rememberScrollState()
    LaunchedEffect(text) { scroll.animateScrollTo(scroll.maxValue) }
    // The persona answers simple things in a word or two: give those the stage.
    val short = text.length <= 32
    Box(Modifier.fillMaxSize()) {
        Text(
            text,
            fontSize = if (short) 22.sp else 14.sp,
            lineHeight = if (short) 26.sp else 18.sp,
            fontWeight = if (short) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().verticalScroll(scroll, enabled = false),
        )
    }
}

/** How the assistant's ASCII background looks; [fadeStart] fades it out with the soft edge (null: no fade). */
data class AsciiStyle(
    val opacity: Float,
    val size: androidx.compose.ui.unit.TextUnit,
    val grain: Dp,
    val speed: Float,
    val accent: Boolean,
    val fadeStart: Float?,
)

private const val WAVES = 4
private const val WavesHeight = 0.72f

/** Room the window needs below the island for a glow reaching [reach] past it, blur included. */
fun glowRoom(reach: Dp) = reach + 26.dp

/**
 * The voice glow, not clipped to the island: it fills the parent (the island) and its shapes reach
 * [reach] past its sides and bottom, then the blur feathers it out softly onto the screen.
 */
@Composable
fun AssistantGlow(phase: AssistantPhase, accent: Color, modifier: Modifier = Modifier, reach: Dp = 14.dp) {
    VoiceWaves(
        phase,
        accent,
        LocalContext.current.appGraph.assistant.level,
        modifier.layout { measurable, c ->
            val spill = reach.roundToPx()
            val w = c.maxWidth + spill * 2
            val h = (c.maxHeight * WavesHeight).roundToInt() + spill
            val p = measurable.measure(Constraints.fixed(w, h))
            layout(c.maxWidth, c.maxHeight) { p.place(-spill, c.maxHeight - h + spill) }
        },
        edge = BlurredEdgeTreatment.Unbounded,
    )
}

/** Back waves follow the voice lazily, front ones snap to it: the layers ripple apart on every syllable. */
private val Attack = floatArrayOf(7f, 11f, 16f, 24f)
private val Release = floatArrayOf(2.5f, 3.5f, 5f, 7f)

/** Analogous colors around the user's accent, so the voice looks like *their* island. */
private fun palette(accent: Color): List<Color> {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
    return listOf(-38f, 24f, -12f, 0f).mapIndexed { i, shift ->
        val h = (hsv[0] + shift + 360f) % 360f
        val sat = (hsv[1] * (0.85f + 0.08f * i)).coerceIn(0.45f, 1f)
        val v = hsv[2].coerceAtLeast(0.8f)
        Color(android.graphics.Color.HSVToColor(floatArrayOf(h, sat, v)))
    }
}

/**
 * Soft, blurred light rising from the bottom edge and fading out towards the top: wave-shaped color
 * fields, heavily blurred so they melt into a glow. Height, swell and speed follow [level]
 * (microphone while listening, the voice while answering); thinking makes them roll and breathe on
 * their own; when it's done they settle down. (Android < 12 can't blur and gets the soft gradients.)
 */
@Composable
private fun VoiceWaves(
    phase: AssistantPhase,
    accent: Color,
    level: StateFlow<Float>,
    modifier: Modifier,
    edge: BlurredEdgeTreatment = BlurredEdgeTreatment.Rectangle,
) {
    val target = if (phase == AssistantPhase.ERROR) ErrorPalette else remember(accent) { palette(accent) }
    val colors = List(WAVES) { i ->
        val c by animateColorAsState(target[i], tween(600), label = "wave$i")
        c
    }
    val currentPhase by rememberUpdatedState(phase)
    // Rises in on start, sinks to a calm swell when done.
    val presence = remember { Animatable(0f) }
    LaunchedEffect(phase) {
        presence.animateTo(if (phase == AssistantPhase.DONE) 0.45f else 1f, tween(if (phase == AssistantPhase.DONE) 1200 else 700, easing = FastOutSlowInEasing))
    }
    val clock = remember { mutableFloatStateOf(0f) }
    val smoothed = remember { FloatArray(WAVES) }

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        var t = 0f
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                val thinking = currentPhase == AssistantPhase.THINKING
                val goal = when (currentPhase) {
                    AssistantPhase.THINKING -> 0.3f + 0.16f * sin(t * 2.2f)
                    else -> level.value
                }
                var energy = 0f
                for (i in 0 until WAVES) {
                    val k = if (goal > smoothed[i]) Attack[i] else Release[i]
                    smoothed[i] += (goal - smoothed[i]) * (1f - exp(-k * dt))
                    energy += smoothed[i] / WAVES
                }
                // Louder means faster water; thinking keeps it rolling.
                t += dt * (0.8f + 2.6f * energy + if (thinking) 1.4f else 0f)
                clock.floatValue = t
            }
        }
    }

    val path = remember { Path() }
    // Rectangle edges clamp, so the glow stays solid along the bottom instead of fading in from it.
    // Unbounded (the spilling glow) lets it feather out past every edge instead.
    Canvas(modifier.blur(22.dp, edge)) {
        val t = clock.floatValue
        val w = size.width
        val h = size.height
        val step = 3.dp.toPx()
        val lift = presence.value
        for (i in 0 until WAVES) { // back to front
            val lvl = smoothed[i]
            val depth = i / (WAVES - 1f)
            // Peaks stay a blur radius below the top edge, where the clamped blur would show a seam.
            val mean = h * lift * (0.14f + 0.1f * (1f - depth) + 0.42f * lvl)
            val amp = h * lift * (0.05f + 0.17f * lvl)
            val f1 = 0.9f + 0.35f * i
            val f2 = 1.9f + 0.55f * i
            val dir = if (i % 2 == 0) 1f else -1.3f
            path.reset()
            path.moveTo(0f, h)
            var top = h
            var x = 0f
            while (true) {
                val u = (x / w).coerceAtMost(1f)
                // Taller in the middle, like the voice's energy gathering there.
                val env = 0.3f + 0.7f * sin(PI.toFloat() * u)
                val swell = 0.62f * sin(2f * PI.toFloat() * f1 * u + t * dir + i * 1.7f) +
                    0.38f * sin(2f * PI.toFloat() * f2 * u - t * dir * 1.6f + i * 0.9f)
                val y = h - env * (mean + amp * swell)
                path.lineTo(x, y)
                if (y < top) top = y
                if (x >= w) break
                x = (x + step).coerceAtMost(w)
            }
            path.lineTo(w, h)
            path.close()
            val color = colors[i]
            drawPath(
                path,
                Brush.verticalGradient(
                    0f to color.copy(alpha = 0.15f + 0.25f * lvl),
                    0.55f to color.copy(alpha = 0.45f + 0.2f * depth),
                    1f to color.copy(alpha = 0.7f + 0.25f * depth),
                    startY = top,
                    endY = h,
                ),
            )
        }
    }
}
