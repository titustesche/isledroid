package com.titus.dynamicisland.ui.island

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titus.dynamicisland.appGraph
import com.titus.dynamicisland.data.assistant.AssistantCard
import com.titus.dynamicisland.data.assistant.CardIcon
import com.titus.dynamicisland.data.assistant.CardLive
import com.titus.dynamicisland.ui.theme.GeistMono
import kotlinx.coroutines.delay

/*
 * What the assistant did, one layout per kind of action and no container: a small caption in the
 * assistant's color, the result itself as the hero, quiet details in grey. Everything tinted comes
 * from the one accent.
 */

/** Each kind's fixed height, so the island can size itself before anything is drawn. */
private enum class Block(val height: Dp) {
    TIME(58.dp), TIMER(68.dp), LEVEL(58.dp), STATE(48.dp), SWITCH(50.dp), WEATHER(54.dp),
    MUSIC(46.dp), CALL(44.dp), MESSAGE(64.dp), NOTE(50.dp), ROUTE(52.dp), LABELED(52.dp),
}

private val WeatherIcons = setOf(
    CardIcon.SUN, CardIcon.PARTLY_CLOUDY, CardIcon.CLOUD, CardIcon.FOG, CardIcon.RAIN, CardIcon.SNOW, CardIcon.STORM,
)

private fun blockOf(card: AssistantCard): Block = when {
    card.live == CardLive.MEDIA -> Block.MUSIC
    card.live != null -> Block.SWITCH
    card.icon == CardIcon.ALARM -> Block.TIME
    card.icon == CardIcon.TIMER && card.countdownTo != null -> Block.TIMER
    card.level != null -> Block.LEVEL
    card.icon == CardIcon.RING || card.icon == CardIcon.VIBRATE || card.icon == CardIcon.SILENT -> Block.STATE
    card.icon in WeatherIcons -> Block.WEATHER
    card.icon == CardIcon.CALL -> Block.CALL
    card.icon == CardIcon.MESSAGE || card.icon == CardIcon.NOTIFICATION -> Block.MESSAGE
    card.icon == CardIcon.NOTE -> Block.NOTE
    card.icon == CardIcon.NAVIGATION -> Block.ROUTE
    else -> Block.LABELED
}

internal fun blockHeight(card: AssistantCard): Dp {
    val kind = blockOf(card)
    return kind.height + if (kind == Block.TIME && card.detail != null) 14.dp else 0.dp
}

/** The colors one accent gives a block. */
private class Tints(val accent: Color, val text: Color) {
    val dim = text.copy(alpha = 0.62f)
    /** Sunk most of the way to black: filled pills. */
    val deep = lerp(accent, Color.Black, 0.72f)
    /** Thin outlines. */
    val edge = accent.copy(alpha = 0.45f)
    /** Light text over the ASCII noise gets a dark halo, as on the canvas. */
    val shadow = if (text.luminance() > 0.5f) Shadow(Color.Black, blurRadius = 8f) else null
}

private fun mono(size: TextUnit, weight: FontWeight = FontWeight.Normal, spacing: TextUnit = 0.sp, line: TextUnit = TextUnit.Unspecified) =
    TextStyle(fontFamily = GeistMono, fontSize = size, fontWeight = weight, letterSpacing = spacing, lineHeight = line)

private fun sans(size: TextUnit, weight: FontWeight = FontWeight.Normal, line: TextUnit = TextUnit.Unspecified) =
    TextStyle(fontFamily = FontFamily.Default, fontSize = size, fontWeight = weight, lineHeight = line)

