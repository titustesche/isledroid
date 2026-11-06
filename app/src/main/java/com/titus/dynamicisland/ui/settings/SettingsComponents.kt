package com.titus.dynamicisland.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titus.dynamicisland.ui.theme.GeistMono
import com.titus.dynamicisland.ui.theme.Term
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * The settings' terminal look (see the design canvas): rows on black separated by dashed rules,
 * ON/OFF pills, [x] options, █░ bars, "# notes". Whole rows are the touch targets.
 */

private val ListInset = 8.dp
private val RowStart = 12.dp
private val Page = 20.dp

// ---------------- The list: sections and their dashed rules ----------------

/** False for the first row of a section: only rows that follow another row draw the dashed rule above. */
private val LocalRuleAbove = staticCompositionLocalOf { false }

/**
 * Wraps a screen's list so rows know whether they start a section ([header] resets that), which
 * decides whether they draw the dashed rule above them.
 */
class SettingsListScope(private val inner: LazyListScope) : LazyListScope by inner {
    private var rowsInSection = 0

    fun header(text: String) {
        rowsInSection = 0
        inner.item(contentType = "header") { SectionHeader(text) }
    }

    /** A "# …" note; it doesn't count as a row, so a note right under a header leaves the next row rule-less. */
    fun note(text: String) {
        inner.item(contentType = "note") { Hint(text) }
    }

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        val rule = rowsInSection++ > 0
        inner.item(key, contentType) { CompositionLocalProvider(LocalRuleAbove provides rule) { content() } }
    }

    override fun items(
        count: Int,
        key: ((Int) -> Any)?,
        contentType: (Int) -> Any?,
        itemContent: @Composable LazyItemScope.(Int) -> Unit,
    ) {
        val start = rowsInSection
        rowsInSection += count
        inner.items(count, key, contentType) { i ->
            CompositionLocalProvider(LocalRuleAbove provides (start + i > 0)) { itemContent(i) }
        }
    }
}

private fun Modifier.dashedRuleAbove(show: Boolean) = if (!show) this else drawBehind {
    val inset = (ListInset + RowStart).toPx()
    drawLine(
        Term.Divider, Offset(inset, 0f), Offset(size.width - inset, 0f), strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
    )
}

/** A dashed outline, for cards and the danger button. */
fun Modifier.dashedBorder(color: Color, radius: androidx.compose.ui.unit.Dp) = drawBehind {
    val w = 1.dp.toPx()
    drawRoundRect(
        color, topLeft = Offset(w / 2, w / 2), size = size.copy(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
    )
}

/** "—— LABEL —————" */
@Composable
fun SectionHeader(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = Page, end = Page, top = 30.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DashedLine(Modifier.width(12.dp))
        Spacer(Modifier.width(10.dp))
        Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp, color = Term.Label)
        Spacer(Modifier.width(10.dp))
        DashedLine(Modifier.weight(1f))
    }
}

@Composable
private fun DashedLine(modifier: Modifier) {
    Canvas(modifier.height(1.dp)) {
        drawLine(
            Color(0xFF333333), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
        )
    }
}

/** "# Short pills when something changes." */
@Composable
fun Hint(text: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Term.Faint, fontWeight = FontWeight.SemiBold)) { append("# ") }
            append(text)
        },
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = Term.Label,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Page, vertical = 6.dp),
    )
}

/** Summaries read like the canvas's: lower-case first letter, unless the first word is an acronym (API, OK …). */
private fun String.calm(): String =
    if (length > 1 && this[0].isUpperCase() && this[1].isLowerCase()) this[0].lowercase() + substring(1) else this

// ---------------- Rows ----------------

/** The building block: title (and summary) on the left, [trailing] on the right. */
@Composable
fun SettingRow(
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val alpha = if (enabled) 1f else 0.4f
    Box(Modifier.fillMaxWidth().dashedRuleAbove(LocalRuleAbove.current).padding(horizontal = ListInset)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
                .heightIn(min = 60.dp)
                .padding(start = RowStart, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Box(Modifier.widthIn(min = 20.dp), contentAlignment = Alignment.Center) { leading() }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp, color = Term.Text.copy(alpha = alpha))
                if (summary != null) {
                    Text(summary.calm(), fontSize = 13.sp, lineHeight = 18.sp, color = Term.Dim.copy(alpha = alpha),
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) { trailing() }
        }
    }
}

