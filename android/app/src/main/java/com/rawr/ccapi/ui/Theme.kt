package com.rawr.ccapi.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Rawr Remote's visual identity: matte camera-body blacks with a tungsten-amber
 * accent — the language of a pro body's top-plate LCD rather than app chrome.
 *
 * Rules the screens follow:
 *  - Amber marks the interactive and the live: selection, active states,
 *    progress, camera values. Red is reserved for errors, never selection.
 *  - Technical readouts (counts, sizes, camera values, filenames) are set in
 *    monospace via [mono] — the "top-plate readout" signature.
 *  - Section labels are quiet tracked-out caps; body text is warm off-white,
 *    never pure white. Dark-only by design (field use, immersive fullscreen).
 */
object Rawr {
    val Ink = Color(0xFF0B0B0D)          // app background — near-black, OLED-friendly
    val Panel = Color(0xFF131316)        // sheets, bars
    val Control = Color(0xFF1C1C21)      // pills, chips, inputs, placeholders
    val Line = Color(0xFF26262C)         // hairline borders / dividers
    val TextHi = Color(0xFFF2F0EB)       // warm off-white
    val TextLo = Color(0xFFA19C93)       // muted warm gray
    val Amber = Color(0xFFE8A33D)        // tungsten accent
    val AmberBright = Color(0xFFF2C980)  // amber text on amber-tinted containers
    val AmberDim = Color(0xFF33250E)     // amber-tinted containers (chips, nav pill)
    val OnAmber = Color(0xFF201302)      // text on solid amber
    val Red = Color(0xFFE5484D)          // errors only
}

private val RawrColorScheme = darkColorScheme(
    primary = Rawr.Amber,
    onPrimary = Rawr.OnAmber,
    primaryContainer = Rawr.AmberDim,
    onPrimaryContainer = Rawr.AmberBright,
    secondary = Rawr.TextLo,
    onSecondary = Rawr.Ink,
    // Selected chips and the navigation-bar indicator draw from these.
    secondaryContainer = Rawr.AmberDim,
    onSecondaryContainer = Rawr.AmberBright,
    background = Rawr.Ink,
    onBackground = Rawr.TextHi,
    surface = Rawr.Ink,                  // scaffolds + top bars sit seamless on ink
    onSurface = Rawr.TextHi,
    surfaceVariant = Rawr.Control,
    onSurfaceVariant = Rawr.TextLo,
    surfaceContainerLowest = Color(0xFF09090B),
    surfaceContainerLow = Rawr.Panel,    // bottom sheets
    surfaceContainer = Color(0xFF101014), // navigation bar / rail
    surfaceContainerHigh = Color(0xFF18181D),
    surfaceContainerHighest = Rawr.Control, // cards
    outline = Color(0xFF3A3A42),
    outlineVariant = Rawr.Line,
    error = Rawr.Red,
    onError = Color(0xFFFFFFFF),
)

// Stock Roboto, tuned: labels track out like engraved hardware lettering, and
// titles carry a touch more weight so hairline-bordered layouts stay legible.
private val RawrTypography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(letterSpacing = 0.6.sp),
        labelSmall = labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

// One radius family across the app: small controls 8, cells/pills 12, sheets 16.
private val RawrShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

/** Monospace variant for technical readouts: counts, sizes, camera values. */
internal fun TextStyle.mono(): TextStyle = copy(fontFamily = FontFamily.Monospace)

/** Tracked-out caps for engraved-style labels; pass the text uppercased. */
internal fun TextStyle.engraved(): TextStyle =
    copy(letterSpacing = 2.sp, fontWeight = FontWeight.Medium)

@Composable
internal fun RawrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RawrColorScheme,
        typography = RawrTypography,
        shapes = RawrShapes,
    ) {
        Surface(color = MaterialTheme.colorScheme.background) { content() }
    }
}