/** One thing the assistant did or found; tap to open it, toggle it, or say yes to it. */
@Composable
internal fun ResultBlock(card: AssistantCard, accent: Color) {
    val graph = LocalContext.current.appGraph
    val t = Tints(accent, LocalContentColor.current)
    val kind = blockOf(card)
    Row(
        Modifier
            .fillMaxWidth()
            .height(blockHeight(card))
            .clip(RoundedCornerShape(12.dp))
            .then(if (card.action != null) Modifier.clickable { graph.assistant.onCardClick(card) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            when (kind) {
                Block.TIME -> TimeBlock(card, t)
                Block.TIMER -> TimerBlock(card, t)
                Block.LEVEL, Block.STATE -> LevelBlock(card, t)
                Block.SWITCH -> SwitchBlock(card, t)
                Block.WEATHER -> WeatherBlock(card, t)
                Block.MUSIC -> MusicBlock(card, t)
                Block.CALL -> CallBlock(card, t)
                Block.MESSAGE -> MessageBlock(card, t)
                Block.NOTE -> NoteBlock(card, t)
                Block.ROUTE -> RouteBlock(card, t)
                Block.LABELED -> LabeledBlock(card, t)
            }
        }
        if (card.awaitingConfirmation) {
            Spacer(Modifier.width(10.dp))
            Pill(confirmVerb(card), on = true, t)
        }
    }
}

@Composable
private fun Caption(text: String, t: Tints, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(
        text.uppercase(), modifier,
        style = mono(10.sp, FontWeight.SemiBold, 1.8.sp, 12.sp).copy(shadow = t.shadow),
        color = t.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align,
    )
}

@Composable
private fun Detail(text: String, t: Tints, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start, lines: Int = 1) {
    Text(
        text, modifier, style = mono(11.sp, line = 14.sp).copy(shadow = t.shadow), color = t.dim,
        maxLines = lines, overflow = TextOverflow.Ellipsis, textAlign = align,
    )
}

@Composable
private fun Title(text: String, t: Tints, modifier: Modifier = Modifier) {
    Text(
        text, modifier, style = sans(15.sp, FontWeight.SemiBold, 20.sp).copy(shadow = t.shadow), color = t.text,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Hero(text: String, size: TextUnit, line: TextUnit, t: Tints, spacing: TextUnit = (-1.4).sp, modifier: Modifier = Modifier) {
    Text(
        text, modifier, style = mono(size, FontWeight.SemiBold, spacing, line).copy(shadow = t.shadow), color = t.text,
        maxLines = 1, overflow = TextOverflow.Clip, softWrap = false,
    )
}

/** A tiny island inside the island: filled while on (or waiting for a yes), an outline while off. */
@Composable
private fun Pill(text: String, on: Boolean, t: Tints) {
    Box(
        Modifier
            .height(30.dp)
            .clip(CircleShape)
            .background(if (on) t.deep else Color.Transparent)
            .border(1.dp, if (on) t.edge else t.dim.copy(alpha = 0.3f), CircleShape)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = mono(12.sp, FontWeight.Bold, 0.6.sp), color = if (on) t.accent else t.dim, maxLines = 1)
    }
}

/** Progress drawn in characters: full blocks in the accent for what's there, shade for the rest. */
@Composable
private fun AsciiBar(fraction: Float, t: Tints) {
    val f = fraction.coerceIn(0f, 1f)
    val style = mono(9.sp, line = 10.sp)
    Row(Modifier.fillMaxWidth().height(10.dp)) {
        if (f > 0f) Text("█".repeat(60), Modifier.weight(f), style = style, color = t.accent, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        if (f < 1f) Text("░".repeat(60), Modifier.weight(1f - f), style = style, color = t.text.copy(alpha = 0.18f), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

// ---------------- One block per kind ----------------

/** Alarm: when, nothing else. */
@Composable
private fun TimeBlock(card: AssistantCard, t: Tints) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Caption(card.label ?: "Alarm", t, align = TextAlign.Center)
        Hero(card.title, 38.sp, 44.sp, t, spacing = (-1.5).sp)
        card.detail?.let { Detail(it, t, align = TextAlign.Center) }
    }
}

/** Timer: the live countdown and what's left of it as a bar. */
@Composable
private fun TimerBlock(card: AssistantCard, t: Tints) {
    val end = card.countdownTo ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(end) {
        while (now < end) {
            delay(1_000 - now % 1_000)
            now = System.currentTimeMillis()
        }
    }
    val leftMs = (end - now).coerceAtLeast(0)
    val left = (leftMs + 999) / 1_000
    val total = card.countdownTotalMs?.takeIf { it > 0 }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Caption(if (left == 0L) "Timer · done" else card.label ?: "Timer · running", t, align = TextAlign.Center)
        Hero(if (left == 0L) "00:00" else clock(left), 36.sp, 42.sp, t)
        Spacer(Modifier.height(4.dp))
        AsciiBar(if (total == null) 1f else leftMs.toFloat() / total, t)
    }
}

private fun clock(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Volume, battery, sound mode: the state big, a level bar when there is one. */
@Composable
private fun LevelBlock(card: AssistantCard, t: Tints) {
    Column(Modifier.fillMaxWidth()) {
        Caption(card.label ?: card.subtitle ?: "", t)
        Row(verticalAlignment = Alignment.Bottom) {
            Hero(card.title.uppercase(), 28.sp, 36.sp, t, spacing = 0.sp, modifier = Modifier.weight(1f))
            card.detail?.let { Detail(it, t, Modifier.padding(bottom = 6.dp)) }
        }
        card.level?.let { AsciiBar(it, t) }
    }
}

/** Flashlight and Do Not Disturb: live, with an on/off pill. */
@Composable
private fun SwitchBlock(card: AssistantCard, t: Tints) {
    val look = liveLook(card)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Caption(card.title, t, align = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Pill(look.title, on = !look.dimmed, t)
    }
}

/** Weather: now big on the left, the day's range and the place on the right. */
@Composable
private fun WeatherBlock(card: AssistantCard, t: Tints) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Caption(card.label ?: "Weather", t)
            Hero(card.title.substringBefore(" · "), 36.sp, 42.sp, t)
        }
        Detail(card.detail ?: card.subtitle.orEmpty(), t, Modifier.padding(bottom = 6.dp), TextAlign.End, lines = 2)
    }
}

/** Music: art, track, artist, live. */
@Composable
private fun MusicBlock(card: AssistantCard, t: Tints) {
    val look = liveLook(card)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val art = look.art
        if (art != null) {
            Image(art, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
        } else {
            Glyph(card, t, 40.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val caption = when {
                look.dimmed -> "Nothing playing"
                look.subtitle?.contains("Paused") == true -> "Paused"
                else -> "Now playing"
            }
            Caption(caption, t)
            if (!look.dimmed) {
                Title(look.title, t)
                look.subtitle?.removeSuffix(" · Paused")?.removeSuffix("Paused")?.takeIf { it.isNotBlank() }?.let { Detail(it, t) }
            }
        }
    }
}

/** Call: who, and a way back to the call. */
@Composable
private fun CallBlock(card: AssistantCard, t: Tints) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(t.deep), contentAlignment = Alignment.Center) {
            Text(card.title.firstOrNull()?.uppercase() ?: "?", style = sans(15.sp, FontWeight.SemiBold), color = t.accent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Caption(card.label ?: "Call", t)
            Title(card.title, t)
        }
        if (!card.awaitingConfirmation && card.action != null) {
            Text("open →", style = mono(12.sp, FontWeight.Medium), color = t.dim)
        }
    }
}