/** A block row for controls that need the full width (bars, option lists, fields). */
@Composable
fun SettingBlock(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().dashedRuleAbove(LocalRuleAbove.current).padding(horizontal = ListInset)) {
        Column(Modifier.fillMaxWidth().padding(start = RowStart, end = RowStart, top = 14.dp, bottom = 12.dp)) { content() }
    }
}

/** ON / OFF pill. */
@Composable
fun TermToggle(checked: Boolean, enabled: Boolean = true, onChange: ((Boolean) -> Unit)? = null) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .defaultMinSize(minWidth = 66.dp, minHeight = 48.dp)
            .then(if (onChange != null) Modifier.clip(CircleShape).clickable(enabled = enabled) { onChange(!checked) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val pill = Modifier.width(52.dp).clip(CircleShape)
        val style = TextStyle(fontFamily = GeistMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
        if (checked) {
            Box(pill.background(accent.copy(alpha = if (enabled) 1f else 0.4f)).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                Text("ON", style = style, color = Color.Black)
            }
        } else {
            Box(pill.border(1.dp, Term.Off, CircleShape).padding(vertical = 5.dp), contentAlignment = Alignment.Center) {
                Text("OFF", style = style, color = Term.Dim)
            }
        }
    }
}

@Composable
fun SwitchSetting(title: String, checked: Boolean, onChange: (Boolean) -> Unit, summary: String? = null, enabled: Boolean = true) {
    SettingRow(title, summary, onClick = { onChange(!checked) }, enabled = enabled) {
        TermToggle(checked, enabled)
    }
}

/** A feature switch with its color square next to it. */
@Composable
fun SwitchColorSetting(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    color: Long,
    onColor: (Long) -> Unit,
    summary: String? = null,
) {
    var picking by remember { mutableStateOf(false) }
    SettingRow(title, summary, onClick = { onChange(!checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable { picking = true }, contentAlignment = Alignment.Center) {
                Swatch(color)
            }
            TermToggle(checked)
        }
    }
    if (picking) ColorPickerDialog(title, color, allowAlpha = false, onChange = onColor, onDismiss = { picking = false })
}

/** "Title ………… 60%" and a draggable █░ bar across the row. */
@Composable
fun SliderSetting(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    format: (Float) -> String = { it.roundToInt().toString() },
    steps: Int = 0,
) {
    SettingBlock {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(format(value), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Term.Text)
        }
        AsciiBar(value, range, onChange, steps, Modifier.padding(top = 10.dp))
    }
}

/** Blocks for the part that's set, shade for the rest; tap or drag anywhere on it. */
@Composable
private fun AsciiBar(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    steps: Int,
    modifier: Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = GeistMono, fontSize = 15.sp, letterSpacing = 0.sp)
    val change by rememberUpdatedState(onChange)
    fun pick(x: Float, width: Int) {
        var f = (x / width).coerceIn(0f, 1f)
        if (steps > 0) f = (f * (steps + 1)).roundToInt() / (steps + 1f)
        change(range.start + f * (range.endInclusive - range.start))
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(range, steps) { detectTapGestures { pick(it.x, size.width) } }
            .pointerInput(range, steps) { detectHorizontalDragGestures { change, _ -> change.consume(); pick(change.position.x, size.width) } },
    ) {
        val cell = measurer.measure("█", style).size.width.coerceAtLeast(1)
        val cells = floor(size.width / cell).toInt().coerceAtLeast(1)
        val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        val lit = (fraction * cells).roundToInt()
        // Spread the leftover pixels evenly, so the bar ends exactly at the row's right inset.
        val gap = (size.width - cells * cell) / 2f
        val y = (size.height - measurer.measure("█", style).size.height) / 2f
        drawText(measurer, "█".repeat(lit), Offset(gap, y), style.copy(color = accent))
        drawText(measurer, "░".repeat(cells - lit), Offset(gap + lit * cell, y), style.copy(color = Term.Track))
    }
}

@Composable
fun IntSliderSetting(title: String, value: Int, range: IntRange, unit: String = "dp", onChange: (Int) -> Unit) {
    SliderSetting(
        title = title,
        value = value.toFloat(),
        range = range.first.toFloat()..range.last.toFloat(),
        onChange = { onChange(it.roundToInt()) },
        format = { "${it.roundToInt()} $unit".trim() },
    )
}

