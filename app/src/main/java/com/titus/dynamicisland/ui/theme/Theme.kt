package com.titus.dynamicisland.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.titus.dynamicisland.R

/** Geist Mono (SIL Open Font License, see assets/licenses), one variable file for every weight. */
val GeistMono = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(R.font.geist_mono, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

/** The settings app's terminal look: black, grey type, white as the only accent. */
object Term {
    val Bg = Color.Black
    val Text = Color(0xFFEDEDED)
    /** Summaries and secondary text. */
    val Dim = Color(0xFF8A8A8A)
    /** Section labels, paths, notes. */
    val Label = Color(0xFF808080)
    /** Arrows, unchecked boxes, note marks. */
    val Faint = Color(0xFF5C5C5C)
    val Divider = Color(0xFF232323)
    val Border = Color(0xFF2A2A2A)
    val BorderFocused = Color(0xFF6A6A6A)
    val Off = Color(0xFF3A3A3A)
    val Track = Color(0xFF2E2E2E)
    val Surface = Color(0xFF0E0E0E)
    val Danger = Color(0xFFFF453A)
}

private val Scheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1A1A1A),
    onPrimaryContainer = Term.Text,
    secondary = Term.Dim,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF161616),
    onSecondaryContainer = Term.Text,
    background = Term.Bg,
    onBackground = Term.Text,
    surface = Term.Bg,
    onSurface = Term.Text,
    surfaceVariant = Color(0xFF161616),
    onSurfaceVariant = Term.Dim,
    surfaceContainerLowest = Term.Bg,
    surfaceContainerLow = Term.Surface,
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF161616),
    surfaceContainerHighest = Color(0xFF1C1C1C),
    outline = Term.Border,
    outlineVariant = Term.Divider,
    error = Term.Danger,
)

private fun TextStyle.mono() = copy(fontFamily = GeistMono)

private val Mono = Typography().run {
    Typography(
        displayLarge = displayLarge.mono(), displayMedium = displayMedium.mono(), displaySmall = displaySmall.mono(),
        headlineLarge = headlineLarge.mono(), headlineMedium = headlineMedium.mono(), headlineSmall = headlineSmall.mono(),
        titleLarge = titleLarge.mono(), titleMedium = titleMedium.mono(), titleSmall = titleSmall.mono(),
        bodyLarge = bodyLarge.mono(), bodyMedium = bodyMedium.mono(), bodySmall = bodySmall.mono(),
        labelLarge = labelLarge.mono(), labelMedium = labelMedium.mono(), labelSmall = labelSmall.mono(),
    )
}

@Composable
fun DynamicIslandTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Mono, content = content)
}
