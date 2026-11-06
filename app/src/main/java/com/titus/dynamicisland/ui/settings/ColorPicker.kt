package com.titus.dynamicisland.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import com.titus.dynamicisland.ui.theme.Term
import java.util.Locale
import android.graphics.Color as AColor

/** A settings row showing the current color; tapping opens a full color picker. */
@Composable
fun ColorSetting(
    title: String,
    value: Long,
    onChange: (Long) -> Unit,
    summary: String? = null,
    allowAlpha: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(title, summary, onClick = { open = true }) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
            Text("#" + toHex(value.toInt(), allowAlpha), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Term.Dim)
            Spacer(Modifier.width(10.dp))
            Swatch(value)
        }
    }
    if (open) {
        ColorPickerDialog(
            title = title,
            initial = value,
            allowAlpha = allowAlpha,
            onChange = onChange,
            onDismiss = { open = false },
        )
    }
}

private val Checker = Brush.linearGradient(listOf(Color(0xFFBDBDBD), Color(0xFFF5F5F5)))

private fun toHex(argb: Int, alpha: Boolean) =
    if (alpha) String.format(Locale.ROOT, "%08X", argb) else String.format(Locale.ROOT, "%06X", argb and 0xFFFFFF)

private fun parseHex(text: String, alpha: Boolean): Int? {
    val t = text.trim().removePrefix("#")
    return when {
        t.length == 6 -> t.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
        t.length == 8 && alpha -> t.toLongOrNull(16)?.toInt()
        else -> null
    }
}

/**
 * HSV picker: saturation/value field, hue bar, optional alpha bar and hex entry.
 * Changes are applied live through [onChange]; Cancel restores the initial color.
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Long,
    allowAlpha: Boolean,
    onChange: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val hsv = remember { FloatArray(3).also { AColor.colorToHSV(initial.toInt(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var alpha by remember { mutableFloatStateOf(if (allowAlpha) AColor.alpha(initial.toInt()) / 255f else 1f) }

    fun current(): Int = AColor.HSVToColor((alpha * 255).toInt(), floatArrayOf(hue, sat, value))
    var hex by remember { mutableStateOf(toHex(initial.toInt(), allowAlpha)) }

    fun publish(updateHex: Boolean = true) {
        val c = current()
        if (updateHex) hex = toHex(c, allowAlpha)
        onChange(c.toLong() and 0xFFFFFFFFL)
    }

    AlertDialog(
        onDismissRequest = { onChange(initial); onDismiss() },
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { onChange(initial); onDismiss() }) { Text("Cancel") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SatValField(hue, sat, value) { s, v -> sat = s; value = v; publish() }
                HueBar(hue) { hue = it; publish() }
                if (allowAlpha) AlphaBar(Color(AColor.HSVToColor(floatArrayOf(hue, sat, value))), alpha) { alpha = it; publish() }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Row(Modifier.clip(RoundedCornerShape(10.dp))) {
                        Box(Modifier.size(28.dp, 40.dp).background(Checker).background(Color(initial.toInt())))
                        Box(Modifier.size(28.dp, 40.dp).background(Checker).background(Color(current())))
                    }
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { text ->
                            hex = text.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(if (allowAlpha) 8 else 6)
                            parseHex(hex, allowAlpha)?.let { c ->
                                val out = FloatArray(3).also { AColor.colorToHSV(c, it) }
                                hue = out[0]; sat = out[1]; value = out[2]
                                if (allowAlpha) alpha = AColor.alpha(c) / 255f
                                publish(updateHex = false)
                            }
                        },
                        prefix = { Text("#") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.width(150.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun SatValField(hue: Float, sat: Float, value: Float, onChange: (Float, Float) -> Unit) {
    val pureHue = Color(AColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    fun pick(o: Offset, w: Float, h: Float) = onChange((o.x / w).coerceIn(0f, 1f), 1f - (o.y / h).coerceIn(0f, 1f))
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) { detectTapGestures { pick(it, size.width.toFloat(), size.height.toFloat()) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    pick(change.position, size.width.toFloat(), size.height.toFloat())
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, pureHue)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val c = Offset(sat * size.width, (1f - value) * size.height)
        drawCircle(Color.White, 11.dp.toPx(), c, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.4f), 12.5f.dp.toPx(), c, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun SliderBar(brush: Brush, fraction: Float, background: Brush? = null, onChange: (Float) -> Unit) {
    fun pick(x: Float, w: Float) = onChange((x / w).coerceIn(0f, 1f))
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .pointerInput(Unit) { detectTapGestures { pick(it.x, size.width.toFloat()) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    pick(change.position.x, size.width.toFloat())
                }
            },
    ) {
        background?.let { drawRect(it) }
        drawRect(brush)
        val x = (fraction * size.width).coerceIn(size.height / 2, size.width - size.height / 2)
        drawCircle(Color.White, size.height / 2 - 2.dp.toPx(), Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
    }
}

@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    val colors = remember { (0..6).map { Color(AColor.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) } }
    SliderBar(Brush.horizontalGradient(colors), hue / 360f) { onChange(it * 360f) }
}

@Composable
private fun AlphaBar(color: Color, alpha: Float, onChange: (Float) -> Unit) {
    SliderBar(Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color)), alpha, background = Checker, onChange = onChange)
}