/** A row showing the current choice; tapping it opens the list. */
@Composable
fun <T> ChoiceSetting(title: String, options: List<T>, selected: T, label: (T) -> String, summary: String? = null, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingRow(title, summary, onClick = { open = true }) {
        Box(Modifier.padding(end = 8.dp)) {
            Text(
                buildAnnotatedString {
                    append(label(selected))
                    withStyle(SpanStyle(color = Term.Faint)) { append(" ▾") }
                },
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Term.Text,
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o ->
                    DropdownMenuItem(
                        text = { Text(label(o), fontSize = 15.sp) },
                        onClick = { onSelect(o); open = false },
                        leadingIcon = if (o == selected) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null,
                    )
                }
            }
        }
    }
}

/** "[x] One UI   [ ] Android   [ ] Flat" */
@Composable
fun <T> SegmentedSetting(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    SettingBlock {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 4.dp)) {
            options.forEach { o ->
                val on = o == selected
                Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable { onSelect(o) }, contentAlignment = Alignment.Center) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = if (on) accent else Term.Faint, fontWeight = FontWeight.SemiBold)) {
                                append(if (on) "[x] " else "[ ] ")
                            }
                            append(label(o))
                        },
                        fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (on) Color.White else Term.Dim,
                    )
                }
            }
        }
    }
}

/** Opens a sub-page; [summary] says what's set there. */
@Composable
fun NavRow(title: String, summary: String?, icon: ImageVector?, tint: Color = Term.Text, onClick: () -> Unit) {
    SettingRow(
        title, summary, onClick = onClick,
        leading = icon?.let { { Icon(it, null, Modifier.size(22.dp), tint = tint) } },
    ) {
        Text("→", fontSize = 17.sp, color = Term.Faint, modifier = Modifier.padding(end = 8.dp))
    }
}

/** "[x]" / "[ ]" at the end of a row, for picking items of a list. */
@Composable
fun PickRow(title: String, picked: Boolean, summary: String? = null, enabled: Boolean = true, onToggle: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    SettingRow(title, summary, onClick = onToggle, enabled = enabled) {
        Text(
            if (picked) "[x]" else "[ ]", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            color = if (picked) accent else Term.Faint, modifier = Modifier.padding(end = 10.dp),
        )
    }
}

/** The color square used next to settings. */
@Composable
fun Swatch(color: Long) {
    Box(
        Modifier
            .size(22.dp)
            .border(1.dp, Term.Off, RoundedCornerShape(5.dp))
            .padding(1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(color.toInt())),
    )
}

// ---------------- Buttons, fields, cards ----------------

/** Pill button: [filled] white with black text, otherwise outlined; [danger] dashed red. */
@Composable
fun TermButton(text: String, modifier: Modifier = Modifier, filled: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val shape = CircleShape
    val base = modifier.heightIn(min = 48.dp).clip(shape)
    val styled = when {
        filled -> base.background(Color.White)
        danger -> base.dashedBorder(Term.Danger.copy(alpha = 0.5f), 999.dp)
        else -> base.border(1.dp, Term.Border, shape)
    }
    Box(styled.clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(
            text, fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp,
            color = when {
                filled -> Color.Black
                danger -> Term.Danger
                else -> Term.Text
            },
        )
    }
}

/** A dashed card, for setup steps. */
@Composable
fun TermCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .dashedBorder(Term.Border, 18.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp, color = Term.Label)
        content()
    }
}

/** "Label" above a rounded field with a ">" prompt. [secret] hides the text, with a button to show it. */
@Composable
fun TermField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    var text by remember { mutableStateOf(value) }
    var visible by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    SettingBlock {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp, color = Term.Dim,
            modifier = Modifier.padding(bottom = 8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .border(1.dp, if (focused) Term.BorderFocused else Term.Border, RoundedCornerShape(16.dp))
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(">", fontWeight = FontWeight.SemiBold, color = Term.Faint)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (text.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, fontSize = 16.sp, color = Term.Faint)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it; onChange(it) },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = GeistMono, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Term.Text),
                    cursorBrush = SolidColor(Color.White),
                    visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                )
            }
            if (secret) {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visible) "Hide" else "Show",
                        tint = Term.Dim, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

fun countLabel(n: Int) = if (n == 0) "None selected" else "$n selected"