/** Message: the recipient, the app it went through, and the text. */
@Composable
private fun MessageBlock(card: AssistantCard, t: Tints) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title(card.title, t, Modifier.weight(1f))
            card.label?.let {
                Spacer(Modifier.width(8.dp))
                Caption(it, t)
            }
        }
        Spacer(Modifier.height(5.dp))
        card.subtitle?.let {
            Text(
                "“$it”", style = sans(14.sp, line = 19.sp).copy(shadow = t.shadow), color = t.text,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Note: what was saved. */
@Composable
private fun NoteBlock(card: AssistantCard, t: Tints) {
    Column(Modifier.fillMaxWidth()) {
        Caption(card.label ?: "Note saved", t)
        Spacer(Modifier.height(4.dp))
        Row {
            Text("> ", style = mono(14.sp, line = 20.sp), color = t.dim)
            Text(
                card.title, style = mono(14.sp, line = 20.sp).copy(shadow = t.shadow), color = t.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        card.detail?.let { Detail(it, t) }
    }
}

/** Directions: how, where to, and who routes. */
@Composable
private fun RouteBlock(card: AssistantCard, t: Tints) {
    Column(Modifier.fillMaxWidth()) {
        Caption(card.label ?: "Route", t)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Navigation, null, Modifier.size(18.dp), tint = t.text)
            Spacer(Modifier.width(8.dp))
            Title(card.title, t)
        }
        Spacer(Modifier.height(2.dp))
        Detail(card.detail ?: card.subtitle.orEmpty(), t)
    }
}

/** Everything else: caption, title, one quiet line; an app's icon when it's about an app. */
@Composable
private fun LabeledBlock(card: AssistantCard, t: Tints) {
    val icons = LocalContext.current.appGraph.icons
    val appIcon = remember(card.appPackage) { card.appPackage?.let(icons::icon) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (appIcon != null) {
            Image(appIcon, null, Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Caption(card.label ?: card.icon.name.lowercase().replace('_', ' '), t)
            Title(card.title, t)
            card.subtitle?.takeIf { !it.equals(card.label, ignoreCase = true) }?.let { Detail(it, t) }
        }
    }
}

/** The action's icon in a tinted chip, where there's no picture. */
@Composable
private fun Glyph(card: AssistantCard, t: Tints, size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(t.accent.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
        Icon(card.icon.vector(), null, Modifier.size(size * 0.55f), tint = t.accent)
    }
}
